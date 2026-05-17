package com.genius.smartlight.service.weather.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.dal.dataobject.StoreDO;
import com.genius.smartlight.dal.dataobject.WeatherRecordDO;
import com.genius.smartlight.dal.mysql.StoreMapper;
import com.genius.smartlight.dal.mysql.WeatherRecordMapper;
import com.genius.smartlight.security.SecurityUtils;
import com.genius.smartlight.service.weather.WeatherService;
import com.genius.smartlight.vo.weather.WeatherCurrentRespVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class WeatherServiceImpl implements WeatherService {

    private static final String OPEN_METEO_URL = "https://api.open-meteo.com/v1/forecast";
    private static final int CACHE_MINUTES = 30;

    @Value("${weather.backup.enabled:false}")
    private boolean backupEnabled;

    @Value("${weather.backup.base-url:https://api.openweathermap.org/data/2.5/weather}")
    private String backupBaseUrl;

    @Value("${weather.backup.api-key:}")
    private String backupApiKey;

    private final StoreMapper storeMapper;
    private final WeatherRecordMapper weatherRecordMapper;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public WeatherServiceImpl(StoreMapper storeMapper,
                              WeatherRecordMapper weatherRecordMapper,
                              @Qualifier("weatherRestTemplate") RestTemplate restTemplate,
                              ObjectMapper objectMapper) {
        this.storeMapper = storeMapper;
        this.weatherRecordMapper = weatherRecordMapper;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public WeatherCurrentRespVO getCurrentWeather(Long storeId) {
        StoreDO store = getOwnedStore(storeId);
        WeatherRecordDO latest = getLatestRecord(storeId);
        if (latest != null && latest.getCollectTime() != null
                && latest.getCollectTime().isAfter(LocalDateTime.now().minusMinutes(CACHE_MINUTES))) {
            return convertToRespVO(latest);
        }
        return convertToRespVO(collectForStore(store));
    }

    @Override
    public WeatherCurrentRespVO collectWeather(Long storeId) {
        StoreDO store = getOwnedStore(storeId);
        return convertToRespVO(collectForStore(store));
    }

    @Override
    public void collectAllStoresWeather() {
        List<StoreDO> stores = storeMapper.selectList(
                new LambdaQueryWrapper<StoreDO>()
                        .isNotNull(StoreDO::getLatitude)
                        .isNotNull(StoreDO::getLongitude)
        );
        for (StoreDO store : stores) {
            try {
                collectForStore(store);
            } catch (Exception ex) {
                log.warn("Collect weather failed, storeId={}, city={}, lat={}, lng={}",
                        store.getId(), store.getCity(), store.getLatitude(), store.getLongitude(), ex);
            }
        }
    }

    private StoreDO getOwnedStore(Long storeId) {
        if (storeId == null) {
            throw new ServiceException("storeId不能为空");
        }
        StoreDO store = storeMapper.selectById(storeId);
        if (store == null) {
            throw new ServiceException("店铺不存在");
        }
        Long userId = SecurityUtils.getCurrentUserId();
        if (!userId.equals(store.getUserId())) {
            throw new ServiceException("无权访问该店铺");
        }
        return store;
    }

    private WeatherRecordDO getLatestRecord(Long storeId) {
        return weatherRecordMapper.selectOne(
                new LambdaQueryWrapper<WeatherRecordDO>()
                        .eq(WeatherRecordDO::getStoreId, storeId)
                        .orderByDesc(WeatherRecordDO::getCollectTime)
                        .orderByDesc(WeatherRecordDO::getId)
                        .last("LIMIT 1")
        );
    }

    private String fetchWeatherWithRetry(String url, Long storeId) {
        int maxAttempts = 3;
        Exception lastEx = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return restTemplate.getForObject(url, String.class);
            } catch (ResourceAccessException ex) {
                // Timeout / connection reset / read timed out
                lastEx = ex;
                String reason = ex.getMessage() != null ? ex.getMessage() : "timeout";
                if (reason.length() > 60) reason = reason.substring(0, 60);
                if (attempt < maxAttempts) {
                    log.warn("Weather request failed, retrying, storeId={}, attempt={}/{}, reason={}",
                            storeId, attempt, maxAttempts, reason);
                    sleepMs(attempt == 1 ? 500 : 1000);
                }
            } catch (RestClientException ex) {
                // Check for 502/503/504
                String msg = ex.getMessage() != null ? ex.getMessage() : "";
                boolean retryable = msg.contains("502") || msg.contains("503") || msg.contains("504");
                if (retryable && attempt < maxAttempts) {
                    lastEx = ex;
                    String reason = msg.length() > 80 ? msg.substring(0, 80) : msg;
                    log.warn("Weather request failed, retrying, storeId={}, attempt={}/{}, reason={}",
                            storeId, attempt, maxAttempts, reason);
                    sleepMs(attempt == 1 ? 500 : 1000);
                } else {
                    log.warn("Weather API request failed, storeId={}", storeId, ex);
                    throw new ServiceException("天气接口调用失败：" + ex.getMessage());
                }
            }
        }
        // All retries exhausted
        log.warn("Weather API all retries exhausted, storeId={}", storeId, lastEx);
        throw new ServiceException("天气接口连接超时或不可用，已重试" + (maxAttempts - 1) + "次，请稍后重试");
    }

    private void sleepMs(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private WeatherRecordDO collectForStore(StoreDO store) {
        if (store.getLatitude() == null || store.getLongitude() == null) {
            throw new ServiceException("店铺缺少经纬度，无法采集天气");
        }

        // 1. Try Open-Meteo
        try {
            return collectFromOpenMeteo(store);
        } catch (Exception e) {
            log.warn("Open-Meteo weather request failed after retries, storeId={}, reason={}",
                    store.getId(), sanitizeReason(e.getMessage()));
        }

        // 2. Try OpenWeatherMap backup
        if (backupEnabled && backupApiKey != null && !backupApiKey.isBlank()) {
            log.info("Trying backup weather provider, storeId={}, provider=openweathermap", store.getId());
            try {
                WeatherRecordDO record = collectFromOpenWeatherMap(store);
                log.info("Weather collected by backup provider, storeId={}, provider=openweathermap", store.getId());
                return record;
            } catch (Exception e) {
                log.warn("Backup weather provider failed, storeId={}, provider=openweathermap, reason={}",
                        store.getId(), sanitizeReason(e.getMessage()));
            }
        } else if (backupEnabled) {
            log.warn("OpenWeatherMap backup weather provider is not configured, storeId={}", store.getId());
        }

        // 3. Use latest valid weather record as fallback
        WeatherRecordDO latest = getLatestRecord(store.getId());
        if (latest != null) {
            log.info("Use latest valid weather fallback, storeId={}, latestWeatherTime={}",
                    store.getId(), latest.getCollectTime());
            return latest;
        }

        throw new ServiceException("天气采集失败：主源和备用源均不可用，且无历史有效天气记录");
    }

    private WeatherRecordDO collectFromOpenMeteo(StoreDO store) {
        String url = UriComponentsBuilder.fromUriString(OPEN_METEO_URL)
                .queryParam("latitude", store.getLatitude())
                .queryParam("longitude", store.getLongitude())
                .queryParam("current", "temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m")
                .queryParam("daily", "weather_code,temperature_2m_max,temperature_2m_min")
                .queryParam("timezone", "auto")
                .queryParam("forecast_days", 1)
                .queryParam("wind_speed_unit", "kmh")
                .build()
                .toUriString();

        String raw = fetchWeatherWithRetry(url, store.getId());
        if (raw == null || raw.isBlank()) {
            throw new ServiceException("天气接口返回为空");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(raw);
        } catch (JsonProcessingException ex) {
            throw new ServiceException("天气接口返回解析失败");
        }

        JsonNode current = root.path("current");
        JsonNode daily = root.path("daily");
        Integer weatherCode = current.path("weather_code").isNumber() ? current.path("weather_code").asInt() : null;
        LocalDateTime collectTime = parseCollectTime(current.path("time").asText(null));

        return buildAndSaveRecord(store, weatherCode, mapWeatherText(weatherCode),
                toBigDecimal(current.path("temperature_2m")),
                toBigDecimal(current.path("apparent_temperature")),
                toBigDecimal(current.path("relative_humidity_2m")),
                toBigDecimal(current.path("wind_speed_10m")),
                firstBigDecimal(daily.path("temperature_2m_max")),
                firstBigDecimal(daily.path("temperature_2m_min")),
                collectTime);
    }

    private WeatherRecordDO collectFromOpenWeatherMap(StoreDO store) {
        String url = UriComponentsBuilder.fromUriString(backupBaseUrl)
                .queryParam("lat", store.getLatitude())
                .queryParam("lon", store.getLongitude())
                .queryParam("units", "metric")
                .queryParam("lang", "zh_cn")
                .queryParam("appid", backupApiKey)
                .build()
                .toUriString();

        // Use a separate RestTemplate with shorter timeout for backup
        RestTemplate backupRt = createShortTimeoutTemplate();
        String raw;
        try {
            raw = backupRt.getForObject(url, String.class);
        } catch (Exception e) {
            throw new ServiceException("OpenWeatherMap request failed: " + sanitizeReason(e.getMessage()));
        }
        if (raw == null || raw.isBlank()) {
            throw new ServiceException("OpenWeatherMap returned empty response");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new ServiceException("OpenWeatherMap response parse failed");
        }

        // Check for API error
        if (root.has("cod") && root.get("cod").asInt() != 200) {
            String msg = root.has("message") ? root.path("message").asText() : "unknown error";
            throw new ServiceException("OpenWeatherMap API error: " + msg);
        }

        JsonNode main = root.path("main");
        JsonNode wind = root.path("wind");
        JsonNode weatherArr = root.path("weather");
        JsonNode weather0 = weatherArr.isArray() && weatherArr.size() > 0 ? weatherArr.get(0) : null;

        // Convert OpenWeatherMap weather.id to WMO code
        Integer owmId = weather0 != null && weather0.path("id").isNumber() ? weather0.path("id").asInt() : null;
        Integer wmoCode = convertOpenWeatherCodeToWmo(owmId);

        // Use zh_cn description text
        String weatherText = weather0 != null && !weather0.path("description").isMissingNode()
                ? weather0.path("description").asText() : mapWeatherText(wmoCode);

        return buildAndSaveRecord(store, wmoCode, weatherText,
                toBigDecimal(main.path("temp")),
                toBigDecimal(main.path("feels_like")),
                toBigDecimal(main.path("humidity")),
                toBigDecimal(wind.path("speed")),
                toBigDecimal(main.path("temp_max")),
                toBigDecimal(main.path("temp_min")),
                LocalDateTime.now());
    }

    private WeatherRecordDO buildAndSaveRecord(StoreDO store, Integer weatherCode, String weatherText,
                                                BigDecimal temperature, BigDecimal apparentTemp,
                                                BigDecimal humidity, BigDecimal windSpeed,
                                                BigDecimal tempMax, BigDecimal tempMin,
                                                LocalDateTime collectTime) {
        WeatherRecordDO record = new WeatherRecordDO();
        record.setStoreId(store.getId());
        record.setProvince(store.getProvince());
        record.setCity(store.getCity());
        record.setLatitude(store.getLatitude());
        record.setLongitude(store.getLongitude());
        record.setTemperature(temperature);
        record.setApparentTemperature(apparentTemp);
        record.setHumidity(humidity);
        record.setWindSpeed(windSpeed);
        record.setWeatherCode(weatherCode);
        record.setWeatherText(weatherText);
        record.setTempMax(tempMax);
        record.setTempMin(tempMin);
        record.setCollectTime(collectTime != null ? collectTime : LocalDateTime.now());
        record.setCreateTime(LocalDateTime.now());
        weatherRecordMapper.insert(record);
        return record;
    }

    private RestTemplate createShortTimeoutTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        return new RestTemplate(factory);
    }

    private String sanitizeReason(String msg) {
        if (msg == null) return "unknown";
        // Strip appid=xxx from URLs that may appear in RestClientException messages
        String s = msg.replaceAll("appid=[^&\\s]+", "appid=****");
        if (s.length() > 80) s = s.substring(0, 80);
        return s;
    }

    private Integer convertOpenWeatherCodeToWmo(Integer id) {
        if (id == null) return null;
        if (id >= 200 && id < 300) return 95;   // Thunderstorm
        if (id >= 300 && id < 400) return 51;   // Drizzle
        if (id >= 500 && id < 505) return 61;   // Rain (light to heavy)
        if (id == 511) return 66;                // Freezing rain
        if (id >= 520 && id < 532) return 80;   // Shower rain
        if (id >= 600 && id < 700) return 71;   // Snow
        if (id >= 700 && id < 800) return 45;   // Fog / haze
        if (id == 800) return 0;                 // Clear sky
        if (id == 801) return 1;                 // Few clouds
        if (id == 802) return 2;                 // Scattered clouds
        if (id >= 803 && id <= 804) return 3;   // Broken / overcast
        return null;
    }

    private LocalDateTime parseCollectTime(String value) {
        if (value == null || value.isBlank()) {
            return LocalDateTime.now();
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException ex) {
            log.warn("Parse weather collect time failed, value={}", value, ex);
            return LocalDateTime.now();
        }
    }

    private BigDecimal firstBigDecimal(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray() || arrayNode.isEmpty()) {
            return null;
        }
        return toBigDecimal(arrayNode.get(0));
    }

    private BigDecimal toBigDecimal(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return null;
        }
        return BigDecimal.valueOf(node.asDouble()).setScale(2, RoundingMode.HALF_UP);
    }

    private String mapWeatherText(Integer code) {
        if (code == null) {
            return "未知";
        }
        if (code == 0) {
            return "晴";
        }
        if (Set.of(1, 2, 3).contains(code)) {
            return "多云";
        }
        if (Set.of(45, 48).contains(code)) {
            return "雾";
        }
        if (Set.of(51, 53, 55).contains(code)) {
            return "毛毛雨";
        }
        if (Set.of(61, 63, 65).contains(code)) {
            return "雨";
        }
        if (Set.of(71, 73, 75).contains(code)) {
            return "雪";
        }
        if (Set.of(80, 81, 82).contains(code)) {
            return "阵雨";
        }
        if (Set.of(95, 96, 99).contains(code)) {
            return "雷暴";
        }
        return "未知";
    }

    private WeatherCurrentRespVO convertToRespVO(WeatherRecordDO record) {
        WeatherCurrentRespVO respVO = new WeatherCurrentRespVO();
        respVO.setId(record.getId());
        respVO.setStoreId(record.getStoreId());
        respVO.setProvince(record.getProvince());
        respVO.setCity(record.getCity());
        respVO.setLatitude(record.getLatitude());
        respVO.setLongitude(record.getLongitude());
        respVO.setTemperature(record.getTemperature());
        respVO.setApparentTemperature(record.getApparentTemperature());
        respVO.setHumidity(record.getHumidity());
        respVO.setWindSpeed(record.getWindSpeed());
        respVO.setWeatherCode(record.getWeatherCode());
        respVO.setWeatherText(record.getWeatherText());
        respVO.setTempMax(record.getTempMax());
        respVO.setTempMin(record.getTempMin());
        respVO.setCollectTime(record.getCollectTime());
        respVO.setCreateTime(record.getCreateTime());
        return respVO;
    }
}

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
                log.warn("Collect weather failed, storeId={}", store.getId(), ex);
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

    private WeatherRecordDO collectForStore(StoreDO store) {
        if (store.getLatitude() == null || store.getLongitude() == null) {
            throw new ServiceException("店铺缺少经纬度，无法采集天气");
        }

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

        String raw;
        try {
            raw = restTemplate.getForObject(url, String.class);
        } catch (ResourceAccessException ex) {
            log.warn("Weather API timeout or connection failed, storeId={}", store.getId(), ex);
            throw new ServiceException("天气接口连接超时或不可用，请稍后重试");
        } catch (RestClientException ex) {
            log.warn("Weather API request failed, storeId={}", store.getId(), ex);
            throw new ServiceException("天气接口调用失败：" + ex.getMessage());
        }
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

        WeatherRecordDO record = new WeatherRecordDO();
        record.setStoreId(store.getId());
        record.setProvince(store.getProvince());
        record.setCity(store.getCity());
        record.setLatitude(store.getLatitude());
        record.setLongitude(store.getLongitude());
        record.setTemperature(toBigDecimal(current.path("temperature_2m")));
        record.setApparentTemperature(toBigDecimal(current.path("apparent_temperature")));
        record.setHumidity(toBigDecimal(current.path("relative_humidity_2m")));
        record.setWindSpeed(toBigDecimal(current.path("wind_speed_10m")));
        record.setWeatherCode(weatherCode);
        record.setWeatherText(mapWeatherText(weatherCode));
        record.setTempMax(firstBigDecimal(daily.path("temperature_2m_max")));
        record.setTempMin(firstBigDecimal(daily.path("temperature_2m_min")));
        record.setCollectTime(collectTime);
        record.setCreateTime(LocalDateTime.now());
        weatherRecordMapper.insert(record);
        return record;
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

package com.genius.smartlight.opsadmin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.genius.smartlight.dal.dataobject.*;
import com.genius.smartlight.dal.mysql.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OpsAdminStoreService {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> SORT_WHITELIST = Set.of(
            "createTime", "updateTime", "area", "deviceCount", "latestLux", "durationToday"
    );
    private static final int MAX_PAGE_SIZE = 100;
    private static final int EXPORT_MAX = 5000;

    private final StoreMapper storeMapper;
    private final DeviceMapper deviceMapper;
    private final LuxRecordMapper luxRecordMapper;
    private final DurationRecordMapper durationRecordMapper;
    private final WeatherRecordMapper weatherRecordMapper;

    public List<OpsAdminStoreResp> page(OpsAdminStorePageReq req) {
        int pageSize = Math.min(Math.max(req.getPageSize(), 1), MAX_PAGE_SIZE);
        int page = Math.max(req.getPage(), 1);

        LambdaQueryWrapper<StoreDO> qw = buildStoreWrapper(req);
        List<StoreDO> stores = storeMapper.selectList(qw);

        // Apply advanced filters that require device join
        if (isNotBlank(req.getHasDevices()) || isNotBlank(req.getHasCamlamp())
                || isNotBlank(req.getAutoMode()) || isNotBlank(req.getFirmwareChannel())) {
            stores = filterByDeviceConditions(stores, req);
        }

        // Sort
        String sortBy = SORT_WHITELIST.contains(req.getSortBy()) ? req.getSortBy() : "createTime";
        String sortOrder = "asc".equalsIgnoreCase(req.getSortOrder()) ? "asc" : "desc";
        // Sorting will be applied after enrichment for computed fields

        // Enrich each store
        List<OpsAdminStoreResp> enriched = new ArrayList<>();
        for (StoreDO s : stores) {
            enriched.add(enrich(s));
        }

        // Sort enriched results
        Comparator<OpsAdminStoreResp> cmp = buildComparator(sortBy, sortOrder);
        enriched.sort(cmp);

        // Paginate in-memory (since we need enrichment before sorting)
        int total = enriched.size();
        int from = (page - 1) * pageSize;
        int to = Math.min(from + pageSize, total);
        if (from >= total) return List.of();
        return enriched.subList(from, to);
    }

    public int count(OpsAdminStorePageReq req) {
        LambdaQueryWrapper<StoreDO> qw = buildStoreWrapper(req);
        List<StoreDO> stores = storeMapper.selectList(qw);
        if (isNotBlank(req.getHasDevices()) || isNotBlank(req.getHasCamlamp())
                || isNotBlank(req.getAutoMode()) || isNotBlank(req.getFirmwareChannel())) {
            stores = filterByDeviceConditions(stores, req);
        }
        return stores.size();
    }

    public OpsAdminStoreDetailResp detail(Long storeId) {
        StoreDO s = storeMapper.selectById(storeId);
        if (s == null) return null;
        OpsAdminStoreResp row = enrich(s);
        return OpsAdminStoreDetailResp.from(row);
    }

    public List<OpsAdminStoreResp> export(OpsAdminStorePageReq req) {
        LambdaQueryWrapper<StoreDO> qw = buildStoreWrapper(req);
        List<StoreDO> stores = storeMapper.selectList(qw);
        if (isNotBlank(req.getHasDevices()) || isNotBlank(req.getHasCamlamp())
                || isNotBlank(req.getAutoMode()) || isNotBlank(req.getFirmwareChannel())) {
            stores = filterByDeviceConditions(stores, req);
        }
        if (stores.size() > EXPORT_MAX) {
            stores = stores.subList(0, EXPORT_MAX);
        }
        List<OpsAdminStoreResp> result = new ArrayList<>();
        for (StoreDO s : stores) {
            result.add(enrich(s));
        }
        String sortBy = SORT_WHITELIST.contains(req.getSortBy()) ? req.getSortBy() : "createTime";
        String sortOrder = "asc".equalsIgnoreCase(req.getSortOrder()) ? "asc" : "desc";
        result.sort(buildComparator(sortBy, sortOrder));
        return result;
    }

    private LambdaQueryWrapper<StoreDO> buildStoreWrapper(OpsAdminStorePageReq req) {
        LambdaQueryWrapper<StoreDO> qw = new LambdaQueryWrapper<>();
        if (isNotBlank(req.getKeyword())) {
            String kw = req.getKeyword().trim();
            qw.and(w -> w
                    .like(StoreDO::getStoreName, kw)
                    .or().like(StoreDO::getProvince, kw)
                    .or().like(StoreDO::getCity, kw)
                    .or().eq(StoreDO::getId, safeLong(kw))
                    .or().eq(StoreDO::getUserId, safeLong(kw)));
        }
        if (isNotBlank(req.getProvince())) qw.eq(StoreDO::getProvince, req.getProvince().trim());
        if (isNotBlank(req.getCity())) qw.eq(StoreDO::getCity, req.getCity().trim());
        if (isNotBlank(req.getStoreStyle())) qw.eq(StoreDO::getStoreStyle, req.getStoreStyle().trim());
        if (req.getMinArea() != null) qw.ge(StoreDO::getArea, req.getMinArea());
        if (req.getMaxArea() != null) qw.le(StoreDO::getArea, req.getMaxArea());
        return qw;
    }

    private List<StoreDO> filterByDeviceConditions(List<StoreDO> stores, OpsAdminStorePageReq req) {
        List<StoreDO> result = new ArrayList<>();
        for (StoreDO s : stores) {
            List<DeviceDO> devs = deviceMapper.selectList(
                    new LambdaQueryWrapper<DeviceDO>().eq(DeviceDO::getStoreId, s.getId()));
            if (isNotBlank(req.getHasDevices())) {
                if ("yes".equals(req.getHasDevices()) && devs.isEmpty()) continue;
                if ("no".equals(req.getHasDevices()) && !devs.isEmpty()) continue;
            }
            if (isNotBlank(req.getHasCamlamp())) {
                boolean has = devs.stream().anyMatch(d -> "camlamp".equalsIgnoreCase(d.getDeviceType()));
                if ("yes".equals(req.getHasCamlamp()) && !has) continue;
                if ("no".equals(req.getHasCamlamp()) && has) continue;
            }
            if (isNotBlank(req.getAutoMode())) {
                boolean hasAuto = devs.stream().anyMatch(d -> Boolean.TRUE.equals(d.getAutoMode()));
                if ("yes".equals(req.getAutoMode()) && !hasAuto) continue;
                if ("no".equals(req.getAutoMode()) && hasAuto) continue;
            }
            if (isNotBlank(req.getFirmwareChannel())) {
                boolean has = devs.stream().anyMatch(d -> req.getFirmwareChannel().equalsIgnoreCase(d.getFirmwareChannel()));
                if (!has) continue;
            }
            result.add(s);
        }
        return result;
    }

    private OpsAdminStoreResp enrich(StoreDO s) {
        OpsAdminStoreResp r = new OpsAdminStoreResp();
        // Base
        r.setId(String.valueOf(s.getId()));
        r.setUserId(String.valueOf(s.getUserId()));
        r.setStoreName(s.getStoreName());
        r.setStoreStyle(s.getStoreStyle());
        r.setArea(s.getArea());
        r.setProvince(s.getProvince());
        r.setCity(s.getCity());
        r.setLatitude(s.getLatitude());
        r.setLongitude(s.getLongitude());
        r.setCreateTime(fmt(s.getCreateTime()));
        r.setUpdateTime(fmt(s.getUpdateTime()));

        // Device stats
        List<DeviceDO> devs = deviceMapper.selectList(
                new LambdaQueryWrapper<DeviceDO>().eq(DeviceDO::getStoreId, s.getId()));
        r.setDeviceCount(devs.size());
        r.setLampCount((int) devs.stream().filter(d -> !"camlamp".equalsIgnoreCase(d.getDeviceType())).count());
        r.setCamlampCount((int) devs.stream().filter(d -> "camlamp".equalsIgnoreCase(d.getDeviceType())).count());
        r.setAutoModeDeviceCount((int) devs.stream().filter(d -> Boolean.TRUE.equals(d.getAutoMode())).count());
        r.setManualModeDeviceCount(r.getDeviceCount() - r.getAutoModeDeviceCount());
        r.setStableFirmwareCount((int) devs.stream().filter(d -> "stable".equalsIgnoreCase(d.getFirmwareChannel())).count());
        r.setTestFirmwareCount((int) devs.stream().filter(d -> "test".equalsIgnoreCase(d.getFirmwareChannel())).count());
        r.setOtaUpdatingCount((int) devs.stream().filter(d -> "updating".equalsIgnoreCase(d.getOtaStatus())).count());
        r.setOtaFailedCount((int) devs.stream().filter(d -> "failed".equalsIgnoreCase(d.getOtaStatus())).count());

        // Lighting params from device
        BigDecimal avgB = avgInt(devs, DeviceDO::getBrightness);
        BigDecimal avgT = avgInt(devs, DeviceDO::getTemp);
        BigDecimal avgRB = avgInt(devs, DeviceDO::getRecommendedBrightness);
        BigDecimal avgRT = avgInt(devs, DeviceDO::getRecommendedTemp);
        r.setAvgBrightness(avgB);
        r.setAvgTemp(avgT);
        r.setAvgRecommendedBrightness(avgRB);
        r.setAvgRecommendedTemp(avgRT);
        r.setAutoModeRatio(r.getDeviceCount() > 0 ? BigDecimal.valueOf(r.getAutoModeDeviceCount())
                .divide(BigDecimal.valueOf(r.getDeviceCount()), 4, RoundingMode.HALF_UP) : null);

        // Deviations
        if (avgB != null && avgRB != null) r.setBrightnessDeviationAvg(avgB.subtract(avgRB));
        if (avgT != null && avgRT != null) r.setTempDeviationAvg(avgT.subtract(avgRT));

        // Lux
        enrichLux(s.getId(), r);

        // Duration
        enrichDuration(s.getId(), r);

        // Weather
        enrichWeather(s.getId(), r);

        // Strategy
        enrichStrategy(r);

        return r;
    }

    private void enrichLux(Long storeId, OpsAdminStoreResp r) {
        try {
            List<LuxRecordDO> all = luxRecordMapper.selectList(
                    new LambdaQueryWrapper<LuxRecordDO>().eq(LuxRecordDO::getStoreId, storeId)
                            .orderByDesc(LuxRecordDO::getCollectTime));
            if (all.isEmpty()) return;
            LuxRecordDO latest = all.get(0);
            r.setLatestLux(latest.getLuxValue());
            r.setLatestLuxTime(fmt(latest.getCollectTime()));

            LocalDate today = LocalDate.now();
            List<LuxRecordDO> todayList = all.stream()
                    .filter(x -> x.getCollectTime() != null && x.getCollectTime().toLocalDate().equals(today))
                    .collect(Collectors.toList());
            r.setLuxRecordCountToday(todayList.size());
            if (!todayList.isEmpty()) {
                BigDecimal sum = BigDecimal.ZERO;
                BigDecimal max = BigDecimal.ZERO;
                BigDecimal min = new BigDecimal("999999");
                for (LuxRecordDO rec : todayList) {
                    BigDecimal v = rec.getLuxValue() != null ? rec.getLuxValue() : BigDecimal.ZERO;
                    sum = sum.add(v);
                    if (v.compareTo(max) > 0) max = v;
                    if (v.compareTo(min) < 0) min = v;
                }
                r.setAvgLuxToday(sum.divide(BigDecimal.valueOf(todayList.size()), 2, RoundingMode.HALF_UP));
                r.setMaxLuxToday(max);
                r.setMinLuxToday(min.compareTo(new BigDecimal("999999")) < 0 ? min : null);
            }
        } catch (Exception e) {
            log.warn("[ops-admin] Failed to collect lux for store {}: {}", storeId, e.getMessage());
        }
    }

    private void enrichDuration(Long storeId, OpsAdminStoreResp r) {
        try {
            List<DurationRecordDO> all = durationRecordMapper.selectList(
                    new LambdaQueryWrapper<DurationRecordDO>().eq(DurationRecordDO::getStoreId, storeId));
            if (all.isEmpty()) return;
            LocalDate today = LocalDate.now();
            long todayTotal = 0;
            long todayCount = 0;
            long grandTotal = 0;
            for (DurationRecordDO rec : all) {
                long v = rec.getDurationValue() != null ? rec.getDurationValue() : 0;
                grandTotal += v;
                if (rec.getStatDate() != null && rec.getStatDate().equals(today)) {
                    todayTotal += v;
                    todayCount++;
                }
            }
            r.setDurationToday(todayTotal);
            r.setDurationTotal(grandTotal);
            r.setAvgDurationToday(todayCount > 0 ? todayTotal / todayCount : null);
            r.setDurationRecordCountToday((int) todayCount);
        } catch (Exception e) {
            log.warn("[ops-admin] Failed to collect duration for store {}: {}", storeId, e.getMessage());
        }
    }

    private void enrichWeather(Long storeId, OpsAdminStoreResp r) {
        try {
            List<WeatherRecordDO> all = weatherRecordMapper.selectList(
                    new LambdaQueryWrapper<WeatherRecordDO>().eq(WeatherRecordDO::getStoreId, storeId)
                            .orderByDesc(WeatherRecordDO::getCollectTime));
            if (all.isEmpty()) return;
            WeatherRecordDO w = all.get(0);
            r.setLatestWeatherText(w.getWeatherText());
            r.setLatestWeatherCode(w.getWeatherCode());
            r.setLatestOutdoorTemp(w.getTemperature());
            r.setLatestApparentTemp(w.getApparentTemperature());
            r.setLatestHumidity(w.getHumidity());
            r.setLatestWindSpeed(w.getWindSpeed());
            r.setLatestTempMax(w.getTempMax());
            r.setLatestTempMin(w.getTempMin());
            r.setLatestWeatherTime(fmt(w.getCollectTime()));
        } catch (Exception e) {
            log.warn("[ops-admin] Failed to collect weather for store {}: {}", storeId, e.getMessage());
        }
    }

    private void enrichStrategy(OpsAdminStoreResp r) {
        BigDecimal area = r.getArea();
        boolean hasArea = area != null && area.compareTo(BigDecimal.ZERO) > 0;
        r.setDeviceDensity(hasArea ? BigDecimal.valueOf(r.getDeviceCount())
                .divide(area, 4, RoundingMode.HALF_UP) : null);
        r.setLampDensity(hasArea ? BigDecimal.valueOf(r.getLampCount())
                .divide(area, 4, RoundingMode.HALF_UP) : null);
        r.setCamlampDensity(hasArea ? BigDecimal.valueOf(r.getCamlampCount())
                .divide(area, 4, RoundingMode.HALF_UP) : null);
        r.setLuxPerArea(hasArea && r.getLatestLux() != null ? r.getLatestLux()
                .divide(area, 4, RoundingMode.HALF_UP) : null);
        r.setDurationPerAreaToday(hasArea && r.getDurationToday() != null ? BigDecimal.valueOf(r.getDurationToday())
                .divide(area, 4, RoundingMode.HALF_UP) : null);
        r.setHasCamlamp(r.getCamlampCount() > 0);
        r.setHasAutoModeDevices(r.getAutoModeDeviceCount() > 0);

        // lightLevelStatus
        if (r.getLatestLux() == null) r.setLightLevelStatus("UNKNOWN");
        else if (r.getLatestLux().compareTo(new BigDecimal("300")) < 0) r.setLightLevelStatus("LOW");
        else if (r.getLatestLux().compareTo(new BigDecimal("800")) > 0) r.setLightLevelStatus("HIGH");
        else r.setLightLevelStatus("NORMAL");

        // energyStrategyHint
        if (r.getLatestLux() == null || r.getDeviceCount() == 0) {
            r.setEnergyStrategyHint("数据不足");
        } else if ("HIGH".equals(r.getLightLevelStatus()) && r.getDurationToday() != null && r.getDurationToday() > 0) {
            r.setEnergyStrategyHint("可关注节能调光");
        } else if ("LOW".equals(r.getLightLevelStatus())) {
            r.setEnergyStrategyHint("可关注补光策略");
        } else if (r.getAutoModeDeviceCount() == 0 && r.getDeviceCount() > 0) {
            r.setEnergyStrategyHint("可关注自动模式覆盖率");
        } else {
            r.setEnergyStrategyHint("暂无明显异常");
        }
    }

    private Comparator<OpsAdminStoreResp> buildComparator(String sortBy, String sortOrder) {
        boolean asc = "asc".equalsIgnoreCase(sortOrder);
        return switch (sortBy) {
            case "area" -> asc ? Comparator.nullsLast(Comparator.comparing(OpsAdminStoreResp::getArea))
                    : Comparator.nullsLast(Comparator.comparing(OpsAdminStoreResp::getArea).reversed());
            case "deviceCount" -> asc ? Comparator.comparingInt(OpsAdminStoreResp::getDeviceCount)
                    : Comparator.comparingInt(OpsAdminStoreResp::getDeviceCount).reversed();
            case "latestLux" -> asc ? Comparator.nullsLast(Comparator.comparing(OpsAdminStoreResp::getLatestLux))
                    : Comparator.nullsLast(Comparator.comparing(OpsAdminStoreResp::getLatestLux).reversed());
            case "durationToday" -> asc ? Comparator.nullsLast(Comparator.comparing(OpsAdminStoreResp::getDurationToday))
                    : Comparator.nullsLast(Comparator.comparing(OpsAdminStoreResp::getDurationToday).reversed());
            default -> asc ? Comparator.comparing(OpsAdminStoreResp::getCreateTime)
                    : Comparator.comparing(OpsAdminStoreResp::getCreateTime).reversed();
        };
    }

    // Helpers
    private String fmt(LocalDateTime dt) {
        return dt != null ? DT_FMT.format(dt) : null;
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    private Long safeLong(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0L; }
    }

    private BigDecimal avgInt(List<DeviceDO> devs, java.util.function.Function<DeviceDO, Integer> getter) {
        List<Integer> vals = devs.stream().map(getter).filter(Objects::nonNull).toList();
        if (vals.isEmpty()) return null;
        long sum = 0;
        for (Integer v : vals) sum += v;
        return BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(vals.size()), 2, RoundingMode.HALF_UP);
    }

    // --- Time-series export ---

    public List<Map<String, Object>> timeSeriesExport(OpsAdminTimeSeriesExportReq req) {
        List<Map<String, Object>> result = new ArrayList<>();
        List<Long> sids = new ArrayList<>();
        if (req.getStoreIds() != null) {
            for (String sid : req.getStoreIds()) {
                try { sids.add(Long.parseLong(sid)); } catch (NumberFormatException ignored) {}
            }
        }
        if (sids.isEmpty()) return result;

        LocalDateTime start = parseDt(req.getStartTime());
        LocalDateTime end = parseDt(req.getEndTime());
        if (start == null) start = LocalDateTime.now().minusDays(7);
        if (end == null) end = LocalDateTime.now();

        // Preload store names
        Map<Long, String> storeNames = new LinkedHashMap<>();
        for (Long sid : sids) {
            StoreDO s = storeMapper.selectById(sid);
            storeNames.put(sid, s != null ? s.getStoreName() : String.valueOf(sid));
        }

        // Preload device info
        Map<Long, DeviceDO> deviceMap = new LinkedHashMap<>();
        for (Long sid : sids) {
            List<DeviceDO> devs = deviceMapper.selectList(
                    new LambdaQueryWrapper<DeviceDO>().eq(DeviceDO::getStoreId, sid));
            for (DeviceDO d : devs) deviceMap.put(d.getId(), d);
        }

        List<String> types = req.getDataTypes() != null ? req.getDataTypes() : List.of("lux", "duration", "weather");

        if (types.contains("lux")) {
            List<LuxRecordDO> luxList = luxRecordMapper.selectList(
                    new LambdaQueryWrapper<LuxRecordDO>()
                            .in(LuxRecordDO::getStoreId, sids)
                            .ge(start != null, LuxRecordDO::getCollectTime, start)
                            .le(end != null, LuxRecordDO::getCollectTime, end)
                            .orderByAsc(LuxRecordDO::getCollectTime));
            for (LuxRecordDO rec : luxList) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("dataType", "lux");
                row.put("recordTime", fmt(rec.getCollectTime()));
                StoreDO st = storeMapper.selectById(rec.getStoreId());
                row.put("storeId", String.valueOf(rec.getStoreId()));
                row.put("storeName", st != null ? st.getStoreName() : String.valueOf(rec.getStoreId()));
                row.put("province", st != null ? st.getProvince() : "");
                row.put("city", st != null ? st.getCity() : "");
                row.put("deviceId", rec.getDeviceId() != null ? String.valueOf(rec.getDeviceId()) : "");
                row.put("chipId", rec.getChipId() != null ? rec.getChipId() : "");
                DeviceDO dev = rec.getDeviceId() != null ? deviceMap.get(rec.getDeviceId()) : null;
                row.put("deviceType", dev != null && dev.getDeviceType() != null ? dev.getDeviceType() : "");
                row.put("luxValue", rec.getLuxValue());
                row.put("lightLevelStatus", luxToStatus(rec.getLuxValue()));
                result.add(row);
            }
        }

        if (types.contains("duration")) {
            List<DurationRecordDO> durList = durationRecordMapper.selectList(
                    new LambdaQueryWrapper<DurationRecordDO>()
                            .in(DurationRecordDO::getStoreId, sids)
                            .ge(start != null, DurationRecordDO::getCollectTime, start)
                            .le(end != null, DurationRecordDO::getCollectTime, end)
                            .orderByAsc(DurationRecordDO::getCollectTime));
            for (DurationRecordDO rec : durList) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("dataType", "duration");
                row.put("recordTime", fmt(rec.getCollectTime()));
                StoreDO st = storeMapper.selectById(rec.getStoreId());
                row.put("storeId", String.valueOf(rec.getStoreId()));
                row.put("storeName", st != null ? st.getStoreName() : String.valueOf(rec.getStoreId()));
                row.put("province", st != null ? st.getProvince() : "");
                row.put("city", st != null ? st.getCity() : "");
                row.put("deviceId", rec.getDeviceId() != null ? String.valueOf(rec.getDeviceId()) : "");
                row.put("chipId", rec.getChipId() != null ? rec.getChipId() : "");
                DeviceDO dev = rec.getDeviceId() != null ? deviceMap.get(rec.getDeviceId()) : null;
                row.put("deviceType", dev != null && dev.getDeviceType() != null ? dev.getDeviceType() : "");
                row.put("durationMinutes", rec.getDurationValue() != null ? rec.getDurationValue() / 60000 : 0);
                row.put("autoMode", dev != null && dev.getAutoMode() != null ? dev.getAutoMode() : null);
                result.add(row);
            }
        }

        if (types.contains("weather")) {
            List<WeatherRecordDO> weatherList = weatherRecordMapper.selectList(
                    new LambdaQueryWrapper<WeatherRecordDO>()
                            .in(WeatherRecordDO::getStoreId, sids)
                            .ge(start != null, WeatherRecordDO::getCollectTime, start)
                            .le(end != null, WeatherRecordDO::getCollectTime, end)
                            .orderByAsc(WeatherRecordDO::getCollectTime));
            for (WeatherRecordDO rec : weatherList) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("dataType", "weather");
                row.put("recordTime", fmt(rec.getCollectTime()));
                StoreDO st = storeMapper.selectById(rec.getStoreId());
                row.put("storeId", String.valueOf(rec.getStoreId()));
                row.put("storeName", st != null ? st.getStoreName() : String.valueOf(rec.getStoreId()));
                row.put("province", st != null ? st.getProvince() : "");
                row.put("city", st != null ? st.getCity() : "");
                row.put("weatherText", rec.getWeatherText());
                row.put("weatherCode", rec.getWeatherCode());
                row.put("temperature", rec.getTemperature());
                row.put("apparentTemperature", rec.getApparentTemperature());
                row.put("humidity", rec.getHumidity());
                row.put("windSpeed", rec.getWindSpeed());
                row.put("tempMax", rec.getTempMax());
                row.put("tempMin", rec.getTempMin());
                result.add(row);
            }
        }

        return result;
    }

    private LocalDateTime parseDt(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDateTime.parse(s.replace("T", " ").substring(0, 19).trim(), DT_FMT); }
        catch (Exception e) { return null; }
    }

    private String luxToStatus(BigDecimal lux) {
        if (lux == null) return "UNKNOWN";
        double v = lux.doubleValue();
        if (v < 300) return "LOW";
        if (v > 800) return "HIGH";
        return "NORMAL";
    }

    // --- Store timeline ---

    public OpsAdminStoreTimelineResp timeline(Long storeId, String startTime, String endTime, String granularity) {
        StoreDO st = storeMapper.selectById(storeId);
        if (st == null) return null;

        LocalDateTime start = parseDt(startTime);
        LocalDateTime end = parseDt(endTime);
        if (start == null) start = LocalDateTime.now().minusDays(7);
        if (end == null) end = LocalDateTime.now();

        String gran = granularity != null ? granularity : "hour";
        int rawLimit = "raw".equals(gran) ? 2000 : Integer.MAX_VALUE;

        OpsAdminStoreTimelineResp resp = new OpsAdminStoreTimelineResp();
        resp.setStoreId(String.valueOf(storeId));
        resp.setStoreName(st.getStoreName());
        resp.setStartTime(fmt(start));
        resp.setEndTime(fmt(end));
        resp.setGranularity(gran);

        // Lux series
        List<LuxRecordDO> luxList = luxRecordMapper.selectList(
                new LambdaQueryWrapper<LuxRecordDO>()
                        .eq(LuxRecordDO::getStoreId, storeId)
                        .ge(LuxRecordDO::getCollectTime, start)
                        .le(LuxRecordDO::getCollectTime, end)
                        .orderByAsc(LuxRecordDO::getCollectTime));
        if (luxList.size() > rawLimit) luxList = luxList.subList(luxList.size() - rawLimit, luxList.size());
        resp.setLuxSeries(aggregateLux(luxList, gran));

        // Duration series
        List<DurationRecordDO> durList = durationRecordMapper.selectList(
                new LambdaQueryWrapper<DurationRecordDO>()
                        .eq(DurationRecordDO::getStoreId, storeId)
                        .ge(DurationRecordDO::getCollectTime, start)
                        .le(DurationRecordDO::getCollectTime, end)
                        .orderByAsc(DurationRecordDO::getCollectTime));
        if (durList.size() > rawLimit) durList = durList.subList(durList.size() - rawLimit, durList.size());
        resp.setDurationSeries(aggregateDuration(durList, gran));

        // Brightness/temp history not available (DeviceDO is snapshot only, DurationRecordDO lacks these fields)
        resp.setBrightnessSeries(List.of());
        resp.setTempSeries(List.of());

        // Weather series
        List<WeatherRecordDO> weatherList = weatherRecordMapper.selectList(
                new LambdaQueryWrapper<WeatherRecordDO>()
                        .eq(WeatherRecordDO::getStoreId, storeId)
                        .ge(WeatherRecordDO::getCollectTime, start)
                        .le(WeatherRecordDO::getCollectTime, end)
                        .orderByAsc(WeatherRecordDO::getCollectTime));
        if (weatherList.size() > rawLimit) weatherList = weatherList.subList(weatherList.size() - rawLimit, weatherList.size());
        resp.setWeatherSeries(aggregateWeather(weatherList, gran));
        resp.setWeatherMeta(buildWeatherMeta(weatherList, gran, start, end));

        return resp;
    }

    private List<Map<String, Object>> aggregateLux(List<LuxRecordDO> records, String gran) {
        if (records.isEmpty()) return List.of();
        if ("raw".equals(gran)) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (LuxRecordDO r : records) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("time", fmt(r.getCollectTime()));
                m.put("avgLux", r.getLuxValue());
                m.put("maxLux", r.getLuxValue());
                m.put("minLux", r.getLuxValue());
                m.put("count", 1);
                list.add(m);
            }
            return list;
        }
        return luxBucket(records, gran);
    }

    private List<Map<String, Object>> luxBucket(List<LuxRecordDO> records, String gran) {
        Map<String, List<BigDecimal>> buckets = new LinkedHashMap<>();
        for (LuxRecordDO r : records) {
            String key = bucketKey(r.getCollectTime(), gran);
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(r.getLuxValue() != null ? r.getLuxValue() : BigDecimal.ZERO);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (var e : buckets.entrySet()) {
            List<BigDecimal> vals = e.getValue();
            BigDecimal sum = vals.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal avg = sum.divide(BigDecimal.valueOf(vals.size()), 2, RoundingMode.HALF_UP);
            BigDecimal max = vals.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            BigDecimal min = vals.stream().min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("time", e.getKey());
            m.put("avgLux", avg);
            m.put("maxLux", max);
            m.put("minLux", min);
            m.put("count", vals.size());
            result.add(m);
        }
        return result;
    }

    private List<Map<String, Object>> aggregateDuration(List<DurationRecordDO> records, String gran) {
        if (records.isEmpty()) return List.of();
        if ("raw".equals(gran)) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (DurationRecordDO r : records) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("time", fmt(r.getCollectTime()));
                m.put("durationMinutes", r.getDurationValue() != null ? r.getDurationValue() / 60000 : 0);
                m.put("deviceCount", 1);
                list.add(m);
            }
            return list;
        }
        Map<String, List<Long>> buckets = new LinkedHashMap<>();
        for (DurationRecordDO r : records) {
            String key = bucketKey(r.getCollectTime(), gran);
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(r.getDurationValue() != null ? r.getDurationValue() : 0L);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (var e : buckets.entrySet()) {
            long total = e.getValue().stream().mapToLong(Long::longValue).sum();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("time", e.getKey());
            m.put("durationMinutes", total / 60000);
            m.put("deviceCount", e.getValue().size());
            result.add(m);
        }
        return result;
    }

    private List<Map<String, Object>> aggregateWeather(List<WeatherRecordDO> records, String gran) {
        if (records.isEmpty()) return List.of();
        if ("raw".equals(gran)) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (WeatherRecordDO r : records) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("time", fmt(r.getCollectTime()));
                m.put("weatherText", r.getWeatherText());
                m.put("outdoorTemp", r.getTemperature());
                m.put("apparentTemp", r.getApparentTemperature());
                m.put("humidity", r.getHumidity());
                m.put("windSpeed", r.getWindSpeed());
                list.add(m);
            }
            return list;
        }
        Map<String, List<WeatherRecordDO>> buckets = new LinkedHashMap<>();
        for (WeatherRecordDO r : records) {
            String key = bucketKey(r.getCollectTime(), gran);
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (var e : buckets.entrySet()) {
            List<WeatherRecordDO> list = e.getValue();
            WeatherRecordDO latest = list.get(list.size() - 1);
            BigDecimal avgTemp = list.stream().map(WeatherRecordDO::getTemperature).filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(list.size()), 1, RoundingMode.HALF_UP);
            BigDecimal avgHumidity = list.stream().map(WeatherRecordDO::getHumidity).filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(list.size()), 1, RoundingMode.HALF_UP);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("time", e.getKey());
            m.put("weatherText", latest.getWeatherText());
            m.put("outdoorTemp", avgTemp);
            m.put("apparentTemp", latest.getApparentTemperature());
            m.put("humidity", avgHumidity);
            m.put("windSpeed", latest.getWindSpeed());
            result.add(m);
        }
        return result;
    }

    private Map<String, Object> buildWeatherMeta(List<WeatherRecordDO> weatherList, String gran,
                                                   LocalDateTime start, LocalDateTime end) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("hasData", !weatherList.isEmpty());
        meta.put("pointCount", weatherList.size());
        meta.put("latestWeatherTime", weatherList.isEmpty() ? null : fmt(weatherList.get(weatherList.size() - 1).getCollectTime()));

        // Check completeness
        if (weatherList.isEmpty()) {
            meta.put("missingHint", "当前时间范围内暂无天气记录");
            meta.put("stale", true);
        } else {
            long expectedPoints = estimateExpectedPoints(start, end, gran);
            if (weatherList.size() < expectedPoints * 0.6) {
                meta.put("missingHint", "天气记录数量较少，可能存在接口 502、网络波动或采集失败");
                meta.put("stale", true);
            } else {
                meta.put("stale", false);
            }
        }
        return meta;
    }

    private long estimateExpectedPoints(LocalDateTime start, LocalDateTime end, String gran) {
        long hours = java.time.Duration.between(start, end).toHours();
        if (hours <= 0) return 1;
        if ("day".equals(gran)) return Math.max(1, hours / 24);
        return Math.max(1, hours);
    }

    private String bucketKey(LocalDateTime dt, String gran) {
        if ("day".equals(gran)) return dt.toLocalDate().toString();
        // hour
        return dt.toLocalDate().toString() + " " + String.format("%02d:00", dt.getHour());
    }
}

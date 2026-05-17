package com.genius.smartlight.opsadmin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.genius.smartlight.dal.dataobject.*;
import com.genius.smartlight.dal.mysql.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Slf4j
@Service
public class OpsAdminDashboardService {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private final StoreMapper storeMapper;
    private final DeviceMapper deviceMapper;
    private final LuxRecordMapper luxRecordMapper;
    private final DurationRecordMapper durationRecordMapper;
    private final WeatherRecordMapper weatherRecordMapper;

    @Value("${OPS_AI_ENABLED:false}")
    private boolean opsAiEnabled;
    @Value("${OPS_AI_MODEL:}")
    private String opsAiModel;

    public OpsAdminDashboardService(StoreMapper storeMapper, DeviceMapper deviceMapper,
                                     LuxRecordMapper luxRecordMapper, DurationRecordMapper durationRecordMapper,
                                     WeatherRecordMapper weatherRecordMapper) {
        this.storeMapper = storeMapper;
        this.deviceMapper = deviceMapper;
        this.luxRecordMapper = luxRecordMapper;
        this.durationRecordMapper = durationRecordMapper;
        this.weatherRecordMapper = weatherRecordMapper;
    }

    public Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("backendOnline", true);
        m.put("serverTime", DT_FMT.format(Instant.now()));

        LocalDateTime todayStart = LocalDate.now().atStartOfDay();

        try { m.put("storeCount", storeMapper.selectCount(null)); } catch (Exception e) { m.put("storeCount", 0); }
        try { m.put("deviceCount", deviceMapper.selectCount(null)); } catch (Exception e) { m.put("deviceCount", 0); }

        try {
            int total = Math.toIntExact(deviceMapper.selectCount(null));
            int auto = Math.toIntExact(deviceMapper.selectCount(
                    new LambdaQueryWrapper<DeviceDO>().eq(DeviceDO::getAutoMode, true)));
            m.put("autoModeRatio", total > 0 ? Math.round((double) auto / total * 100.0) / 100.0 : 0);
        } catch (Exception e) { m.put("autoModeRatio", 0); }

        try { m.put("todayLuxRecordCount", luxRecordMapper.selectCount(
                new LambdaQueryWrapper<LuxRecordDO>().ge(LuxRecordDO::getCreateTime, todayStart))); }
        catch (Exception e) { m.put("todayLuxRecordCount", 0); }

        try { m.put("todayDurationRecordCount", durationRecordMapper.selectCount(
                new LambdaQueryWrapper<DurationRecordDO>().ge(DurationRecordDO::getCreateTime, todayStart))); }
        catch (Exception e) { m.put("todayDurationRecordCount", 0); }

        // Weather: real data
        try {
            long todayWeather = weatherRecordMapper.selectCount(
                    new LambdaQueryWrapper<WeatherRecordDO>().ge(WeatherRecordDO::getCreateTime, todayStart));
            m.put("todayWeatherRecordCount", todayWeather);
            WeatherRecordDO latest = weatherRecordMapper.selectOne(
                    new LambdaQueryWrapper<WeatherRecordDO>().orderByDesc(WeatherRecordDO::getCollectTime).last("LIMIT 1"));
            m.put("latestWeatherTime", latest != null ? DT_FMT.format(latest.getCollectTime()) : null);
            m.put("weatherFailedCountToday", null); // requires log analysis, not available here
            // Status: normal if recent data exists (within 3h), warning if stale, unknown if no data
            if (latest == null) {
                m.put("weatherStatus", "unknown");
            } else if (latest.getCollectTime().isAfter(LocalDateTime.now().minus(3, ChronoUnit.HOURS))) {
                m.put("weatherStatus", "normal");
            } else {
                m.put("weatherStatus", "warning");
            }
        } catch (Exception e) {
            m.put("todayWeatherRecordCount", 0);
            m.put("latestWeatherTime", null);
            m.put("weatherFailedCountToday", null);
            m.put("weatherStatus", "unknown");
        }

        // AI fabric health: unknown without live check
        m.put("aiFabricHealth", "unknown");

        // Log AI mode: from env config
        if (opsAiEnabled && !opsAiModel.isBlank()) {
            m.put("logAiMode", opsAiModel.contains("deepseek") ? "deepseek" : "configured");
        } else {
            m.put("logAiMode", "rule");
        }

        // OTA updating count: from device table
        try {
            long otaUpdating = deviceMapper.selectCount(
                    new LambdaQueryWrapper<DeviceDO>().eq(DeviceDO::getOtaStatus, "updating"));
            m.put("otaUpdatingCount", (int) otaUpdating);
        } catch (Exception e) { m.put("otaUpdatingCount", 0); }

        return m;
    }
}

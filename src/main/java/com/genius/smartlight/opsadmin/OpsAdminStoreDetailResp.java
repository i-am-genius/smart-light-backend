package com.genius.smartlight.opsadmin;

import lombok.Data;
import java.math.BigDecimal;
import java.util.Map;
import java.util.LinkedHashMap;

@Data
public class OpsAdminStoreDetailResp {
    private Map<String, Object> base;
    private Map<String, Object> devices;
    private Map<String, Object> lighting;
    private Map<String, Object> duration;
    private Map<String, Object> recommendation;
    private Map<String, Object> weather;
    private Map<String, Object> strategy;

    public static OpsAdminStoreDetailResp from(OpsAdminStoreResp row) {
        OpsAdminStoreDetailResp r = new OpsAdminStoreDetailResp();

        r.base = new LinkedHashMap<>();
        r.base.put("id", row.getId());
        r.base.put("userId", row.getUserId());
        r.base.put("storeName", row.getStoreName());
        r.base.put("storeStyle", row.getStoreStyle());
        r.base.put("area", row.getArea());
        r.base.put("province", row.getProvince());
        r.base.put("city", row.getCity());
        r.base.put("latitude", row.getLatitude());
        r.base.put("longitude", row.getLongitude());
        r.base.put("createTime", row.getCreateTime());
        r.base.put("updateTime", row.getUpdateTime());

        r.devices = new LinkedHashMap<>();
        r.devices.put("deviceCount", row.getDeviceCount());
        r.devices.put("lampCount", row.getLampCount());
        r.devices.put("camlampCount", row.getCamlampCount());
        r.devices.put("autoModeDeviceCount", row.getAutoModeDeviceCount());
        r.devices.put("manualModeDeviceCount", row.getManualModeDeviceCount());
        r.devices.put("stableFirmwareCount", row.getStableFirmwareCount());
        r.devices.put("testFirmwareCount", row.getTestFirmwareCount());
        r.devices.put("otaUpdatingCount", row.getOtaUpdatingCount());
        r.devices.put("otaFailedCount", row.getOtaFailedCount());

        r.lighting = new LinkedHashMap<>();
        r.lighting.put("latestLux", row.getLatestLux());
        r.lighting.put("latestLuxTime", row.getLatestLuxTime());
        r.lighting.put("avgLuxToday", row.getAvgLuxToday());
        r.lighting.put("maxLuxToday", row.getMaxLuxToday());
        r.lighting.put("minLuxToday", row.getMinLuxToday());
        r.lighting.put("luxRecordCountToday", row.getLuxRecordCountToday());

        r.duration = new LinkedHashMap<>();
        r.duration.put("durationToday", row.getDurationToday());
        r.duration.put("durationTotal", row.getDurationTotal());
        r.duration.put("avgDurationToday", row.getAvgDurationToday());
        r.duration.put("durationRecordCountToday", row.getDurationRecordCountToday());

        r.recommendation = new LinkedHashMap<>();
        r.recommendation.put("avgBrightness", row.getAvgBrightness());
        r.recommendation.put("avgTemp", row.getAvgTemp());
        r.recommendation.put("avgRecommendedBrightness", row.getAvgRecommendedBrightness());
        r.recommendation.put("avgRecommendedTemp", row.getAvgRecommendedTemp());
        r.recommendation.put("autoModeRatio", row.getAutoModeRatio());
        r.recommendation.put("brightnessDeviationAvg", row.getBrightnessDeviationAvg());
        r.recommendation.put("tempDeviationAvg", row.getTempDeviationAvg());

        r.weather = new LinkedHashMap<>();
        r.weather.put("latestWeatherText", row.getLatestWeatherText());
        r.weather.put("latestWeatherCode", row.getLatestWeatherCode());
        r.weather.put("latestOutdoorTemp", row.getLatestOutdoorTemp());
        r.weather.put("latestApparentTemp", row.getLatestApparentTemp());
        r.weather.put("latestHumidity", row.getLatestHumidity());
        r.weather.put("latestWindSpeed", row.getLatestWindSpeed());
        r.weather.put("latestTempMax", row.getLatestTempMax());
        r.weather.put("latestTempMin", row.getLatestTempMin());
        r.weather.put("latestWeatherTime", row.getLatestWeatherTime());

        r.strategy = new LinkedHashMap<>();
        r.strategy.put("deviceDensity", row.getDeviceDensity());
        r.strategy.put("lampDensity", row.getLampDensity());
        r.strategy.put("camlampDensity", row.getCamlampDensity());
        r.strategy.put("luxPerArea", row.getLuxPerArea());
        r.strategy.put("durationPerAreaToday", row.getDurationPerAreaToday());
        r.strategy.put("hasCamlamp", row.isHasCamlamp());
        r.strategy.put("hasAutoModeDevices", row.isHasAutoModeDevices());
        r.strategy.put("lightLevelStatus", row.getLightLevelStatus());
        r.strategy.put("energyStrategyHint", row.getEnergyStrategyHint());

        return r;
    }
}

package com.genius.smartlight.opsadmin;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class OpsAdminStoreResp {
    // base
    private String id;
    private String userId;
    private String storeName;
    private String storeStyle;
    private BigDecimal area;
    private String province;
    private String city;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private String createTime;
    private String updateTime;

    // device stats
    private int deviceCount;
    private int lampCount;
    private int camlampCount;
    private int autoModeDeviceCount;
    private int manualModeDeviceCount;
    private int stableFirmwareCount;
    private int testFirmwareCount;
    private int otaUpdatingCount;
    private int otaFailedCount;

    // lighting params
    private BigDecimal avgBrightness;
    private BigDecimal avgTemp;
    private BigDecimal avgRecommendedBrightness;
    private BigDecimal avgRecommendedTemp;
    private BigDecimal autoModeRatio;
    private BigDecimal brightnessDeviationAvg;
    private BigDecimal tempDeviationAvg;

    // lux
    private BigDecimal latestLux;
    private String latestLuxTime;
    private BigDecimal avgLuxToday;
    private BigDecimal maxLuxToday;
    private BigDecimal minLuxToday;
    private int luxRecordCountToday;

    // duration
    private Long durationToday;
    private Long durationTotal;
    private Long avgDurationToday;
    private int durationRecordCountToday;

    // weather
    private String latestWeatherText;
    private Integer latestWeatherCode;
    private BigDecimal latestOutdoorTemp;
    private BigDecimal latestApparentTemp;
    private BigDecimal latestHumidity;
    private BigDecimal latestWindSpeed;
    private BigDecimal latestTempMax;
    private BigDecimal latestTempMin;
    private String latestWeatherTime;

    // strategy
    private BigDecimal deviceDensity;
    private BigDecimal lampDensity;
    private BigDecimal camlampDensity;
    private BigDecimal luxPerArea;
    private BigDecimal durationPerAreaToday;
    private boolean hasCamlamp;
    private boolean hasAutoModeDevices;
    private String lightLevelStatus;
    private String energyStrategyHint;
}

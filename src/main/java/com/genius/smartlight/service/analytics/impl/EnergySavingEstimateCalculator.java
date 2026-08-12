package com.genius.smartlight.service.analytics.impl;

import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.vo.analytics.StrategyCompareRespVO;

import java.util.List;

/**
 * 使用灯具当前有效亮度估算当日能耗。当前数据模型没有额定功率与亮度历史，
 * 因此按默认单灯功率和营业时长计算；缺失亮度按 100% 处理，避免夸大节能效果。
 */
final class EnergySavingEstimateCalculator {

    static final int DEFAULT_RATED_POWER_WATTS = 20;
    static final int DEFAULT_OPERATING_HOURS = 14;
    private static final int FULL_BRIGHTNESS = 100;

    private EnergySavingEstimateCalculator() {
    }

    static StrategyCompareRespVO calculate(List<DeviceDO> lampDevices) {
        if (lampDevices == null || lampDevices.isEmpty()) {
            return empty("暂无已绑定灯具，暂时无法估算节能效果");
        }

        int brightnessDataCount = 0;
        int autoDimmingDeviceCount = 0;
        double brightnessTotal = 0;

        for (DeviceDO device : lampDevices) {
            Integer effectiveBrightness = resolveEffectiveBrightness(device);
            if (effectiveBrightness != null) {
                brightnessDataCount++;
            } else {
                effectiveBrightness = FULL_BRIGHTNESS;
            }
            if (Boolean.TRUE.equals(device.getAutoMode())) {
                autoDimmingDeviceCount++;
            }
            brightnessTotal += clampBrightness(effectiveBrightness);
        }

        int lampCount = lampDevices.size();
        double averageBrightness = brightnessTotal / lampCount;
        double baselineEnergy = lampCount * DEFAULT_RATED_POWER_WATTS
                * DEFAULT_OPERATING_HOURS / 1000.0;
        double smartEnergy = baselineEnergy * averageBrightness / FULL_BRIGHTNESS;
        double savedEnergy = Math.max(0, baselineEnergy - smartEnergy);
        double savingRate = baselineEnergy == 0 ? 0 : savedEnergy / baselineEnergy * 100;
        int coverage = (int) Math.round(brightnessDataCount * 100.0 / lampCount);

        StrategyCompareRespVO response = new StrategyCompareRespVO();
        response.setHasData(true);
        response.setTodaySavingRatePercent(round(savingRate, 1));
        response.setBaselineEnergyKwh(round(baselineEnergy, 2));
        response.setSmartEnergyKwh(round(smartEnergy, 2));
        response.setSavedEnergyKwh(round(savedEnergy, 2));
        response.setLampCount(lampCount);
        response.setAutoDimmingDeviceCount(autoDimmingDeviceCount);
        response.setAverageBrightnessPercent(round(averageBrightness, 1));
        response.setAverageBrightnessReductionPercent(round(FULL_BRIGHTNESS - averageBrightness, 1));
        response.setDataCoveragePercent(coverage);
        response.setRatedPowerWatts(DEFAULT_RATED_POWER_WATTS);
        response.setOperatingHours(DEFAULT_OPERATING_HOURS);
        response.setCalculationBasis(buildCalculationBasis(coverage));
        return response;
    }

    static StrategyCompareRespVO empty(String reason) {
        StrategyCompareRespVO response = new StrategyCompareRespVO();
        response.setHasData(false);
        response.setEmptyReason(reason);
        response.setCalculationBasis("等待灯具数据后自动生成节能估算");
        return response;
    }

    private static Integer resolveEffectiveBrightness(DeviceDO device) {
        if (Boolean.TRUE.equals(device.getAutoMode()) && device.getRecommendedBrightness() != null) {
            return device.getRecommendedBrightness();
        }
        return device.getBrightness();
    }

    private static int clampBrightness(int brightness) {
        return Math.max(0, Math.min(FULL_BRIGHTNESS, brightness));
    }

    private static String buildCalculationBasis(int coverage) {
        String basis = "按20W/盏、每日14小时及当前有效亮度估算";
        if (coverage < 100) {
            return basis + "；缺失亮度按100%保守计算";
        }
        return basis;
    }

    private static double round(double value, int scale) {
        double factor = Math.pow(10, scale);
        return Math.round(value * factor) / factor;
    }
}

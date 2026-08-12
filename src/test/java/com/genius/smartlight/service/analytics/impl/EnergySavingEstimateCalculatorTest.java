package com.genius.smartlight.service.analytics.impl;

import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.vo.analytics.StrategyCompareRespVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnergySavingEstimateCalculatorTest {

    @Test
    void calculatesEnergyFromManualAndRecommendedBrightness() {
        DeviceDO manualLamp = lamp(50, null, false);
        DeviceDO autoLamp = lamp(80, 40, true);

        StrategyCompareRespVO result = EnergySavingEstimateCalculator.calculate(
                List.of(manualLamp, autoLamp));

        assertTrue(result.isHasData());
        assertEquals(55.0, result.getTodaySavingRatePercent());
        assertEquals(0.56, result.getBaselineEnergyKwh());
        assertEquals(0.25, result.getSmartEnergyKwh());
        assertEquals(0.31, result.getSavedEnergyKwh());
        assertEquals(45.0, result.getAverageBrightnessPercent());
        assertEquals(55.0, result.getAverageBrightnessReductionPercent());
        assertEquals(1, result.getAutoDimmingDeviceCount());
        assertEquals(100, result.getDataCoveragePercent());
    }

    @Test
    void treatsMissingBrightnessAsFullPowerForConservativeEstimate() {
        StrategyCompareRespVO result = EnergySavingEstimateCalculator.calculate(
                List.of(lamp(null, null, false)));

        assertTrue(result.isHasData());
        assertEquals(0.0, result.getTodaySavingRatePercent());
        assertEquals(result.getBaselineEnergyKwh(), result.getSmartEnergyKwh());
        assertEquals(0, result.getDataCoveragePercent());
        assertTrue(result.getCalculationBasis().contains("保守计算"));
    }

    @Test
    void returnsEmptyStateWhenStoreHasNoLamp() {
        StrategyCompareRespVO result = EnergySavingEstimateCalculator.calculate(List.of());

        assertFalse(result.isHasData());
        assertTrue(result.getEmptyReason().contains("暂无已绑定灯具"));
    }

    private DeviceDO lamp(Integer brightness, Integer recommendedBrightness, boolean autoMode) {
        DeviceDO device = new DeviceDO();
        device.setBrightness(brightness);
        device.setRecommendedBrightness(recommendedBrightness);
        device.setAutoMode(autoMode);
        return device;
    }
}

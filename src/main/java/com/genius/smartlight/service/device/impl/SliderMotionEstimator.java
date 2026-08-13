package com.genius.smartlight.service.device.impl;

import com.genius.smartlight.common.ServiceException;

final class SliderMotionEstimator {

    static final double SLIDER_MIN_MM = 0D;
    static final double SLIDER_MAX_MM = 2500D;
    static final long SAFETY_MARGIN_MS = 300L;

    private SliderMotionEstimator() {
    }

    static long estimateDelayMs(
            double currentPositionMm,
            double targetPositionMm,
            double calibrationDistanceMm,
            double calibrationTimeSeconds) {
        validatePosition(currentPositionMm);
        validatePosition(targetPositionMm);
        if (!Double.isFinite(calibrationDistanceMm)
                || calibrationDistanceMm <= 0D
                || calibrationDistanceMm > SLIDER_MAX_MM
                || !Double.isFinite(calibrationTimeSeconds)
                || calibrationTimeSeconds <= 0D) {
            throw new ServiceException("请先填写当前速度档从 0 到滑轨预设位置的滑轨移动时间");
        }

        double speedMmPerSecond = calibrationDistanceMm / calibrationTimeSeconds;
        double travelSeconds = Math.abs(targetPositionMm - currentPositionMm) / speedMmPerSecond;
        return (long) Math.ceil(travelSeconds * 1000D) + SAFETY_MARGIN_MS;
    }

    private static void validatePosition(double positionMm) {
        if (!Double.isFinite(positionMm)
                || positionMm < SLIDER_MIN_MM
                || positionMm > SLIDER_MAX_MM) {
            throw new ServiceException("滑轨位置范围必须是 0 到 2500 mm");
        }
    }
}

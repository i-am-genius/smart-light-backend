package com.genius.smartlight.service.device.impl;

import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.vo.device.DeviceCamRoiItemVO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SliderCollisionPlanner {
    private SliderCollisionPlanner() {}

    static CollisionPlan plan(
            double currentMm,
            double targetMm,
            List<DeviceCamRoiItemVO> items,
            Map<String, Double> sliderPresets) {
        validatePosition(currentMm);
        validatePosition(targetMm);
        double pathMin = Math.min(currentMm, targetMm);
        double pathMax = Math.max(currentMm, targetMm);
        Map<String, GuardedLamp> affected = new LinkedHashMap<>();
        for (DeviceCamRoiItemVO item : items == null ? List.<DeviceCamRoiItemVO>of() : items) {
            if (item == null || item.getTargetChipId() == null || item.getTargetChipId().isBlank()
                    || item.getTargetIndex() == null) continue;
            Double lampPosition = sliderPresets == null
                    ? null
                    : sliderPresets.get(String.valueOf(item.getTargetIndex()));
            if (lampPosition == null) continue;
            double position = requirePosition(lampPosition, "区域 " + item.getTargetIndex() + " 的 Slider 位置");
            if (position < pathMin || position > pathMax) continue;
            double parkSeconds = requirePositive(item.getCollisionParkTimeSeconds(), "灯具 Pan/Tilt 回零时间");
            long parkMs = (long) Math.ceil(parkSeconds * 1000D);
            String chipId = item.getTargetChipId().trim();
            affected.merge(chipId, new GuardedLamp(chipId, parkMs),
                    (left, right) -> new GuardedLamp(left.chipId(), Math.max(left.parkTimeMs(), right.parkTimeMs())));
        }
        List<GuardedLamp> lamps = new ArrayList<>(affected.values());
        long maxParkMs = lamps.stream().mapToLong(GuardedLamp::parkTimeMs).max().orElse(0L);
        return new CollisionPlan(List.copyOf(lamps),
                lamps.isEmpty() ? 0L : maxParkMs + SliderMotionEstimator.SAFETY_MARGIN_MS);
    }

    private static double requirePosition(Double value, String field) {
        if (value == null || !Double.isFinite(value) || value < 0D || value > 2500D) {
            throw new ServiceException(field + "必须在 0 到 2500 mm 之间");
        }
        return value;
    }

    private static double requirePositive(Double value, String field) {
        if (value == null || !Double.isFinite(value) || value <= 0D) {
            throw new ServiceException("请先填写" + field);
        }
        return value;
    }

    private static void validatePosition(double value) { requirePosition(value, "滑轨位置"); }

    record GuardedLamp(String chipId, long parkTimeMs) {}
    record CollisionPlan(List<GuardedLamp> lamps, long parkDelayMs) {}
}

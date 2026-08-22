package com.genius.smartlight.service.device.impl;

import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.vo.device.DeviceCamRoiItemVO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SliderCollisionPlanner {
    private SliderCollisionPlanner() {}

    static CollisionPlan plan(double currentMm, double targetMm, List<DeviceCamRoiItemVO> items) {
        validatePosition(currentMm);
        validatePosition(targetMm);
        if (Math.abs(currentMm - targetMm) < 0.001D) return new CollisionPlan(List.of(), 0L);
        double pathMin = Math.min(currentMm, targetMm);
        double pathMax = Math.max(currentMm, targetMm);
        Map<String, GuardedLamp> affected = new LinkedHashMap<>();
        for (DeviceCamRoiItemVO item : items == null ? List.<DeviceCamRoiItemVO>of() : items) {
            if (item == null || item.getTargetChipId() == null || item.getTargetChipId().isBlank()) continue;
            double center = requirePosition(item.getCollisionCenterMm(), "碰撞中心位置");
            double clearance = requirePositive(item.getCollisionClearanceMm(), "碰撞避让距离");
            double parkSeconds = requirePositive(item.getCollisionParkTimeSeconds(), "灯具 Pan/Tilt 避让时间");
            double zoneMin = Math.max(SliderMotionEstimator.SLIDER_MIN_MM, center - clearance);
            double zoneMax = Math.min(SliderMotionEstimator.SLIDER_MAX_MM, center + clearance);
            if (pathMax < zoneMin || pathMin > zoneMax) continue;
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

package com.genius.smartlight.service.device;

import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.dal.dataobject.DeviceSliderStateDO;
import com.genius.smartlight.dal.mysql.DeviceSliderStateMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SliderMotionStateService {

    private static final Set<String> SPEED_MODES = Set.of("slow", "normal", "fast");
    private static final double SLIDER_MIN_MM = 0D;
    private static final double SLIDER_MAX_MM = 2500D;

    private final DeviceSliderStateMapper sliderStateMapper;

    public SliderStateSnapshot getSnapshot(String chipId, Long storeId) {
        DeviceSliderStateDO state = sliderStateMapper.selectById(chipId);
        if (state == null) {
            return new SliderStateSnapshot(0D, 0D, "normal", null, null);
        }
        verifyStore(state, storeId);

        LocalDateTime now = LocalDateTime.now();
        double current = validPosition(state.getCurrentPositionMm(), 0D);
        double target = validPosition(state.getTargetPositionMm(), current);
        LocalDateTime startedAt = state.getMotionStartedAt();
        LocalDateTime endAt = state.getMotionEndAt();
        if (endAt != null && !now.isBefore(endAt)) {
            completeMotion(chipId, storeId, target);
            return new SliderStateSnapshot(target, target, normalizeSpeed(state.getSpeedMode()), null, null);
        }
        if (startedAt != null && endAt != null && endAt.isAfter(startedAt) && now.isAfter(startedAt)) {
            long totalMs = Math.max(1L, Duration.between(startedAt, endAt).toMillis());
            long elapsedMs = Math.max(0L, Duration.between(startedAt, now).toMillis());
            double progress = Math.min(1D, (double) elapsedMs / totalMs);
            current += (target - current) * progress;
        }
        return new SliderStateSnapshot(current, target, normalizeSpeed(state.getSpeedMode()), startedAt, endAt);
    }

    public void updateSpeedMode(String chipId, Long storeId, String speedMode) {
        SliderStateSnapshot snapshot = getSnapshot(chipId, storeId);
        save(chipId, storeId, snapshot.currentPositionMm(), snapshot.currentPositionMm(),
                normalizeSpeed(speedMode), null, null);
    }

    public void beginMotion(
            String chipId,
            Long storeId,
            double currentPositionMm,
            double targetPositionMm,
            String speedMode,
            LocalDateTime startedAt,
            LocalDateTime endAt) {
        validatePosition(currentPositionMm);
        validatePosition(targetPositionMm);
        if (startedAt == null || endAt == null || !endAt.isAfter(startedAt)) {
            throw new ServiceException("滑轨预计完成时间无效");
        }
        save(chipId, storeId, currentPositionMm, targetPositionMm,
                normalizeSpeed(speedMode), startedAt, endAt);
    }

    public void completeMotion(String chipId, Long storeId, double positionMm) {
        validatePosition(positionMm);
        SliderStateSnapshot snapshot = getSnapshotWithoutPromotion(chipId, storeId);
        save(chipId, storeId, positionMm, positionMm, snapshot.speedMode(), null, null);
    }

    public void recordCommandedPosition(String chipId, Long storeId, double positionMm) {
        completeMotion(chipId, storeId, positionMm);
    }

    private SliderStateSnapshot getSnapshotWithoutPromotion(String chipId, Long storeId) {
        DeviceSliderStateDO state = sliderStateMapper.selectById(chipId);
        if (state == null) {
            return new SliderStateSnapshot(0D, 0D, "normal", null, null);
        }
        verifyStore(state, storeId);
        double current = validPosition(state.getCurrentPositionMm(), 0D);
        return new SliderStateSnapshot(
                current,
                validPosition(state.getTargetPositionMm(), current),
                normalizeSpeed(state.getSpeedMode()),
                state.getMotionStartedAt(),
                state.getMotionEndAt()
        );
    }

    private void save(
            String chipId,
            Long storeId,
            double currentPositionMm,
            double targetPositionMm,
            String speedMode,
            LocalDateTime startedAt,
            LocalDateTime endAt) {
        DeviceSliderStateDO state = sliderStateMapper.selectById(chipId);
        if (state == null) {
            state = new DeviceSliderStateDO();
            state.setChipId(chipId);
            state.setStoreId(storeId);
            state.setCurrentPositionMm(currentPositionMm);
            state.setTargetPositionMm(targetPositionMm);
            state.setSpeedMode(speedMode);
            state.setMotionStartedAt(startedAt);
            state.setMotionEndAt(endAt);
            state.setUpdateTime(LocalDateTime.now());
            sliderStateMapper.insert(state);
            return;
        }
        verifyStore(state, storeId);
        state.setCurrentPositionMm(currentPositionMm);
        state.setTargetPositionMm(targetPositionMm);
        state.setSpeedMode(speedMode);
        state.setMotionStartedAt(startedAt);
        state.setMotionEndAt(endAt);
        state.setUpdateTime(LocalDateTime.now());
        sliderStateMapper.updateById(state);
    }

    private void verifyStore(DeviceSliderStateDO state, Long storeId) {
        if (state.getStoreId() != null && storeId != null && !state.getStoreId().equals(storeId)) {
            throw new ServiceException("滑轨状态与当前店铺不匹配");
        }
    }

    private String normalizeSpeed(String value) {
        String normalized = value == null ? "normal" : value.trim().toLowerCase(Locale.ROOT);
        return SPEED_MODES.contains(normalized) ? normalized : "normal";
    }

    private double validPosition(Double value, double fallback) {
        if (value == null || !Double.isFinite(value) || value < SLIDER_MIN_MM || value > SLIDER_MAX_MM) {
            return fallback;
        }
        return value;
    }

    private void validatePosition(double value) {
        if (!Double.isFinite(value) || value < SLIDER_MIN_MM || value > SLIDER_MAX_MM) {
            throw new ServiceException("滑轨位置范围必须是 0 到 2500 mm");
        }
    }

    public record SliderStateSnapshot(
            double currentPositionMm,
            double targetPositionMm,
            String speedMode,
            LocalDateTime motionStartedAt,
            LocalDateTime motionEndAt) {
    }
}

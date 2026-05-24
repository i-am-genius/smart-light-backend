package com.genius.smartlight.service.lighteffect.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.service.lighteffect.LightEffectService;
import com.genius.smartlight.service.store.CurrentStoreService;
import com.genius.smartlight.vo.lighteffect.LightEffectStateReqVO;
import com.genius.smartlight.vo.lighteffect.LightEffectStateRespVO;
import com.genius.smartlight.websocket.WebSocketPushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class LightEffectServiceImpl implements LightEffectService {

    private static final String EFFECT_WAVE = "wave";
    private static final String SCOPE_ALL = "all";
    private static final int MIN_TEMP = 2700;
    private static final int MAX_TEMP = 6500;
    private static final long INACTIVE_STATE_TTL_MS = Duration.ofHours(24).toMillis();

    private final DeviceMapper deviceMapper;
    private final CurrentStoreService currentStoreService;
    private final WebSocketPushService webSocketPushService;
    private final ObjectMapper objectMapper;

    private final Object lock = new Object();
    private final Map<Long, LightEffectStateRespVO> storeStates = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> waveTargetChipIds = new ConcurrentHashMap<>();
    private final Map<Long, Long> storeStateAccessTimes = new ConcurrentHashMap<>();

    @Override
    public LightEffectStateRespVO getState() {
        Long storeId = getCurrentStoreId();
        synchronized (lock) {
            return copyState(getStoreState(storeId));
        }
    }

    @Override
    public LightEffectStateRespVO saveState(LightEffectStateReqVO reqVO) {
        Long storeId = getCurrentStoreId();
        LightEffectStateRespVO oldState;
        LightEffectStateRespVO nextState;
        Set<String> previousTargets;
        boolean shouldRunWave;

        synchronized (lock) {
            oldState = copyState(getStoreState(storeId));
            LightEffectStateRespVO state = mergeState(getStoreState(storeId), reqVO);
            storeStates.put(storeId, state);
            nextState = copyState(state);
            previousTargets = copyTargets(storeId);
            shouldRunWave = Boolean.TRUE.equals(state.getEnabled()) && EFFECT_WAVE.equals(state.getEffect());
        }

        if (shouldRunWave) {
            syncWaveTargets(storeId, oldState, nextState, previousTargets);
        } else {
            disableWaveTargetsAndClear(storeId, oldState, previousTargets);
        }

        LightEffectStateRespVO latest;
        synchronized (lock) {
            latest = copyState(getStoreState(storeId));
        }
        webSocketPushService.pushLightEffectStateToStore(storeId, latest);
        return latest;
    }

    @Override
    public LightEffectStateRespVO close() {
        return closeWaveForStore(getCurrentStoreId(), true);
    }

    @Override
    public LightEffectStateRespVO closeForLightControl(Long storeId) {
        if (storeId == null) {
            return defaultState();
        }
        return closeWaveForStore(storeId, false);
    }

    private LightEffectStateRespVO closeWaveForStore(Long storeId, boolean forceBroadcast) {
        LightEffectStateRespVO oldState;
        Set<String> previousTargets;
        boolean wasRunning;

        synchronized (lock) {
            oldState = copyState(getStoreState(storeId));
            previousTargets = copyTargets(storeId);
            wasRunning = Boolean.TRUE.equals(oldState.getEnabled()) && EFFECT_WAVE.equals(oldState.getEffect());
        }

        if (wasRunning) {
            disableWaveTargetsAndClear(storeId, oldState, previousTargets);
        }

        LightEffectStateRespVO closed;
        synchronized (lock) {
            LightEffectStateRespVO state = getStoreState(storeId);
            if (wasRunning || Boolean.TRUE.equals(state.getEnabled())) {
                state.setEnabled(false);
                state.setUpdateTime(LocalDateTime.now());
                storeStates.put(storeId, state);
            }
            waveTargetChipIds.remove(storeId);
            closed = copyState(state);
        }

        if (forceBroadcast || wasRunning) {
            webSocketPushService.pushLightEffectStateToStore(storeId, closed);
        }
        return closed;
    }

    private void syncWaveTargets(
            Long storeId,
            LightEffectStateRespVO oldState,
            LightEffectStateRespVO nextState,
            Set<String> previousTargets
    ) {
        Set<String> oldTargets = resolvePreviousTargets(storeId, oldState, previousTargets);
        List<DeviceDO> targetDevices = findTargetDevices(storeId, nextState.getSelectedScope());
        Set<String> nextTargets = new LinkedHashSet<>();

        Set<String> removedTargets = new LinkedHashSet<>(oldTargets);
        Set<String> configuredTargets = targetChipIds(targetDevices);
        removedTargets.removeAll(configuredTargets);
        sendWaveDisabled(removedTargets);

        double phaseIndex = safeDouble(nextState.getPhaseIndex(), 0D);
        double phaseGap = safeDouble(nextState.getPhaseGap(), 0.8D);
        int baseTemp = clamp(nextState.getBaseTemp(), MIN_TEMP, MAX_TEMP);
        int amplitude = clamp(nextState.getAmplitude(), 0, 1900);
        int brightness = clamp(nextState.getBrightness(), 0, 100);
        double speed = clamp(safeDouble(nextState.getSpeed(), 1D), 0.2D, 5D);
        int minTemp = clamp(nextState.getMinTemp(), MIN_TEMP, MAX_TEMP);
        int maxTemp = clamp(nextState.getMaxTemp(), MIN_TEMP, MAX_TEMP);

        if (targetDevices.isEmpty()) {
            log.warn("Wave target empty, storeId={}, selectedScope={}", storeId, nextState.getSelectedScope());
        }
        log.info("Wave enabled, storeId={}, selectedScope={}, targetCount={}, minTemp={}, maxTemp={}, speed={}, brightness={}",
                storeId, nextState.getSelectedScope(), targetDevices.size(), minTemp, maxTemp, speed, brightness);

        for (int index = 0; index < targetDevices.size(); index++) {
            DeviceDO device = targetDevices.get(index);
            String chipId = normalizeChipId(device.getChipId());
            if (chipId == null) {
                continue;
            }

            double phaseOffset = phaseIndex + index * phaseGap;
            int initialTemp = clamp(
                    (int) Math.round(baseTemp + Math.sin(phaseOffset) * amplitude),
                    MIN_TEMP,
                    MAX_TEMP
            );

            boolean sent = sendWaveEnabled(
                    chipId,
                    baseTemp,
                    amplitude,
                    minTemp,
                    maxTemp,
                    speed,
                    phaseOffset,
                    initialTemp,
                    brightness
            );
            nextTargets.add(chipId);
            if (!sent) {
                log.warn("Wave effect config send failed, chipId={}", chipId);
            }
        }

        synchronized (lock) {
            if (nextTargets.isEmpty()) {
                waveTargetChipIds.remove(storeId);
            } else {
                waveTargetChipIds.put(storeId, nextTargets);
            }
        }
    }

    private void disableWaveTargetsAndClear(
            Long storeId,
            LightEffectStateRespVO oldState,
            Set<String> previousTargets
    ) {
        Set<String> targets = resolvePreviousTargets(storeId, oldState, previousTargets);
        sendWaveDisabled(targets);
        if (!targets.isEmpty()
                || (oldState != null && Boolean.TRUE.equals(oldState.getEnabled()) && EFFECT_WAVE.equals(oldState.getEffect()))) {
            log.info("Wave disabled, storeId={}, targetCount={}", storeId, targets.size());
        }
        synchronized (lock) {
            waveTargetChipIds.remove(storeId);
        }
    }

    private Set<String> resolvePreviousTargets(
            Long storeId,
            LightEffectStateRespVO oldState,
            Set<String> previousTargets
    ) {
        if (previousTargets != null && !previousTargets.isEmpty()) {
            return new LinkedHashSet<>(previousTargets);
        }
        if (oldState != null
                && Boolean.TRUE.equals(oldState.getEnabled())
                && EFFECT_WAVE.equals(oldState.getEffect())) {
            return targetChipIds(findTargetDevices(storeId, oldState.getSelectedScope()));
        }
        return new LinkedHashSet<>();
    }

    private boolean sendWaveEnabled(
            String chipId,
            int baseTemp,
            int amplitude,
            int minTemp,
            int maxTemp,
            double speed,
            double phaseOffset,
            int initialTemp,
            int brightness
    ) {
        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("type", "effect");
        msg.put("effect", EFFECT_WAVE);
        msg.put("enabled", true);
        msg.put("baseTemp", baseTemp);
        msg.put("amplitude", amplitude);
        msg.put("minTemp", minTemp);
        msg.put("maxTemp", maxTemp);
        msg.put("speed", speed);
        msg.put("phaseOffset", phaseOffset);
        msg.put("initialTemp", initialTemp);
        msg.put("brightness", brightness);
        return webSocketPushService.pushRawToDevice(chipId, msg.toString());
    }

    private void sendWaveDisabled(Set<String> targets) {
        if (targets == null || targets.isEmpty()) {
            return;
        }

        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("type", "effect");
        msg.put("effect", EFFECT_WAVE);
        msg.put("enabled", false);
        String payload = msg.toString();

        for (String chipId : targets) {
            webSocketPushService.pushRawToDevice(chipId, payload);
        }
    }

    private Set<String> targetChipIds(List<DeviceDO> devices) {
        Set<String> result = new LinkedHashSet<>();
        for (DeviceDO device : devices) {
            String chipId = normalizeChipId(device.getChipId());
            if (chipId != null) {
                result.add(chipId);
            }
        }
        return result;
    }

    private List<DeviceDO> findTargetDevices(Long storeId, String selectedScope) {
        List<DeviceDO> devices = deviceMapper.selectList(
                new LambdaQueryWrapper<DeviceDO>()
                        .eq(DeviceDO::getStoreId, storeId)
        );

        String scope = normalizeScope(selectedScope);
        return devices.stream()
                .filter(this::isLightDevice)
                .filter(device -> SCOPE_ALL.equals(scope) || scope.equals(normalizeScope(device.getDisplayName())))
                .sorted(Comparator
                        .comparing((DeviceDO device) -> normalizeScope(device.getDisplayName()))
                        .thenComparingInt(device -> parseDeviceNo(device.getDeviceNo()))
                        .thenComparing(device -> device.getChipId() == null ? "" : device.getChipId()))
                .toList();
    }

    private Long getCurrentStoreId() {
        return currentStoreService.getCurrentStoreId();
    }

    private LightEffectStateRespVO getStoreState(Long storeId) {
        storeStateAccessTimes.put(storeId, System.currentTimeMillis());
        return storeStates.computeIfAbsent(storeId, ignored -> defaultState());
    }

    @Scheduled(fixedDelay = 3_600_000L)
    public void cleanupInactiveStoreStates() {
        long expireBefore = System.currentTimeMillis() - INACTIVE_STATE_TTL_MS;
        synchronized (lock) {
            for (Long storeId : new LinkedHashSet<>(storeStates.keySet())) {
                Long lastAccess = storeStateAccessTimes.get(storeId);
                LightEffectStateRespVO state = storeStates.get(storeId);
                boolean runningWave = state != null
                        && Boolean.TRUE.equals(state.getEnabled())
                        && EFFECT_WAVE.equals(state.getEffect());
                boolean hasTargets = waveTargetChipIds.containsKey(storeId);
                if (!runningWave && !hasTargets && lastAccess != null && lastAccess < expireBefore) {
                    storeStates.remove(storeId);
                    storeStateAccessTimes.remove(storeId);
                }
            }
        }
    }

    private Set<String> copyTargets(Long storeId) {
        Set<String> targets = waveTargetChipIds.get(storeId);
        return targets == null ? new LinkedHashSet<>() : new LinkedHashSet<>(targets);
    }

    private boolean isLightDevice(DeviceDO device) {
        String deviceType = device.getDeviceType();
        if (deviceType == null) {
            return false;
        }
        String normalized = deviceType.trim().toLowerCase(Locale.ROOT);
        return "lamp".equals(normalized) || "camlamp".equals(normalized);
    }

    private LightEffectStateRespVO mergeState(LightEffectStateRespVO current, LightEffectStateReqVO reqVO) {
        LightEffectStateRespVO next = copyState(current == null ? defaultState() : current);
        if (reqVO == null) {
            next.setUpdateTime(LocalDateTime.now());
            return next;
        }

        next.setEffect(normalizeEffect(reqVO.getEffect()));
        if (reqVO.getEnabled() != null) {
            next.setEnabled(reqVO.getEnabled());
        }

        boolean hasBounds = reqVO.getMinTemp() != null || reqVO.getMaxTemp() != null;
        boolean hasLegacyTemp = !hasBounds
                && (reqVO.getBaseTemp() != null || reqVO.getRange() != null || reqVO.getAmplitude() != null);

        if (hasBounds) {
            if (reqVO.getMinTemp() != null) {
                next.setMinTemp(clamp(reqVO.getMinTemp(), MIN_TEMP, MAX_TEMP));
            }
            if (reqVO.getMaxTemp() != null) {
                next.setMaxTemp(clamp(reqVO.getMaxTemp(), MIN_TEMP, MAX_TEMP));
            }
            normalizeTemperatureFromBounds(next);
        } else if (hasLegacyTemp) {
            if (reqVO.getBaseTemp() != null) {
                next.setBaseTemp(clamp(reqVO.getBaseTemp(), MIN_TEMP, MAX_TEMP));
            }
            Integer amplitude = reqVO.getAmplitude() != null ? reqVO.getAmplitude() : reqVO.getRange();
            if (amplitude != null) {
                next.setAmplitude(clamp(amplitude, 0, 1900));
                next.setRange(clamp(amplitude, 0, 1900));
            }
            normalizeTemperatureFromBaseAmplitude(next);
        } else {
            normalizeTemperatureFromBounds(next);
        }

        if (reqVO.getSpeed() != null) {
            next.setSpeed(clamp(reqVO.getSpeed(), 0.2D, 5D));
        }
        if (reqVO.getBrightness() != null) {
            next.setBrightness(clamp(reqVO.getBrightness(), 0, 100));
        }
        if (reqVO.getPhaseIndex() != null) {
            next.setPhaseIndex(reqVO.getPhaseIndex());
        }
        if (reqVO.getPhaseGap() != null) {
            next.setPhaseGap(clamp(reqVO.getPhaseGap(), 0D, 3D));
        }
        if (reqVO.getSelectedScope() != null && !reqVO.getSelectedScope().isBlank()) {
            next.setSelectedScope(reqVO.getSelectedScope().trim());
        }

        next.setUpdateTime(LocalDateTime.now());
        return next;
    }

    private void normalizeTemperatureFromBounds(LightEffectStateRespVO state) {
        int minTemp = clamp(state.getMinTemp(), MIN_TEMP, MAX_TEMP);
        int maxTemp = clamp(state.getMaxTemp(), MIN_TEMP, MAX_TEMP);
        int low = Math.min(minTemp, maxTemp);
        int high = Math.max(minTemp, maxTemp);
        int baseTemp = Math.round((low + high) / 2.0F);
        int amplitude = Math.round((high - low) / 2.0F);

        state.setMinTemp(low);
        state.setMaxTemp(high);
        state.setBaseTemp(baseTemp);
        state.setAmplitude(amplitude);
        state.setRange(amplitude);
    }

    private void normalizeTemperatureFromBaseAmplitude(LightEffectStateRespVO state) {
        int baseTemp = clamp(state.getBaseTemp(), MIN_TEMP, MAX_TEMP);
        int amplitude = clamp(state.getAmplitude() == null ? state.getRange() : state.getAmplitude(), 0, 1900);
        state.setMinTemp(clamp(baseTemp - amplitude, MIN_TEMP, MAX_TEMP));
        state.setMaxTemp(clamp(baseTemp + amplitude, MIN_TEMP, MAX_TEMP));
        normalizeTemperatureFromBounds(state);
    }

    private LightEffectStateRespVO defaultState() {
        LightEffectStateRespVO respVO = new LightEffectStateRespVO();
        respVO.setEffect(EFFECT_WAVE);
        respVO.setEnabled(false);
        respVO.setMinTemp(2700);
        respVO.setMaxTemp(6500);
        respVO.setBaseTemp(4600);
        respVO.setRange(1900);
        respVO.setAmplitude(1900);
        respVO.setSpeed(1D);
        respVO.setBrightness(70);
        respVO.setPhaseIndex(0D);
        respVO.setPhaseGap(0.8D);
        respVO.setSelectedScope(SCOPE_ALL);
        respVO.setUpdateTime(LocalDateTime.now());
        return respVO;
    }

    private LightEffectStateRespVO copyState(LightEffectStateRespVO source) {
        LightEffectStateRespVO target = new LightEffectStateRespVO();
        target.setEffect(source.getEffect());
        target.setEnabled(source.getEnabled());
        target.setMinTemp(source.getMinTemp());
        target.setMaxTemp(source.getMaxTemp());
        target.setBaseTemp(source.getBaseTemp());
        target.setRange(source.getRange());
        target.setAmplitude(source.getAmplitude());
        target.setSpeed(source.getSpeed());
        target.setBrightness(source.getBrightness());
        target.setPhaseIndex(source.getPhaseIndex());
        target.setPhaseGap(source.getPhaseGap());
        target.setSelectedScope(source.getSelectedScope());
        target.setUpdateTime(source.getUpdateTime());
        return target;
    }

    private String normalizeEffect(String effect) {
        if (effect == null || effect.isBlank()) {
            return EFFECT_WAVE;
        }
        return effect.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return SCOPE_ALL;
        }
        return scope.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeChipId(String chipId) {
        if (chipId == null) {
            return null;
        }
        String value = chipId.trim();
        return value.isEmpty() ? null : value;
    }

    private double safeDouble(Double value, double fallback) {
        return value == null || !Double.isFinite(value) ? fallback : value;
    }

    private int parseDeviceNo(String value) {
        if (value == null || value.isBlank()) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private int clamp(Integer value, int min, int max) {
        int next = value == null ? min : value;
        return Math.min(Math.max(next, min), max);
    }

    private int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    private double clamp(double value, double min, double max) {
        return Math.min(Math.max(value, min), max);
    }
}

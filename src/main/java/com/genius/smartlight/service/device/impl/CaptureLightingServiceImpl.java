package com.genius.smartlight.service.device.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.genius.smartlight.common.DeviceTypeUtil;
import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.dataobject.StoreDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.dal.mysql.StoreMapper;
import com.genius.smartlight.security.SecurityUtils;
import com.genius.smartlight.service.device.CaptureLightingService;
import com.genius.smartlight.websocket.DeviceSessionManager;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class CaptureLightingServiceImpl implements CaptureLightingService {

    private final DeviceMapper deviceMapper;
    private final StoreMapper storeMapper;
    private final DeviceSessionManager deviceSessionManager;
    private final ObjectMapper objectMapper;

    private final Map<String, Object> lampLocks = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Long>> leasesByLamp = new ConcurrentHashMap<>();

    @Value("${device.capture-lighting.brightness:80}")
    private int standardBrightness;

    @Value("${device.capture-lighting.temp:4000}")
    private int standardTemp;

    @Value("${device.capture-lighting.ttl-ms:15000}")
    private long ttlMs;

    @Value("${device.capture-lighting.settle-ms:300}")
    private long settleMs;

    @Override
    public String startStandard(String lampChipId, String requestedSessionId) {
        DeviceDO lamp = requireLamp(lampChipId);
        if (!deviceSessionManager.isOnline(lamp.getChipId())) {
            throw new ServiceException("目标 Lamp 离线，无法启用拍摄标准光照");
        }

        String sessionId = normalizeSessionId(requestedSessionId);
        long effectiveTtlMs = effectiveTtlMs();
        Object lock = lampLocks.computeIfAbsent(lamp.getChipId(), ignored -> new Object());
        synchronized (lock) {
            long now = System.currentTimeMillis();
            Map<String, Long> leases = leasesByLamp.computeIfAbsent(
                    lamp.getChipId(), ignored -> new HashMap<>()
            );
            pruneExpired(leases, now);
            leases.put(sessionId, now + effectiveTtlMs);

            if (!sendCaptureLighting(lamp, true, effectiveTtlMs)) {
                leases.remove(sessionId);
                if (leases.isEmpty()) {
                    leasesByLamp.remove(lamp.getChipId(), leases);
                }
                throw new ServiceException("拍摄标准光照指令下发失败");
            }
        }
        return sessionId;
    }

    @Override
    public void stop(String lampChipId, String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        DeviceDO lamp = requireLamp(lampChipId);
        Object lock = lampLocks.computeIfAbsent(lamp.getChipId(), ignored -> new Object());
        synchronized (lock) {
            Map<String, Long> leases = leasesByLamp.get(lamp.getChipId());
            if (leases == null) {
                return;
            }

            pruneExpired(leases, System.currentTimeMillis());
            boolean removed = leases.remove(sessionId.trim()) != null;
            if (!removed) {
                if (leases.isEmpty()) {
                    leasesByLamp.remove(lamp.getChipId(), leases);
                }
                return;
            }
            if (!leases.isEmpty()) {
                return;
            }

            leasesByLamp.remove(lamp.getChipId(), leases);
            if (deviceSessionManager.isOnline(lamp.getChipId())) {
                sendCaptureLighting(lamp, false, 0L);
            }
        }
    }

    @Override
    public long getSettleMs() {
        return Math.max(0L, Math.min(2000L, settleMs));
    }

    private boolean sendCaptureLighting(DeviceDO lamp, boolean active, long effectiveTtlMs) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("type", "captureLighting");
        payload.put("id", lamp.getChipId());
        payload.put("chipId", lamp.getChipId());
        payload.put("active", active);
        if (active) {
            payload.put("brightness", Math.max(0, Math.min(100, standardBrightness)));
            payload.put("temp", Math.max(2700, Math.min(6500, standardTemp)));
            payload.put("ttlMs", effectiveTtlMs);
        }
        return deviceSessionManager.sendToDevice(lamp.getChipId(), payload.toString());
    }

    private long effectiveTtlMs() {
        return Math.max(1000L, Math.min(30000L, ttlMs));
    }

    private void pruneExpired(Map<String, Long> leases, long now) {
        leases.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue() <= now);
    }

    private String normalizeSessionId(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return UUID.randomUUID().toString();
        }
        String normalized = sessionId.trim();
        if (normalized.length() > 128) {
            throw new ServiceException("拍摄标准光照 sessionId 过长");
        }
        return normalized;
    }

    private DeviceDO requireLamp(String lampChipId) {
        if (!StringUtils.hasText(lampChipId)) {
            throw new ServiceException("Lamp 芯片 ID 不能为空");
        }
        DeviceDO lamp = deviceMapper.selectOne(new LambdaQueryWrapper<DeviceDO>()
                .eq(DeviceDO::getChipId, lampChipId.trim())
                .last("limit 1"));
        if (lamp == null || !DeviceTypeUtil.isLampLike(lamp.getDeviceType())) {
            throw new ServiceException("Lamp 设备不存在或类型不支持");
        }

        Long currentUserId = SecurityUtils.getCurrentUserIdOrNull();
        if (currentUserId != null) {
            StoreDO store = storeMapper.selectOne(new LambdaQueryWrapper<StoreDO>()
                    .eq(StoreDO::getUserId, currentUserId)
                    .last("limit 1"));
            if (store == null || lamp.getStoreId() == null || !lamp.getStoreId().equals(store.getId())) {
                throw new ServiceException("无权操作该 Lamp");
            }
        }
        return lamp;
    }
}

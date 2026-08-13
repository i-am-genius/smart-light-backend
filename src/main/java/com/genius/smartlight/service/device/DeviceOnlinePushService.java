package com.genius.smartlight.service.device;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.vo.device.DeviceOnlineStatusRespVO;
import com.genius.smartlight.websocket.DeviceSessionManager;
import com.genius.smartlight.websocket.WebSocketPushService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class DeviceOnlinePushService {

    private final DeviceMapper deviceMapper;
    private final DeviceSessionManager deviceSessionManager;
    private final WebSocketPushService webSocketPushService;
    private final DeviceLastSeenService deviceLastSeenService;
    private final DeviceFleetLifecycleService deviceFleetLifecycleService;
    private final DeviceCamService deviceCamService;

    /**
     * 记录上一次已推送的在线状态，避免重复推送
     */
    private final Map<String, Boolean> lastPushedStatusMap = new ConcurrentHashMap<>();

    public void pushIfChanged(String chipId) {
        if (chipId == null || chipId.isBlank()) {
            return;
        }

        boolean currentOnline = deviceSessionManager.isOnline(chipId);
        Boolean lastPushed = lastPushedStatusMap.get(chipId);

        if (lastPushed == null || lastPushed != currentOnline) {
            if (currentOnline) {
                deviceLastSeenService.persistIfDue(chipId, deviceSessionManager.getLastSeen(chipId));
            } else {
                deviceLastSeenService.persistNow(chipId, deviceSessionManager.getLastSeen(chipId));
            }
            DeviceDO device = deviceMapper.selectOne(
                    new LambdaQueryWrapper<DeviceDO>()
                            .eq(DeviceDO::getChipId, chipId)
            );
            Long storeId = device != null ? device.getStoreId() : null;
            if (storeId == null) {
                return;
            }

            DeviceOnlineStatusRespVO respVO = buildOnlineStatus(chipId, device);
            webSocketPushService.pushOnlineStatus(respVO, storeId);
            lastPushedStatusMap.put(chipId, currentOnline);
            deviceCamService.handleDeviceOnlineStatusChanged(chipId, currentOnline);
            deviceFleetLifecycleService.onOnlineStatusChanged(storeId);
        }
    }

    public void scanAndPushOfflineChanges() {
        Set<String> trackedChipIds = deviceSessionManager.getTrackedChipIds();
        for (String chipId : trackedChipIds) {
            pushIfChanged(chipId);
        }
        cleanupStaleStatusKeys(trackedChipIds);
    }

    private void cleanupStaleStatusKeys(Set<String> trackedChipIds) {
        if (lastPushedStatusMap.isEmpty()) {
            return;
        }

        Set<String> tracked = normalizeChipIds(trackedChipIds);
        Set<String> existingDevices = loadExistingDeviceChipIds();

        lastPushedStatusMap.keySet().removeIf(chipId -> {
            String normalizedChipId = deviceSessionManager.normalizeChipId(chipId);
            return normalizedChipId == null
                    || (!existingDevices.contains(normalizedChipId) && !tracked.contains(normalizedChipId));
        });
    }

    private Set<String> loadExistingDeviceChipIds() {
        List<DeviceDO> devices = deviceMapper.selectList(
                new LambdaQueryWrapper<DeviceDO>()
                        .select(DeviceDO::getChipId)
                        .isNotNull(DeviceDO::getChipId)
        );
        Set<String> chipIds = new HashSet<>();
        for (DeviceDO device : devices) {
            String chipId = deviceSessionManager.normalizeChipId(device.getChipId());
            if (chipId != null) {
                chipIds.add(chipId);
            }
        }
        return chipIds;
    }

    private Set<String> normalizeChipIds(Set<String> chipIds) {
        Set<String> normalized = new HashSet<>();
        for (String chipId : chipIds) {
            String value = deviceSessionManager.normalizeChipId(chipId);
            if (value != null) {
                normalized.add(value);
            }
        }
        return normalized;
    }

    private DeviceOnlineStatusRespVO buildOnlineStatus(String chipId, DeviceDO device) {
        DeviceOnlineStatusRespVO respVO = new DeviceOnlineStatusRespVO();
        respVO.setChipId(chipId);
        respVO.setIp(device != null ? device.getIp() : null);
        respVO.setOnline(deviceSessionManager.isOnline(chipId));
        respVO.setLastSeen(deviceSessionManager.getLastSeen(chipId));
        respVO.setLastSeenAt(device != null ? device.getLastSeenAt() : null);
        if (device != null) {
            respVO.setGarmentDetectionStatus(deviceCamService.getGarmentDetectionStatus(device.getStoreId()));
            respVO.setNearby(deviceCamService.getLampNearby(chipId));
            respVO.setLastTakenAt(deviceCamService.getLastTakenAt(chipId));
            respVO.setTrackingStatus(deviceCamService.getTrackingStatus(chipId));
        }
        return respVO;
    }
}

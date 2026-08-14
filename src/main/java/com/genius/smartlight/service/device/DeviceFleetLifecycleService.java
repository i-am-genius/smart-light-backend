package com.genius.smartlight.service.device;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.genius.smartlight.common.DeviceTypeUtil;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.websocket.DeviceSessionManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class DeviceFleetLifecycleService {

    private final DeviceMapper deviceMapper;
    private final DeviceSessionManager deviceSessionManager;
    private final DeviceCamService deviceCamService;
    private final Map<Long, AutomaticGarmentDetectionGate> detectionGates = new ConcurrentHashMap<>();

    public void onOnlineStatusChanged(Long storeId) {
        if (storeId == null) {
            return;
        }
        List<DeviceDO> requiredDevices = deviceMapper.selectList(
                new LambdaQueryWrapper<DeviceDO>().eq(DeviceDO::getStoreId, storeId)
        ).stream().filter(this::participatesInAutomaticDetection).toList();
        int onlineCount = (int) requiredDevices.stream()
                .filter(device -> deviceSessionManager.isOnline(device.getChipId()))
                .count();

        AutomaticGarmentDetectionGate.Action action = detectionGates
                .computeIfAbsent(storeId, ignored -> new AutomaticGarmentDetectionGate())
                .evaluate(requiredDevices.size(), onlineCount);
        if (action == AutomaticGarmentDetectionGate.Action.RESET_NOT_DETECTED) {
            deviceCamService.resetAutomaticGarmentDetection(storeId);
        } else if (action == AutomaticGarmentDetectionGate.Action.START_FULL_SCAN) {
            deviceCamService.startAutomaticGarmentDetection(storeId);
        }
    }

    private boolean participatesInAutomaticDetection(DeviceDO device) {
        if (device == null || device.getChipId() == null || device.getChipId().isBlank()) {
            return false;
        }
        String type = DeviceTypeUtil.normalize(device.getDeviceType());
        return DeviceTypeUtil.LAMP.equals(type)
                || DeviceTypeUtil.CAM.equals(type)
                || DeviceTypeUtil.CAM_LAMP.equals(type);
    }
}


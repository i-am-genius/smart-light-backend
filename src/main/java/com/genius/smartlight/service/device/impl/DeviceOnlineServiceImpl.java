package com.genius.smartlight.service.device.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.service.device.DeviceOnlineService;
import com.genius.smartlight.service.device.DeviceCamService;
import com.genius.smartlight.service.store.CurrentStoreService;
import com.genius.smartlight.vo.device.DeviceOnlineStatusRespVO;
import com.genius.smartlight.websocket.DeviceSessionManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DeviceOnlineServiceImpl implements DeviceOnlineService {

    private final DeviceMapper deviceMapper;
    private final CurrentStoreService currentStoreService;
    private final DeviceSessionManager deviceSessionManager;
    private final DeviceCamService deviceCamService;

    @Override
    public DeviceOnlineStatusRespVO getOnlineStatus(String chipId) {
        Long storeId = currentStoreService.getCurrentStoreId();
        DeviceDO device = deviceMapper.selectOne(
                new LambdaQueryWrapper<DeviceDO>()
                        .eq(DeviceDO::getChipId, chipId)
                        .last("limit 1")
        );
        if (device == null) {
            throw new ServiceException("Device does not exist");
        }
        if (device.getStoreId() == null || !device.getStoreId().equals(storeId)) {
            throw new ServiceException("No permission to view this device");
        }
        return toOnlineStatus(device);
    }

    @Override
    public List<DeviceOnlineStatusRespVO> getOnlineStatusList() {
        Long storeId = currentStoreService.getCurrentStoreId();
        List<DeviceDO> devices = deviceMapper.selectList(
                new LambdaQueryWrapper<DeviceDO>()
                        .eq(DeviceDO::getStoreId, storeId)
        );
        return devices.stream().map(this::toOnlineStatus).toList();
    }

    private DeviceOnlineStatusRespVO toOnlineStatus(DeviceDO device) {
        DeviceOnlineStatusRespVO respVO = new DeviceOnlineStatusRespVO();
        respVO.setChipId(device.getChipId());
        respVO.setIp(device.getIp());
        respVO.setOnline(deviceSessionManager.isOnline(device.getChipId()));
        respVO.setLastSeen(deviceSessionManager.getLastSeen(device.getChipId()));
        respVO.setLastSeenAt(device.getLastSeenAt());
        respVO.setGarmentDetectionStatus(deviceCamService.getGarmentDetectionStatus(device.getStoreId()));
        respVO.setNearby(deviceCamService.getLampNearby(device.getChipId()));
        respVO.setLastTakenAt(deviceCamService.getLastTakenAt(device.getChipId()));
        respVO.setTrackingStatus(deviceCamService.getTrackingStatus(device.getChipId()));
        return respVO;
    }
}

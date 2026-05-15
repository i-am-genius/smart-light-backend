package com.genius.smartlight.service.device.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.dataobject.StoreDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.dal.mysql.StoreMapper;
import com.genius.smartlight.security.SecurityUtils;
import com.genius.smartlight.service.device.DeviceOnlineService;
import com.genius.smartlight.vo.device.DeviceOnlineStatusRespVO;
import com.genius.smartlight.websocket.DeviceSessionManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DeviceOnlineServiceImpl implements DeviceOnlineService {

    private final DeviceMapper deviceMapper;
    private final StoreMapper storeMapper;
    private final DeviceSessionManager deviceSessionManager;

    private Long getCurrentStoreId() {
        Long userId = SecurityUtils.getCurrentUserId();
        StoreDO store = storeMapper.selectOne(
                new LambdaQueryWrapper<StoreDO>()
                        .eq(StoreDO::getUserId, userId)
        );
        if (store == null) {
            throw new ServiceException("当前用户未绑定店铺");
        }
        return store.getId();
    }

    @Override
    public DeviceOnlineStatusRespVO getOnlineStatus(String chipId) {
        Long storeId = getCurrentStoreId();
        DeviceDO device = deviceMapper.selectOne(
                new LambdaQueryWrapper<DeviceDO>()
                        .eq(DeviceDO::getChipId, chipId)
        );
        if (device == null) {
            throw new ServiceException("设备不存在");
        }
        if (device.getStoreId() == null || !device.getStoreId().equals(storeId)) {
            throw new ServiceException("无权查看该设备");
        }

        DeviceOnlineStatusRespVO respVO = new DeviceOnlineStatusRespVO();
        respVO.setChipId(device.getChipId());
        respVO.setIp(device.getIp());
        respVO.setOnline(deviceSessionManager.isOnline(chipId));
        respVO.setLastSeen(deviceSessionManager.getLastSeen(chipId));
        return respVO;
    }

    @Override
    public List<DeviceOnlineStatusRespVO> getOnlineStatusList() {
        Long storeId = getCurrentStoreId();
        List<DeviceDO> devices = deviceMapper.selectList(
                new LambdaQueryWrapper<DeviceDO>()
                        .eq(DeviceDO::getStoreId, storeId)
        );

        return devices.stream().map(device -> {
            DeviceOnlineStatusRespVO respVO = new DeviceOnlineStatusRespVO();
            respVO.setChipId(device.getChipId());
            respVO.setIp(device.getIp());
            respVO.setOnline(deviceSessionManager.isOnline(device.getChipId()));
            respVO.setLastSeen(deviceSessionManager.getLastSeen(device.getChipId()));
            return respVO;
        }).toList();
    }
}

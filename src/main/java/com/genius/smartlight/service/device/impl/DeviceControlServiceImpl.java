package com.genius.smartlight.service.device.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.convert.device.DeviceConvert;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.dataobject.StoreDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.dal.mysql.StoreMapper;
import com.genius.smartlight.security.SecurityUtils;
import com.genius.smartlight.service.device.DeviceControlService;
import com.genius.smartlight.vo.device.DeviceRespVO;
import com.genius.smartlight.vo.device.DeviceStateSyncReqVO;
import com.genius.smartlight.websocket.DeviceSessionManager;
import com.genius.smartlight.websocket.WebSocketPushService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DeviceControlServiceImpl implements DeviceControlService {

    private final DeviceMapper deviceMapper;
    private final StoreMapper storeMapper;
    private final DeviceSessionManager deviceSessionManager;
    private final WebSocketPushService webSocketPushService;
    private final ObjectMapper objectMapper;

    @Override
    public DeviceRespVO syncStateToDevice(String chipId, DeviceStateSyncReqVO reqVO) {
        Long userId = SecurityUtils.getCurrentUserId();
        StoreDO store = storeMapper.selectOne(
                new LambdaQueryWrapper<StoreDO>()
                        .eq(StoreDO::getUserId, userId)
        );
        if (store == null) {
            throw new ServiceException("当前用户未绑定店铺");
        }

        DeviceDO device = deviceMapper.selectOne(
                new LambdaQueryWrapper<DeviceDO>()
                        .eq(DeviceDO::getChipId, chipId)
        );
        if (device == null) {
            throw new ServiceException("设备不存在");
        }
        if (device.getStoreId() == null || !device.getStoreId().equals(store.getId())) {
            throw new ServiceException("无权操作该设备");
        }

        if (!deviceSessionManager.isOnline(chipId)) {
            throw new ServiceException("设备未连接或已离线");
        }

        if (reqVO.getBrightness() != null) {
            device.setBrightness(reqVO.getBrightness());
        }
        if (reqVO.getTemp() != null) {
            device.setTemp(reqVO.getTemp());
        }
        if (reqVO.getAutoMode() != null) {
            device.setAutoMode(reqVO.getAutoMode());
        }
        if (reqVO.getRecommendedBrightness() != null) {
            device.setRecommendedBrightness(reqVO.getRecommendedBrightness());
        }
        if (reqVO.getRecommendedTemp() != null) {
            device.setRecommendedTemp(reqVO.getRecommendedTemp());
        }
        if (reqVO.getFabric() != null) {
            device.setFabric(reqVO.getFabric());
        }
        if (reqVO.getMainColorRgb() != null) {
            device.setMainColorRgb(reqVO.getMainColorRgb());
        }

        device.setUpdateTime(LocalDateTime.now());
        deviceMapper.updateById(device);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "state");
        payload.put("chipId", chipId);
        payload.put("brightness", device.getBrightness());
        payload.put("temp", device.getTemp());
        payload.put("autoMode", device.getAutoMode());
        payload.put("recommendedBrightness", device.getRecommendedBrightness());
        payload.put("recommendedTemp", device.getRecommendedTemp());
        payload.put("fabric", device.getFabric());
        payload.put("mainColorRgb", device.getMainColorRgb());

        try {
            String text = objectMapper.writeValueAsString(payload);
            boolean sent = deviceSessionManager.sendToDevice(chipId, text);
            if (!sent) {
                throw new ServiceException("状态下发失败");
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("状态指令序列化失败：" + e.getMessage());
        }

        DeviceRespVO respVO = DeviceConvert.convert(device);
        webSocketPushService.pushState(respVO);
        return respVO;
    }
}

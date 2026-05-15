package com.genius.smartlight.service.device;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.vo.device.DeviceOnlineStatusRespVO;
import com.genius.smartlight.websocket.DeviceSessionManager;
import com.genius.smartlight.websocket.WebSocketPushService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class DeviceOnlinePushService {

    private final DeviceMapper deviceMapper;
    private final DeviceSessionManager deviceSessionManager;
    private final WebSocketPushService webSocketPushService;

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
        }
    }

    public void scanAndPushOfflineChanges() {
        for (String chipId : deviceSessionManager.getTrackedChipIds()) {
            pushIfChanged(chipId);
        }
    }

    private DeviceOnlineStatusRespVO buildOnlineStatus(String chipId, DeviceDO device) {
        DeviceOnlineStatusRespVO respVO = new DeviceOnlineStatusRespVO();
        respVO.setChipId(chipId);
        respVO.setIp(device != null ? device.getIp() : null);
        respVO.setOnline(deviceSessionManager.isOnline(chipId));
        respVO.setLastSeen(deviceSessionManager.getLastSeen(chipId));
        return respVO;
    }
}

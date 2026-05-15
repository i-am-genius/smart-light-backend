package com.genius.smartlight.service.device.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.convert.device.DeviceConvert;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.dataobject.StoreDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.dal.mysql.StoreMapper;
import com.genius.smartlight.security.SecurityUtils;
import com.genius.smartlight.service.device.DeviceService;
import com.genius.smartlight.service.device.OtaProgressStore;
import com.genius.smartlight.service.lighteffect.LightEffectService;
import com.genius.smartlight.vo.device.DeviceRespVO;
import com.genius.smartlight.vo.device.DeviceSaveReqVO;
import com.genius.smartlight.vo.device.LightEffectReqVO;
import com.genius.smartlight.websocket.DeviceSessionManager;
import com.genius.smartlight.websocket.WebSocketPushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceServiceImpl implements DeviceService {

    private final WebSocketPushService webSocketPushService;
    private final DeviceSessionManager deviceSessionManager;
    private final DeviceMapper deviceMapper;
    private final StoreMapper storeMapper;
    private final ObjectMapper objectMapper;
    private final OtaProgressStore otaProgressStore;
    private final LightEffectService lightEffectService;

    /**
     * 获取当前登录用户对应的店铺 ID。
     */
    private StoreDO getCurrentStore() {
        Long userId = SecurityUtils.getCurrentUserId();
        StoreDO store = storeMapper.selectOne(
                new LambdaQueryWrapper<StoreDO>()
                        .eq(StoreDO::getUserId, userId)
        );
        if (store == null) {
            throw new ServiceException("当前用户未绑定店铺");
        }
        return store;
    }

    /**
     * 按 chipId 查询设备并校验是否属于当前用户店铺。
     */
    private DeviceDO getDeviceByChipIdForCurrentStore(String chipId) {
        StoreDO store = getCurrentStore();
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
        return device;
    }

    /**
     * 按主键 ID 查询设备并校验是否属于当前用户店铺。
     */
    private DeviceDO getDeviceByIdForCurrentStore(Long id) {
        StoreDO store = getCurrentStore();
        DeviceDO device = deviceMapper.selectById(id);
        if (device == null) {
            throw new ServiceException("设备不存在");
        }
        if (device.getStoreId() == null || !device.getStoreId().equals(store.getId())) {
            throw new ServiceException("无权操作该设备");
        }
        return device;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createDevice(DeviceSaveReqVO reqVO) {
        StoreDO store = getCurrentStore();

        DeviceDO exist = deviceMapper.selectOne(
                new LambdaQueryWrapper<DeviceDO>()
                        .eq(DeviceDO::getChipId, reqVO.getChipId())
        );

        LocalDateTime now = LocalDateTime.now();

        if (exist != null) {
            if (exist.getStoreId() != null && !exist.getStoreId().equals(store.getId())) {
                throw new ServiceException("该设备已被其他店铺绑定");
            }

            if (exist.getStoreId() != null && exist.getStoreId().equals(store.getId())) {
                throw new ServiceException("该设备已添加到当前店铺");
            }

            exist.setStoreId(store.getId());
            exist.setDeviceType(reqVO.getDeviceType());
            exist.setDeviceNo(reqVO.getDeviceNo());
            exist.setDisplayName(reqVO.getDisplayName());
            exist.setIp(reqVO.getIp());
            exist.setBrightness(reqVO.getBrightness());
            exist.setTemp(reqVO.getTemp());
            exist.setAutoMode(reqVO.getAutoMode());
            exist.setRecommendedBrightness(reqVO.getRecommendedBrightness());
            exist.setRecommendedTemp(reqVO.getRecommendedTemp());
            exist.setFabric(reqVO.getFabric());
            exist.setMainColorRgb(reqVO.getMainColorRgb());
            exist.setUpdateTime(now);

            deviceMapper.updateById(exist);

            DeviceRespVO respVO = toResp(exist);
            webSocketPushService.pushState(respVO);

            log.info("Device created, id={}, chipId={}, storeId={}", exist.getId(), exist.getChipId(), exist.getStoreId());
            return exist.getId();
        }

        DeviceDO device = DeviceConvert.convert(reqVO);
        device.setStoreId(store.getId());
        device.setCreateTime(now);
        device.setUpdateTime(now);

        deviceMapper.insert(device);

        DeviceRespVO respVO = toResp(device);
        webSocketPushService.pushState(respVO);

        log.info("Device created, id={}, chipId={}, storeId={}", device.getId(), device.getChipId(), device.getStoreId());
        return device.getId();
    }

    @Override
    public void updateDevice(Long id, DeviceSaveReqVO reqVO, boolean lightControl) {
        DeviceDO device = getDeviceByIdForCurrentStore(id);

        if (lightControl) {
            lightEffectService.closeForLightControl(device.getStoreId());
        }

        if (!device.getChipId().equals(reqVO.getChipId())) {
            DeviceDO exist = deviceMapper.selectOne(
                    new LambdaQueryWrapper<DeviceDO>()
                            .eq(DeviceDO::getChipId, reqVO.getChipId())
            );
            if (exist != null) {
                throw new ServiceException("芯片ID已存在");
            }
        }

        DeviceDO updateObj = DeviceConvert.convert(reqVO);
        updateObj.setId(id);
        updateObj.setCreateTime(device.getCreateTime());
        updateObj.setUpdateTime(LocalDateTime.now());
        updateObj.setStoreId(device.getStoreId());
        updateObj.setFirmwareVersion(device.getFirmwareVersion());
        updateObj.setFirmwareVersionCode(device.getFirmwareVersionCode());
        updateObj.setFirmwareChannel(device.getFirmwareChannel());
        updateObj.setOtaStatus(device.getOtaStatus());

        updateObj.setDisplayName(reqVO.getDisplayName());

        deviceMapper.updateById(updateObj);

        DeviceRespVO respVO = toResp(updateObj);

        webSocketPushService.pushState(respVO);
        webSocketPushService.pushStateToDevice(updateObj.getChipId(), respVO);
        log.info("Device updated, id={}, chipId={}, storeId={}, lightControl={}",
                updateObj.getId(), updateObj.getChipId(), updateObj.getStoreId(), lightControl);
    }

    @Override
    public void deleteDevice(Long id) {
        DeviceDO device = getDeviceByIdForCurrentStore(id);

        notifyDeviceResumeBroadcast(device);
        Long storeId = device.getStoreId();
        String chipId = device.getChipId();
        deviceMapper.deleteById(id);
        webSocketPushService.pushDeviceDeleted(id, chipId, storeId);
        log.info("Device deleted, id={}, chipId={}, storeId={}", id, chipId, storeId);
    }

    private void notifyDeviceResumeBroadcast(DeviceDO device) {
        String chipId = deviceSessionManager.normalizeChipId(device.getChipId());
        log.info("准备发送恢复广播指令, chipId={}", chipId);

        if (chipId == null) {
            log.warn("Device chipId is blank, skip resume_broadcast, deviceId={}", device.getId());
            return;
        }

        boolean online = deviceSessionManager.isOnline(chipId);
        log.debug("Device online state before resume_broadcast, chipId={}, online={}", chipId, online);

        if (!online) {
            log.warn("设备离线，无法发送恢复广播指令, chipId={}", chipId);
            return;
        }

        try {
            ObjectNode msg = objectMapper.createObjectNode();
            msg.put("type", "command");
            msg.put("cmd", "resume_broadcast");
            msg.put("action", "resumeBroadcast");
            msg.put("resumeBroadcast", true);

            String json = msg.toString();
            log.debug("Sending resume_broadcast command, chipId={}, cmd=resume_broadcast", chipId);

            boolean sent = deviceSessionManager.sendToDevice(chipId, json);

            if (sent) {
                log.info("恢复广播指令发送成功, chipId={}", chipId);
            } else {
                log.warn("恢复广播指令发送失败(设备session已关闭), chipId={}", chipId);
            }
        } catch (Exception e) {
            log.error("恢复广播指令发送异常, chipId={}, error={}", chipId, e.getMessage(), e);
        }
    }

    @Override
    public DeviceRespVO getDevice(Long id) {
        DeviceDO device = getDeviceByIdForCurrentStore(id);
        return toResp(device);
    }

    @Override
    public List<DeviceRespVO> getDeviceList() {
        StoreDO store = getCurrentStore();
        List<DeviceDO> list = deviceMapper.selectList(
                new LambdaQueryWrapper<DeviceDO>()
                        .eq(DeviceDO::getStoreId, store.getId())
                        .orderByDesc(DeviceDO::getId)
        );
        return list.stream().map(this::toResp).toList();
    }

    @Override
    public DeviceRespVO getDeviceByChipId(String chipId) {
        DeviceDO device = getDeviceByChipIdForCurrentStore(chipId);
        return toResp(device);
    }

    @Override
    public List<DeviceRespVO> getCurrentUserDeviceList() {
        StoreDO store = getCurrentStore();
        List<DeviceDO> list = deviceMapper.selectList(
                new LambdaQueryWrapper<DeviceDO>()
                        .eq(DeviceDO::getStoreId, store.getId())
                        .orderByDesc(DeviceDO::getId)
        );
        return list.stream().map(this::toResp).toList();
    }

    @Override
    public void bindDeviceToCurrentStore(String chipId, String displayName) {
        StoreDO store = getCurrentStore();
        String normalizedChipId = deviceSessionManager.normalizeChipId(chipId);
        if (normalizedChipId == null) {
            throw new ServiceException("设备不存在");
        }

        DeviceDO device = deviceMapper.selectOne(
                new LambdaQueryWrapper<DeviceDO>()
                        .eq(DeviceDO::getChipId, normalizedChipId)
        );
        if (device == null) {
            throw new ServiceException("设备不存在");
        }

        Long deviceStoreId = device.getStoreId();
        if (deviceStoreId != null && !deviceStoreId.equals(store.getId())) {
            log.warn("Bind device rejected: device already belongs to another store");
            throw new ServiceException("设备已绑定其他店铺");
        }

        if (deviceStoreId == null) {
            device.setStoreId(store.getId());
        }

        if (displayName != null && !displayName.isBlank()) {
            device.setDisplayName(displayName);
        }

        device.setUpdateTime(LocalDateTime.now());
        deviceMapper.updateById(device);

        webSocketPushService.pushState(toResp(device));
    }

    @Override
    public boolean locateDevice(String chipId) {
        getDeviceByChipIdForCurrentStore(chipId);

        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("type", "locate");
        msg.put("times", 3);
        msg.put("duration", 1200);

        boolean sent = webSocketPushService.pushRawToDevice(chipId, msg.toString());

        if (!sent) {
            throw new ServiceException("设备离线，无法定位");
        }

        return true;
    }

    @Override
    public void sendLightEffect(String chipId, LightEffectReqVO reqVO) {
        getDeviceByChipIdForCurrentStore(chipId);

        ObjectNode msg = objectMapper.createObjectNode();

        msg.put("type", "lightEffect");
        msg.put("effect", reqVO.getEffect() == null ? "wave" : reqVO.getEffect());
        msg.put("enabled", reqVO.getEnabled() == null || reqVO.getEnabled());

        if (reqVO.getBaseTemp() != null) {
            msg.put("baseTemp", reqVO.getBaseTemp());
        }
        if (reqVO.getRange() != null) {
            msg.put("range", reqVO.getRange());
        }
        if (reqVO.getSpeed() != null) {
            msg.put("speed", reqVO.getSpeed());
        }
        if (reqVO.getBrightness() != null) {
            msg.put("brightness", reqVO.getBrightness());
        }
        if (reqVO.getPhaseIndex() != null) {
            msg.put("phaseIndex", reqVO.getPhaseIndex());
        }
        if (reqVO.getPhaseGap() != null) {
            msg.put("phaseGap", reqVO.getPhaseGap());
        }

        boolean sent = webSocketPushService.pushRawToDevice(chipId, msg.toString());

        if (!sent) {
            throw new ServiceException("设备离线，灯效下发失败");
        }
    }

    @Override
    public void updateFirmwareChannel(String chipId, String channel) {
        DeviceDO device = getDeviceByChipIdForCurrentStore(chipId);

        String normalized = channel == null ? "" : channel.trim().toLowerCase(Locale.ROOT);
        if (!"stable".equals(normalized) && !"test".equals(normalized)) {
            throw new ServiceException("固件通道不正确，只支持 stable 或 test");
        }

        device.setFirmwareChannel(normalized);
        if (device.getOtaStatus() == null || device.getOtaStatus().isBlank()) {
            device.setOtaStatus("idle");
        }
        device.setUpdateTime(LocalDateTime.now());
        deviceMapper.updateById(device);
        webSocketPushService.pushState(toResp(device));
    }

    private DeviceRespVO toResp(DeviceDO device) {
        return otaProgressStore.applyProgress(DeviceConvert.convert(device));
    }
}

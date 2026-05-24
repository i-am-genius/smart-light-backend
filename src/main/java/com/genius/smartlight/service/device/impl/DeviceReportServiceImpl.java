package com.genius.smartlight.service.device.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.convert.device.DeviceConvert;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.service.device.DeviceReportService;
import com.genius.smartlight.service.device.OtaProgressStore;
import com.genius.smartlight.vo.device.DeviceRespVO;
import com.genius.smartlight.vo.device.DeviceStateReportReqVO;
import com.genius.smartlight.websocket.DeviceSessionManager;
import com.genius.smartlight.websocket.WebSocketPushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceReportServiceImpl implements DeviceReportService {

    private final DeviceMapper deviceMapper;
    private final WebSocketPushService webSocketPushService;
    private final DeviceSessionManager deviceSessionManager;
    private final OtaProgressStore otaProgressStore;

    @Override
    public void reportState(DeviceStateReportReqVO reqVO) {
        String chipId = reqVO.getChipId();
        log.debug("Device state report, chipId={} ip={} brightness={} temp={} autoMode={}",
                chipId, reqVO.getIp(), reqVO.getBrightness(), reqVO.getTemp(), reqVO.getAutoMode());

        DeviceDO device = deviceMapper.selectOne(
                new LambdaQueryWrapper<DeviceDO>()
                        .eq(DeviceDO::getChipId, chipId)
        );

        if (device == null) {
            log.warn("设备状态上报被拒绝：设备未注册 chipId={} ip={}", chipId, reqVO.getIp());
            throw new ServiceException("设备不存在，请先添加设备");
        }

        // 设备上报不修改 storeId 等归属字段，仅更新设备自身状态
        if (reqVO.getIp() != null) {
            device.setIp(reqVO.getIp());
        }
        if (reqVO.getDeviceType() != null) {
            device.setDeviceType(reqVO.getDeviceType());
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
        if (reqVO.getFirmwareVersion() != null) {
            device.setFirmwareVersion(reqVO.getFirmwareVersion());
        }
        if (reqVO.getFirmwareVersionCode() != null) {
            device.setFirmwareVersionCode(reqVO.getFirmwareVersionCode());
        }
        if (reqVO.getFirmwareChannel() != null) {
            device.setFirmwareChannel(normalizeChannel(reqVO.getFirmwareChannel()));
        }
        String oldOtaStatus = normalizeOtaStatus(device.getOtaStatus());
        String newOtaStatus = oldOtaStatus;
        boolean otaStatusReported = reqVO.getOtaStatus() != null;
        if (otaStatusReported) {
            newOtaStatus = normalizeOtaStatus(reqVO.getOtaStatus());
            device.setOtaStatus(newOtaStatus);
        }
        updateOtaProgress(chipId, oldOtaStatus, newOtaStatus, reqVO.getOtaProgress(), otaStatusReported);

        device.setUpdateTime(LocalDateTime.now());
        deviceMapper.updateById(device);

        // 设备已经走 ws/device 注册过，这里刷新 lastSeen
        deviceSessionManager.touch(chipId);

        DeviceRespVO respVO = otaProgressStore.applyProgress(DeviceConvert.convert(device));
        // 推送给该设备所属店铺的浏览器客户端（storeId 由 DeviceConvert 从 DeviceDO 填充）
        webSocketPushService.pushState(respVO);
    }

    private void updateOtaProgress(String chipId, String oldStatus, String newStatus, Integer progress, boolean statusReported) {
        if (progress != null) {
            otaProgressStore.setProgress(chipId, progress);
        }

        if (!statusReported) {
            if (progress != null && otaProgressStore.getProgress(chipId) == null) {
                otaProgressStore.setProgress(chipId, 0);
            }
            return;
        }

        if ("success".equals(newStatus)) {
            otaProgressStore.clearProgress(chipId);
            if (!"success".equals(oldStatus)) {
                log.info("OTA success, chipId={}, progress=100", chipId);
            }
            return;
        }
        if ("idle".equals(newStatus)) {
            otaProgressStore.clearProgress(chipId);
            return;
        }
        if ("failed".equals(newStatus)) {
            if (otaProgressStore.getProgress(chipId) == null) {
                otaProgressStore.setProgress(chipId, 0);
            }
            if (!"failed".equals(oldStatus)) {
                log.warn("OTA failed, chipId={}, progress={}, status={}",
                        chipId, otaProgressStore.getProgress(chipId), newStatus);
            }
            return;
        }
        if ("updating".equals(newStatus)) {
            if (progress != null) {
                otaProgressStore.setProgress(chipId, progress);
            } else if (!"updating".equals(oldStatus)) {
                otaProgressStore.setProgress(chipId, 0);
            } else if (otaProgressStore.getProgress(chipId) == null) {
                otaProgressStore.setProgress(chipId, 0);
            }
        }
    }

    private String normalizeChannel(String channel) {
        String value = channel == null ? "" : channel.trim().toLowerCase(Locale.ROOT);
        return "test".equals(value) ? "test" : "stable";
    }

    private String normalizeOtaStatus(String otaStatus) {
        String value = otaStatus == null ? "" : otaStatus.trim().toLowerCase(Locale.ROOT);
        if ("updating".equals(value) || "success".equals(value) || "failed".equals(value)) {
            return value;
        }
        return "idle";
    }
}

package com.genius.smartlight.service.device.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.genius.smartlight.common.DeviceTypeUtil;
import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.convert.device.DeviceConvert;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.dataobject.OtaFirmwareDO;
import com.genius.smartlight.dal.dataobject.StoreDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.dal.mysql.OtaFirmwareMapper;
import com.genius.smartlight.dal.mysql.StoreMapper;
import com.genius.smartlight.security.SecurityUtils;
import com.genius.smartlight.service.device.DeviceOtaService;
import com.genius.smartlight.service.device.OtaDownloadSecurityService;
import com.genius.smartlight.service.device.OtaProgressStore;
import com.genius.smartlight.vo.device.DeviceOtaCheckRespVO;
import com.genius.smartlight.vo.device.DeviceOtaStartReqVO;
import com.genius.smartlight.websocket.DeviceSessionManager;
import com.genius.smartlight.websocket.WebSocketPushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.Locale;

@Service
@Slf4j
@RequiredArgsConstructor
public class DeviceOtaServiceImpl implements DeviceOtaService {

    private static final String CHANNEL_STABLE = "stable";
    private static final String CHANNEL_TEST = "test";
    private static final String OTA_STATUS_UPDATING = "updating";

    private final DeviceMapper deviceMapper;
    private final OtaFirmwareMapper otaFirmwareMapper;
    private final StoreMapper storeMapper;
    private final DeviceSessionManager deviceSessionManager;
    private final WebSocketPushService webSocketPushService;
    private final ObjectMapper objectMapper;
    private final OtaProgressStore otaProgressStore;
    private final OtaDownloadSecurityService otaDownloadSecurityService;

    /**
     * 按 chipId 查询设备并校验是否属于当前用户店铺。
     */
    private DeviceDO getDeviceByChipIdForCurrentStore(String chipId) {
        if (chipId == null || chipId.isBlank()) {
            throw new ServiceException("芯片ID不能为空");
        }
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
        return device;
    }

    @Override
    public DeviceOtaCheckRespVO checkUpdate(String chipId, String channel) {
        DeviceDO device = getDeviceByChipIdForCurrentStore(chipId);
        String currentChannel = normalizeChannel(device.getFirmwareChannel());
        String targetChannel = resolveTargetChannel(channel, currentChannel);
        OtaFirmwareDO firmware = findLatestFirmware(device, targetChannel);
        return buildCheckResp(device, firmware, currentChannel, targetChannel);
    }

    @Override
    public DeviceOtaCheckRespVO startUpdate(String chipId, DeviceOtaStartReqVO reqVO) {
        DeviceDO device = getDeviceByChipIdForCurrentStore(chipId);
        String currentChannel = normalizeChannel(device.getFirmwareChannel());
        String targetChannel = resolveTargetChannel(reqVO == null ? null : reqVO.getChannel(), currentChannel);
        OtaFirmwareDO firmware = resolveFirmware(device, reqVO, targetChannel);

        if (firmware == null) {
            throw new ServiceException("暂无可用固件");
        }
        if (!Boolean.TRUE.equals(firmware.getEnabled())) {
            throw new ServiceException("固件已禁用");
        }
        if (!safeEquals(normalizeDeviceType(device.getDeviceType()), normalizeDeviceType(firmware.getDeviceType()))) {
            throw new ServiceException("固件设备类型不匹配");
        }
        if (!safeEquals(targetChannel, normalizeChannel(firmware.getChannel()))) {
            throw new ServiceException("固件通道与目标通道不匹配");
        }
        otaDownloadSecurityService.validateStoredFileUrl(firmware.getFileUrl());
        String signedFileUrl = otaDownloadSecurityService.signDownloadUrl(firmware.getFileUrl());

        int currentCode = device.getFirmwareVersionCode() == null ? 0 : device.getFirmwareVersionCode();
        int targetCode = firmware.getVersionCode() == null ? 0 : firmware.getVersionCode();
        if (!isUpdatable(currentChannel, currentCode, targetChannel, targetCode)) {
            throw new ServiceException("当前已是最新固件");
        }
        if (!deviceSessionManager.isOnline(chipId)) {
            throw new ServiceException("设备未连接或已离线");
        }

        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("type", "ota_update");
        msg.put("url", signedFileUrl);
        msg.put("version", firmware.getVersion());
        msg.put("versionCode", firmware.getVersionCode());
        msg.put("channel", targetChannel);
        if (firmware.getMd5() != null && !firmware.getMd5().isBlank()) {
            msg.put("md5", firmware.getMd5());
        }

        boolean sent = webSocketPushService.pushRawToDevice(chipId, msg.toString());
        if (!sent) {
            throw new ServiceException("OTA指令下发失败");
        }

        device.setOtaStatus(OTA_STATUS_UPDATING);
        device.setUpdateTime(LocalDateTime.now());
        otaProgressStore.setProgress(chipId, 0);
        deviceMapper.updateById(device);
        webSocketPushService.pushState(DeviceConvert.convert(device));
        log.info("OTA start, chipId={}, version={}, versionCode={}, channel={}",
                chipId, firmware.getVersion(), firmware.getVersionCode(), targetChannel);

        return buildCheckResp(device, firmware, currentChannel, targetChannel);
    }

    private OtaFirmwareDO resolveFirmware(DeviceDO device, DeviceOtaStartReqVO reqVO, String targetChannel) {
        if (reqVO != null && reqVO.getFirmwareId() != null) {
            OtaFirmwareDO firmware = otaFirmwareMapper.selectById(reqVO.getFirmwareId());
            if (firmware == null) {
                throw new ServiceException("固件不存在");
            }
            return firmware;
        }
        return findLatestFirmware(device, targetChannel);
    }

    private OtaFirmwareDO findLatestFirmware(DeviceDO device, String targetChannel) {
        return otaFirmwareMapper.selectOne(
                new LambdaQueryWrapper<OtaFirmwareDO>()
                        .eq(OtaFirmwareDO::getDeviceType, normalizeDeviceType(device.getDeviceType()))
                        .eq(OtaFirmwareDO::getChannel, targetChannel)
                        .eq(OtaFirmwareDO::getEnabled, true)
                        .orderByDesc(OtaFirmwareDO::getVersionCode)
                        .orderByDesc(OtaFirmwareDO::getUpdateTime)
                        .orderByDesc(OtaFirmwareDO::getId)
                        .last("limit 1")
        );
    }

    private DeviceOtaCheckRespVO buildCheckResp(DeviceDO device, OtaFirmwareDO firmware, String currentChannel, String targetChannel) {
        DeviceOtaCheckRespVO respVO = new DeviceOtaCheckRespVO();
        respVO.setChipId(device.getChipId());
        respVO.setDeviceType(device.getDeviceType());
        respVO.setChannel(targetChannel);
        respVO.setCurrentVersion(device.getFirmwareVersion());
        respVO.setCurrentVersionCode(device.getFirmwareVersionCode());
        respVO.setOtaStatus(device.getOtaStatus());

        if (firmware != null) {
            respVO.setFirmwareId(firmware.getId());
            respVO.setLatestVersion(firmware.getVersion());
            respVO.setLatestVersionCode(firmware.getVersionCode());
            respVO.setFileUrl(otaDownloadSecurityService.signDownloadUrl(firmware.getFileUrl()));
            respVO.setMd5(firmware.getMd5());
            respVO.setChangelog(firmware.getChangelog());
            int currentCode = device.getFirmwareVersionCode() == null ? 0 : device.getFirmwareVersionCode();
            int latestCode = firmware.getVersionCode() == null ? 0 : firmware.getVersionCode();
            respVO.setHasUpdate(isUpdatable(currentChannel, currentCode, targetChannel, latestCode));
        } else {
            respVO.setHasUpdate(false);
        }
        return respVO;
    }

    private String resolveTargetChannel(String channel, String currentChannel) {
        if (channel == null || channel.isBlank()) {
            return currentChannel;
        }
        return normalizeChannel(channel);
    }

    private boolean isUpdatable(String currentChannel, int currentCode, String targetChannel, int targetCode) {
        if (targetCode <= 0) {
            return false;
        }
        if (!safeEquals(currentChannel, targetChannel)) {
            return true;
        }
        return targetCode > currentCode;
    }

    private String normalizeChannel(String channel) {
        String value = channel == null ? "" : channel.trim().toLowerCase(Locale.ROOT);
        if (CHANNEL_TEST.equals(value)) {
            return CHANNEL_TEST;
        }
        return CHANNEL_STABLE;
    }

    private String normalizeDeviceType(String deviceType) {
        return DeviceTypeUtil.normalize(deviceType);
    }

    private boolean safeEquals(String a, String b) {
        return a != null && b != null && a.equals(b);
    }

    private void validateFileUrl(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) {
            throw new ServiceException("固件文件地址不能为空");
        }

        try {
            URI uri = URI.create(fileUrl);
            String scheme = uri.getScheme();
            String host = uri.getHost();

            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new ServiceException("固件文件地址必须使用 http 或 https 协议");
            }
            if (host == null || host.isBlank()) {
                throw new ServiceException("固件文件地址主机名不能为空");
            }

            String lowerHost = host.toLowerCase(Locale.ROOT);
            if ("localhost".equals(lowerHost)
                    || "127.0.0.1".equals(lowerHost)
                    || "::1".equals(lowerHost)
                    || "0:0:0:0:0:0:0:1".equals(lowerHost)) {
                throw new ServiceException("固件文件地址不能使用 localhost，ESP8266 无法访问");
            }
        } catch (IllegalArgumentException e) {
            throw new ServiceException("固件文件地址无效");
        }
    }
}

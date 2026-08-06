package com.genius.smartlight.service.device.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.genius.smartlight.common.DeviceTypeUtil;
import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.convert.device.DeviceConvert;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.service.device.DeviceLastSeenService;
import com.genius.smartlight.service.device.DeviceReportService;
import com.genius.smartlight.service.device.OtaProgressStore;
import com.genius.smartlight.vo.device.DeviceRespVO;
import com.genius.smartlight.vo.device.DeviceStateReportReqVO;
import com.genius.smartlight.websocket.DeviceSessionManager;
import com.genius.smartlight.websocket.WebSocketPushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceReportServiceImpl implements DeviceReportService {

    private final DeviceMapper deviceMapper;
    private final WebSocketPushService webSocketPushService;
    private final DeviceSessionManager deviceSessionManager;
    private final OtaProgressStore otaProgressStore;
    private final ObjectMapper objectMapper;
    private final DeviceLastSeenService deviceLastSeenService;

    @Override
    public void reportState(DeviceStateReportReqVO reqVO) {
        long totalStartNs = System.nanoTime();
        String chipId = reqVO.getChipId();
        log.debug("[STATE-REPORT-PERF] chipId={} step=start", chipId);
        log.debug("Device state report, chipId={} ip={} brightness={} temp={} autoMode={}",
                chipId, reqVO.getIp(), reqVO.getBrightness(), reqVO.getTemp(), reqVO.getAutoMode());

        long stepStartNs = System.nanoTime();
        DeviceDO device = deviceMapper.selectOne(
                new LambdaQueryWrapper<DeviceDO>()
                        .eq(DeviceDO::getChipId, chipId)
        );
        logPerf(chipId, "queryDevice", stepStartNs);

        if (device == null) {
            log.warn("Device state report rejected: unregistered device, chipId={} ip={}", chipId, reqVO.getIp());
            throw new ServiceException("Device does not exist, please add it first");
        }

        // Device reports are telemetry; user-managed light settings remain
        // database-owned and are pushed back to the device on registration.
        if (reqVO.getIp() != null) {
            device.setIp(reqVO.getIp());
        }
        if (reqVO.getDeviceType() != null) {
            device.setDeviceType(normalizeDeviceType(reqVO.getDeviceType()));
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
        stepStartNs = System.nanoTime();
        if (isCompletedSelfTest(reqVO.getSelfTest())) {
            device.setSelfTestJson(writeSelfTestJson(reqVO.getSelfTest()));
            device.setSelfTestTime(LocalDateTime.now());
        }
        logPerf(chipId, "saveSelfTest", stepStartNs);

        stepStartNs = System.nanoTime();
        String oldOtaStatus = normalizeOtaStatus(device.getOtaStatus());
        String newOtaStatus = oldOtaStatus;
        boolean otaStatusReported = reqVO.getOtaStatus() != null;
        if (otaStatusReported) {
            newOtaStatus = normalizeOtaStatus(reqVO.getOtaStatus());
            device.setOtaStatus(newOtaStatus);
        }
        updateOtaProgress(chipId, oldOtaStatus, newOtaStatus, reqVO.getOtaProgress(), otaStatusReported);
        logPerf(chipId, "otaProgress", stepStartNs);

        device.setUpdateTime(LocalDateTime.now());
        stepStartNs = System.nanoTime();
        deviceMapper.updateById(device);
        logPerf(chipId, "updateDevice", stepStartNs);

        // Refresh lastSeen for devices that have registered through ws/device.
        stepStartNs = System.nanoTime();
        deviceSessionManager.touch(chipId);
        LocalDateTime persistedLastSeenAt = deviceLastSeenService.persistIfDue(
                chipId, deviceSessionManager.getLastSeen(chipId));
        if (persistedLastSeenAt != null) {
            device.setLastSeenAt(persistedLastSeenAt);
        }
        logPerf(chipId, "lastSeen", stepStartNs);

        stepStartNs = System.nanoTime();
        DeviceRespVO respVO = otaProgressStore.applyProgress(DeviceConvert.convert(device));
        logPerf(chipId, "convert", stepStartNs);

        stepStartNs = System.nanoTime();
        webSocketPushService.pushState(respVO);
        logPerf(chipId, "pushWs", stepStartNs);
        log.debug("[STATE-REPORT-PERF] chipId={} total={}ms", chipId, elapsedMs(totalStartNs));
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

    private boolean isCompletedSelfTest(Map<String, Object> selfTest) {
        return selfTest != null && Boolean.TRUE.equals(selfTest.get("done"));
    }

    private String writeSelfTestJson(Map<String, Object> selfTest) {
        try {
            return objectMapper.writeValueAsString(selfTest);
        } catch (JacksonException e) {
            throw new ServiceException("Invalid selfTest payload");
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

    private String normalizeDeviceType(String deviceType) {
        return DeviceTypeUtil.normalize(deviceType);
    }

    private void logPerf(String chipId, String step, long startedNs) {
        log.debug("[STATE-REPORT-PERF] chipId={} step={} cost={}ms", chipId, step, elapsedMs(startedNs));
    }

    private long elapsedMs(long startedNs) {
        return (System.nanoTime() - startedNs) / 1_000_000L;
    }
}

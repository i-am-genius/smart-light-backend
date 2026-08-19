package com.genius.smartlight.service.device.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.service.ai.GarmentResultCodec;
import com.genius.smartlight.service.device.GarmentSourceResultService;
import com.genius.smartlight.websocket.WebSocketPushService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GarmentSourceResultServiceImpl implements GarmentSourceResultService {

    private final DeviceMapper deviceMapper;
    private final ObjectMapper objectMapper;
    private final WebSocketPushService webSocketPushService;

    @Override
    public void saveLatestResult(String lampChipId, String sourceKey) {
        if (!StringUtils.hasText(lampChipId) || !StringUtils.hasText(sourceKey)) {
            return;
        }
        DeviceDO device = findLamp(lampChipId);
        if (device == null || !StringUtils.hasText(device.getGarmentResultJson())) {
            return;
        }

        String normalizedSourceKey = sourceKey.trim();
        SourceResultDocument document = read(device.getGarmentSourceResultJson());
        document.getSources().put(normalizedSourceKey, device.getGarmentResultJson());
        document.setLatestSourceKey(normalizedSourceKey);
        document.setVersion(1);
        try {
            device.setGarmentSourceResultJson(objectMapper.writeValueAsString(document));
            device.setUpdateTime(LocalDateTime.now());
            if (deviceMapper.updateById(device) != 1) {
                throw new ServiceException("保存拍摄来源识别结果失败");
            }
        } catch (ServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("save garment source result failed, chipId={}, sourceKey={}, exceptionType={}",
                    lampChipId, normalizedSourceKey, exception.getClass().getSimpleName());
            throw new ServiceException("拍摄来源识别结果序列化失败");
        }

        pushSnapshot(device, normalizedSourceKey, device.getGarmentResultJson());
    }

    @Override
    public void pushLatestResult(String lampChipId) {
        if (!StringUtils.hasText(lampChipId)) {
            return;
        }
        DeviceDO device = findLamp(lampChipId);
        if (device == null || !Boolean.TRUE.equals(device.getGarmentAimEnabled())) {
            return;
        }

        SourceResultDocument document = read(device.getGarmentSourceResultJson());
        String sourceKey = resolveLatestSourceKey(document, device.getGarmentResultJson());
        if (!StringUtils.hasText(sourceKey)) {
            log.debug("latest garment source unavailable, chipId={}", lampChipId);
            return;
        }
        String sourceResultJson = document.getSources().get(sourceKey);
        if (!StringUtils.hasText(sourceResultJson)) {
            log.debug("latest garment source result unavailable, chipId={}, sourceKey={}", lampChipId, sourceKey);
            return;
        }
        pushSnapshot(device, sourceKey, sourceResultJson);
    }

    private DeviceDO findLamp(String lampChipId) {
        return deviceMapper.selectOne(new LambdaQueryWrapper<DeviceDO>()
                .eq(DeviceDO::getChipId, lampChipId.trim())
                .last("limit 1"));
    }

    private void pushSnapshot(DeviceDO device, String sourceKey, String resultJson) {
        GarmentResultCodec.decode(resultJson).ifPresent(snapshot ->
                webSocketPushService.pushGarmentAimToDevice(
                        device.getChipId(),
                        snapshot,
                        sourceKey,
                        Boolean.TRUE.equals(device.getGarmentAimEnabled())
                )
        );
    }

    private String resolveLatestSourceKey(SourceResultDocument document, String globalLatestJson) {
        if (StringUtils.hasText(document.getLatestSourceKey())
                && document.getSources().containsKey(document.getLatestSourceKey())) {
            return document.getLatestSourceKey();
        }
        if (!StringUtils.hasText(globalLatestJson)) {
            return null;
        }

        String match = null;
        for (Map.Entry<String, String> entry : document.getSources().entrySet()) {
            if (!globalLatestJson.equals(entry.getValue())) {
                continue;
            }
            if (match != null && !match.equals(entry.getKey())) {
                return null;
            }
            match = entry.getKey();
        }
        return match;
    }

    private SourceResultDocument read(String json) {
        if (!StringUtils.hasText(json)) {
            return new SourceResultDocument();
        }
        try {
            SourceResultDocument document = objectMapper.readValue(json, SourceResultDocument.class);
            if (document.getSources() == null) {
                document.setSources(new LinkedHashMap<>());
            }
            return document;
        } catch (Exception exception) {
            log.warn("garment source result document decode failed, exceptionType={}",
                    exception.getClass().getSimpleName());
            return new SourceResultDocument();
        }
    }

    @Data
    public static class SourceResultDocument {
        private Integer version = 1;
        private String latestSourceKey;
        private Map<String, String> sources = new LinkedHashMap<>();
    }
}

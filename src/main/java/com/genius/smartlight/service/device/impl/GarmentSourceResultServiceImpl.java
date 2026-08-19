package com.genius.smartlight.service.device.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.service.device.GarmentSourceResultService;
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

    @Override
    public void saveLatestResult(String lampChipId, String sourceKey) {
        if (!StringUtils.hasText(lampChipId) || !StringUtils.hasText(sourceKey)) {
            return;
        }
        DeviceDO device = deviceMapper.selectOne(new LambdaQueryWrapper<DeviceDO>()
                .eq(DeviceDO::getChipId, lampChipId.trim())
                .last("limit 1"));
        if (device == null || !StringUtils.hasText(device.getGarmentResultJson())) {
            return;
        }

        SourceResultDocument document = read(device.getGarmentSourceResultJson());
        document.getSources().put(sourceKey.trim(), device.getGarmentResultJson());
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
                    lampChipId, sourceKey, exception.getClass().getSimpleName());
            throw new ServiceException("拍摄来源识别结果序列化失败");
        }
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
        private Map<String, String> sources = new LinkedHashMap<>();
    }
}

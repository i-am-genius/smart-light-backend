package com.genius.smartlight.service.device.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.genius.smartlight.common.DeviceTypeUtil;
import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.dataobject.StoreDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.dal.mysql.StoreMapper;
import com.genius.smartlight.security.SecurityUtils;
import com.genius.smartlight.service.ai.GarmentAimCalibrationFitter;
import com.genius.smartlight.service.ai.GarmentAimTarget;
import com.genius.smartlight.service.ai.GarmentResultCodec;
import com.genius.smartlight.service.device.GarmentAimCalibrationService;
import com.genius.smartlight.vo.ai.GarmentResultSnapshot;
import com.genius.smartlight.vo.device.DeviceGarmentAimCalibrationRespVO;
import com.genius.smartlight.vo.device.DeviceGarmentAimCalibrationSampleReqVO;
import com.genius.smartlight.websocket.DeviceSessionManager;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class GarmentAimCalibrationServiceImpl implements GarmentAimCalibrationService {

    private static final int DOCUMENT_VERSION = 2;
    private static final int MAX_SAMPLE_COUNT = 60;

    private static final double LEGACY_DEFAULT_PAN = 0D;
    private static final double LEGACY_DEFAULT_TILT = 20D;
    private static final double DEFAULT_HORIZONTAL_FOV = 60D;
    private static final double DEFAULT_VERTICAL_FOV = 45D;

    private final DeviceMapper deviceMapper;
    private final StoreMapper storeMapper;
    private final DeviceSessionManager deviceSessionManager;
    private final ObjectMapper objectMapper;

    @Override
    public DeviceGarmentAimCalibrationRespVO getCalibration(String lampChipId) {
        DeviceDO lamp = requireOwnedLamp(lampChipId);
        return buildResponse(lamp, readDocument(lamp));
    }

    @Override
    public DeviceGarmentAimCalibrationRespVO addSample(
            String lampChipId,
            DeviceGarmentAimCalibrationSampleReqVO reqVO) {
        DeviceDO lamp = requireOwnedLamp(lampChipId);
        if (!deviceSessionManager.isOnline(lamp.getChipId())) {
            throw new ServiceException("Lamp 离线，无法确认标定位置");
        }

        GarmentResultSnapshot snapshot = GarmentResultCodec.decode(lamp.getGarmentResultJson())
                .orElseThrow(() -> new ServiceException("尚无可用于标定的服装识别结果"));
        GarmentAimTarget target = GarmentAimTarget.from(snapshot)
                .orElseThrow(() -> new ServiceException("当前服装识别坐标无效，请重新拍摄"));
        if (snapshot.getRecognizedAt() == null) {
            throw new ServiceException("当前识别结果缺少时间标识，请重新拍摄");
        }

        CalibrationDocument document = readDocument(lamp);
        boolean duplicate = document.getSamples().stream()
                .anyMatch(sample -> snapshot.getRecognizedAt().equals(sample.getRecognizedAt()));
        if (duplicate) {
            throw new ServiceException("当前识别位置已经标定，请移动服装并重新拍摄");
        }

        StoredSample sample = new StoredSample();
        sample.setId(UUID.randomUUID().toString());
        sample.setCenterX(target.centerX());
        sample.setCenterY(target.centerY());
        sample.setPanOffset(reqVO.getPan() - defaultGarmentPan(lamp));
        sample.setTiltOffset(reqVO.getTilt() - defaultGarmentTilt(lamp));
        sample.setRecognizedAt(snapshot.getRecognizedAt());
        sample.setCreatedAt(LocalDateTime.now());
        document.getSamples().add(sample);
        document.getSamples().sort(Comparator.comparing(StoredSample::getCreatedAt));
        while (document.getSamples().size() > MAX_SAMPLE_COUNT) {
            document.getSamples().remove(0);
        }
        document.setVersion(DOCUMENT_VERSION);
        document.setUpdatedAt(LocalDateTime.now());
        persist(lamp, document);
        return buildResponse(lamp, document);
    }

    @Override
    public DeviceGarmentAimCalibrationRespVO clearCalibration(String lampChipId) {
        DeviceDO lamp = requireOwnedLamp(lampChipId);
        CalibrationDocument document = new CalibrationDocument();
        document.setUpdatedAt(LocalDateTime.now());
        persist(lamp, document);
        return buildResponse(lamp, document);
    }

    @Override
    public Optional<GarmentAimCalibrationFitter.Pose> predict(
            String lampChipId,
            GarmentAimTarget target) {
        if (!StringUtils.hasText(lampChipId) || target == null) {
            return Optional.empty();
        }
        DeviceDO lamp = deviceMapper.selectOne(
                new LambdaQueryWrapper<DeviceDO>()
                        .eq(DeviceDO::getChipId, lampChipId.trim())
                        .last("limit 1")
        );
        if (lamp == null || !DeviceTypeUtil.isLampLike(lamp.getDeviceType())) {
            return Optional.empty();
        }
        GarmentAimCalibrationFitter.FitResult fit = fit(readDocument(lamp));
        if (!fit.ready() || fit.model() == null) {
            return Optional.empty();
        }
        return Optional.of(applyDefault(lamp, fit.model().predict(target.centerX(), target.centerY())));
    }

    private DeviceDO requireOwnedLamp(String lampChipId) {
        if (!StringUtils.hasText(lampChipId)) {
            throw new ServiceException("Lamp 芯片 ID 不能为空");
        }
        Long userId = SecurityUtils.getCurrentUserId();
        StoreDO store = storeMapper.selectOne(
                new LambdaQueryWrapper<StoreDO>()
                        .eq(StoreDO::getUserId, userId)
                        .last("limit 1")
        );
        if (store == null) {
            throw new ServiceException("当前用户未绑定店铺");
        }
        DeviceDO lamp = deviceMapper.selectOne(
                new LambdaQueryWrapper<DeviceDO>()
                        .eq(DeviceDO::getChipId, lampChipId.trim())
                        .last("limit 1")
        );
        if (lamp == null) {
            throw new ServiceException("Lamp 设备不存在");
        }
        if (lamp.getStoreId() == null || !lamp.getStoreId().equals(store.getId())) {
            throw new ServiceException("无权操作该设备");
        }
        if (!DeviceTypeUtil.isLampLike(lamp.getDeviceType())) {
            throw new ServiceException("该设备不支持服装照射标定");
        }
        return lamp;
    }

    private CalibrationDocument readDocument(DeviceDO lamp) {
        if (lamp == null || !StringUtils.hasText(lamp.getGarmentAimCalibrationJson())) {
            return new CalibrationDocument();
        }
        try {
            CalibrationDocument document = objectMapper.readValue(
                    lamp.getGarmentAimCalibrationJson(),
                    CalibrationDocument.class
            );
            if (document.getSamples() == null) {
                document.setSamples(new ArrayList<>());
            }
            migrateLegacySamples(document);
            return document;
        } catch (Exception exception) {
            log.warn("garment aim calibration decode failed, chipId={}, exceptionType={}",
                    lamp.getChipId(), exception.getClass().getSimpleName());
            return new CalibrationDocument();
        }
    }

    static void migrateLegacySamples(CalibrationDocument document) {
        if (document == null || document.getSamples() == null) {
            return;
        }
        document.getSamples().forEach(sample -> {
            if (sample.getPanOffset() == null && sample.getPan() != null) {
                sample.setPanOffset(sample.getPan() - LEGACY_DEFAULT_PAN);
            }
            if (sample.getTiltOffset() == null && sample.getTilt() != null) {
                sample.setTiltOffset(sample.getTilt() - LEGACY_DEFAULT_TILT);
            }
        });
        document.setVersion(DOCUMENT_VERSION);
    }

    private void persist(DeviceDO lamp, CalibrationDocument document) {
        try {
            lamp.setGarmentAimCalibrationJson(objectMapper.writeValueAsString(document));
            lamp.setUpdateTime(LocalDateTime.now());
            if (deviceMapper.updateById(lamp) != 1) {
                throw new ServiceException("保存标定数据失败");
            }
        } catch (ServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ServiceException("标定数据序列化失败");
        }
    }

    private GarmentAimCalibrationFitter.FitResult fit(CalibrationDocument document) {
        List<GarmentAimCalibrationFitter.Sample> samples = document.getSamples().stream()
                .map(sample -> new GarmentAimCalibrationFitter.Sample(
                        sample.getCenterX(),
                        sample.getCenterY(),
                        panOffset(sample),
                        tiltOffset(sample)
                ))
                .toList();
        return GarmentAimCalibrationFitter.fit(samples);
    }

    private DeviceGarmentAimCalibrationRespVO buildResponse(
            DeviceDO lamp,
            CalibrationDocument document) {
        GarmentAimCalibrationFitter.FitResult fit = fit(document);
        DeviceGarmentAimCalibrationRespVO response = new DeviceGarmentAimCalibrationRespVO();
        response.setLampChipId(lamp.getChipId());
        response.setSampleCount(document.getSamples().size());
        response.setMinimumSampleCount(GarmentAimCalibrationFitter.MIN_SAMPLE_COUNT);
        response.setRecommendedSampleCount(GarmentAimCalibrationFitter.RECOMMENDED_SAMPLE_COUNT);
        response.setModelReady(fit.ready());
        response.setStatusCode(fit.reason());
        response.setStatusMessage(statusMessage(fit, document.getSamples().size()));
        response.setHorizontalCoverage(fit.xCoverage());
        response.setVerticalCoverage(fit.yCoverage());
        response.setUpdatedAt(document.getUpdatedAt());
        if (fit.model() != null) {
            response.setRmsePan(fit.model().pan().rmse());
            response.setRmseTilt(fit.model().tilt().rmse());
        }
        response.setSamples(document.getSamples().stream()
                .sorted(Comparator.comparing(StoredSample::getCreatedAt).reversed())
                .map(sample -> toResponseSample(lamp, sample))
                .toList());

        Optional<GarmentResultSnapshot> snapshot = GarmentResultCodec.decode(lamp.getGarmentResultJson());
        Optional<GarmentAimTarget> target = snapshot.flatMap(GarmentAimTarget::from);
        response.setCurrentTargetValid(target.isPresent());
        if (target.isPresent()) {
            GarmentAimTarget value = target.get();
            LocalDateTime recognizedAt = snapshot.map(GarmentResultSnapshot::getRecognizedAt).orElse(null);
            response.setCurrentCenterX(value.centerX());
            response.setCurrentCenterY(value.centerY());
            response.setCurrentRecognizedAt(recognizedAt);
            response.setCurrentTargetSampled(recognizedAt != null && document.getSamples().stream()
                    .anyMatch(sample -> recognizedAt.equals(sample.getRecognizedAt())));

            GarmentAimCalibrationFitter.Pose suggestion;
            if (fit.ready() && fit.model() != null) {
                suggestion = applyDefault(lamp, fit.model().predict(value.centerX(), value.centerY()));
                response.setSuggestionSource("calibrated");
            } else {
                suggestion = fallback(lamp, value);
                response.setSuggestionSource("default");
            }
            response.setSuggestedPan(suggestion.pan());
            response.setSuggestedTilt(suggestion.tilt());
        } else {
            response.setCurrentTargetSampled(false);
        }
        return response;
    }

    private DeviceGarmentAimCalibrationRespVO.Sample toResponseSample(
            DeviceDO lamp,
            StoredSample stored) {
        DeviceGarmentAimCalibrationRespVO.Sample sample =
                new DeviceGarmentAimCalibrationRespVO.Sample();
        sample.setId(stored.getId());
        sample.setCenterX(stored.getCenterX());
        sample.setCenterY(stored.getCenterY());
        sample.setPan(defaultGarmentPan(lamp) + panOffset(stored));
        sample.setTilt(defaultGarmentTilt(lamp) + tiltOffset(stored));
        sample.setRecognizedAt(stored.getRecognizedAt());
        sample.setCreatedAt(stored.getCreatedAt());
        return sample;
    }

    private String statusMessage(
            GarmentAimCalibrationFitter.FitResult fit,
            int sampleCount) {
        return switch (fit.reason()) {
            case "ready" -> "标定模型已启用；继续添加样本会自动重新拟合";
            case "insufficient_coverage" -> "请把服装移到画面更靠左/右和更靠上/下的位置继续采样";
            case "degenerate_samples" -> "样本位置过于接近一条直线，请增加角落或中心位置样本";
            default -> "已采集 " + sampleCount + " 个样本，至少需要 "
                    + GarmentAimCalibrationFitter.MIN_SAMPLE_COUNT + " 个不同位置";
        };
    }

    private GarmentAimCalibrationFitter.Pose fallback(
            DeviceDO lamp,
            GarmentAimTarget target) {
        return clamp(new GarmentAimCalibrationFitter.Pose(
                defaultGarmentPan(lamp) + (target.centerX() - 0.5D) * DEFAULT_HORIZONTAL_FOV,
                defaultGarmentTilt(lamp) - (target.centerY() - 0.5D) * DEFAULT_VERTICAL_FOV
        ));
    }

    private GarmentAimCalibrationFitter.Pose applyDefault(
            DeviceDO lamp,
            GarmentAimCalibrationFitter.Pose offset) {
        return clamp(new GarmentAimCalibrationFitter.Pose(
                defaultGarmentPan(lamp) + offset.pan(),
                defaultGarmentTilt(lamp) + offset.tilt()
        ));
    }

    private double defaultGarmentPan(DeviceDO lamp) {
        return lamp.getGarmentDefaultPan() != null
                ? lamp.getGarmentDefaultPan()
                : LEGACY_DEFAULT_PAN;
    }

    private double defaultGarmentTilt(DeviceDO lamp) {
        return lamp.getGarmentDefaultTilt() != null
                ? lamp.getGarmentDefaultTilt()
                : LEGACY_DEFAULT_TILT;
    }

    private double panOffset(StoredSample sample) {
        if (sample.getPanOffset() != null) {
            return sample.getPanOffset();
        }
        return sample.getPan() != null ? sample.getPan() - LEGACY_DEFAULT_PAN : Double.NaN;
    }

    private double tiltOffset(StoredSample sample) {
        if (sample.getTiltOffset() != null) {
            return sample.getTiltOffset();
        }
        return sample.getTilt() != null ? sample.getTilt() - LEGACY_DEFAULT_TILT : Double.NaN;
    }

    private GarmentAimCalibrationFitter.Pose clamp(GarmentAimCalibrationFitter.Pose pose) {
        return new GarmentAimCalibrationFitter.Pose(
                clamp(pose.pan(), -90D, 90D),
                clamp(pose.tilt(), -90D, 90D)
        );
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    @Data
    public static class CalibrationDocument {
        private Integer version = DOCUMENT_VERSION;
        private List<StoredSample> samples = new ArrayList<>();
        private LocalDateTime updatedAt;
    }

    @Data
    public static class StoredSample {
        private String id;
        private Double centerX;
        private Double centerY;
        private Double panOffset;
        private Double tiltOffset;

        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        private Double pan;

        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        private Double tilt;

        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        private Double slider;
        private LocalDateTime recognizedAt;
        private LocalDateTime createdAt;
    }
}

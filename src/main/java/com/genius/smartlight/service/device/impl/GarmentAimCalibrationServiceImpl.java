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
import com.genius.smartlight.vo.device.DeviceGarmentAimCalibrationCopyReqVO;
import com.genius.smartlight.vo.device.DeviceGarmentAimCalibrationMigrationReqVO;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class GarmentAimCalibrationServiceImpl implements GarmentAimCalibrationService {

    private static final int LEGACY_PROFILE_VERSION = 2;
    private static final int DOCUMENT_VERSION = 3;
    private static final int MAX_SAMPLE_COUNT = 60;
    private static final String DEFAULT_SOURCE_KEY = "PHONE";

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
        return getCalibration(lampChipId, DEFAULT_SOURCE_KEY);
    }

    @Override
    public DeviceGarmentAimCalibrationRespVO getCalibration(String lampChipId, String sourceKey) {
        DeviceDO lamp = requireOwnedLamp(lampChipId);
        return buildResponse(lamp, readDocument(lamp), normalizeSourceKey(sourceKey));
    }

    @Override
    public DeviceGarmentAimCalibrationRespVO addSample(String lampChipId, DeviceGarmentAimCalibrationSampleReqVO reqVO) {
        return addSample(lampChipId, DEFAULT_SOURCE_KEY, reqVO);
    }

    @Override
    public DeviceGarmentAimCalibrationRespVO addSample(
            String lampChipId,
            String sourceKey,
            DeviceGarmentAimCalibrationSampleReqVO reqVO) {
        DeviceDO lamp = requireOwnedLamp(lampChipId);
        if (!deviceSessionManager.isOnline(lamp.getChipId())) {
            throw new ServiceException("Lamp 离线，无法确认标定位置");
        }

        String normalizedSourceKey = normalizeSourceKey(sourceKey);
        CalibrationDocument document = readDocument(lamp);
        ensureLegacyMigrated(document);
        CalibrationProfile profile = document.getProfiles().computeIfAbsent(normalizedSourceKey, ignored -> new CalibrationProfile());

        GarmentResultSnapshot snapshot = readSourceSnapshot(lamp, normalizedSourceKey)
                .orElseThrow(() -> new ServiceException("当前拍摄设备尚无可用于标定的服装识别结果"));
        GarmentAimTarget target = GarmentAimTarget.from(snapshot)
                .orElseThrow(() -> new ServiceException("当前服装识别坐标无效，请重新拍摄"));
        if (snapshot.getRecognizedAt() == null) {
            throw new ServiceException("当前识别结果缺少时间标识，请重新拍摄");
        }
        if (profile.getSamples().stream().anyMatch(sample -> snapshot.getRecognizedAt().equals(sample.getRecognizedAt()))) {
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
        profile.getSamples().add(sample);
        sortAndTrim(profile.getSamples());
        profile.setUpdatedAt(LocalDateTime.now());
        document.setVersion(DOCUMENT_VERSION);
        document.setUpdatedAt(LocalDateTime.now());
        persist(lamp, document);
        return buildResponse(lamp, document, normalizedSourceKey);
    }

    @Override
    public DeviceGarmentAimCalibrationRespVO clearCalibration(String lampChipId) {
        return clearCalibration(lampChipId, DEFAULT_SOURCE_KEY);
    }

    @Override
    public DeviceGarmentAimCalibrationRespVO clearCalibration(String lampChipId, String sourceKey) {
        DeviceDO lamp = requireOwnedLamp(lampChipId);
        String normalizedSourceKey = normalizeSourceKey(sourceKey);
        CalibrationDocument document = readDocument(lamp);
        ensureLegacyMigrated(document);
        document.getProfiles().remove(normalizedSourceKey);
        document.setVersion(DOCUMENT_VERSION);
        document.setUpdatedAt(LocalDateTime.now());
        persist(lamp, document);
        return buildResponse(lamp, document, normalizedSourceKey);
    }

    @Override
    public DeviceGarmentAimCalibrationRespVO migrateLegacy(
            String lampChipId,
            DeviceGarmentAimCalibrationMigrationReqVO reqVO) {
        DeviceDO lamp = requireOwnedLamp(lampChipId);
        CalibrationDocument document = readDocument(lamp);
        String sourceKey = normalizeSourceKey(reqVO.getSourceKey());
        if (!hasLegacySamples(document)) {
            return buildResponse(lamp, document, sourceKey);
        }
        CalibrationProfile existing = document.getProfiles().get(sourceKey);
        if (existing != null && !existing.getSamples().isEmpty()) {
            throw new ServiceException("目标拍摄设备已经存在标定数据，不能自动合并旧版样本");
        }
        CalibrationProfile profile = existing != null ? existing : new CalibrationProfile();
        profile.setSamples(copySamples(document.getSamples()));
        sortAndTrim(profile.getSamples());
        profile.setUpdatedAt(LocalDateTime.now());
        document.getProfiles().put(sourceKey, profile);
        document.setSamples(new ArrayList<>());
        document.setVersion(DOCUMENT_VERSION);
        document.setUpdatedAt(LocalDateTime.now());
        persist(lamp, document);
        return buildResponse(lamp, document, sourceKey);
    }

    @Override
    public void copyCalibration(String lampChipId, DeviceGarmentAimCalibrationCopyReqVO reqVO) {
        DeviceDO sourceLamp = requireOwnedLamp(lampChipId);
        String sourceKey = normalizeSourceKey(reqVO.getSourceKey());
        CalibrationDocument sourceDocument = readDocument(sourceLamp);
        ensureLegacyMigrated(sourceDocument);
        CalibrationProfile sourceProfile = sourceDocument.getProfiles().get(sourceKey);
        if (sourceProfile == null || sourceProfile.getSamples().isEmpty()) {
            throw new ServiceException("源 Lamp 在该拍摄设备下没有可复制的标定数据");
        }

        boolean overwrite = Boolean.TRUE.equals(reqVO.getOverwrite());
        List<DeviceDO> targets = new ArrayList<>();
        List<CalibrationDocument> targetDocuments = new ArrayList<>();
        for (String targetChipId : reqVO.getTargetLampChipIds()) {
            DeviceDO targetLamp = requireOwnedLamp(targetChipId);
            if (targetLamp.getChipId().equals(sourceLamp.getChipId())) {
                throw new ServiceException("目标 Lamp 不能与源 Lamp 相同");
            }
            CalibrationDocument targetDocument = readDocument(targetLamp);
            ensureLegacyMigrated(targetDocument);
            CalibrationProfile current = targetDocument.getProfiles().get(sourceKey);
            if (!overwrite && current != null && !current.getSamples().isEmpty()) {
                throw new ServiceException(targetLamp.getChipId() + " 已存在该拍摄设备的标定数据，请确认覆盖后重试");
            }
            targets.add(targetLamp);
            targetDocuments.add(targetDocument);
        }

        for (int index = 0; index < targets.size(); index++) {
            CalibrationProfile copied = new CalibrationProfile();
            copied.setSamples(copySamples(sourceProfile.getSamples()));
            copied.setUpdatedAt(LocalDateTime.now());
            CalibrationDocument targetDocument = targetDocuments.get(index);
            targetDocument.getProfiles().put(sourceKey, copied);
            targetDocument.setVersion(DOCUMENT_VERSION);
            targetDocument.setUpdatedAt(LocalDateTime.now());
            persist(targets.get(index), targetDocument);
        }
    }

    @Override
    public Optional<GarmentAimCalibrationFitter.Pose> predict(String lampChipId, GarmentAimTarget target) {
        if (!StringUtils.hasText(lampChipId) || target == null) {
            return Optional.empty();
        }
        DeviceDO lamp = findLamp(lampChipId);
        if (lamp == null) {
            return Optional.empty();
        }
        CalibrationDocument document = readDocument(lamp);
        if (hasLegacySamples(document)) {
            return predictWithSamples(lamp, document.getSamples(), target);
        }
        CalibrationProfile profile = document.getProfiles().get(DEFAULT_SOURCE_KEY);
        if (profile == null && document.getProfiles().size() == 1) {
            profile = document.getProfiles().values().iterator().next();
        }
        return profile == null ? Optional.empty() : predictWithSamples(lamp, profile.getSamples(), target);
    }

    @Override
    public Optional<GarmentAimCalibrationFitter.Pose> predict(
            String lampChipId,
            String sourceKey,
            GarmentAimTarget target) {
        if (!StringUtils.hasText(lampChipId) || target == null) {
            return Optional.empty();
        }
        DeviceDO lamp = findLamp(lampChipId);
        if (lamp == null) {
            return Optional.empty();
        }
        CalibrationDocument document = readDocument(lamp);
        if (hasLegacySamples(document)) {
            return Optional.empty();
        }
        CalibrationProfile profile = document.getProfiles().get(normalizeSourceKey(sourceKey));
        return profile == null ? Optional.empty() : predictWithSamples(lamp, profile.getSamples(), target);
    }

    private Optional<GarmentAimCalibrationFitter.Pose> predictWithSamples(
            DeviceDO lamp,
            List<StoredSample> samples,
            GarmentAimTarget target) {
        GarmentAimCalibrationFitter.FitResult fit = fit(samples);
        if (!fit.ready() || fit.model() == null) {
            return Optional.empty();
        }
        return Optional.of(applyDefault(lamp, fit.model().predict(target.centerX(), target.centerY())));
    }

    private DeviceDO findLamp(String lampChipId) {
        DeviceDO lamp = deviceMapper.selectOne(new LambdaQueryWrapper<DeviceDO>()
                .eq(DeviceDO::getChipId, lampChipId.trim()).last("limit 1"));
        return lamp != null && DeviceTypeUtil.isLampLike(lamp.getDeviceType()) ? lamp : null;
    }

    private DeviceDO requireOwnedLamp(String lampChipId) {
        if (!StringUtils.hasText(lampChipId)) {
            throw new ServiceException("Lamp 芯片 ID 不能为空");
        }
        Long userId = SecurityUtils.getCurrentUserId();
        StoreDO store = storeMapper.selectOne(new LambdaQueryWrapper<StoreDO>()
                .eq(StoreDO::getUserId, userId).last("limit 1"));
        if (store == null) {
            throw new ServiceException("当前用户未绑定店铺");
        }
        DeviceDO lamp = deviceMapper.selectOne(new LambdaQueryWrapper<DeviceDO>()
                .eq(DeviceDO::getChipId, lampChipId.trim()).last("limit 1"));
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
            CalibrationDocument document = objectMapper.readValue(lamp.getGarmentAimCalibrationJson(), CalibrationDocument.class);
            if (document.getSamples() == null) document.setSamples(new ArrayList<>());
            if (document.getProfiles() == null) document.setProfiles(new LinkedHashMap<>());
            migrateLegacySamples(document);
            document.getProfiles().values().forEach(profile -> {
                if (profile.getSamples() == null) profile.setSamples(new ArrayList<>());
            });
            return document;
        } catch (Exception exception) {
            log.warn("garment aim calibration decode failed, chipId={}, exceptionType={}",
                    lamp.getChipId(), exception.getClass().getSimpleName());
            return new CalibrationDocument();
        }
    }

    static void migrateLegacySamples(CalibrationDocument document) {
        if (document == null || document.getSamples() == null) return;
        document.getSamples().forEach(sample -> {
            if (sample.getPanOffset() == null && sample.getPan() != null) {
                sample.setPanOffset(sample.getPan() - LEGACY_DEFAULT_PAN);
            }
            if (sample.getTiltOffset() == null && sample.getTilt() != null) {
                sample.setTiltOffset(sample.getTilt() - LEGACY_DEFAULT_TILT);
            }
        });
        if (!document.getSamples().isEmpty()
                && (document.getVersion() == null || document.getVersion() < LEGACY_PROFILE_VERSION)) {
            document.setVersion(LEGACY_PROFILE_VERSION);
        }
    }

    private void ensureLegacyMigrated(CalibrationDocument document) {
        if (hasLegacySamples(document)) {
            throw new ServiceException("发现旧版标定数据，请先选择原始拍摄设备完成迁移");
        }
        document.setVersion(DOCUMENT_VERSION);
    }

    private boolean hasLegacySamples(CalibrationDocument document) {
        return document != null && document.getSamples() != null && !document.getSamples().isEmpty();
    }

    private Optional<GarmentResultSnapshot> readSourceSnapshot(DeviceDO lamp, String sourceKey) {
        if (lamp == null || !StringUtils.hasText(lamp.getGarmentSourceResultJson())) return Optional.empty();
        try {
            SourceResultDocument document = objectMapper.readValue(lamp.getGarmentSourceResultJson(), SourceResultDocument.class);
            if (document.getSources() == null) return Optional.empty();
            String encoded = document.getSources().get(sourceKey);
            return StringUtils.hasText(encoded) ? GarmentResultCodec.decode(encoded) : Optional.empty();
        } catch (Exception exception) {
            log.warn("garment source result decode failed, chipId={}, sourceKey={}, exceptionType={}",
                    lamp.getChipId(), sourceKey, exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private void persist(DeviceDO lamp, CalibrationDocument document) {
        try {
            lamp.setGarmentAimCalibrationJson(objectMapper.writeValueAsString(document));
            lamp.setUpdateTime(LocalDateTime.now());
            if (deviceMapper.updateById(lamp) != 1) throw new ServiceException("保存标定数据失败");
        } catch (ServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ServiceException("标定数据序列化失败");
        }
    }

    private GarmentAimCalibrationFitter.FitResult fit(List<StoredSample> storedSamples) {
        List<GarmentAimCalibrationFitter.Sample> samples = storedSamples.stream()
                .map(sample -> new GarmentAimCalibrationFitter.Sample(
                        sample.getCenterX(), sample.getCenterY(), panOffset(sample), tiltOffset(sample)))
                .toList();
        return GarmentAimCalibrationFitter.fit(samples);
    }

    private DeviceGarmentAimCalibrationRespVO buildResponse(DeviceDO lamp, CalibrationDocument document, String sourceKey) {
        List<StoredSample> samples;
        if (hasLegacySamples(document)) {
            samples = List.of();
        } else {
            CalibrationProfile profile = document.getProfiles().get(sourceKey);
            samples = profile == null ? List.of() : profile.getSamples();
        }
        GarmentAimCalibrationFitter.FitResult fit = fit(samples);
        DeviceGarmentAimCalibrationRespVO response = new DeviceGarmentAimCalibrationRespVO();
        response.setLampChipId(lamp.getChipId());
        response.setSourceKey(sourceKey);
        response.setLegacyMigrationRequired(hasLegacySamples(document));
        response.setLegacySampleCount(hasLegacySamples(document) ? document.getSamples().size() : 0);
        response.setSampleCount(samples.size());
        response.setMinimumSampleCount(GarmentAimCalibrationFitter.MIN_SAMPLE_COUNT);
        response.setRecommendedSampleCount(GarmentAimCalibrationFitter.RECOMMENDED_SAMPLE_COUNT);
        response.setModelReady(!hasLegacySamples(document) && fit.ready());
        if (hasLegacySamples(document)) {
            response.setStatusCode("legacy_migration_required");
            response.setStatusMessage("发现旧版标定数据 " + document.getSamples().size() + " 条，请先选择原始拍摄设备完成迁移");
        } else {
            response.setStatusCode(fit.reason());
            response.setStatusMessage(statusMessage(fit, samples.size()));
        }
        response.setHorizontalCoverage(fit.xCoverage());
        response.setVerticalCoverage(fit.yCoverage());
        response.setUpdatedAt(document.getUpdatedAt());
        if (fit.model() != null) {
            response.setRmsePan(fit.model().pan().rmse());
            response.setRmseTilt(fit.model().tilt().rmse());
        }
        response.setSamples(samples.stream()
                .sorted(Comparator.comparing(StoredSample::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .map(sample -> toResponseSample(lamp, sample)).toList());

        Optional<GarmentResultSnapshot> snapshot = hasLegacySamples(document)
                ? Optional.empty() : readSourceSnapshot(lamp, sourceKey);
        Optional<GarmentAimTarget> target = snapshot.flatMap(GarmentAimTarget::from);
        response.setCurrentTargetValid(target.isPresent());
        if (target.isPresent()) {
            GarmentAimTarget value = target.get();
            LocalDateTime recognizedAt = snapshot.map(GarmentResultSnapshot::getRecognizedAt).orElse(null);
            response.setCurrentCenterX(value.centerX());
            response.setCurrentCenterY(value.centerY());
            response.setCurrentRecognizedAt(recognizedAt);
            response.setCurrentTargetSampled(recognizedAt != null && samples.stream()
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

    private DeviceGarmentAimCalibrationRespVO.Sample toResponseSample(DeviceDO lamp, StoredSample stored) {
        DeviceGarmentAimCalibrationRespVO.Sample sample = new DeviceGarmentAimCalibrationRespVO.Sample();
        sample.setId(stored.getId());
        sample.setCenterX(stored.getCenterX());
        sample.setCenterY(stored.getCenterY());
        sample.setPan(defaultGarmentPan(lamp) + panOffset(stored));
        sample.setTilt(defaultGarmentTilt(lamp) + tiltOffset(stored));
        sample.setRecognizedAt(stored.getRecognizedAt());
        sample.setCreatedAt(stored.getCreatedAt());
        return sample;
    }

    private String statusMessage(GarmentAimCalibrationFitter.FitResult fit, int sampleCount) {
        return switch (fit.reason()) {
            case "ready" -> "标定模型已启用；继续添加样本会自动重新拟合";
            case "insufficient_coverage" -> "请把服装移到画面更靠左/右和更靠上/下的位置继续采样";
            case "degenerate_samples" -> "样本位置过于接近一条直线，请增加角落或中心位置样本";
            default -> "已采集 " + sampleCount + " 个样本，至少需要 "
                    + GarmentAimCalibrationFitter.MIN_SAMPLE_COUNT + " 个不同位置";
        };
    }

    private GarmentAimCalibrationFitter.Pose fallback(DeviceDO lamp, GarmentAimTarget target) {
        return clamp(new GarmentAimCalibrationFitter.Pose(
                defaultGarmentPan(lamp) + (target.centerX() - 0.5D) * DEFAULT_HORIZONTAL_FOV,
                defaultGarmentTilt(lamp) - (target.centerY() - 0.5D) * DEFAULT_VERTICAL_FOV));
    }

    private GarmentAimCalibrationFitter.Pose applyDefault(DeviceDO lamp, GarmentAimCalibrationFitter.Pose offset) {
        return clamp(new GarmentAimCalibrationFitter.Pose(
                defaultGarmentPan(lamp) + offset.pan(), defaultGarmentTilt(lamp) + offset.tilt()));
    }

    private double defaultGarmentPan(DeviceDO lamp) {
        return lamp.getGarmentDefaultPan() != null ? lamp.getGarmentDefaultPan() : LEGACY_DEFAULT_PAN;
    }

    private double defaultGarmentTilt(DeviceDO lamp) {
        return lamp.getGarmentDefaultTilt() != null ? lamp.getGarmentDefaultTilt() : LEGACY_DEFAULT_TILT;
    }

    private double panOffset(StoredSample sample) {
        if (sample.getPanOffset() != null) return sample.getPanOffset();
        return sample.getPan() != null ? sample.getPan() - LEGACY_DEFAULT_PAN : Double.NaN;
    }

    private double tiltOffset(StoredSample sample) {
        if (sample.getTiltOffset() != null) return sample.getTiltOffset();
        return sample.getTilt() != null ? sample.getTilt() - LEGACY_DEFAULT_TILT : Double.NaN;
    }

    private GarmentAimCalibrationFitter.Pose clamp(GarmentAimCalibrationFitter.Pose pose) {
        return new GarmentAimCalibrationFitter.Pose(clamp(pose.pan(), -90D, 90D), clamp(pose.tilt(), -90D, 90D));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private String normalizeSourceKey(String sourceKey) {
        if (!StringUtils.hasText(sourceKey)) return DEFAULT_SOURCE_KEY;
        String normalized = sourceKey.trim();
        if (normalized.equalsIgnoreCase("PHONE")) return DEFAULT_SOURCE_KEY;
        if (normalized.regionMatches(true, 0, "CAMERA:", 0, 7)) {
            String chipId = normalized.substring(7).trim();
            if (!StringUtils.hasText(chipId)) throw new ServiceException("Camera 拍摄设备 ID 不能为空");
            return "CAMERA:" + chipId;
        }
        throw new ServiceException("不支持的拍摄设备来源");
    }

    private void sortAndTrim(List<StoredSample> samples) {
        samples.sort(Comparator.comparing(StoredSample::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
        while (samples.size() > MAX_SAMPLE_COUNT) samples.remove(0);
    }

    private List<StoredSample> copySamples(List<StoredSample> source) {
        List<StoredSample> result = new ArrayList<>();
        for (StoredSample stored : source) {
            StoredSample copy = new StoredSample();
            copy.setId(UUID.randomUUID().toString());
            copy.setCenterX(stored.getCenterX());
            copy.setCenterY(stored.getCenterY());
            copy.setPanOffset(panOffset(stored));
            copy.setTiltOffset(tiltOffset(stored));
            copy.setRecognizedAt(stored.getRecognizedAt());
            copy.setCreatedAt(LocalDateTime.now());
            result.add(copy);
        }
        return result;
    }

    @Data
    public static class CalibrationDocument {
        private Integer version = DOCUMENT_VERSION;
        private List<StoredSample> samples = new ArrayList<>();
        private Map<String, CalibrationProfile> profiles = new LinkedHashMap<>();
        private LocalDateTime updatedAt;
    }

    @Data
    public static class CalibrationProfile {
        private List<StoredSample> samples = new ArrayList<>();
        private LocalDateTime updatedAt;
    }

    @Data
    public static class SourceResultDocument {
        private Integer version = 1;
        private Map<String, String> sources = new LinkedHashMap<>();
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

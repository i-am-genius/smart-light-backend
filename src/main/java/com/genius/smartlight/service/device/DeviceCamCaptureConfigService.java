package com.genius.smartlight.service.device;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.genius.smartlight.common.DeviceTypeUtil;
import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.service.store.CurrentStoreService;
import com.genius.smartlight.vo.device.DeviceCamCaptureConfigVO;
import com.genius.smartlight.vo.device.DeviceCamCaptureTargetVO;
import com.genius.smartlight.vo.device.DeviceCamRoiConfigVO;
import com.genius.smartlight.vo.device.DeviceCamRoiItemVO;
import com.genius.smartlight.vo.device.DeviceCamSliderMoveTimeVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Camera capture/sliding configuration without ROI semantics.
 *
 * The existing capture engine still reads data/cam-config/*.json as
 * DeviceCamRoiConfigVO. During migration this service writes a compatibility
 * representation to the same file so single capture, batch capture, slider
 * timing and AI processing keep working unchanged. Spatial ROI fields are
 * always zero and configured=false, which also prevents the legacy ROI based
 * auto-tracking path from becoming active.
 */
@Service
@RequiredArgsConstructor
public class DeviceCamCaptureConfigService {

    private static final int TARGET_COUNT = 3;
    private static final Path CAM_CONFIG_DIR = Path.of("data", "cam-config").toAbsolutePath().normalize();

    private final DeviceMapper deviceMapper;
    private final CurrentStoreService currentStoreService;
    private final ObjectMapper objectMapper;

    public DeviceCamCaptureConfigVO getForCurrentStore(String camChipId) {
        DeviceDO cam = requireCam(camChipId);
        requireCurrentStore(cam);
        return read(cam.getChipId());
    }

    public DeviceCamCaptureConfigVO getForDevice(String camChipId) {
        DeviceDO cam = requireCam(camChipId);
        return read(cam.getChipId());
    }

    public DeviceCamCaptureConfigVO saveForCurrentStore(
            String camChipId,
            DeviceCamCaptureConfigVO input) {
        DeviceDO cam = requireCam(camChipId);
        requireCurrentStore(cam);
        DeviceCamCaptureConfigVO normalized = normalize(cam.getChipId(), input);

        if (notBlank(normalized.getSliderLampChipId())) {
            requireLampInStore(normalized.getSliderLampChipId(), cam.getStoreId());
        }
        for (DeviceCamCaptureTargetVO target : normalized.getTargets()) {
            if (notBlank(target.getLampChipId())) {
                requireLampInStore(target.getLampChipId(), cam.getStoreId());
            }
        }

        writeCompatibilityConfig(normalized);
        return normalized;
    }

    /** Find the camera/capture-target binding that contains a Lamp. */
    public Optional<CameraLampBinding> findBindingForLamp(String lampChipId, Long storeId) {
        if (!notBlank(lampChipId) || storeId == null) {
            return Optional.empty();
        }
        List<DeviceDO> devices = deviceMapper.selectList(
                new LambdaQueryWrapper<DeviceDO>().eq(DeviceDO::getStoreId, storeId)
        );
        if (devices == null) {
            return Optional.empty();
        }
        return devices.stream()
                .filter(device -> DeviceTypeUtil.isCam(device.getDeviceType()))
                .sorted(Comparator.comparing(DeviceDO::getChipId, String.CASE_INSENSITIVE_ORDER))
                .map(cam -> bindingInConfig(cam, lampChipId))
                .flatMap(Optional::stream)
                .findFirst();
    }

    private Optional<CameraLampBinding> bindingInConfig(DeviceDO cam, String lampChipId) {
        DeviceCamCaptureConfigVO config = read(cam.getChipId());
        return config.getTargets().stream()
                .filter(target -> sameChipId(target.getLampChipId(), lampChipId))
                .findFirst()
                .map(target -> new CameraLampBinding(
                        cam.getChipId(),
                        target.getLampChipId(),
                        normalizeIndex(target.getIndex()),
                        cam.getStoreId()
                ));
    }

    private DeviceCamCaptureConfigVO read(String camChipId) {
        Path path = configPath(camChipId);
        if (!Files.exists(path)) {
            return normalize(camChipId, null);
        }
        try {
            DeviceCamRoiConfigVO legacy = objectMapper.readValue(path.toFile(), DeviceCamRoiConfigVO.class);
            return fromLegacy(camChipId, legacy);
        } catch (IOException exception) {
            throw new ServiceException("读取 cam 拍摄配置失败");
        }
    }

    private DeviceCamCaptureConfigVO fromLegacy(String camChipId, DeviceCamRoiConfigVO legacy) {
        DeviceCamCaptureConfigVO config = new DeviceCamCaptureConfigVO();
        config.setCamChipId(camChipId);
        config.setSliderLampChipId(trimToNull(legacy == null ? null : legacy.getSliderLampChipId()));

        Map<Integer, DeviceCamRoiItemVO> roiByIndex = new LinkedHashMap<>();
        if (legacy != null && legacy.getRois() != null) {
            for (DeviceCamRoiItemVO roi : legacy.getRois()) {
                if (roi != null && roi.getTargetIndex() != null) {
                    roiByIndex.put(normalizeIndex(roi.getTargetIndex()), roi);
                }
            }
        }

        List<DeviceCamCaptureTargetVO> targets = new ArrayList<>();
        for (int index = 1; index <= TARGET_COUNT; index++) {
            DeviceCamCaptureTargetVO target = new DeviceCamCaptureTargetVO();
            target.setIndex(index);
            DeviceCamRoiItemVO roi = roiByIndex.get(index);
            target.setLampChipId(trimToNull(roi == null ? null : roi.getTargetChipId()));
            target.setSliderMm(normalizeSlider(
                    legacy == null || legacy.getSliderPresets() == null
                            ? null
                            : legacy.getSliderPresets().get(String.valueOf(index))
            ));
            DeviceCamSliderMoveTimeVO sourceTimes = legacy == null || legacy.getSliderMoveTimes() == null
                    ? null
                    : legacy.getSliderMoveTimes().get(String.valueOf(index));
            target.setMoveTimes(normalizeMoveTimes(sourceTimes));
            targets.add(target);
        }
        config.setTargets(targets);
        config.setConfigured(isConfigured(config));
        return config;
    }

    private DeviceCamCaptureConfigVO normalize(String camChipId, DeviceCamCaptureConfigVO input) {
        DeviceCamCaptureConfigVO result = new DeviceCamCaptureConfigVO();
        result.setCamChipId(camChipId);
        result.setSliderLampChipId(trimToNull(input == null ? null : input.getSliderLampChipId()));

        Map<Integer, DeviceCamCaptureTargetVO> sourceByIndex = new LinkedHashMap<>();
        if (input != null && input.getTargets() != null) {
            for (DeviceCamCaptureTargetVO target : input.getTargets()) {
                if (target != null && target.getIndex() != null) {
                    sourceByIndex.put(normalizeIndex(target.getIndex()), target);
                }
            }
        }

        List<DeviceCamCaptureTargetVO> targets = new ArrayList<>();
        for (int index = 1; index <= TARGET_COUNT; index++) {
            DeviceCamCaptureTargetVO source = sourceByIndex.get(index);
            DeviceCamCaptureTargetVO target = new DeviceCamCaptureTargetVO();
            target.setIndex(index);
            target.setLampChipId(trimToNull(source == null ? null : source.getLampChipId()));
            target.setSliderMm(normalizeSlider(source == null ? null : source.getSliderMm()));
            target.setMoveTimes(normalizeMoveTimes(source == null ? null : source.getMoveTimes()));
            targets.add(target);
        }
        result.setTargets(targets);
        result.setConfigured(isConfigured(result));
        return result;
    }

    private void writeCompatibilityConfig(DeviceCamCaptureConfigVO config) {
        DeviceCamRoiConfigVO legacy = new DeviceCamRoiConfigVO();
        legacy.setCamChipId(config.getCamChipId());
        legacy.setSliderLampChipId(config.getSliderLampChipId());

        List<DeviceCamRoiItemVO> compatibilityTargets = new ArrayList<>();
        Map<String, Double> sliderPresets = new LinkedHashMap<>();
        Map<String, DeviceCamSliderMoveTimeVO> moveTimes = new LinkedHashMap<>();
        for (DeviceCamCaptureTargetVO target : config.getTargets()) {
            int index = normalizeIndex(target.getIndex());
            DeviceCamRoiItemVO item = new DeviceCamRoiItemVO();
            item.setTargetIndex(index);
            item.setTargetChipId(trimToNull(target.getLampChipId()));
            item.setAreaName(null);
            item.setX(0D);
            item.setY(0D);
            item.setW(0D);
            item.setH(0D);
            compatibilityTargets.add(item);
            sliderPresets.put(String.valueOf(index), normalizeSlider(target.getSliderMm()));
            moveTimes.put(String.valueOf(index), normalizeMoveTimes(target.getMoveTimes()));
        }
        legacy.setRois(compatibilityTargets);
        legacy.setSliderPresets(sliderPresets);
        legacy.setSliderMoveTimes(moveTimes);
        // Critical: keep the old ROI tracking engine disabled.
        legacy.setConfigured(false);

        try {
            Files.createDirectories(CAM_CONFIG_DIR);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(configPath(config.getCamChipId()).toFile(), legacy);
        } catch (IOException exception) {
            throw new ServiceException("保存 cam 拍摄配置失败");
        }
    }

    private boolean isConfigured(DeviceCamCaptureConfigVO config) {
        return notBlank(config.getSliderLampChipId())
                && config.getTargets() != null
                && config.getTargets().size() == TARGET_COUNT
                && config.getTargets().stream().allMatch(target -> notBlank(target.getLampChipId()));
    }

    private DeviceCamSliderMoveTimeVO normalizeMoveTimes(DeviceCamSliderMoveTimeVO source) {
        DeviceCamSliderMoveTimeVO result = new DeviceCamSliderMoveTimeVO();
        result.setSlow(normalizeTime(source == null ? null : source.getSlow()));
        result.setNormal(normalizeTime(source == null ? null : source.getNormal()));
        result.setFast(normalizeTime(source == null ? null : source.getFast()));
        return result;
    }

    private double normalizeTime(Double value) {
        if (value == null || !Double.isFinite(value)) {
            return 0D;
        }
        double clamped = Math.max(0D, Math.min(3600D, value));
        return Math.round(clamped * 1000D) / 1000D;
    }

    private double normalizeSlider(Double value) {
        if (value == null || !Double.isFinite(value)) {
            return 0D;
        }
        return (double) Math.round(Math.max(0D, Math.min(2500D, value)));
    }

    private int normalizeIndex(Integer value) {
        int index = value == null ? 1 : value;
        return Math.max(1, Math.min(TARGET_COUNT, index));
    }

    private DeviceDO requireCam(String chipId) {
        DeviceDO device = requireDevice(chipId);
        if (!DeviceTypeUtil.isCam(device.getDeviceType())) {
            throw new ServiceException("设备不是 cam");
        }
        return device;
    }

    private DeviceDO requireLampInStore(String chipId, Long storeId) {
        DeviceDO device = requireDevice(chipId);
        if (!DeviceTypeUtil.isLampLike(device.getDeviceType())) {
            throw new ServiceException("目标设备必须是 lamp 或 camlamp");
        }
        if (device.getStoreId() == null || !device.getStoreId().equals(storeId)) {
            throw new ServiceException("目标设备不属于当前门店");
        }
        return device;
    }

    private DeviceDO requireDevice(String chipId) {
        if (!notBlank(chipId)) {
            throw new ServiceException("chipId 不能为空");
        }
        DeviceDO device = deviceMapper.selectOne(
                new LambdaQueryWrapper<DeviceDO>()
                        .eq(DeviceDO::getChipId, chipId.trim())
                        .last("limit 1")
        );
        if (device == null) {
            throw new ServiceException("设备不存在");
        }
        return device;
    }

    private void requireCurrentStore(DeviceDO device) {
        Long storeId = currentStoreService.getCurrentStoreId();
        if (device.getStoreId() == null || !device.getStoreId().equals(storeId)) {
            throw new ServiceException("无权操作该设备");
        }
    }

    private Path configPath(String chipId) {
        return CAM_CONFIG_DIR.resolve(safeName(chipId) + ".json").normalize();
    }

    private String safeName(String value) {
        String text = value == null || value.isBlank() ? "unknown" : value.trim();
        return text.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String trimToNull(String value) {
        return notBlank(value) ? value.trim() : null;
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private boolean sameChipId(String left, String right) {
        return notBlank(left) && notBlank(right) && left.trim().equalsIgnoreCase(right.trim());
    }

    public record CameraLampBinding(
            String camChipId,
            String lampChipId,
            int targetIndex,
            Long storeId) {
    }
}

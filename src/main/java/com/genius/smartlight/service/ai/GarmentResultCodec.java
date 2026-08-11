package com.genius.smartlight.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.vo.ai.FabricRecognizeRespVO;
import com.genius.smartlight.vo.ai.GarmentPartRespVO;
import com.genius.smartlight.vo.ai.GarmentResultSnapshot;
import com.genius.smartlight.vo.device.DeviceRespVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public final class GarmentResultCodec {

    private static final Logger log = LoggerFactory.getLogger(GarmentResultCodec.class);

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private GarmentResultCodec() {
    }

    public static String encode(FabricRecognizeRespVO result, LocalDateTime recognizedAt) {
        try {
            GarmentResultSnapshot snapshot = new GarmentResultSnapshot();
            snapshot.setResultVersion(result.getResultVersion());
            snapshot.setClothDetected(result.getClothDetected());
            snapshot.setSegmentationFallback(result.getSegmentationFallback());
            snapshot.setOutfitType(result.getOutfitType());
            snapshot.setImageWidth(result.getImageWidth());
            snapshot.setImageHeight(result.getImageHeight());
            snapshot.setRecognizedAt(recognizedAt);
            snapshot.setGarments(copyGarments(result.getGarments()));
            return MAPPER.writeValueAsString(snapshot);
        } catch (Exception exception) {
            ServiceException serviceException =
                    new ServiceException("服装识别结果序列化失败");
            serviceException.initCause(exception);
            throw serviceException;
        }
    }

    public static Optional<GarmentResultSnapshot> decode(String json) {
        if (!StringUtils.hasText(json)) {
            return Optional.empty();
        }
        try {
            return Optional.of(MAPPER.readValue(json, GarmentResultSnapshot.class));
        } catch (Exception exception) {
            log.warn("服装识别结果反序列化失败: exceptionType={}",
                    exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public static void applyToResponse(DeviceDO device, DeviceRespVO response) {
        String json = device.getGarmentResultJson();
        Optional<GarmentResultSnapshot> snapshot = decode(json);
        if (snapshot.isPresent()) {
            applySnapshot(snapshot.get(), response);
            return;
        }

        applyLegacy(device, response);
    }

    private static void applySnapshot(
            GarmentResultSnapshot snapshot, DeviceRespVO response) {
        response.setResultVersion(snapshot.getResultVersion());
        response.setClothDetected(snapshot.getClothDetected());
        response.setSegmentationFallback(snapshot.getSegmentationFallback());
        response.setOutfitType(snapshot.getOutfitType());
        response.setImageWidth(snapshot.getImageWidth());
        response.setImageHeight(snapshot.getImageHeight());
        response.setGarments(snapshot.getGarments());
    }

    private static void applyLegacy(DeviceDO device, DeviceRespVO response) {
        if (!StringUtils.hasText(device.getFabric())
                && !StringUtils.hasText(device.getMainColorRgb())) {
            return;
        }

        GarmentPartRespVO garment = new GarmentPartRespVO();
        garment.setPosition("upper");
        garment.setCategory("upper");
        garment.setFabric(device.getFabric());
        garment.setMainColorRgb(device.getMainColorRgb());
        garment.setMaskArea(1);

        response.setResultVersion(1);
        response.setClothDetected(true);
        response.setSegmentationFallback(false);
        response.setOutfitType("upper_only");
        response.setGarments(List.of(garment));
    }

    private static List<GarmentPartRespVO> copyGarments(
            List<GarmentPartRespVO> garments) {
        if (garments == null) {
            return null;
        }
        return garments.stream()
                .map(GarmentResultCodec::copyGarment)
                .toList();
    }

    private static GarmentPartRespVO copyGarment(GarmentPartRespVO source) {
        if (source == null) {
            return null;
        }
        GarmentPartRespVO copy = new GarmentPartRespVO();
        copy.setPosition(source.getPosition());
        copy.setCategory(source.getCategory());
        copy.setCategoryConfidence(source.getCategoryConfidence());
        copy.setFabric(source.getFabric());
        copy.setFabricConfidence(source.getFabricConfidence());
        copy.setMainColorRgb(source.getMainColorRgb());
        copy.setMaskArea(source.getMaskArea());
        copy.setX(source.getX());
        copy.setY(source.getY());
        copy.setW(source.getW());
        copy.setH(source.getH());
        return copy;
    }
}

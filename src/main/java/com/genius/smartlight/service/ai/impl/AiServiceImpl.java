package com.genius.smartlight.service.ai.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.convert.device.DeviceConvert;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.dataobject.StoreDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.dal.mysql.StoreMapper;
import com.genius.smartlight.integration.ai.FabricAiClient;
import com.genius.smartlight.integration.ai.PersonDetectClient;
import com.genius.smartlight.security.SecurityUtils;
import com.genius.smartlight.service.ai.AiService;
import com.genius.smartlight.service.ai.MainColorResult;
import com.genius.smartlight.service.ai.MainColorService;
import com.genius.smartlight.vo.ai.FabricRecognizeRespVO;
import com.genius.smartlight.vo.ai.PersonDetectRespVO;
import com.genius.smartlight.vo.device.DeviceRespVO;
import com.genius.smartlight.websocket.WebSocketPushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class AiServiceImpl implements AiService {

    private static final long AI_IMAGE_MAX_SIZE = 10L * 1024L * 1024L;
    private static final Set<String> ALLOWED_IMAGE_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".webp");
    private static final Set<String> ALLOWED_IMAGE_MIME_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final String UNSUPPORTED_IMAGE_MESSAGE = "仅支持 JPG、PNG、WEBP 图片";

    private final DeviceMapper deviceMapper;
    private final FabricAiClient fabricAiClient;
    private final PersonDetectClient personDetectClient;
    private final WebSocketPushService webSocketPushService;
    private final MainColorService mainColorService;
    private final StoreMapper storeMapper;

    @Override
    public FabricRecognizeRespVO fabricRecognize(String chipId, MultipartFile file) {
        long totalStart = System.currentTimeMillis();
        String filename = file == null ? "" : file.getOriginalFilename();
        long fileSize = file == null ? 0L : file.getSize();
        log.info("fabricRecognize start chipId={} filename={} fileSize={}", chipId, filename, fileSize);

        boolean validated = false;
        try {
            validateFile(file);
            validated = true;

            long pythonStart = System.currentTimeMillis();
            FabricRecognizeRespVO result;
            try {
                result = fabricAiClient.recognize(file, chipId);
            } finally {
                log.debug("fabricRecognize cost step=pythonRecognize chipId={} filename={} fileSize={} costMs={}",
                        chipId, filename, fileSize, System.currentTimeMillis() - pythonStart);
            }

            MainColorResult colorResult;
            long mainColorStart = System.currentTimeMillis();
            try {
                String maskedBase64 = result.getClothMaskedPngBase64();
                if (maskedBase64 != null && !maskedBase64.isBlank()) {
                    byte[] maskedBytes = java.util.Base64.getDecoder().decode(maskedBase64);
                    colorResult = mainColorService.extract(new java.io.ByteArrayInputStream(maskedBytes));
                } else {
                    colorResult = mainColorService.extract(file.getInputStream());
                }
            } catch (Exception e) {
                log.warn("fabricRecognize main color extract failed chipId={} filename={} fileSize={}",
                        chipId, filename, fileSize, e);
                colorResult = new MainColorResult("128,128,128", 60, 4500);
            } finally {
                log.debug("fabricRecognize cost step=mainColorExtract chipId={} filename={} fileSize={} costMs={}",
                        chipId, filename, fileSize, System.currentTimeMillis() - mainColorStart);
            }

            MainColorResult adjustedColorResult = applyFabricAdjustment(colorResult, result.getLabel());
            result.setMainColorRgb(adjustedColorResult.getMainColorRgb());
            result.setRecommendedBrightness(adjustedColorResult.getRecommendedBrightness());
            result.setRecommendedTemp(adjustedColorResult.getRecommendedTemp());

            Long deviceStoreId = null;
            long updateStart = System.currentTimeMillis();
            try {
                if (chipId != null && !chipId.isBlank()) {
                    DeviceDO device = updateDeviceAiResult(chipId, result);
                    if (device != null) {
                        deviceStoreId = device.getStoreId();
                    }
                }
            } finally {
                log.debug("fabricRecognize cost step=updateDeviceAndPushState chipId={} filename={} fileSize={} costMs={} skipped={}",
                        chipId, filename, fileSize, System.currentTimeMillis() - updateStart,
                        chipId == null || chipId.isBlank());
            }

            long wsStart = System.currentTimeMillis();
            try {
                webSocketPushService.pushFabricRecognize(chipId, file.getOriginalFilename(), result, deviceStoreId);
            } finally {
                log.debug("fabricRecognize cost step=pushFabricRecognize chipId={} filename={} fileSize={} costMs={}",
                        chipId, filename, fileSize, System.currentTimeMillis() - wsStart);
            }

            result.setClothMaskedPngBase64(null);
            return result;
        } catch (RuntimeException e) {
            if (validated) {
                log.error("fabricRecognize failed, chipId={}, filename={}, reason={}",
                        chipId, filename, e.getMessage(), e);
            }
            throw e;
        } finally {
            log.info("fabricRecognize cost step=total chipId={} filename={} fileSize={} costMs={}",
                    chipId, filename, fileSize, System.currentTimeMillis() - totalStart);
        }
    }

    @Override
    public PersonDetectRespVO personDetect(String chipId, MultipartFile file) {
        long start = System.currentTimeMillis();
        String filename = file == null ? "" : file.getOriginalFilename();
        boolean validated = false;
        try {
            validateFile(file);
            validated = true;
            PersonDetectRespVO result = personDetectClient.detect(file);

            Long storeId = resolveDeviceStoreIdIfOwned(chipId);
            webSocketPushService.pushPersonDetect(chipId, file.getOriginalFilename(), result, storeId);
            log.info("personDetect completed, chipId={}, filename={}, count={}, costMs={}",
                    chipId, filename, result.getCount(), System.currentTimeMillis() - start);
            return result;
        } catch (RuntimeException e) {
            if (validated) {
                log.error("personDetect failed, chipId={}, filename={}, reason={}",
                        chipId, filename, e.getMessage(), e);
            }
            throw e;
        }
    }

    private Long resolveDeviceStoreIdIfOwned(String chipId) {
        if (chipId == null || chipId.isBlank()) {
            return null;
        }
        DeviceDO device = deviceMapper.selectOne(
                new LambdaQueryWrapper<DeviceDO>()
                        .eq(DeviceDO::getChipId, chipId)
        );
        if (device == null || device.getStoreId() == null) {
            return null;
        }
        Long currentUserId = SecurityUtils.getCurrentUserId();
        StoreDO store = storeMapper.selectOne(
                new LambdaQueryWrapper<StoreDO>()
                        .eq(StoreDO::getUserId, currentUserId)
                        .last("limit 1")
        );
        if (store == null || !device.getStoreId().equals(store.getId())) {
            log.warn("AI chipId ownership check failed, chipId={} deviceStoreId={} currentUserId={}",
                    chipId, device.getStoreId(), currentUserId);
            return null;
        }
        return device.getStoreId();
    }

    private DeviceDO updateDeviceAiResult(String chipId, FabricRecognizeRespVO result) {
        DeviceDO device = deviceMapper.selectOne(
                new LambdaQueryWrapper<DeviceDO>()
                        .eq(DeviceDO::getChipId, chipId)
        );

        if (device == null) {
            return null;
        }

        Long currentUserId = SecurityUtils.getCurrentUserId();
        StoreDO currentUserStore = storeMapper.selectOne(
                new LambdaQueryWrapper<StoreDO>()
                        .eq(StoreDO::getUserId, currentUserId)
                        .last("limit 1")
        );
        if (currentUserStore == null || device.getStoreId() == null
                || !device.getStoreId().equals(currentUserStore.getId())) {
            log.warn("AI result write rejected, chipId={} deviceStoreId={} currentUserId={}",
                    chipId, device.getStoreId(), currentUserId);
            return null;
        }

        device.setFabric(result.getLabel());
        device.setMainColorRgb(result.getMainColorRgb());
        device.setRecommendedBrightness(result.getRecommendedBrightness());
        device.setRecommendedTemp(result.getRecommendedTemp());
        device.setUpdateTime(LocalDateTime.now());
        deviceMapper.updateById(device);

        DeviceRespVO respVO = DeviceConvert.convert(device);
        webSocketPushService.pushState(respVO);
        webSocketPushService.pushStateToDevice(chipId, respVO);

        return device;
    }

    private MainColorResult applyFabricAdjustment(MainColorResult colorResult, String fabric) {
        MainColorResult baseResult = colorResult == null
                ? new MainColorResult("128,128,128", 60, 4500)
                : colorResult;

        int brightness = baseResult.getRecommendedBrightness() == null
                ? 60
                : baseResult.getRecommendedBrightness();
        int temp = baseResult.getRecommendedTemp() == null
                ? 4500
                : baseResult.getRecommendedTemp();

        String normalizedFabric = normalizeFabric(fabric);
        if (normalizedFabric.contains("cotton")) {
            brightness += 5;
            temp += 100;
        } else if (normalizedFabric.contains("polyester")) {
            brightness -= 5;
            temp += 150;
        } else if (normalizedFabric.contains("wool") || normalizedFabric.contains("cashmere")) {
            brightness -= 3;
            temp -= 250;
        }

        return new MainColorResult(
                baseResult.getMainColorRgb(),
                clamp(brightness, 30, 95),
                clamp(temp, 2700, 6500)
        );
    }

    private String normalizeFabric(String fabric) {
        return fabric == null ? "" : fabric.trim().toLowerCase(Locale.ROOT);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ServiceException("上传文件不能为空");
        }
        if (file.getSize() > AI_IMAGE_MAX_SIZE) {
            throw new ServiceException("AI 图片大小不能超过 10MB");
        }

        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().trim().toLowerCase(Locale.ROOT);
        boolean extensionAllowed = ALLOWED_IMAGE_EXTENSIONS.stream().anyMatch(filename::endsWith);
        if (!extensionAllowed) {
            throw new ServiceException(UNSUPPORTED_IMAGE_MESSAGE);
        }

        String contentType = file.getContentType() == null ? "" : file.getContentType().trim().toLowerCase(Locale.ROOT);
        int semicolonIndex = contentType.indexOf(';');
        if (semicolonIndex >= 0) {
            contentType = contentType.substring(0, semicolonIndex).trim();
        }
        if (!ALLOWED_IMAGE_MIME_TYPES.contains(contentType)) {
            throw new ServiceException(UNSUPPORTED_IMAGE_MESSAGE);
        }

        byte[] header = new byte[12];
        int length;
        try (java.io.InputStream inputStream = file.getInputStream()) {
            length = inputStream.read(header);
        } catch (IOException e) {
            throw new ServiceException("图片读取失败");
        }
        if (!hasAllowedImageMagic(header, length)) {
            throw new ServiceException(UNSUPPORTED_IMAGE_MESSAGE);
        }
    }

    private boolean hasAllowedImageMagic(byte[] header, int length) {
        if (length >= 3
                && (header[0] & 0xFF) == 0xFF
                && (header[1] & 0xFF) == 0xD8
                && (header[2] & 0xFF) == 0xFF) {
            return true;
        }
        if (length >= 4
                && (header[0] & 0xFF) == 0x89
                && header[1] == 0x50
                && header[2] == 0x4E
                && header[3] == 0x47) {
            return true;
        }
        return length >= 12
                && header[0] == 0x52
                && header[1] == 0x49
                && header[2] == 0x46
                && header[3] == 0x46
                && header[8] == 0x57
                && header[9] == 0x45
                && header[10] == 0x42
                && header[11] == 0x50;
    }
}

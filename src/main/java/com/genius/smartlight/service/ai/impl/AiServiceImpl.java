package com.genius.smartlight.service.ai.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.convert.device.DeviceConvert;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.dataobject.PersonFlowRecordDO;
import com.genius.smartlight.dal.dataobject.StoreDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.dal.mysql.StoreMapper;
import com.genius.smartlight.integration.ai.FabricAiClient;
import com.genius.smartlight.integration.ai.PersonDetectClient;
import com.genius.smartlight.security.SecurityUtils;
import com.genius.smartlight.service.ai.AiService;
import com.genius.smartlight.service.ai.MainColorResult;
import com.genius.smartlight.service.ai.MainColorService;
import com.genius.smartlight.service.personflow.PersonFlowRecordService;
import com.genius.smartlight.vo.ai.FabricRecognizeRespVO;
import com.genius.smartlight.vo.ai.PersonDetectRespVO;
import com.genius.smartlight.vo.device.DeviceRespVO;
import com.genius.smartlight.websocket.WebSocketPushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
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

    private static final Path FABRIC_UPLOAD_BASE_DIR = Path.of("/opt/smartlight/uploads/fabric");
    private static final String FABRIC_PUBLIC_BASE_URL = "https://api.genius.show/uploads/fabric";
    private static final DateTimeFormatter FILE_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final DeviceMapper deviceMapper;
    private final FabricAiClient fabricAiClient;
    private final PersonDetectClient personDetectClient;
    private final WebSocketPushService webSocketPushService;
    private final MainColorService mainColorService;
    private final StoreMapper storeMapper;
    private final PersonFlowRecordService personFlowRecordService;

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

            // 读取文件字节，保存原始图片到 A 服务器，然后用字节包装调用 AI
            byte[] imageBytes;
            try {
                imageBytes = file.getBytes();
            } catch (IOException e) {
                throw new ServiceException("读取上传图片失败");
            }
            String originalContentType = file.getContentType();
            String fileBase = buildImageFileBase(chipId);
            String originalExtension = resolveExtension(file.getOriginalFilename(), originalContentType);
            String originalFilename = fileBase + "_original" + originalExtension;

            long saveOriginalStart = System.currentTimeMillis();
            try {
                ensureFabricUploadDirs();
                saveImageBytes(imageBytes, "original", originalFilename);
            } catch (IOException e) {
                throw new ServiceException("保存原始图片失败");
            } finally {
                log.debug("fabricRecognize cost step=saveOriginal chipId={} filename={} fileSize={} costMs={}",
                        chipId, filename, fileSize, System.currentTimeMillis() - saveOriginalStart);
            }

            MultipartFile aiFile = wrapBytesAsMultipartFile(imageBytes, file.getOriginalFilename(), originalContentType);

            long pythonStart = System.currentTimeMillis();
            FabricRecognizeRespVO result;
            try {
                result = fabricAiClient.recognize(aiFile, chipId);
            } finally {
                log.debug("fabricRecognize cost step=pythonRecognize chipId={} filename={} fileSize={} costMs={}",
                        chipId, filename, fileSize, System.currentTimeMillis() - pythonStart);
            }

            // 如果 AI 返回存档 base64（远程模式），由 A 后端保存图片
            String annotatedFilename = fileBase + "_annotated.jpg";
            String combinedFilename = fileBase + "_combined.jpg";
            boolean savedLocally = saveArchiveImagesFromBase64(result, annotatedFilename, combinedFilename,
                    chipId, filename, fileSize);

            // 始终使用 A 后端生成的 original URL
            result.setOriginalImagePath(FABRIC_UPLOAD_BASE_DIR.resolve("original").resolve(originalFilename)
                    .toString().replace("\\", "/"));
            result.setOriginalImageUrl(FABRIC_PUBLIC_BASE_URL + "/original/" + originalFilename);

            // 如果后端成功保存了 annotated/combined，优先使用本地 URL
            if (savedLocally) {
                result.setAnnotatedImagePath(FABRIC_UPLOAD_BASE_DIR.resolve("annotated").resolve(annotatedFilename)
                        .toString().replace("\\", "/"));
                result.setAnnotatedImageUrl(FABRIC_PUBLIC_BASE_URL + "/annotated/" + annotatedFilename);
                result.setCombinedImagePath(FABRIC_UPLOAD_BASE_DIR.resolve("combined").resolve(combinedFilename)
                        .toString().replace("\\", "/"));
                result.setCombinedImageUrl(FABRIC_PUBLIC_BASE_URL + "/combined/" + combinedFilename);
            }

            // 清除存档 base64 字段，避免泄漏到前端或数据库
            result.setArchiveAnnotatedJpgBase64(null);
            result.setArchiveCombinedJpgBase64(null);

            MainColorResult colorResult;
            long mainColorStart = System.currentTimeMillis();
            try {
                String maskedBase64 = result.getClothMaskedPngBase64();
                if (maskedBase64 != null && !maskedBase64.isBlank()) {
                    try (InputStream maskedStream = Base64.getDecoder().wrap(
                            new ByteArrayInputStream(maskedBase64.getBytes(StandardCharsets.ISO_8859_1)))) {
                        colorResult = mainColorService.extract(maskedStream);
                    }
                } else {
                    colorResult = mainColorService.extract(new ByteArrayInputStream(imageBytes));
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

            PersonFlowRecordDO saved = null;
            try {
                saved = savePersonFlowRecord(chipId, file, result, storeId);
            } catch (Exception e) {
                log.error("Failed to save person_flow_record, chipId={}, filename={}", chipId, filename, e);
            }

            try {
                Long pushStoreId = saved != null ? saved.getStoreId() : storeId;
                Long recordId = saved != null ? saved.getId() : null;
                webSocketPushService.pushPersonDetect(chipId, file.getOriginalFilename(), result, pushStoreId, recordId);
            } catch (Exception e) {
                log.error("Failed to push personDetection via WebSocket, chipId={}, filename={}", chipId, filename, e);
            }

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

    private PersonFlowRecordDO savePersonFlowRecord(String chipId, MultipartFile file,
                                       PersonDetectRespVO result, Long storeId) {
        PersonFlowRecordDO record = new PersonFlowRecordDO();

        if (storeId == null) {
            storeId = resolveStoreIdForFlowRecord(chipId);
        }
        record.setStoreId(storeId);

        try {
            Long userId = SecurityUtils.getCurrentUserId();
            record.setUserId(userId);
        } catch (Exception e) {
            log.debug("Cannot resolve current user for person_flow_record", e);
        }

        record.setChipId(chipId != null && !chipId.isBlank() ? chipId : null);
        record.setSource("UPLOAD");
        record.setPersonCount(result.getCount());
        record.setConfidence(result.getConfidence());
        record.setProcessingTime(result.getProcessingTime());

        LocalDateTime detectTime = parseDetectTime(result.getTimestamp());
        record.setDetectTime(detectTime != null ? detectTime : LocalDateTime.now());

        record.setImageName(file != null ? file.getOriginalFilename() : null);

        personFlowRecordService.saveRecord(record);
        return record;
    }

    private LocalDateTime parseDetectTime(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(timestamp, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(timestamp, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (Exception e2) {
                log.debug("Cannot parse detect timestamp: {}", timestamp);
                return null;
            }
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

    private Long resolveStoreIdForFlowRecord(String chipId) {
        if (chipId != null && !chipId.isBlank()) {
            DeviceDO device = deviceMapper.selectOne(
                    new LambdaQueryWrapper<DeviceDO>()
                            .eq(DeviceDO::getChipId, chipId)
                            .last("limit 1")
            );
            if (device != null && device.getStoreId() != null) {
                return device.getStoreId();
            }
        }

        try {
            Long userId = SecurityUtils.getCurrentUserId();
            StoreDO store = storeMapper.selectOne(
                    new LambdaQueryWrapper<StoreDO>()
                            .eq(StoreDO::getUserId, userId)
                            .last("limit 1")
            );
            if (store != null) {
                return store.getId();
            }
        } catch (Exception e) {
            log.debug("Cannot resolve store from current user for flow record", e);
        }

        log.warn("Cannot resolve store_id for person_flow_record, chipId={}", chipId);
        return null;
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

    /**
     * 生成留档图片的基础文件名：{chipId}_{timestamp}_{random}
     */
    private String buildImageFileBase(String chipId) {
        String safeChipId = chipId != null && !chipId.isBlank()
                ? chipId.replaceAll("[^A-Za-z0-9_-]", "")
                : "unknown";
        String timestamp = LocalDateTime.now().format(FILE_TIMESTAMP_FORMATTER);
        byte[] randomBytes = new byte[4];
        SECURE_RANDOM.nextBytes(randomBytes);
        String randomSuffix = HexFormat.of().formatHex(randomBytes);
        return safeChipId + "_" + timestamp + "_" + randomSuffix;
    }

    /**
     * 确保 fabric 三级存档目录存在
     */
    private void ensureFabricUploadDirs() throws IOException {
        for (String subdir : new String[]{"original", "annotated", "combined"}) {
            Files.createDirectories(FABRIC_UPLOAD_BASE_DIR.resolve(subdir));
        }
    }

    /**
     * 将字节写入存档子目录
     */
    private void saveImageBytes(byte[] bytes, String subdir, String filename) throws IOException {
        Path targetPath = FABRIC_UPLOAD_BASE_DIR.resolve(subdir).resolve(filename);
        Files.write(targetPath, bytes);
        log.debug("fabric archive saved: {}", targetPath);
    }

    /**
     * 从 AI 返回的 base64 解码并保存 annotated 和 combined 图片。
     * 返回 true 表示至少保存了一张图。
     */
    private boolean saveArchiveImagesFromBase64(FabricRecognizeRespVO result,
                                                  String annotatedFilename,
                                                  String combinedFilename,
                                                  String chipId, String filename, long fileSize) {
        boolean saved = false;
        long start = System.currentTimeMillis();
        try {
            String annotatedB64 = result.getArchiveAnnotatedJpgBase64();
            if (annotatedB64 != null && !annotatedB64.isBlank()) {
                byte[] decoded = Base64.getDecoder().decode(annotatedB64);
                saveImageBytes(decoded, "annotated", annotatedFilename);
                saved = true;
            }
        } catch (Exception e) {
            log.warn("fabricRecognize failed to save annotated archive chipId={} filename={}", chipId, filename, e);
        } finally {
            log.debug("fabricRecognize cost step=saveAnnotatedArchive chipId={} filename={} fileSize={} costMs={}",
                    chipId, filename, fileSize, System.currentTimeMillis() - start);
        }

        long combinedStart = System.currentTimeMillis();
        try {
            String combinedB64 = result.getArchiveCombinedJpgBase64();
            if (combinedB64 != null && !combinedB64.isBlank()) {
                byte[] decoded = Base64.getDecoder().decode(combinedB64);
                saveImageBytes(decoded, "combined", combinedFilename);
                saved = true;
            }
        } catch (Exception e) {
            log.warn("fabricRecognize failed to save combined archive chipId={} filename={}", chipId, filename, e);
        } finally {
            log.debug("fabricRecognize cost step=saveCombinedArchive chipId={} filename={} fileSize={} costMs={}",
                    chipId, filename, fileSize, System.currentTimeMillis() - combinedStart);
        }

        return saved;
    }

    /**
     * 根据文件名和 MIME 类型推断图片扩展名（含点号）
     */
    private String resolveExtension(String filename, String contentType) {
        if (filename != null && filename.contains(".")) {
            String ext = filename.substring(filename.lastIndexOf('.')).toLowerCase(Locale.ROOT);
            if (Set.of(".jpg", ".jpeg", ".png", ".webp").contains(ext)) {
                return ext;
            }
        }
        if (contentType != null) {
            return switch (contentType.toLowerCase(Locale.ROOT)) {
                case "image/jpeg" -> ".jpg";
                case "image/png" -> ".png";
                case "image/webp" -> ".webp";
                default -> ".jpg";
            };
        }
        return ".jpg";
    }

    /**
     * 将字节数组包装为 MultipartFile，用于 AI 调用（原始流已被读取）
     */
    private static MultipartFile wrapBytesAsMultipartFile(byte[] bytes, String originalFilename, String contentType) {
        return new MultipartFile() {
            @Override
            public String getName() {
                return "image";
            }

            @Override
            public String getOriginalFilename() {
                return originalFilename != null ? originalFilename : "image.jpg";
            }

            @Override
            public String getContentType() {
                return contentType;
            }

            @Override
            public boolean isEmpty() {
                return bytes.length == 0;
            }

            @Override
            public long getSize() {
                return bytes.length;
            }

            @Override
            public byte[] getBytes() {
                return bytes;
            }

            @Override
            public InputStream getInputStream() {
                return new ByteArrayInputStream(bytes);
            }

            @Override
            public void transferTo(File dest) throws IOException {
                Files.write(dest.toPath(), bytes);
            }
        };
    }
}

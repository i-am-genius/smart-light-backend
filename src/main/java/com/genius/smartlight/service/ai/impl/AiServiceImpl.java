package com.genius.smartlight.service.ai.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.genius.smartlight.common.DeviceTypeUtil;
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
import com.genius.smartlight.service.ai.GarmentRecognitionProcessor;
import com.genius.smartlight.service.ai.GarmentResultCodec;
import com.genius.smartlight.service.personflow.PersonFlowRecordService;
import com.genius.smartlight.vo.ai.FabricRecognizeRespVO;
import com.genius.smartlight.vo.ai.PersonDetectRespVO;
import com.genius.smartlight.vo.device.DeviceRespVO;
import com.genius.smartlight.websocket.WebSocketPushService;
import com.genius.smartlight.websocket.fabric.FabricImageLiveNotifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
    private static final DateTimeFormatter FILE_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final DeviceMapper deviceMapper;
    private final FabricAiClient fabricAiClient;
    private final PersonDetectClient personDetectClient;
    private final WebSocketPushService webSocketPushService;
    private final GarmentRecognitionProcessor garmentRecognitionProcessor;
    private final StoreMapper storeMapper;
    private final PersonFlowRecordService personFlowRecordService;
    private final FabricImageLiveNotifier fabricImageLiveNotifier;

    @Override
    public FabricRecognizeRespVO fabricRecognize(String chipId, MultipartFile file) {
        return fabricRecognize(chipId, file, FABRIC_UPLOAD_BASE_DIR);
    }

    FabricRecognizeRespVO fabricRecognize(
            String chipId,
            MultipartFile file,
            Path archiveBaseDirectory) {
        long totalStart = System.currentTimeMillis();
        String filename = file == null ? "" : file.getOriginalFilename();
        long fileSize = file == null ? 0L : file.getSize();
        log.info("fabricRecognize start chipId={} filename={} fileSize={}", chipId, filename, fileSize);

        boolean validated = false;
        try {
            validateFile(file);
            validated = true;
            Long deviceStoreId = resolveOwnedDeviceStoreId(chipId);

            String originalContentType = file.getContentType();
            String archiveId = buildImageFileBase(chipId);
            String originalExtension = resolveExtension(file.getOriginalFilename(), originalContentType);
            String originalFilename = archiveId + "_original" + originalExtension;

            long saveOriginalStart = System.currentTimeMillis();
            try {
                ensureFabricUploadDirs(archiveBaseDirectory);
                saveMultipartFileAtomic(
                        file,
                        archiveBaseDirectory,
                        "original",
                        originalFilename
                );
            } catch (IOException e) {
                throw new ServiceException("保存原始图片失败");
            } finally {
                log.debug("fabricRecognize cost step=saveOriginal chipId={} filename={} fileSize={} costMs={}",
                        chipId, filename, fileSize, System.currentTimeMillis() - saveOriginalStart);
            }

            long pythonStart = System.currentTimeMillis();
            FabricRecognizeRespVO result;
            try {
                result = fabricAiClient.recognize(
                        file,
                        chipId,
                        archiveId,
                        false
                );
            } finally {
                log.debug("fabricRecognize cost step=pythonRecognize chipId={} filename={} fileSize={} costMs={}",
                        chipId, filename, fileSize, System.currentTimeMillis() - pythonStart);
            }
            garmentRecognitionProcessor.process(result);

            // 如果 AI 返回存档 base64（远程模式），由 A 后端保存图片
            String annotatedFilename = archiveId + "_annotated.jpg";
            String combinedFilename = archiveId + "_combined.jpg";
            String aiAnnotatedImagePath = result.getAnnotatedImagePath();
            ArchiveSaveResult savedArchives = saveArchiveImagesFromBase64(
                    result, annotatedFilename, combinedFilename,
                    chipId, filename, fileSize, archiveBaseDirectory);
            boolean annotatedAvailable = savedArchives.annotatedSaved()
                    || archiveFileExists(
                    archiveBaseDirectory,
                    "annotated",
                    annotatedFilename
            );
            boolean combinedAvailable = savedArchives.combinedSaved()
                    || archiveFileExists(
                    archiveBaseDirectory,
                    "combined",
                    combinedFilename
            );

            // 始终使用 A 后端生成的 original URL
            result.setOriginalImagePath("original/" + originalFilename);
            result.setOriginalImageUrl(buildArchiveFileUrl("original", originalFilename));

            log.info("archiveSaveResult chipId={} annotatedSaved={} combinedSaved={} annotatedB64Present={}",
                    chipId, savedArchives.annotatedSaved(), savedArchives.combinedSaved(),
                    result.getArchiveAnnotatedJpgBase64() != null && !result.getArchiveAnnotatedJpgBase64().isBlank());
            applyLocalArchiveLocations(
                    result,
                    annotatedFilename,
                    combinedFilename,
                    annotatedAvailable,
                    combinedAvailable
            );
            clearUnsafeArchiveLocations(
                    result,
                    annotatedAvailable,
                    combinedAvailable
            );

            // 清除存档 base64 字段，避免泄漏到前端或数据库
            result.setArchiveAnnotatedJpgBase64(null);
            result.setArchiveCombinedJpgBase64(null);

            DeviceDO updatedDevice = null;
            long updateStart = System.currentTimeMillis();
            try {
                if (chipId != null && !chipId.isBlank()) {
                    updatedDevice = updateDeviceAiResult(chipId, result);
                    if (updatedDevice != null) {
                        deviceStoreId = updatedDevice.getStoreId();
                        log.info("updateDeviceAiResult OK chipId={} deviceId={} storeId={}", chipId, updatedDevice.getId(), deviceStoreId);
                    } else {
                        log.warn("updateDeviceAiResult returned null, chipId={}", chipId);
                    }
                } else {
                    log.warn("updateDeviceAiResult skipped: chipId is blank, chipId={}", chipId);
                }
            } finally {
                log.debug("fabricRecognize cost step=updateDeviceAndPushState chipId={} filename={} fileSize={} costMs={} skipped={}",
                        chipId, filename, fileSize, System.currentTimeMillis() - updateStart,
                        chipId == null || chipId.isBlank());
            }

            long wsStart = System.currentTimeMillis();
            try {
                if (updatedDevice != null) {
                    log.info("pushFabricRecognize chipId={} storeId={} annotatedImageUrl={} originalImageUrl={}",
                            chipId, deviceStoreId, result.getAnnotatedImageUrl(), result.getOriginalImageUrl());
                    webSocketPushService.pushFabricRecognize(
                            chipId,
                            file.getOriginalFilename(),
                            result,
                            deviceStoreId
                    );
                } else {
                    log.warn("pushFabricRecognize skipped: updatedDevice is null, chipId={}", chipId);
                }
            } finally {
                log.debug("fabricRecognize cost step=pushFabricRecognize chipId={} filename={} fileSize={} costMs={}",
                        chipId, filename, fileSize, System.currentTimeMillis() - wsStart);
            }
            fabricImageLiveNotifier.pushIfPresent(
                    deviceStoreId,
                    chipId,
                    archiveBaseDirectory,
                    annotatedAvailable ? annotatedFilename : null,
                    aiAnnotatedImagePath
            );

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
            PersonDetectRespVO result = personDetectClient.detect(file, false);

            Long storeId = resolveOwnedDeviceStoreId(chipId);

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
            storeId = resolveCurrentStoreId();
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

    private Long resolveOwnedDeviceStoreId(String chipId) {
        if (chipId == null || chipId.isBlank()) {
            return null;
        }
        DeviceDO device = deviceMapper.selectOne(
                new LambdaQueryWrapper<DeviceDO>()
                        .eq(DeviceDO::getChipId, chipId)
        );
        if (device == null || device.getStoreId() == null) {
            throw new ServiceException("AI device does not exist or is not bound to a store");
        }
        // 设备端请求（无登录用户）：设备已通过 task token 鉴权，直接返回设备所属 storeId
        Long currentUserId = SecurityUtils.getCurrentUserIdOrNull();
        if (currentUserId == null) {
            return device.getStoreId();
        }
        StoreDO store = getCurrentUserStore(currentUserId);
        if (store == null || !device.getStoreId().equals(store.getId())) {
            log.warn("AI chipId ownership check failed, chipId={} deviceStoreId={} currentUserId={}",
                    chipId, device.getStoreId(), currentUserId);
            throw new ServiceException("No permission to use this device for AI recognition");
        }
        return device.getStoreId();
    }

    private Long resolveCurrentStoreId() {
        Long userId = SecurityUtils.getCurrentUserId();
        StoreDO store = getCurrentUserStore(userId);
        if (store == null) {
            throw new ServiceException("Current user has no store");
        }
        return store.getId();
    }

    private StoreDO getCurrentUserStore(Long userId) {
        if (userId == null) {
            return null;
        }
        return storeMapper.selectOne(
                new LambdaQueryWrapper<StoreDO>()
                        .eq(StoreDO::getUserId, userId)
                        .last("limit 1")
        );
    }

    private DeviceDO updateDeviceAiResult(String chipId, FabricRecognizeRespVO result) {
        DeviceDO device = deviceMapper.selectOne(
                new LambdaQueryWrapper<DeviceDO>()
                        .eq(DeviceDO::getChipId, chipId)
        );

        if (device == null) {
            return null;
        }

        // 设备端请求（无登录用户）：设备已通过 task token 鉴权，跳过用户级所有权校验
        Long currentUserId = SecurityUtils.getCurrentUserIdOrNull();
        if (currentUserId != null) {
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
        }

        LocalDateTime recognizedAt = LocalDateTime.now();
        String garmentJson = GarmentResultCodec.encode(result, recognizedAt);
        device.setFabric(result.getLabel());
        device.setMainColorRgb(result.getMainColorRgb());
        device.setRecommendedBrightness(result.getRecommendedBrightness());
        device.setRecommendedTemp(result.getRecommendedTemp());
        device.setGarmentResultJson(garmentJson);
        device.setUpdateTime(recognizedAt);
        int updatedRows = deviceMapper.updateById(device);
        if (updatedRows != 1) {
            throw new ServiceException("AI result update failed");
        }

        DeviceRespVO respVO = DeviceConvert.convert(device);
        webSocketPushService.pushState(respVO);
        if (!DeviceTypeUtil.isCam(respVO.getDeviceType())) {
            webSocketPushService.pushStateToDevice(chipId, respVO);
        }

        return device;
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
        if (safeChipId.length() > 64) {
            safeChipId = safeChipId.substring(0, 64);
        }
        String timestamp = LocalDateTime.now().format(FILE_TIMESTAMP_FORMATTER);
        byte[] randomBytes = new byte[4];
        SECURE_RANDOM.nextBytes(randomBytes);
        String randomSuffix = HexFormat.of().formatHex(randomBytes);
        return safeChipId + "_" + timestamp + "_" + randomSuffix;
    }

    /**
     * 确保 fabric 三级存档目录存在
     */
    private void ensureFabricUploadDirs(Path baseDirectory) throws IOException {
        Path normalizedBaseDirectory = baseDirectory.toAbsolutePath().normalize();
        for (String subdir : new String[]{"original", "annotated", "combined"}) {
            Path directory = normalizedBaseDirectory.resolve(subdir).normalize();
            if (!directory.startsWith(normalizedBaseDirectory)) {
                throw new IOException("Invalid fabric archive path");
            }
            Files.createDirectories(directory);
        }
    }

    private static String buildArchiveFileUrl(String type, String filename) {
        return "/admin/ai/fabric-archive/file?type="
                + UriUtils.encode(type, StandardCharsets.UTF_8)
                + "&filename="
                + UriUtils.encode(filename, StandardCharsets.UTF_8);
    }

    static void applyLocalArchiveLocations(FabricRecognizeRespVO result,
                                           String annotatedFilename,
                                           String combinedFilename,
                                           boolean annotatedSaved,
                                           boolean combinedSaved) {
        if (annotatedSaved) {
            result.setAnnotatedImagePath("annotated/" + annotatedFilename);
            result.setAnnotatedImageUrl(buildArchiveFileUrl("annotated", annotatedFilename));
        }
        if (combinedSaved) {
            result.setCombinedImagePath("combined/" + combinedFilename);
            result.setCombinedImageUrl(buildArchiveFileUrl("combined", combinedFilename));
        }
    }

    private static void clearUnsafeArchiveLocations(
            FabricRecognizeRespVO result,
            boolean annotatedAvailable,
            boolean combinedAvailable) {
        if (!annotatedAvailable) {
            if (isLocalFilesystemLocation(result.getAnnotatedImagePath())) {
                result.setAnnotatedImagePath(null);
            }
            if (isLocalFilesystemLocation(result.getAnnotatedImageUrl())) {
                result.setAnnotatedImageUrl(null);
            }
        }
        if (!combinedAvailable) {
            if (isLocalFilesystemLocation(result.getCombinedImagePath())) {
                result.setCombinedImagePath(null);
            }
            if (isLocalFilesystemLocation(result.getCombinedImageUrl())) {
                result.setCombinedImageUrl(null);
            }
        }
    }

    private static boolean isLocalFilesystemLocation(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.trim().replace('\\', '/');
        if (normalized.startsWith("/admin/ai/fabric-archive/file?")) {
            return false;
        }
        return normalized.startsWith("/")
                || normalized.startsWith("file:")
                || normalized.matches("^[A-Za-z]:/.*");
    }

    private static boolean archiveFileExists(
            Path baseDirectory,
            String subdir,
            String filename) {
        Path normalizedBaseDirectory = baseDirectory.toAbsolutePath().normalize();
        Path directory = normalizedBaseDirectory.resolve(subdir).normalize();
        Path target = directory.resolve(filename).normalize();
        return directory.startsWith(normalizedBaseDirectory)
                && target.startsWith(directory)
                && Files.isRegularFile(target);
    }

    static void saveMultipartFileAtomic(
            MultipartFile file,
            Path baseDirectory,
            String subdir,
            String filename) throws IOException {
        Path normalizedBaseDirectory = baseDirectory.toAbsolutePath().normalize();
        Path directory = normalizedBaseDirectory.resolve(subdir).normalize();
        if (!directory.startsWith(normalizedBaseDirectory)) {
            throw new IOException("Invalid fabric archive path");
        }
        Files.createDirectories(directory);

        Path target = directory.resolve(filename).normalize();
        Path temp = directory.resolve(filename + ".tmp").normalize();
        if (!target.startsWith(directory) || !temp.startsWith(directory)) {
            throw new IOException("Invalid fabric archive path");
        }

        try {
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(
                        temp,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(
                        temp,
                        target,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * 将字节写入存档子目录
     */
    private void saveImageBytes(
            byte[] bytes,
            Path baseDirectory,
            String subdir,
            String filename) throws IOException {
        Path normalizedBaseDirectory = baseDirectory.toAbsolutePath().normalize();
        Path directory = normalizedBaseDirectory.resolve(subdir).normalize();
        Path targetPath = directory.resolve(filename).normalize();
        if (!directory.startsWith(normalizedBaseDirectory)
                || !targetPath.startsWith(directory)) {
            throw new IOException("Invalid fabric archive path");
        }
        Files.write(targetPath, bytes);
        log.debug("fabric archive saved: {}", targetPath);
    }

    /**
     * 从 AI 返回的 base64 解码并保存 annotated 和 combined 图片。
     * 分别记录两类图片是否成功保存，避免其中一类失败时覆盖 AI 返回的有效路径。
     */
    private ArchiveSaveResult saveArchiveImagesFromBase64(FabricRecognizeRespVO result,
                                                           String annotatedFilename,
                                                           String combinedFilename,
                                                           String chipId, String filename, long fileSize,
                                                           Path baseDirectory) {
        boolean annotatedSaved = false;
        long start = System.currentTimeMillis();
        try {
            String annotatedB64 = result.getArchiveAnnotatedJpgBase64();
            if (annotatedB64 != null && !annotatedB64.isBlank()) {
                byte[] decoded = Base64.getDecoder().decode(annotatedB64);
                validateArchiveImageBytes(decoded);
                saveImageBytes(decoded, baseDirectory, "annotated", annotatedFilename);
                annotatedSaved = true;
            }
        } catch (Exception e) {
            log.warn("fabricRecognize failed to save annotated archive chipId={} filename={}", chipId, filename, e);
        } finally {
            log.debug("fabricRecognize cost step=saveAnnotatedArchive chipId={} filename={} fileSize={} costMs={}",
                    chipId, filename, fileSize, System.currentTimeMillis() - start);
        }

        long combinedStart = System.currentTimeMillis();
        boolean combinedSaved = false;
        try {
            String combinedB64 = result.getArchiveCombinedJpgBase64();
            if (combinedB64 != null && !combinedB64.isBlank()) {
                byte[] decoded = Base64.getDecoder().decode(combinedB64);
                validateArchiveImageBytes(decoded);
                saveImageBytes(decoded, baseDirectory, "combined", combinedFilename);
                combinedSaved = true;
            }
        } catch (Exception e) {
            log.warn("fabricRecognize failed to save combined archive chipId={} filename={}", chipId, filename, e);
        } finally {
            log.debug("fabricRecognize cost step=saveCombinedArchive chipId={} filename={} fileSize={} costMs={}",
                    chipId, filename, fileSize, System.currentTimeMillis() - combinedStart);
        }

        return new ArchiveSaveResult(annotatedSaved, combinedSaved);
    }

    private record ArchiveSaveResult(boolean annotatedSaved, boolean combinedSaved) {
    }

    /**
     * 根据文件名和 MIME 类型推断图片扩展名（含点号）
     */
    private void validateArchiveImageBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > AI_IMAGE_MAX_SIZE) {
            throw new ServiceException("AI archive image is invalid");
        }
        if (!hasAllowedImageMagic(bytes, Math.min(bytes.length, 12))) {
            throw new ServiceException("AI archive image format is invalid");
        }
    }

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
}

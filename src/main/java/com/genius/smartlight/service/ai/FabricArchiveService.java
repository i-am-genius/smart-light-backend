package com.genius.smartlight.service.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.service.store.CurrentStoreService;
import com.genius.smartlight.vo.ai.FabricArchiveDeleteRespVO;
import com.genius.smartlight.vo.ai.FabricArchiveItemRespVO;
import com.genius.smartlight.vo.ai.FabricArchivePageRespVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class FabricArchiveService {

    private static final Path FABRIC_ARCHIVE_BASE_DIR = Path.of("/opt/smartlight/uploads/fabric");
    private static final String FABRIC_ARCHIVE_BASE_URL = "https://api.genius.show/uploads/fabric";
    private static final Set<String> FABRIC_ARCHIVE_TYPES = Set.of("original", "annotated", "combined");
    private static final Set<String> FABRIC_ARCHIVE_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png");
    private static final DateTimeFormatter FILENAME_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final DateTimeFormatter RESPONSE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern FABRIC_ARCHIVE_FILENAME_PATTERN = Pattern.compile(
            "^(.+)_([0-9]{8})_([0-9]{6})_[A-Fa-f0-9]{8}_(original|annotated|combined)\\.(jpg|jpeg|png)$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern FABRIC_ARCHIVE_BASENAME_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final Pattern FABRIC_ARCHIVE_DELETE_FILENAME_PATTERN = Pattern.compile(
            "^(.+)_(original|annotated|combined)\\.(jpg|jpeg|png)$",
            Pattern.CASE_INSENSITIVE
    );

    private final DeviceMapper deviceMapper;
    private final CurrentStoreService currentStoreService;

    public FabricArchivePageRespVO listArchive(String type, Integer page, Integer pageSize) throws IOException {
        String normalizedType = normalizeArchiveType(type);
        int safePage = normalizePage(page);
        int safePageSize = normalizePageSize(pageSize);
        Path targetDir = FABRIC_ARCHIVE_BASE_DIR.resolve(normalizedType).normalize();

        Set<String> allowedChipIds = getAllowedChipIds();

        List<FabricArchiveItemRespVO> items;
        if (!Files.isDirectory(targetDir)) {
            items = List.of();
        } else {
            try (Stream<Path> stream = Files.list(targetDir)) {
                items = stream
                        .filter(Files::isRegularFile)
                        .filter(this::isArchiveImage)
                        .map(path -> buildArchiveItem(path, normalizedType))
                        .filter(item -> allowedChipIds.isEmpty() || allowedChipIds.contains(item.getChipId()))
                        .sorted(Comparator.comparing(
                                item -> readLastModifiedMillis(targetDir.resolve(item.getFilename())),
                                Comparator.reverseOrder()
                        ))
                        .toList();
            }
        }

        long total = items.size();
        int fromIndex = Math.min((safePage - 1) * safePageSize, items.size());
        int toIndex = Math.min(fromIndex + safePageSize, items.size());

        FabricArchivePageRespVO respVO = new FabricArchivePageRespVO();
        respVO.setList(items.subList(fromIndex, toIndex));
        respVO.setTotal(total);
        respVO.setPage(safePage);
        respVO.setPageSize(safePageSize);
        return respVO;
    }

    public FabricArchiveDeleteRespVO deleteArchiveGroup(String filename, String baseName) throws IOException {
        String normalizedBaseName = resolveDeleteBaseName(filename, baseName);
        FabricArchiveDeleteRespVO respVO = new FabricArchiveDeleteRespVO();
        respVO.setBaseName(normalizedBaseName == null ? "" : normalizedBaseName);
        respVO.setDeletedFiles(new ArrayList<>());
        respVO.setMissingFiles(new ArrayList<>());

        if (!isSafeArchiveBaseName(normalizedBaseName)) {
            respVO.setSuccess(false);
            respVO.setMsg("文件名或 baseName 不正确");
            respVO.setDeletedCount(0);
            return respVO;
        }

        // Validate ownership: chipId in filename must belong to current store
        validateOwnership(normalizedBaseName);

        Set<String> allowedChipIds = getAllowedChipIds();

        for (String archiveType : List.of("original", "annotated", "combined")) {
            for (String extension : List.of(".jpg", ".jpeg", ".png")) {
                Path candidate = FABRIC_ARCHIVE_BASE_DIR
                        .resolve(archiveType)
                        .resolve(normalizedBaseName + "_" + archiveType + extension)
                        .normalize();

                if (!candidate.startsWith(FABRIC_ARCHIVE_BASE_DIR)) {
                    respVO.setSuccess(false);
                    respVO.setMsg("归档路径不正确");
                    respVO.setDeletedCount(respVO.getDeletedFiles().size());
                    return respVO;
                }

                String filePath = candidate.toString();
                if (!Files.exists(candidate)) {
                    respVO.getMissingFiles().add(filePath);
                    continue;
                }

                if (Files.isRegularFile(candidate)) {
                    Files.delete(candidate);
                    respVO.getDeletedFiles().add(filePath);
                } else {
                    respVO.getMissingFiles().add(filePath);
                }
            }
        }

        respVO.setDeletedCount(respVO.getDeletedFiles().size());
        if (respVO.getDeletedCount() > 0) {
            respVO.setSuccess(true);
            respVO.setMsg("已删除归档图片组");
        } else {
            respVO.setSuccess(false);
            respVO.setMsg("归档图片组不存在");
        }
        return respVO;
    }

    private Set<String> getAllowedChipIds() {
        try {
            Long storeId = currentStoreService.getCurrentStoreId();
            List<DeviceDO> devices = deviceMapper.selectList(
                    new LambdaQueryWrapper<DeviceDO>()
                            .eq(DeviceDO::getStoreId, storeId)
            );
            return devices.stream()
                    .map(DeviceDO::getChipId)
                    .collect(java.util.stream.Collectors.toSet());
        } catch (Exception e) {
            return Set.of();
        }
    }

    private void validateOwnership(String baseName) {
        // baseName format: CHIPID_20260414_103000_A1B2C3D4
        if (baseName == null || baseName.isBlank()) {
            throw new ServiceException("文件名不正确");
        }
        int firstUnderscore = baseName.indexOf('_');
        if (firstUnderscore <= 0) {
            throw new ServiceException("无法从文件名中识别设备");
        }
        String chipId = baseName.substring(0, firstUnderscore);
        Set<String> allowedChipIds = getAllowedChipIds();
        if (!allowedChipIds.contains(chipId)) {
            throw new ServiceException("无权删除该设备的留档图片");
        }
    }

    private String resolveDeleteBaseName(String filename, String baseName) {
        if (baseName != null && !baseName.isBlank()) {
            return baseName.trim();
        }
        if (filename == null || filename.isBlank()) {
            return null;
        }

        String value = filename.trim();
        Matcher matcher = FABRIC_ARCHIVE_DELETE_FILENAME_PATTERN.matcher(value);
        return matcher.matches() ? matcher.group(1) : null;
    }

    private boolean isSafeArchiveBaseName(String baseName) {
        if (baseName == null || baseName.isBlank()) {
            return false;
        }
        if (baseName.contains("..") || baseName.contains("/") || baseName.contains("\\")) {
            return false;
        }
        return FABRIC_ARCHIVE_BASENAME_PATTERN.matcher(baseName).matches();
    }

    private String normalizeArchiveType(String type) {
        String value = type == null ? "combined" : type.trim().toLowerCase(Locale.ROOT);
        return FABRIC_ARCHIVE_TYPES.contains(value) ? value : "combined";
    }

    private int normalizePage(Integer page) {
        return page == null || page < 1 ? 1 : page;
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return 30;
        }
        return Math.min(pageSize, 100);
    }

    private boolean isArchiveImage(Path path) {
        String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return FABRIC_ARCHIVE_EXTENSIONS.stream().anyMatch(filename::endsWith);
    }

    private FabricArchiveItemRespVO buildArchiveItem(Path path, String type) {
        String filename = path.getFileName().toString();
        Map<String, String> parsed = parseArchiveFilename(filename);

        FabricArchiveItemRespVO item = new FabricArchiveItemRespVO();
        item.setFilename(filename);
        item.setUrl(FABRIC_ARCHIVE_BASE_URL + "/" + type + "/" + filename);
        item.setType(type);
        item.setChipId(parsed.getOrDefault("chipId", ""));
        item.setCreateTime(parsed.getOrDefault("createTime", ""));
        item.setSize(readSize(path));
        return item;
    }

    private Map<String, String> parseArchiveFilename(String filename) {
        Matcher matcher = FABRIC_ARCHIVE_FILENAME_PATTERN.matcher(filename);
        if (!matcher.matches()) {
            return Map.of("chipId", "", "createTime", "");
        }

        String createTime = "";
        try {
            LocalDateTime time = LocalDateTime.parse(
                    matcher.group(2) + "_" + matcher.group(3),
                    FILENAME_TIME_FORMATTER
            );
            createTime = time.format(RESPONSE_TIME_FORMATTER);
        } catch (Exception ignored) {
            createTime = "";
        }

        return Map.of(
                "chipId", matcher.group(1),
                "createTime", createTime
        );
    }

    private Long readSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private Long readLastModifiedMillis(Path path) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class)
                    .lastModifiedTime()
                    .toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }
}

package com.genius.smartlight.websocket.fabric;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.genius.smartlight.common.DeviceTypeUtil;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Component
public class FabricImageArchiveStore {

    private static final Path DEFAULT_BASE_DIR = Path.of("/opt/smartlight/uploads/fabric");
    private static final Pattern ANNOTATED_SUFFIX = Pattern.compile(
            "^[0-9]{8}_[0-9]{6}_[A-Fa-f0-9]{8}_annotated\\.(jpg|jpeg|png|webp)$",
            Pattern.CASE_INSENSITIVE
    );

    private final DeviceMapper deviceMapper;
    private final Path annotatedRoot;

    @Autowired
    public FabricImageArchiveStore(DeviceMapper deviceMapper) {
        this(deviceMapper, DEFAULT_BASE_DIR);
    }

    FabricImageArchiveStore(DeviceMapper deviceMapper, Path baseDir) {
        this.deviceMapper = Objects.requireNonNull(deviceMapper, "deviceMapper");
        this.annotatedRoot = Objects.requireNonNull(baseDir, "baseDir")
                .resolve("annotated")
                .toAbsolutePath()
                .normalize();
    }

    public List<ArchivedFabricImage> findLatestForStore(Long storeId) {
        if (storeId == null || !Files.isDirectory(annotatedRoot)) {
            return List.of();
        }
        Set<String> allowedChipIds = deviceMapper.selectList(
                        new LambdaQueryWrapper<DeviceDO>()
                                .eq(DeviceDO::getStoreId, storeId)
                ).stream()
                .filter(device -> DeviceTypeUtil.isLampLike(device.getDeviceType()))
                .map(DeviceDO::getChipId)
                .filter(Objects::nonNull)
                .filter(chipId -> !chipId.isBlank())
                .collect(Collectors.toSet());
        if (allowedChipIds.isEmpty()) {
            return List.of();
        }

        List<String> chipIdsByLength = allowedChipIds.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        Map<String, ArchivedFabricImage> latestByChipId = new HashMap<>();
        try (Stream<Path> files = Files.list(annotatedRoot)) {
            files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> path.toAbsolutePath().normalize())
                    .filter(path -> path.startsWith(annotatedRoot))
                    .forEach(path -> candidate(path, chipIdsByLength).ifPresent(image ->
                            latestByChipId.merge(
                                    image.chipId(),
                                    image,
                                    (current, next) -> next.lastModifiedMillis() > current.lastModifiedMillis()
                                            ? next : current
                            )));
        } catch (IOException e) {
            log.warn("[fabric-image] event=archive_scan_failed, storeId={}, errorType={}",
                    storeId, e.getClass().getSimpleName());
            return List.of();
        }

        List<ArchivedFabricImage> result = new ArrayList<>(latestByChipId.values());
        result.sort(Comparator.comparing(ArchivedFabricImage::chipId));
        return result;
    }

    private java.util.Optional<ArchivedFabricImage> candidate(Path path, List<String> allowedChipIds) {
        String filename = path.getFileName().toString();
        String chipId = allowedChipIds.stream()
                .filter(allowed -> filename.startsWith(allowed + "_"))
                .filter(allowed -> ANNOTATED_SUFFIX.matcher(
                        filename.substring(allowed.length() + 1)
                ).matches())
                .findFirst()
                .orElse(null);
        if (chipId == null) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(new ArchivedFabricImage(
                    filename,
                    chipId,
                    mimeType(filename),
                    path,
                    Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis()
            ));
        } catch (IOException e) {
            log.debug("[fabric-image] event=archive_stat_failed, imageId={}, errorType={}",
                    filename, e.getClass().getSimpleName());
            return java.util.Optional.empty();
        }
    }

    private String mimeType(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    public record ArchivedFabricImage(
            String imageId,
            String chipId,
            String mimeType,
            Path path,
            long lastModifiedMillis
    ) {
    }
}

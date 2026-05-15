package com.genius.smartlight.opsadmin;

import com.genius.smartlight.vo.ai.FabricArchiveDeleteRespVO;
import com.genius.smartlight.vo.ai.FabricArchiveItemRespVO;
import com.genius.smartlight.vo.ai.FabricArchivePageRespVO;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class OpsAdminGalleryService {

    private static final Path FABRIC_ARCHIVE_BASE_DIR = Path.of("/opt/smartlight/uploads/fabric");
    private static final String FABRIC_ARCHIVE_BASE_URL = "https://api.genius.show/uploads/fabric";
    private static final Set<String> FABRIC_ARCHIVE_TYPES = Set.of("original", "annotated", "combined");
    private static final Set<String> FABRIC_ARCHIVE_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png");
    private static final Pattern FABRIC_ARCHIVE_FILENAME_PATTERN =
            Pattern.compile("^(?<chipId>[A-Za-z0-9]{1,32})_(?<dateTime>\\d{8}_\\d{6})_(?<random>[A-Za-z0-9]{8})_(original|annotated|combined)\\.(jpg|jpeg|png)$");
    private static final Pattern FABRIC_ARCHIVE_DELETE_FILENAME_PATTERN =
            Pattern.compile("^(?<base>.*)_(original|annotated|combined)\\.(jpg|jpeg|png)$");
    private static final Pattern FABRIC_ARCHIVE_BASENAME_PATTERN =
            Pattern.compile("^[A-Za-z0-9_-]+$");

    public FabricArchivePageRespVO listImages(String type, int page, int pageSize) throws IOException {
        String normalizedType = FABRIC_ARCHIVE_TYPES.contains(type) ? type : "combined";
        int safePage = Math.max(page, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), 100);
        Path targetDir = FABRIC_ARCHIVE_BASE_DIR.resolve(normalizedType).normalize();

        List<FabricArchiveItemRespVO> items;
        if (!Files.isDirectory(targetDir)) {
            items = List.of();
        } else {
            try (Stream<Path> stream = Files.list(targetDir)) {
                items = stream
                        .filter(Files::isRegularFile)
                        .filter(this::isImageFile)
                        .map(path -> buildItem(path, normalizedType))
                        .sorted(Comparator.comparing(FabricArchiveItemRespVO::getFilename).reversed())
                        .toList();
            }
        }

        long total = items.size();
        int from = Math.min((safePage - 1) * safePageSize, items.size());
        int to = Math.min(from + safePageSize, items.size());

        FabricArchivePageRespVO resp = new FabricArchivePageRespVO();
        resp.setList(items.subList(from, to));
        resp.setTotal(total);
        resp.setPage(safePage);
        resp.setPageSize(safePageSize);
        return resp;
    }

    public FabricArchiveDeleteRespVO deleteImage(String filename, String baseName) throws IOException {
        String normalizedBaseName = baseName != null && !baseName.isBlank()
                ? baseName.trim()
                : extractBaseName(filename);

        FabricArchiveDeleteRespVO resp = new FabricArchiveDeleteRespVO();
        resp.setBaseName(normalizedBaseName == null ? "" : normalizedBaseName);
        resp.setDeletedFiles(new ArrayList<>());
        resp.setMissingFiles(new ArrayList<>());

        if (normalizedBaseName == null || normalizedBaseName.isBlank()
                || normalizedBaseName.contains("..")
                || normalizedBaseName.contains("/")
                || normalizedBaseName.contains("\\")
                || !FABRIC_ARCHIVE_BASENAME_PATTERN.matcher(normalizedBaseName).matches()) {
            resp.setSuccess(false);
            resp.setMsg("文件名不正确");
            return resp;
        }

        for (String archiveType : List.of("original", "annotated", "combined")) {
            for (String ext : List.of(".jpg", ".jpeg", ".png")) {
                Path candidate = FABRIC_ARCHIVE_BASE_DIR
                        .resolve(archiveType)
                        .resolve(normalizedBaseName + "_" + archiveType + ext)
                        .normalize();
                if (!candidate.startsWith(FABRIC_ARCHIVE_BASE_DIR)) {
                    resp.setSuccess(false);
                    resp.setMsg("路径不正确");
                    return resp;
                }
                if (!Files.exists(candidate)) {
                    resp.getMissingFiles().add(candidate.getFileName().toString());
                    continue;
                }
                Files.delete(candidate);
                resp.getDeletedFiles().add(candidate.getFileName().toString());
            }
        }

        resp.setDeletedCount(resp.getDeletedFiles().size());
        resp.setSuccess(resp.getDeletedCount() > 0);
        resp.setMsg(resp.getDeletedCount() > 0 ? "已删除" : "文件不存在");
        return resp;
    }

    private String extractBaseName(String filename) {
        if (filename == null || filename.isBlank()) return null;
        Matcher m = FABRIC_ARCHIVE_DELETE_FILENAME_PATTERN.matcher(filename.trim());
        return m.matches() ? m.group(1) : null;
    }

    private boolean isImageFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return FABRIC_ARCHIVE_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private FabricArchiveItemRespVO buildItem(Path path, String type) {
        String filename = path.getFileName().toString();
        Map<String, String> parsed = parseFilename(filename);

        FabricArchiveItemRespVO item = new FabricArchiveItemRespVO();
        item.setFilename(filename);
        item.setUrl(FABRIC_ARCHIVE_BASE_URL + "/" + type + "/" + filename);
        item.setType(type);
        item.setChipId(parsed.getOrDefault("chipId", ""));
        item.setCreateTime(parsed.getOrDefault("createTime", ""));
        try {
            item.setSize(Files.size(path));
        } catch (IOException e) {
            item.setSize(0L);
        }
        return item;
    }

    private Map<String, String> parseFilename(String filename) {
        Matcher m = FABRIC_ARCHIVE_FILENAME_PATTERN.matcher(filename);
        if (!m.matches()) return Map.of();
        return Map.of(
                "chipId", m.group("chipId"),
                "createTime", m.group("dateTime")
        );
    }
}

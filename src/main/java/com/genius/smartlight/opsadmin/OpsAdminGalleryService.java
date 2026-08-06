package com.genius.smartlight.opsadmin;

import com.genius.smartlight.service.ai.FabricArchiveService;
import com.genius.smartlight.vo.ai.FabricArchiveDeleteRespVO;
import com.genius.smartlight.vo.ai.FabricArchiveItemRespVO;
import com.genius.smartlight.vo.ai.FabricArchivePageRespVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 运营后台图库服务，委托 {@link FabricArchiveService} 完成文件操作，
 * 仅替换响应中的 URL 前缀为 /ops-admin/gallery/images/file，
 * 不做店铺级别的 chipId 权限校验。
 */
@Service
@RequiredArgsConstructor
public class OpsAdminGalleryService {

    private static final Path FABRIC_ARCHIVE_BASE_DIR = Path.of("/opt/smartlight/uploads/fabric");
    private static final Set<String> FABRIC_ARCHIVE_TYPES = Set.of("original", "annotated", "combined");
    private static final Set<String> FABRIC_ARCHIVE_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png");
    private static final Pattern FABRIC_ARCHIVE_BASENAME_PATTERN =
            Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final String OPS_URL_PREFIX = "/ops-admin/gallery/images/file";

    private final FabricArchiveService fabricArchiveService;

    public FabricArchivePageRespVO listImages(String type, int page, int pageSize) throws IOException {
        return listImages(type, page, pageSize, null);
    }

    public FabricArchivePageRespVO listImages(String type, int page, int pageSize, String opsToken) throws IOException {
        return listImages(type, page, pageSize, opsToken, null);
    }

    public FabricArchivePageRespVO listImages(
            String type,
            int page,
            int pageSize,
            String opsToken,
            String publicBaseUrl) throws IOException {
        String normalizedType = FABRIC_ARCHIVE_TYPES.contains(type) ? type : "combined";
        int safePage = Math.max(page, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), 100);
        Path targetDir = FABRIC_ARCHIVE_BASE_DIR.resolve(normalizedType).normalize();

        List<FabricArchiveItemRespVO> items;
        if (!Files.isDirectory(targetDir)) {
            items = List.of();
        } else {
            try (Stream<Path> stream = Files.list(targetDir)) {
                List<FabricArchiveItemRespVO> list = stream
                        .filter(Files::isRegularFile)
                        .filter(this::isImageFile)
                        .map(path -> fabricArchiveService.buildArchiveItem(path, normalizedType, OPS_URL_PREFIX))
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
                list.sort(Comparator.comparing(FabricArchiveItemRespVO::getFilename).reversed());
                items = list;
            }
        }
        decorateImageUrls(items, opsToken, publicBaseUrl);

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

    private void decorateImageUrls(List<FabricArchiveItemRespVO> items, String opsToken, String publicBaseUrl) {
        String baseUrl = normalizeBaseUrl(publicBaseUrl);
        String encodedToken = opsToken == null || opsToken.isBlank()
                ? null
                : URLEncoder.encode(opsToken.trim(), StandardCharsets.UTF_8);
        for (FabricArchiveItemRespVO item : items) {
            String url = item.getUrl();
            if (url != null && !url.isBlank()) {
                if (baseUrl != null && url.startsWith("/")) {
                    url = baseUrl + url;
                }
                if (encodedToken != null) {
                    url = url + (url.contains("?") ? "&" : "?") + "token=" + encodedToken;
                }
                item.setUrl(url);
            }
        }
    }

    private String normalizeBaseUrl(String publicBaseUrl) {
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            return null;
        }
        String value = publicBaseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.isBlank() ? null : value;
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

    public Path getImageFile(String type, String filename) {
        String normalizedType = FABRIC_ARCHIVE_TYPES.contains(type) ? type : "combined";
        if (filename == null || filename.isBlank()
                || filename.contains("..")
                || filename.contains("/")
                || filename.contains("\\")) {
            throw new IllegalArgumentException("Invalid archive filename");
        }
        Path target = FABRIC_ARCHIVE_BASE_DIR
                .resolve(normalizedType)
                .resolve(filename.trim())
                .normalize();
        if (!target.startsWith(FABRIC_ARCHIVE_BASE_DIR)
                || !Files.isRegularFile(target)
                || !isImageFile(target)) {
            throw new IllegalArgumentException("Archive file not found");
        }
        return target;
    }

    private String extractBaseName(String filename) {
        if (filename == null || filename.isBlank()) return null;
        java.util.regex.Matcher m = Pattern.compile(
                "^(?<base>.*)_(original|annotated|combined)\\.(jpg|jpeg|png)$"
        ).matcher(filename.trim());
        return m.matches() ? m.group(1) : null;
    }

    private boolean isImageFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return FABRIC_ARCHIVE_EXTENSIONS.stream().anyMatch(name::endsWith);
    }
}

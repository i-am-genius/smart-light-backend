package com.genius.smartlight.opsadmin;

import com.genius.smartlight.common.CommonResult;
import com.genius.smartlight.common.FileDownloadUtil;
import com.genius.smartlight.common.MediaTypeUtil;
import com.genius.smartlight.vo.ai.FabricArchiveDeleteRespVO;
import com.genius.smartlight.vo.ai.FabricArchivePageRespVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Path;

@RestController
@RequestMapping("/ops-admin/gallery")
@RequiredArgsConstructor
public class OpsAdminGalleryController {

    private final OpsAdminGalleryService galleryService;

    @Value("${app.public-base-url:}")
    private String publicBaseUrl;

    @GetMapping("/images")
    public CommonResult<FabricArchivePageRespVO> listImages(
            @RequestParam(defaultValue = "combined") String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "30") int pageSize,
            HttpServletRequest request) throws IOException {
        return CommonResult.success(galleryService.listImages(
                type,
                page,
                pageSize,
                resolveBearerToken(request),
                resolvePublicBaseUrl(request)
        ));
    }

    @DeleteMapping("/images")
    public CommonResult<FabricArchiveDeleteRespVO> deleteImage(
            @RequestParam(required = false) String filename,
            @RequestParam(required = false) String baseName) throws IOException {
        return CommonResult.success(galleryService.deleteImage(filename, baseName));
    }

    @GetMapping("/images/file")
    public ResponseEntity<Resource> imageFile(
            @RequestParam(defaultValue = "combined") String type,
            @RequestParam String filename) {
        Path file = galleryService.getImageFile(type, filename);
        return FileDownloadUtil.inlineFile(file, MediaTypeUtil.resolveImageMediaType(file.getFileName().toString()));
    }

    private MediaType resolveImageMediaType(String filename) {
        return MediaTypeUtil.resolveImageMediaType(filename);
    }

    private String resolveBearerToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return null;
    }

    private String resolvePublicBaseUrl(HttpServletRequest request) {
        if (publicBaseUrl != null && !publicBaseUrl.isBlank()) {
            return publicBaseUrl.trim();
        }
        String proto = firstHeaderValue(request.getHeader("X-Forwarded-Proto"));
        if (proto == null || proto.isBlank()) {
            proto = request.getScheme();
        }
        String host = firstHeaderValue(request.getHeader("X-Forwarded-Host"));
        if (host == null || host.isBlank()) {
            host = request.getHeader("Host");
        }
        if (host == null || host.isBlank()) {
            host = request.getServerName();
            int port = request.getServerPort();
            if (port > 0 && !("http".equals(proto) && port == 80) && !("https".equals(proto) && port == 443)) {
                host = host + ":" + port;
            }
        }
        return proto + "://" + host;
    }

    private String firstHeaderValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        int comma = value.indexOf(',');
        return comma >= 0 ? value.substring(0, comma).trim() : value.trim();
    }
}

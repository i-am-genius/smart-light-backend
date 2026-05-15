package com.genius.smartlight.controller.admin.device;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
public class OtaFirmwareDownloadController {

    private static final Path OTA_BASE_DIR = Path.of("data", "ota")
            .toAbsolutePath()
            .normalize();

    @GetMapping("/ota/**")
    public ResponseEntity<Resource> download(HttpServletRequest request) {
        String requestPath = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && requestPath.startsWith(contextPath)) {
            requestPath = requestPath.substring(contextPath.length());
        }

        String relativePath = requestPath.substring("/ota/".length());
        relativePath = UriUtils.decode(relativePath, StandardCharsets.UTF_8);

        Path target = OTA_BASE_DIR.resolve(relativePath).normalize();
        if (!target.startsWith(OTA_BASE_DIR) || !Files.isRegularFile(target)) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(target);
        String filename = target.getFileName().toString();

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                .body(resource);
    }
}

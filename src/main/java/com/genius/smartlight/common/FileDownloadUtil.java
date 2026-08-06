package com.genius.smartlight.common;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * 文件下载响应构建工具类。
 */
public final class FileDownloadUtil {

    private FileDownloadUtil() {
    }

    /**
     * 构建文件下载 ResponseEntity（Content-Disposition: inline）。
     */
    public static ResponseEntity<Resource> inlineFile(Path file, MediaType mediaType) {
        return buildResponse(file, mediaType, true);
    }

    /**
     * 构建文件下载 ResponseEntity（Content-Disposition: attachment）。
     */
    public static ResponseEntity<Resource> attachmentFile(Path file, MediaType mediaType) {
        return buildResponse(file, mediaType, false);
    }

    private static ResponseEntity<Resource> buildResponse(Path file, MediaType mediaType, boolean inline) {
        Resource resource = new FileSystemResource(file);
        String filename = file.getFileName().toString();
        ContentDisposition disposition = inline
                ? ContentDisposition.inline().filename(filename, StandardCharsets.UTF_8).build()
                : ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(resource);
    }
}

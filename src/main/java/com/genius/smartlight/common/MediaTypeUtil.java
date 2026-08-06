package com.genius.smartlight.common;

import org.springframework.http.MediaType;

/**
 * 媒体类型工具类。
 */
public final class MediaTypeUtil {

    private MediaTypeUtil() {
    }

    /**
     * 根据文件名后缀解析图片 MediaType，默认返回 JPEG。
     */
    public static MediaType resolveImageMediaType(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase();
        if (lower.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (lower.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        return MediaType.IMAGE_JPEG;
    }
}

package com.genius.smartlight.common;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

public class RequestLogUtils {

    private static final String[] IP_HEADERS = {
        "X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP",
        "WL-Proxy-Client-IP", "HTTP_X_FORWARDED_FOR"
    };

    private static final String[] SENSITIVE_KEYS = {
        "token", "access_token", "refresh_token", "authorization",
        "password", "passwd", "pwd", "secret", "apiKey", "api_key",
        "appid", "key", "jwt"
    };

    public static String getClientIp(HttpServletRequest request) {
        for (String header : IP_HEADERS) {
            String ip = request.getHeader(header);
            if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
                int comma = ip.indexOf(',');
                return comma > 0 ? ip.substring(0, comma).trim() : ip.trim();
            }
        }
        return request.getRemoteAddr();
    }

    public static String sanitizeQueryString(String query) {
        if (query == null || query.isBlank()) return "";
        String s = query;
        for (String key : SENSITIVE_KEYS) {
            s = s.replaceAll("(?i)[?&]" + key + "=[^&]*", "&" + key + "=****");
        }
        if (s.startsWith("&")) s = "?" + s.substring(1);
        if (s.length() > 500) s = s.substring(0, 500) + "...";
        return s;
    }

    public static String truncate(String s, int maxLen) {
        if (s == null) return "unknown";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    public static String logContext(HttpServletRequest request) {
        return String.format("clientIp=%s, method=%s, uri=%s, query=%s, userAgent=%s",
                getClientIp(request),
                request.getMethod(),
                request.getRequestURI(),
                sanitizeQueryString(request.getQueryString()),
                truncate(request.getHeader("User-Agent"), 200));
    }

    public static String generateRequestId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}

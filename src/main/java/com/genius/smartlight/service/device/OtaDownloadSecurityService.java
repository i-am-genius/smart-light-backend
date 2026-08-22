package com.genius.smartlight.service.device;

import com.genius.smartlight.common.ServiceException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class OtaDownloadSecurityService {

    public static final Path OTA_BASE_DIR = Path.of("data", "ota")
            .toAbsolutePath()
            .normalize();

    private static final Pattern OTA_RELATIVE_PATH_PATTERN = Pattern.compile(
            "^(lamp|cam|camlamp|cam_capture)/(stable|test)/[1-9][0-9]*/firmware\\.bin$"
    );
    private static final Pattern HOST_PATTERN = Pattern.compile("^[A-Za-z0-9.-]+(?::[0-9]{1,5})?$");
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final Environment environment;

    private String signingSecret;

    @PostConstruct
    public void init() {
        signingSecret = firstNonBlank(
                environment.getProperty("ota.download.secret"),
                environment.getProperty("jwt.secret")
        );
        if (signingSecret == null || signingSecret.length() < 32) {
            throw new IllegalStateException("ota.download.secret or jwt.secret must be at least 32 characters");
        }
        if (environment.getProperty("ota.download.secret") == null
                || environment.getProperty("ota.download.secret").isBlank()) {
            log.warn("ota.download.secret is not configured; OTA download signing falls back to jwt.secret");
        }
    }

    public String buildRelativePath(String deviceType, String channel, Integer versionCode) {
        if (versionCode == null || versionCode <= 0) {
            throw new ServiceException("Invalid OTA versionCode");
        }
        return normalizeRelativePath(deviceType + "/" + channel + "/" + versionCode + "/firmware.bin");
    }

    public Path resolveDownloadPath(String relativePath) {
        String normalized = normalizeRelativePath(relativePath);
        Path target = OTA_BASE_DIR.resolve(normalized).normalize();
        if (!target.startsWith(OTA_BASE_DIR)) {
            throw new ServiceException("Invalid OTA path");
        }
        return target;
    }

    public String buildStoredFileUrl(String requestHost, String deviceType, String channel, Integer versionCode) {
        String relativePath = buildRelativePath(deviceType, channel, versionCode);
        return resolvePublicBaseUrl(requestHost) + "/ota/" + relativePath;
    }

    public String signDownloadUrl(String fileUrl) {
        String relativePath = extractRelativePathFromUrl(fileUrl);
        String baseUrl = resolveBaseUrlFromConfiguredOrStoredUrl(fileUrl);
        return baseUrl + "/ota/" + relativePath + "?token=" + sign(relativePath);
    }

    public String normalizeRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new ServiceException("OTA path is empty");
        }
        String value = UriUtils.decode(relativePath.trim(), StandardCharsets.UTF_8)
                .replace('\\', '/');
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        if (value.startsWith("ota/")) {
            value = value.substring("ota/".length());
        }
        if (value.contains("?")) {
            value = value.substring(0, value.indexOf('?'));
        }
        Path normalizedPath = Path.of(value).normalize();
        String normalized = normalizedPath.toString().replace('\\', '/');
        if (!OTA_RELATIVE_PATH_PATTERN.matcher(normalized).matches()) {
            throw new ServiceException("Invalid OTA firmware path");
        }
        return normalized;
    }

    public boolean isTokenValid(String relativePath, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String expected = sign(normalizeRelativePath(relativePath));
        return constantTimeEquals(expected, token);
    }

    /**
     * 使用已规范化的路径验证 token，避免重复 normalizeRelativePath。
     */
    public boolean isTokenValidForNormalized(String normalizedRelativePath, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String expected = sign(normalizedRelativePath);
        return constantTimeEquals(expected, token);
    }

    private boolean constantTimeEquals(String expected, String token) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                token.trim().getBytes(StandardCharsets.US_ASCII)
        );
    }

    public String extractRelativePathFromUrl(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) {
            throw new ServiceException("OTA fileUrl is empty");
        }
        try {
            URI uri = URI.create(fileUrl.trim());
            validateHttpUri(uri);
            String path = uri.getPath();
            if (path == null || !path.startsWith("/ota/")) {
                throw new ServiceException("OTA fileUrl must point to /ota/");
            }
            return normalizeRelativePath(path.substring("/ota/".length()));
        } catch (IllegalArgumentException e) {
            throw new ServiceException("Invalid OTA fileUrl");
        }
    }

    public void validateStoredFileUrl(String fileUrl) {
        extractRelativePathFromUrl(fileUrl);
    }

    private String sign(String relativePath) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(relativePath.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new ServiceException("Cannot sign OTA download URL");
        }
    }

    private String resolveBaseUrlFromConfiguredOrStoredUrl(String fileUrl) {
        String configured = normalizeConfiguredPublicBaseUrl();
        if (configured != null) {
            return configured;
        }
        URI uri = URI.create(fileUrl.trim());
        validateHttpUri(uri);
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority();
    }

    private String resolvePublicBaseUrl(String requestHost) {
        String configured = normalizeConfiguredPublicBaseUrl();
        if (configured != null) {
            return configured;
        }

        String host = requestHost == null ? "" : requestHost.trim();
        if (!HOST_PATTERN.matcher(host).matches()) {
            throw new ServiceException("Invalid Host for OTA download URL");
        }

        String hostOnly = host;
        int colonIndex = hostOnly.indexOf(':');
        if (colonIndex > 0) {
            hostOnly = hostOnly.substring(0, colonIndex);
        }
        rejectUnsafeHost(hostOnly);
        return "http://" + host;
    }

    private String normalizeConfiguredPublicBaseUrl() {
        String value = environment.getProperty("app.public-base-url");
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        URI uri = URI.create(trimmed);
        validateHttpUri(uri);
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new ServiceException("app.public-base-url must not contain query or fragment");
        }
        return trimmed;
    }

    private void validateHttpUri(URI uri) {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new ServiceException("OTA fileUrl must use http or https");
        }
        if (host == null || host.isBlank()) {
            throw new ServiceException("OTA fileUrl host is empty");
        }
        rejectUnsafeHost(host);
    }

    private void rejectUnsafeHost(String host) {
        String lowerHost = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(lowerHost)
                || "127.0.0.1".equals(lowerHost)
                || "0.0.0.0".equals(lowerHost)
                || "::1".equals(lowerHost)
                || "0:0:0:0:0:0:0:1".equals(lowerHost)) {
            throw new ServiceException("OTA download URL host is not reachable by devices");
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}

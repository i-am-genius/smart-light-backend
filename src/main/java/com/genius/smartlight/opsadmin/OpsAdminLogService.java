package com.genius.smartlight.opsadmin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@Service
public class OpsAdminLogService {

    private static final String LOG_DIR = "/opt/smartlight/logs/";

    private static final Map<String, String> TYPE_FILE_MAP = Map.of(
            "important", "backend-important.log",
            "ws", "backend-ws.log",
            "error", "backend-error.log"
    );

    private static final Set<String> ALLOWED_LEVELS = Set.of("ALL", "INFO", "WARN", "ERROR", "DEBUG");

    private static final Map<String, List<String>> MODULE_KEYWORDS = Map.of(
            "websocket", List.of("websocket", ".w.", "session", "WebSocket", "AppWebSocket"),
            "device", List.of("device", "Device", "otaStatus", "chipId"),
            "ai", List.of("ai", "fabric", "person", "recognize", "AiController"),
            "ota", List.of("ota", "firmware", "versionCode", "firmwareVersion"),
            "wave", List.of("wave", "lightEffect", "LightEffect"),
            "auth", List.of("auth", "security", "jwt", "token", "OpsAdminAuth", "JwtToken", "login", "AuthController"),
            "lux", List.of("lux", "light"),
            "weather", List.of("weather")
    );

    private static final int MIN_LINES = 1;
    private static final int MAX_LINES = 1000;
    private static final int DEFAULT_LINES = 200;
    private static final int MAX_KEYWORD_LEN = 100;

    public Map<String, Object> tail(String type, int rawLines, String level, String keyword, String module) {
        int lines = Math.max(MIN_LINES, Math.min(MAX_LINES, rawLines > 0 ? rawLines : DEFAULT_LINES));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        result.put("file", TYPE_FILE_MAP.getOrDefault(type, "unknown"));
        result.put("lines", lines);
        result.put("level", level != null ? level : "ALL");
        result.put("keyword", keyword != null ? keyword : "");
        result.put("module", module != null ? module : "ALL");

        if (!TYPE_FILE_MAP.containsKey(type)) {
            result.put("content", "无效的日志类型: " + type);
            return result;
        }

        String filePath = LOG_DIR + TYPE_FILE_MAP.get(type);

        try {
            String content = readLastLines(filePath, lines);
            content = applyFilters(content, level, keyword, module);
            result.put("content", content.isEmpty() ? "暂无日志" : content);
        } catch (IOException e) {
            log.warn("[ops-admin] Failed to read log file: {} (type={})", TYPE_FILE_MAP.get(type), type);
            result.put("content", "日志文件不可用");
        }

        return result;
    }

    private String applyFilters(String content, String level, String keyword, String module) {
        if (content.isEmpty()) return content;

        String[] rawLines = content.split("\n");
        List<String> filtered = new ArrayList<>(Arrays.asList(rawLines));

        if (level != null && !"ALL".equals(level) && ALLOWED_LEVELS.contains(level)) {
            String marker = " " + level + " ";
            filtered = filtered.stream().filter(line -> line.contains(marker)).toList();
        }

        if (module != null && !"ALL".equals(module) && MODULE_KEYWORDS.containsKey(module)) {
            List<String> keys = MODULE_KEYWORDS.get(module);
            filtered = filtered.stream()
                    .filter(line -> keys.stream().anyMatch(k -> line.toLowerCase(Locale.ROOT).contains(k)))
                    .toList();
        }

        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.length() > MAX_KEYWORD_LEN
                    ? keyword.substring(0, MAX_KEYWORD_LEN)
                    : keyword;
            String lowerKw = kw.toLowerCase(Locale.ROOT);
            filtered = filtered.stream()
                    .filter(line -> line.toLowerCase(Locale.ROOT).contains(lowerKw))
                    .toList();
        }

        return String.join("\n", filtered);
    }

    private String readLastLines(String filePath, int maxLines) throws IOException {
        Deque<String> deque = new ArrayDeque<>(maxLines);

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (deque.size() >= maxLines) {
                    deque.removeFirst();
                }
                deque.addLast(line);
            }
        } catch (java.io.FileNotFoundException e) {
            return "";
        }

        return String.join("\n", deque);
    }
}

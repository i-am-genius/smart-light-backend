package com.genius.smartlight.opsadmin;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@Service
public class OpsAdminLogReadService {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern LOG_START = Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} ");
    private static final Pattern LOG_LEVEL_PATTERN = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+\\[[^\\]]*\\]\\s+(ERROR|WARN|INFO|DEBUG|TRACE)\\b");
    private static final Pattern EXCEPTION_CONTINUATION = Pattern.compile(
            "^(Caused by|\\s+at |\\t|Suppressed:|\\s+\\.\\.\\. \\d+ (more|common frames)|[\\w.]+Exception|\\s+\\[\\w+\\])"
    );
    private static final Set<String> PRIORITY_MARKERS = Set.of(
            "ERROR", "WARN", "Exception", "Caused by", "Failed", "Timeout",
            "Denied", "Unauthorized", "SQLSyntaxErrorException", "Error",
            "Refused", "timed out", "OutOfMemoryError", "TEXT_PARTIAL_WRITING",
            "broadcastToStore failed", "The remote endpoint was in state",
            "invalid state for called method", "sendMessage"
    );

    private final Map<String, String> logTypeToPath = new LinkedHashMap<>();
    private final Set<String> allowedTypes = new HashSet<>();

    @PostConstruct
    public void init() {
        add("backend", envOrProp("OPS_LOG_BACKEND_PATH", "/opt/smartlight/logs/backend-important.log"));
        add("backend-error", envOrProp("OPS_LOG_BACKEND_ERROR_PATH", "/opt/smartlight/logs/backend-error.log"));
        add("backend-ws", envOrProp("OPS_LOG_BACKEND_WS_PATH", "/opt/smartlight/logs/backend-ws.log"));
        add("fabric-ai", envOrProp("OPS_LOG_FABRIC_AI_PATH", "/opt/smartlight/logs/fabric-ai.log"));
        add("fabric-ai-error", envOrProp("OPS_LOG_FABRIC_AI_ERROR_PATH", "/opt/smartlight/logs/fabric-ai-error.log"));
        add("nginx-error", envOrProp("OPS_LOG_NGINX_ERROR_PATH", "/var/log/nginx/error.log"));
        add("nginx-access", envOrProp("OPS_LOG_NGINX_ACCESS_PATH", "/var/log/nginx/access.log"));
        add("security", resolveSecurityPath());
        add("mysql-error", resolveMysqlPath());
        add("backend-service", "/dev/null");  // systemd type — read via journalctl, not file
        add("fabric-ai-service", "/dev/null"); // systemd type — read via journalctl, not file
        log.info("[ops-admin] Log whitelist configured: {}", logTypeToPath.keySet());
    }

    private String resolveSecurityPath() {
        String env = envOrProp("OPS_LOG_SECURITY_PATH", null);
        if (env != null && !env.isBlank()) return env.trim();
        if (new java.io.File("/var/log/secure").exists()) return "/var/log/secure";
        if (new java.io.File("/var/log/auth.log").exists()) return "/var/log/auth.log";
        return "/var/log/secure"; // default
    }

    private String resolveMysqlPath() {
        String env = envOrProp("OPS_LOG_MYSQL_ERROR_PATH", null);
        if (env != null && !env.isBlank()) return env.trim();
        if (new java.io.File("/var/log/mysql/mysqld.log").exists()) return "/var/log/mysql/mysqld.log";
        if (new java.io.File("/var/log/mysqld.log").exists()) return "/var/log/mysqld.log";
        if (new java.io.File("/var/log/mysql/error.log").exists()) return "/var/log/mysql/error.log";
        if (new java.io.File("/var/log/mariadb/mariadb.log").exists()) return "/var/log/mariadb/mariadb.log";
        return "/var/log/mysql/mysqld.log"; // default (most common)
    }

    private void add(String type, String path) {
        logTypeToPath.put(type, path);
        allowedTypes.add(type);
    }

    private static String envOrProp(String key, String fallback) {
        String v = System.getenv(key);
        if (v != null && !v.isBlank()) return v.trim();
        v = System.getProperty(key);
        if (v != null && !v.isBlank()) return v.trim();
        return fallback;
    }

    public boolean isAllowedType(String logType) {
        return allowedTypes.contains(logType);
    }

    private static final int MAX_VISIBLE_CHARS = 200 * 1024;

    public LogReadResult readFromVisibleLogs(List<String> visibleLogs, LogReadRequest req) {
        LogReadResult result = new LogReadResult();
        if (visibleLogs == null || visibleLogs.isEmpty()) return result;

        int maxLines = Math.min(req.getMaxLines(), 2000);
        if (maxLines < 1) maxLines = 500;

        Set<String> levels = req.getLevels() != null && !req.getLevels().isEmpty()
                ? new HashSet<>(req.getLevels().stream().map(String::toUpperCase).toList())
                : Collections.emptySet();
        String kw = req.getKeyword() != null ? req.getKeyword().toLowerCase(Locale.ROOT) : null;
        OpsAdminLogSanitizer sanitizer = req.getSanitizer();

        // Group into events first, then filter entire events by level
        List<OpsAdminLogService.LogEvent> events = OpsAdminLogService.groupLogEvents(visibleLogs);

        List<String> merged = new ArrayList<>();
        boolean truncated = false;
        int eventCount = 0;

        for (OpsAdminLogService.LogEvent ev : events) {
            if (merged.size() >= maxLines) { truncated = true; break; }
            // Filter by level: keep entire event (including stacks) if event level matches
            if (!levels.isEmpty() && !levels.contains(ev.level != null ? ev.level : "INFO")) continue;
            // Filter by keyword: keep event if any line matches
            if (kw != null && !kw.isEmpty()) {
                boolean matches = false;
                for (String l : ev.lines) {
                    if (l.toLowerCase(Locale.ROOT).contains(kw)) { matches = true; break; }
                }
                if (!matches) continue;
            }
            eventCount++;
            for (String line : ev.lines) {
                if (merged.size() >= maxLines) { truncated = true; break; }
                String s = sanitizer != null ? sanitizer.sanitize(line) : line;
                merged.add(s);
            }
        }

        result.lines = merged;
        result.truncated = truncated;
        result.analyzedLineCount = merged.size();
        result.analyzedEventCount = eventCount;
        return result;
    }

    public LogReadResult read(LogReadRequest req) {
        LogReadResult result = new LogReadResult();
        String path = logTypeToPath.get(req.getLogType());
        File file = new File(path);
        if (!file.exists()) {
            log.warn("[ops-admin] Log file not found: {}", path);
            return result;
        }

        LocalDateTime start = parseTime(req.getStartTime());
        LocalDateTime end = parseTime(req.getEndTime());
        Set<String> levels = req.getLevels() != null && !req.getLevels().isEmpty()
                ? new HashSet<>(req.getLevels().stream().map(String::toUpperCase).toList())
                : Collections.emptySet();
        String kw = req.getKeyword() != null ? req.getKeyword().toLowerCase(Locale.ROOT) : null;
        OpsAdminLogSanitizer sanitizer = req.getSanitizer();

        List<String> allLines = new ArrayList<>();
        List<String> priorityLines = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
            String line;
            boolean inExceptionBlock = false;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;

                boolean isNewEntry = LOG_START.matcher(line).find();
                if (isNewEntry) {
                    inExceptionBlock = false;
                    if (!timeMatches(line, start, end)) continue;
                    if (!levelMatches(line, levels)) continue;
                    if (!keywordMatches(line, kw)) continue;
                } else if (!inExceptionBlock) {
                    if (!EXCEPTION_CONTINUATION.matcher(line).find()) continue;
                    inExceptionBlock = true;
                }

                String sanitized = sanitizer != null ? sanitizer.sanitize(line) : line;
                if (isPriorityLine(line)) {
                    priorityLines.add(sanitized);
                } else {
                    allLines.add(sanitized);
                }
            }
        } catch (java.io.FileNotFoundException e) {
            log.warn("[ops-admin] Log file not found: {}", path);
            return result;
        } catch (Exception e) {
            log.error("[ops-admin] Failed to read log: {}", path, e);
            return result;
        }

        int maxLines = req.getMaxLines();
        List<String> merged = new ArrayList<>(priorityLines);
        int remaining = maxLines - merged.size();
        if (remaining > 0) {
            int take = Math.min(remaining, allLines.size());
            merged.addAll(allLines.subList(0, take));
        } else if (merged.size() > maxLines) {
            merged = merged.subList(0, maxLines);
        }

        int total = priorityLines.size() + allLines.size();
        result.lines = merged;
        result.truncated = total > maxLines;
        result.analyzedLineCount = merged.size();
        return result;
    }

    private boolean timeMatches(String line, LocalDateTime start, LocalDateTime end) {
        if (start == null && end == null) return true;
        try {
            String ts = line.substring(0, 19);
            LocalDateTime t = LocalDateTime.parse(ts, DT_FMT);
            if (start != null && t.isBefore(start)) return false;
            if (end != null && t.isAfter(end)) return false;
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    private boolean levelMatches(String line, Set<String> levels) {
        if (levels.isEmpty()) return true;
        java.util.regex.Matcher m = LOG_LEVEL_PATTERN.matcher(line);
        return m.find() && levels.contains(m.group(1));
    }

    private boolean keywordMatches(String line, String keyword) {
        if (keyword == null || keyword.isEmpty()) return true;
        return line.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private boolean isPriorityLine(String line) {
        for (String marker : PRIORITY_MARKERS) {
            if (line.contains(marker)) return true;
        }
        return false;
    }

    private LocalDateTime parseTime(String time) {
        if (time == null || time.isBlank()) return null;
        try {
            return LocalDateTime.parse(time, DT_FMT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public static class LogReadRequest {
        private String logType;
        private String startTime;
        private String endTime;
        private List<String> levels;
        private String keyword;
        private int maxLines = 500;
        private OpsAdminLogSanitizer sanitizer;

        public String getLogType() { return logType; }
        public void setLogType(String v) { this.logType = v; }
        public String getStartTime() { return startTime; }
        public void setStartTime(String v) { this.startTime = v; }
        public String getEndTime() { return endTime; }
        public void setEndTime(String v) { this.endTime = v; }
        public List<String> getLevels() { return levels; }
        public void setLevels(List<String> v) { this.levels = v; }
        public String getKeyword() { return keyword; }
        public void setKeyword(String v) { this.keyword = v; }
        public int getMaxLines() { return maxLines; }
        public void setMaxLines(int v) { this.maxLines = v; }
        public OpsAdminLogSanitizer getSanitizer() { return sanitizer; }
        public void setSanitizer(OpsAdminLogSanitizer v) { this.sanitizer = v; }
    }

    public static class LogReadResult {
        public List<String> lines = Collections.emptyList();
        public boolean truncated;
        public int analyzedLineCount;
        public int analyzedEventCount;
    }
}

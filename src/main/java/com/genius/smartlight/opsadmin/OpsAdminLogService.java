package com.genius.smartlight.opsadmin;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

@Slf4j
@Service
public class OpsAdminLogService {

    private static final String LOG_DIR = "/opt/smartlight/logs/";
    private static final String ARCHIVE_DIR = "/opt/smartlight/logs/archive/";

    private final Map<String, String> typeFilePath = new LinkedHashMap<>();
    private final Map<String, String> systemdServiceMap = new LinkedHashMap<>();
    private final Set<String> allTypes = new HashSet<>();

    private final OpsAdminLogSanitizer sanitizer;

    public OpsAdminLogService(OpsAdminLogSanitizer sanitizer) {
        this.sanitizer = sanitizer;
    }

    @PostConstruct
    public void init() {
        // --- File-based log types ---
        addFileType("important", "backend-important.log");
        addFileType("ws", "backend-ws.log");
        addFileType("error", "backend-error.log");
        addFileType("nginx-access", "/var/log/nginx/access.log");
        addFileType("nginx-error", "/var/log/nginx/error.log");
        addFileType("fabric-ai", "/opt/smartlight/logs/fabric-ai.log");
        addFileType("fabric-ai-error", "/opt/smartlight/logs/fabric-ai-error.log");

        // Security: try /var/log/secure first, fallback to /var/log/auth.log
        addFileType("security", resolveExistingPath(
                envOrProp("OPS_LOG_SECURITY_PATH", null),
                "/var/log/secure",
                "/var/log/auth.log"
        ));

        // MySQL: env override > auto-detect among known paths > journal fallback
        addFileType("mysql-error", resolveExistingPath(
                envOrProp("OPS_LOG_MYSQL_ERROR_PATH", null),
                "/var/log/mysql/mysqld.log",
                "/var/log/mysqld.log",
                "/var/log/mysql/error.log",
                "/var/log/mariadb/mariadb.log"
        ));

        // --- Systemd journal types (whitelisted services only) ---
        addSystemdType("backend-service",
                envOrProp("OPS_LOG_BACKEND_SERVICE_NAME", "smartlight-backend.service"));
        addSystemdType("fabric-ai-service",
                envOrProp("OPS_LOG_FABRIC_AI_SERVICE_NAME", "smartlight-fabric-ai.service"));

        log.info("[ops-admin] Log types configured — files: {}, systemd: {}",
                typeFilePath.keySet(), systemdServiceMap.keySet());
    }

    private void addFileType(String type, String path) {
        typeFilePath.put(type, path);
        allTypes.add(type);
    }

    private void addSystemdType(String type, String serviceName) {
        systemdServiceMap.put(type, serviceName);
        allTypes.add(type);
    }

    /**
     * Resolve which file path exists among candidates.
     * If envOverride is set (non-null), use it directly.
     * Otherwise try candidates in order and return the first that exists.
     * If none exist, return the first candidate as default (will show friendly error at read time).
     */
    private String resolveExistingPath(String envOverride, String... candidates) {
        if (envOverride != null && !envOverride.isBlank()) {
            return envOverride.trim();
        }
        for (String path : candidates) {
            if (new java.io.File(path).exists()) {
                return path;
            }
        }
        return candidates[0]; // default: first candidate (will produce clear error if missing)
    }

    private static String envOrProp(String key, String fallback) {
        String v = System.getenv(key);
        if (v != null && !v.isBlank()) return v.trim();
        v = System.getProperty(key);
        if (v != null && !v.isBlank()) return v.trim();
        return fallback;
    }

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

    private static final Pattern LOG_DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
    private static final Pattern LOG_LEVEL_PATTERN = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+\\[[^\\]]*\\]\\s+(ERROR|WARN|INFO|DEBUG|TRACE)\\b");

    private static final int MIN_LINES = 1;
    private static final int MAX_LINES = 10000;
    private static final int ALL_LINES_CAP = 10000;
    private static final int MAX_KEYWORD_LEN = 100;

    // Pattern to validate systemd service names: alphanumeric, dots, dashes, underscores, @
    private static final Pattern SERVICE_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9.@_-]*\\.service$");

    public Map<String, Object> tail(String type, int rawLines, String level, String keyword, String module, String date) {
        int lines;
        if (rawLines <= 0) {
            lines = ALL_LINES_CAP;
        } else {
            lines = Math.max(MIN_LINES, Math.min(MAX_LINES, rawLines));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        result.put("lines", lines);
        result.put("level", level != null ? level : "ALL");
        result.put("keyword", keyword != null ? keyword : "");
        result.put("module", module != null ? module : "ALL");

        if (!allTypes.contains(type)) {
            result.put("content", "无效的日志类型: " + type);
            return result;
        }

        // --- Systemd journal types ---
        if (systemdServiceMap.containsKey(type)) {
            String serviceName = systemdServiceMap.get(type);
            result.put("file", "journalctl -u " + serviceName);
            result.put("archive", false);
            try {
                String content = readJournalCtl(serviceName, lines);
                content = sanitizeContent(content);
                content = filterHealthCheckLines(type, content);
                content = applyFilters(content, level, keyword, module);
                result.put("content", content.isEmpty() ? "暂无日志" : content);
            } catch (Exception e) {
                log.warn("[ops-admin] Failed to read journalctl for: {} (type={})", serviceName, type, e);
                result.put("content", "无法读取 systemd 日志: " + e.getMessage());
            }
            return result;
        }

        // --- File-based types ---
        String baseName = typeFilePath.get(type);
        boolean hasDate = date != null && !date.isBlank() && date.matches("\\d{4}-\\d{2}-\\d{2}");
        boolean isAbsolutePath = baseName.startsWith("/");

        if (hasDate && !isAbsolutePath) {
            result.put("file", baseName + " (date " + date + ")");
            result.put("date", date);
            result.put("archive", true);
            try {
                List<String> allLines = readLogsForDate(baseName, date);
                int from = Math.max(0, allLines.size() - lines);
                String content = String.join("\n", allLines.subList(from, allLines.size()));
                content = sanitizeContent(content);
                content = filterHealthCheckLines(type, content);
                content = applyFilters(content, level, keyword, module);
                result.put("content", content.isEmpty() ? "该日期暂无日志" : content);
            } catch (Exception e) {
                log.warn("[ops-admin] Failed to read logs for date: {}/{} (type={})", date, baseName, type, e);
                result.put("content", "日志读取失败");
            }
        } else {
            result.put("file", baseName);
            result.put("archive", false);
            String filePath = isAbsolutePath ? baseName : LOG_DIR + baseName;
            try {
                String content = readLastLines(filePath, lines);
                content = sanitizeContent(content);
                content = filterHealthCheckLines(type, content);
                content = applyFilters(content, level, keyword, module);
                result.put("content", content.isEmpty() ? "暂无日志" : content);
            } catch (FileNotFoundException e) {
                log.warn("[ops-admin] Log file not found: {} (type={})", filePath, type);
                handleFileNotFound(type, filePath, lines, level, keyword, module, result);
            } catch (IOException e) {
                log.warn("[ops-admin] Failed to read log file: {} (type={})", baseName, type);
                String msg = e.getMessage();
                if (msg != null && msg.toLowerCase().contains("permission")) {
                    result.put("notice", true);
                    result.put("content", "日志文件存在但当前后端用户无权限读取: " + filePath
                            + "\n\n建议将后端运行用户加入 adm 组：usermod -a -G adm <后端运行用户>"
                            + "\n或使用 ACL 授权：setfacl -m u:<后端运行用户>:r " + filePath);
                } else {
                    result.put("content", "日志文件不可用");
                }
            }
        }

        return result;
    }

    /**
     * Execute journalctl to read systemd service logs.
     * Only allows whitelisted service names matching the expected pattern.
     */
    private String readJournalCtl(String serviceName, int maxLines) throws IOException {
        // Validate service name against whitelist pattern
        if (!SERVICE_NAME_PATTERN.matcher(serviceName).matches()) {
            throw new IOException("不允许的服务名称: " + serviceName);
        }

        // Build the command: journalctl -u <service> --no-pager -n <lines>
        List<String> cmd = List.of(
                "journalctl", "-u", serviceName,
                "--no-pager", "-n", String.valueOf(maxLines)
        );

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IOException("无法执行 journalctl 命令（可能未安装或不在 PATH 中）: " + e.getMessage());
        }

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() > 0) output.append("\n");
                output.append(line);
            }
        }

        try {
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("journalctl 命令执行超时");
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String errOutput = output.toString().trim();
                if (!errOutput.isEmpty()) {
                    throw new IOException("journalctl 返回错误 (exit=" + exitCode + "): " + errOutput);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("journalctl 命令被中断");
        }

        return output.toString();
    }

    /**
     * Apply sanitization to log content to mask sensitive data.
     */
    private String sanitizeContent(String content) {
        if (content == null || content.isEmpty()) return content;
        if (sanitizer == null) return content;
        String[] lines = content.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append("\n");
            sb.append(sanitizer.sanitize(lines[i]));
        }
        return sb.toString();
    }

    /**
     * Filter out successful health check lines from AI error logs.
     * Keeps lines that mention /health but also contain error/exception markers.
     * Only applies to fabric-ai-error log type.
     */
    private String filterHealthCheckLines(String logType, String content) {
        if (!"fabric-ai-error".equals(logType)) return content;
        if (content == null || content.isEmpty()) return content;
        String[] lines = content.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (isHealthCheckLine(line) && !isErrorOrExceptionLine(line)) {
                continue; // skip successful health checks
            }
            if (sb.length() > 0) sb.append("\n");
            sb.append(line);
        }
        return sb.toString();
    }

    private boolean isHealthCheckLine(String line) {
        return line.contains("/health");
    }

    private boolean isErrorOrExceptionLine(String line) {
        String lower = line.toLowerCase();
        return lower.contains("error") || lower.contains("exception")
            || lower.contains("traceback") || lower.contains(" 500 ")
            || lower.contains("fail") || lower.contains("timeout")
            || lower.contains("critical") || lower.contains("fatal");
    }

    /**
     * Handle file-not-found for any file-based log type.
     * For mysql-error, also tries systemd journal as fallback before giving up.
     */
    private void handleFileNotFound(String type, String filePath, int lines,
                                     String level, String keyword, String module,
                                     Map<String, Object> result) {
        java.io.File f = new java.io.File(filePath);
        if (f.exists()) {
            // File exists but can't be read (permission denied)
            result.put("notice", true);
            String group = "mysql-error".equals(type) ? "mysql" : "adm";
            result.put("content", "日志文件存在但当前后端用户无权限读取: " + filePath
                    + "\n\n建议将后端运行用户加入 " + group + " 组：usermod -a -G " + group + " <后端运行用户>"
                    + "\n或使用 ACL 授权：setfacl -m u:<后端运行用户>:r " + filePath);
            return;
        }

        // File truly doesn't exist — try journal fallback for mysql-error
        if ("mysql-error".equals(type)) {
            String journalContent = tryMysqlJournalFallback(lines, level, keyword, module);
            if (journalContent != null) {
                result.put("file", "journalctl (MySQL fallback)");
                result.put("content", journalContent.isEmpty() ? "暂无 MySQL 日志" : journalContent);
                return;
            }
            result.put("notice", true);
            result.put("content", "MySQL 日志文件不存在: " + filePath
                    + "\n\n已尝试以下路径均未找到："
                    + "\n  /var/log/mysql/mysqld.log"
                    + "\n  /var/log/mysqld.log"
                    + "\n  /var/log/mysql/error.log"
                    + "\n  /var/log/mariadb/mariadb.log"
                    + "\n\n已尝试 systemd journal (mysqld / mysql / mariadb) 均未找到日志。"
                    + "\n\n可设置环境变量 OPS_LOG_MYSQL_ERROR_PATH 指定路径。");
            return;
        }

        // Generic: file not found
        result.put("notice", true);
        result.put("content", "日志文件不存在或无权限: " + filePath);
    }

    /**
     * Try reading MySQL logs from systemd journal as fallback.
     * Attempts mysqld.service, mysql.service, mariadb.service in order.
     * Returns null if all fail.
     */
    private String tryMysqlJournalFallback(int lines, String level, String keyword, String module) {
        String[] services = {"mysqld.service", "mysql.service", "mariadb.service"};
        for (String svc : services) {
            try {
                String content = readJournalCtl(svc, lines);
                if (content != null && !content.isBlank()) {
                    content = sanitizeContent(content);
                    content = applyFilters(content, level, keyword, module);
                    return content;
                }
            } catch (Exception e) {
                log.debug("[ops-admin] MySQL journal fallback failed for {}: {}", svc, e.getMessage());
            }
        }
        return null;
    }

    /**
     * Read logs for a specific date from both archive .gz files and the active log file.
     */
    private List<String> readLogsForDate(String baseName, String date) throws IOException {
        List<String> allLines = new ArrayList<>();

        // A. Read from archive .gz files matching the date
        String archivePrefix = baseName.replace(".log", "");
        java.io.File archiveDir = new java.io.File(ARCHIVE_DIR);
        java.io.File[] matching = archiveDir.listFiles((dir, name) ->
                name.startsWith(archivePrefix + "." + date) && name.endsWith(".log.gz"));
        if (matching != null) {
            for (java.io.File f : matching) {
                try (GZIPInputStream gzis = new GZIPInputStream(new FileInputStream(f));
                     BufferedReader reader = new BufferedReader(new InputStreamReader(gzis, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        allLines.add(line);
                    }
                } catch (Exception e) {
                    log.warn("[ops-admin] Failed to read archive: {}", f.getName(), e);
                }
            }
        }

        // B. Read from active log file and filter by date
        java.io.File activeFile = new java.io.File(LOG_DIR + baseName);
        if (activeFile.exists()) {
            List<String> dateFiltered = new ArrayList<>();
            boolean keeping = false;
            try (BufferedReader reader = new BufferedReader(new FileReader(activeFile, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    boolean isLogLine = LOG_DATE_PATTERN.matcher(line).lookingAt();
                    if (isLogLine) {
                        keeping = line.startsWith(date);
                    }
                    if (keeping) {
                        dateFiltered.add(line);
                    }
                }
            }
            allLines.addAll(dateFiltered);
        }

        return allLines;
    }

    private String applyFilters(String content, String level, String keyword, String module) {
        if (content.isEmpty()) return content;

        String[] rawLines = content.split("\n");
        List<String> filtered = new ArrayList<>(Arrays.asList(rawLines));

        // Detect if log uses Java logback format (lines start with "yyyy-MM-dd HH:mm:ss.SSS")
        boolean isJavaFormat = filtered.stream()
                .anyMatch(line -> LOG_DATE_PATTERN.matcher(line).lookingAt());

        if (isJavaFormat) {
            // Java log format: use keepWithStackTraces to preserve stack traces after matched headers
            if (level != null && !"ALL".equals(level) && ALLOWED_LEVELS.contains(level)) {
                filtered = keepWithStackTraces(filtered, line -> {
                    java.util.regex.Matcher m = LOG_LEVEL_PATTERN.matcher(line);
                    return m.find() && level.equals(m.group(1));
                });
            }

            if (module != null && !"ALL".equals(module) && MODULE_KEYWORDS.containsKey(module)) {
                List<String> keys = MODULE_KEYWORDS.get(module);
                filtered = keepWithStackTraces(filtered,
                        line -> keys.stream().anyMatch(k -> line.toLowerCase(Locale.ROOT).contains(k)));
            }

            if (keyword != null && !keyword.isBlank()) {
                String kw = keyword.length() > MAX_KEYWORD_LEN
                        ? keyword.substring(0, MAX_KEYWORD_LEN)
                        : keyword;
                String lowerKw = kw.toLowerCase(Locale.ROOT);
                filtered = keepWithStackTraces(filtered,
                        line -> line.toLowerCase(Locale.ROOT).contains(lowerKw));
            }
        } else {
            // Non-Java format (e.g., nginx access log, security log, MySQL log): per-line filtering
            // Level and module filters don't apply to non-Java log formats
            if (keyword != null && !keyword.isBlank()) {
                String kw = keyword.length() > MAX_KEYWORD_LEN
                        ? keyword.substring(0, MAX_KEYWORD_LEN)
                        : keyword;
                String lowerKw = kw.toLowerCase(Locale.ROOT);
                filtered = filtered.stream()
                        .filter(line -> line.toLowerCase(Locale.ROOT).contains(lowerKw))
                        .collect(Collectors.toList());
            }
        }

        return String.join("\n", filtered);
    }

    /**
     * Apply a line filter but keep stack trace lines that follow matched event lines.
     */
    private List<String> keepWithStackTraces(List<String> lines, java.util.function.Predicate<String> matchFn) {
        List<String> result = new ArrayList<>();
        boolean keeping = false;
        for (String line : lines) {
            boolean isLogLine = LOG_DATE_PATTERN.matcher(line).lookingAt();
            if (isLogLine) {
                keeping = matchFn.test(line);
            }
            if (keeping) {
                result.add(line);
            }
        }
        return result;
    }

    // --- Event grouping ---

    public static class LogEvent {
        public String firstLine;
        public String level;
        public String timestamp;
        public String logger;
        public List<String> lines = new ArrayList<>();
        public String fullText() { return String.join("\n", lines); }
        public boolean isErrorOrWarn() { return "ERROR".equals(level) || "WARN".equals(level); }
    }

    public static List<LogEvent> groupLogEvents(String content) {
        if (content == null || content.isBlank()) return new ArrayList<>();
        return groupLogEvents(Arrays.asList(content.split("\n")));
    }

    public static List<LogEvent> groupLogEvents(List<String> rawLines) {
        LogEvent current = null;
        List<LogEvent> events = new ArrayList<>();
        if (rawLines == null || rawLines.isEmpty()) return events;
        for (String line : rawLines) {
            boolean isLogLine = LOG_DATE_PATTERN.matcher(line).lookingAt();
            if (isLogLine) {
                current = new LogEvent();
                current.firstLine = line;
                current.lines.add(line);
                events.add(current);
                // Extract level
                for (String lv : List.of("ERROR", "WARN", "INFO", "DEBUG", "TRACE")) {
                    if (line.contains(" " + lv + " ")) { current.level = lv; break; }
                }
                if (current.level == null) current.level = "INFO";
                // Extract logger (last bracket segment before dash)
                int lastBracket = line.lastIndexOf(']');
                int dash = line.indexOf(" - ", lastBracket > 0 ? lastBracket : 0);
                if (lastBracket > 0 && dash > lastBracket) {
                    current.logger = line.substring(lastBracket + 1, dash).trim();
                }
            } else if (current != null) {
                current.lines.add(line);
            }
        }
        return events;
    }

    public static List<String> flattenEvents(List<LogEvent> events) {
        if (events == null || events.isEmpty()) return List.of();
        List<String> lines = new ArrayList<>();
        for (LogEvent event : events) {
            lines.addAll(event.lines);
        }
        return lines;
    }

    public static int lineHash(List<String> lines) {
        int hash = 1;
        if (lines == null) return hash;
        for (String line : lines) {
            hash = 31 * hash + (line == null ? 0 : line.hashCode());
        }
        return hash;
    }

    public static String filterEventsByLevel(List<LogEvent> events, String level) {
        if (level == null || "ALL".equals(level)) return eventsToText(events);
        return eventsToText(events.stream().filter(e -> level.equals(e.level)).toList());
    }

    public static String filterEventsByKeyword(List<LogEvent> events, String keyword) {
        if (keyword == null || keyword.isBlank()) return eventsToText(events);
        String kw = keyword.toLowerCase(Locale.ROOT);
        return eventsToText(events.stream()
                .filter(e -> e.lines.stream().anyMatch(l -> l.toLowerCase(Locale.ROOT).contains(kw)))
                .toList());
    }

    public static String eventsToText(List<LogEvent> events) {
        return events.stream().map(LogEvent::fullText).collect(Collectors.joining("\n"));
    }

    // --- Diagnostic context for AI ---

    public static String buildDiagnosticContext(List<LogEvent> events, int maxChars) {
        List<LogEvent> errWarn = events.stream().filter(LogEvent::isErrorOrWarn).toList();
        if (errWarn.isEmpty()) errWarn = events;
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(20, errWarn.size());
        for (int i = 0; i < limit; i++) {
            LogEvent ev = errWarn.get(i);
            sb.append(ev.firstLine).append("\n");
            if (sb.length() > maxChars) break;
            for (int j = 1; j < ev.lines.size(); j++) {
                String line = ev.lines.get(j);
                boolean keep = line.contains("Exception") || line.contains("Cause:") || line.contains("Caused by:")
                        || line.contains("### Error querying") || line.contains("The error may exist")
                        || line.contains("The error may involve") || line.contains("The error occurred")
                        || line.contains("Failed to obtain") || line.contains("Connection refused")
                        || line.contains("Access denied") || line.contains("Communications link")
                        || line.contains("Too many connections") || line.contains("Unknown database")
                        || line.contains("HikariPool") || line.contains("JDBC Connection")
                        || line.contains("TEXT_PARTIAL_WRITING") || line.contains("broadcastToStore failed")
                        || line.contains("The remote endpoint was in state")
                        || line.contains("invalid state for called method")
                        || line.contains("sendMessage") || line.contains("ConcurrentWebSocketSessionDecorator");
                if (keep) {
                    sb.append(line).append("\n");
                    if (sb.length() > maxChars) break;
                }
            }
            if (sb.length() > maxChars) break;
        }
        if (sb.length() > maxChars) {
            return sb.substring(0, maxChars) + "\n... (truncated)";
        }
        return sb.toString();
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
            throw e; // re-throw for explicit handling in tail()
        }

        return String.join("\n", deque);
    }
}

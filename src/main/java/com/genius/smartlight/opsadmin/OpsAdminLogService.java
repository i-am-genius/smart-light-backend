package com.genius.smartlight.opsadmin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

@Slf4j
@Service
public class OpsAdminLogService {

    private static final String LOG_DIR = "/opt/smartlight/logs/";
    private static final String ARCHIVE_DIR = "/opt/smartlight/logs/archive/";

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

    private static final Pattern LOG_DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
    private static final Pattern LOG_LEVEL_PATTERN = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+\\[[^\\]]*\\]\\s+(ERROR|WARN|INFO|DEBUG|TRACE)\\b");

    private static final int MIN_LINES = 1;
    private static final int MAX_LINES = 10000;
    private static final int DEFAULT_LINES = 500;
    private static final int ALL_LINES_CAP = 10000;
    private static final int MAX_KEYWORD_LEN = 100;

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

        if (!TYPE_FILE_MAP.containsKey(type)) {
            result.put("content", "无效的日志类型: " + type);
            return result;
        }

        String baseName = TYPE_FILE_MAP.get(type);
        boolean hasDate = date != null && !date.isBlank() && date.matches("\\d{4}-\\d{2}-\\d{2}");

        if (hasDate) {
            result.put("file", baseName + " (date " + date + ")");
            result.put("date", date);
            result.put("archive", true);
            try {
                List<String> allLines = readLogsForDate(baseName, date);
                // Take last N lines
                int from = Math.max(0, allLines.size() - lines);
                String content = String.join("\n", allLines.subList(from, allLines.size()));
                content = applyFilters(content, level, keyword, module);
                result.put("content", content.isEmpty() ? "该日期暂无日志" : content);
            } catch (Exception e) {
                log.warn("[ops-admin] Failed to read logs for date: {}/{} (type={})", date, baseName, type, e);
                result.put("content", "日志读取失败");
            }
        } else {
            result.put("file", baseName);
            result.put("archive", false);
            String filePath = LOG_DIR + baseName;
            try {
                String content = readLastLines(filePath, lines);
                content = applyFilters(content, level, keyword, module);
                result.put("content", content.isEmpty() ? "暂无日志" : content);
            } catch (IOException e) {
                log.warn("[ops-admin] Failed to read log file: {} (type={})", baseName, type);
                result.put("content", "日志文件不可用");
            }
        }

        return result;
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
        List<LogEvent> events = new ArrayList<>();
        if (content == null || content.isBlank()) return events;
        String[] rawLines = content.split("\n");
        LogEvent current = null;
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
                        || line.contains("HikariPool") || line.contains("JDBC Connection");
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
            return "";
        }

        return String.join("\n", deque);
    }
}

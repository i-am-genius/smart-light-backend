package com.genius.smartlight.opsadmin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
public class OpsAdminSystemStatusService {

    private static final String SMARTLIGHT_DIR = "/opt/smartlight";
    private static final String LOG_DIR = "/opt/smartlight/logs/";
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private final RestTemplate aiRestTemplate;
    private final RestTemplate healthCheckRestTemplate;
    private final String fabricHealthUrl;
    private final String flowHealthUrl;
    private final String nginxHealthUrl;
    private final String serverManagerHealthUrl;
    private final String aiFabricUrl;
    private final String aiFlowUrl;

    public OpsAdminSystemStatusService(
            @Qualifier("aiRestTemplate") RestTemplate aiRestTemplate,
            @Qualifier("healthCheckRestTemplate") RestTemplate healthCheckRestTemplate,
            @Value("${ops.status.fabric-health-url:${ops.status.ai-health-url:}}") String fabricHealthUrl,
            @Value("${ops.status.flow-health-url:}") String flowHealthUrl,
            @Value("${ops.status.nginx-health-url:}") String nginxHealthUrl,
            @Value("${ops.status.server-manager-health-url:http://127.0.0.1:9080/actuator/health}") String serverManagerHealthUrl,
            @Value("${ai.fabric.url:}") String aiFabricUrl,
            @Value("${ai.flow.url:}") String aiFlowUrl) {
        this.aiRestTemplate = aiRestTemplate;
        this.healthCheckRestTemplate = healthCheckRestTemplate;
        this.fabricHealthUrl = fabricHealthUrl;
        this.flowHealthUrl = flowHealthUrl;
        this.nginxHealthUrl = nginxHealthUrl;
        this.serverManagerHealthUrl = serverManagerHealthUrl;
        this.aiFabricUrl = aiFabricUrl;
        this.aiFlowUrl = aiFlowUrl;
    }

    public Map<String, Object> collect() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("serverTime", DT_FMT.format(Instant.now()));
        result.put("backend", collectBackend());
        result.put("jvm", collectJvm());
        result.put("system", collectSystem());
        result.put("memory", collectMemory());
        result.put("swap", collectSwap());
        result.put("disk", collectDisk());
        result.put("processes", collectProcesses());
        result.put("logs", collectLogs());
        List<Map<String, Object>> services = collectServices();
        result.put("services", services);
        result.put("summary", collectSummary(services));
        return result;
    }

    private Map<String, Object> collectBackend() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "UP");
        try {
            m.put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        } catch (Exception e) {
            m.put("uptimeSeconds", -1);
        }
        m.put("javaVersion", System.getProperty("java.version", "unknown"));
        m.put("appVersion", getClass().getPackage().getImplementationVersion() != null
                ? getClass().getPackage().getImplementationVersion() : "0.0.1-SNAPSHOT");
        m.put("workingDir", System.getProperty("user.dir", "unknown"));
        return m;
    }

    private Map<String, Object> collectJvm() {
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        long total = rt.totalMemory();
        long free = rt.freeMemory();
        long used = total - free;
        long percent = max > 0 ? used * 100 / max : 0;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("maxMemoryMb", max / 1024 / 1024);
        m.put("totalMemoryMb", total / 1024 / 1024);
        m.put("freeMemoryMb", free / 1024 / 1024);
        m.put("usedMemoryMb", used / 1024 / 1024);
        m.put("usedPercent", (int) percent);
        return m;
    }

    private Map<String, Object> collectMemory() {
        Map<String, Object> m = new LinkedHashMap<>();

        // Prefer /proc/meminfo (MemAvailable is more accurate than MXBean free)
        File meminfo = new File("/proc/meminfo");
        if (meminfo.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(meminfo, StandardCharsets.UTF_8))) {
                long memTotalKb = -1;
                long memAvailableKb = -1;
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("MemTotal:")) {
                        String val = line.substring(9).trim().replaceAll("\\D+$", "");
                        memTotalKb = Long.parseLong(val);
                    } else if (line.startsWith("MemAvailable:")) {
                        String val = line.substring(13).trim().replaceAll("\\D+$", "");
                        memAvailableKb = Long.parseLong(val);
                    }
                    if (memTotalKb >= 0 && memAvailableKb >= 0) break;
                }
                if (memTotalKb > 0 && memAvailableKb >= 0) {
                    long usedKb = memTotalKb - memAvailableKb;
                    long percent = usedKb * 100 / memTotalKb;
                    m.put("totalMemoryMb", memTotalKb / 1024);
                    m.put("freeMemoryMb", memAvailableKb / 1024);
                    m.put("usedMemoryMb", usedKb / 1024);
                    m.put("usedPercent", (int) percent);
                    m.put("status", "OK");
                    return m;
                }
            } catch (Exception e) {
                log.warn("[ops-admin] Failed to read /proc/meminfo: {}", e.getMessage());
            }
        }

        // Fallback to OperatingSystemMXBean
        try {
            java.lang.management.OperatingSystemMXBean osBean =
                    ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                long totalBytes = sunBean.getTotalMemorySize();
                long freeBytes = sunBean.getFreeMemorySize();
                long usedBytes = totalBytes - freeBytes;
                long percent = totalBytes > 0 ? usedBytes * 100 / totalBytes : 0;
                m.put("totalMemoryMb", totalBytes / 1024 / 1024);
                m.put("freeMemoryMb", freeBytes / 1024 / 1024);
                m.put("usedMemoryMb", usedBytes / 1024 / 1024);
                m.put("usedPercent", (int) percent);
                m.put("status", "OK");
            } else {
                m.put("status", "UNKNOWN");
            }
        } catch (Exception e) {
            log.warn("[ops-admin] Failed to collect physical memory info: {}", e.getMessage());
            m.put("status", "UNKNOWN");
        }
        return m;
    }

    private Map<String, Object> collectSwap() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            java.lang.management.OperatingSystemMXBean osBean =
                    ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                long totalBytes = sunBean.getTotalSwapSpaceSize();
                long freeBytes = sunBean.getFreeSwapSpaceSize();
                if (totalBytes <= 0) {
                    m.put("status", "UNKNOWN");
                    m.put("totalSwapMb", 0);
                    m.put("freeSwapMb", 0);
                    m.put("usedSwapMb", 0);
                    m.put("usedPercent", 0);
                    m.put("detail", "swap not configured");
                } else {
                    long usedBytes = totalBytes - freeBytes;
                    long percent = usedBytes * 100 / totalBytes;
                    m.put("status", "OK");
                    m.put("totalSwapMb", totalBytes / 1024 / 1024);
                    m.put("freeSwapMb", freeBytes / 1024 / 1024);
                    m.put("usedSwapMb", usedBytes / 1024 / 1024);
                    m.put("usedPercent", (int) percent);
                }
            } else {
                m.put("status", "UNKNOWN");
                m.put("detail", "OS MXBean not available");
            }
        } catch (Exception e) {
            log.warn("[ops-admin] Failed to collect swap info: {}", e.getMessage());
            m.put("status", "UNKNOWN");
            m.put("detail", "read error");
        }
        return m;
    }

    private Map<String, Object> collectSystem() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("osName", System.getProperty("os.name", "unknown"));
        m.put("osArch", System.getProperty("os.arch", "unknown"));
        m.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        try {
            double load = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
            m.put("systemLoadAverage", load >= 0 ? Math.round(load * 100.0) / 100.0 : -1);
        } catch (Exception e) {
            m.put("systemLoadAverage", -1);
        }
        return m;
    }

    private Map<String, Object> collectDisk() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", SMARTLIGHT_DIR);
        try {
            File dir = new File(SMARTLIGHT_DIR);
            long total = dir.getTotalSpace();
            long free = dir.getFreeSpace();
            long used = total - free;
            long percent = total > 0 ? used * 100 / total : 0;
            m.put("totalGb", Math.round(total * 10.0 / 1024 / 1024 / 1024) / 10.0);
            m.put("freeGb", Math.round(free * 10.0 / 1024 / 1024 / 1024) / 10.0);
            m.put("usedGb", Math.round(used * 10.0 / 1024 / 1024 / 1024) / 10.0);
            m.put("usedPercent", (int) percent);
        } catch (Exception e) {
            log.warn("[ops-admin] Failed to collect disk info: {}", e.getMessage());
            m.put("totalGb", -1);
            m.put("freeGb", -1);
            m.put("usedGb", -1);
            m.put("usedPercent", -1);
        }
        return m;
    }

    private Map<String, Object> collectProcesses() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> topMemory = new ArrayList<>();

        String osName = System.getProperty("os.name", "").toLowerCase();
        if (!osName.contains("linux")) {
            result.put("topMemory", topMemory);
            return result;
        }

        File procDir = new File("/proc");
        File[] procFiles = procDir.listFiles();
        if (procFiles == null) {
            result.put("topMemory", topMemory);
            return result;
        }

        long currentPid = ProcessHandle.current().pid();
        long totalMemoryBytes;
        try {
            java.lang.management.OperatingSystemMXBean osBean =
                    ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                totalMemoryBytes = sunBean.getTotalMemorySize();
            } else {
                totalMemoryBytes = 0;
            }
        } catch (Exception e) {
            totalMemoryBytes = 0;
        }

        List<Map<String, Object>> procList = new ArrayList<>();

        for (File pidDir : procFiles) {
            if (!pidDir.isDirectory()) continue;
            String pidName = pidDir.getName();
            long pid;
            try {
                pid = Long.parseLong(pidName);
            } catch (NumberFormatException e) {
                continue;
            }
            if (pid <= 0) continue;

            try {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("pid", pid);
                entry.put("currentBackend", pid == currentPid);

                // Read /proc/[pid]/status
                File statusFile = new File(pidDir, "status");
                String name = "";
                long vmRssKb = 0;
                long vmSizeKb = 0;
                if (statusFile.exists()) {
                    try (BufferedReader reader = new BufferedReader(new FileReader(statusFile, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.startsWith("Name:")) {
                                name = line.substring(5).trim();
                            } else if (line.startsWith("VmRSS:")) {
                                String val = line.substring(6).trim();
                                val = val.replaceAll("\\D+$", "");
                                vmRssKb = Long.parseLong(val);
                            } else if (line.startsWith("VmSize:")) {
                                String val = line.substring(7).trim();
                                val = val.replaceAll("\\D+$", "");
                                vmSizeKb = Long.parseLong(val);
                            }
                        }
                    }
                }
                entry.put("name", name);
                entry.put("rssMb", vmRssKb / 1024);
                entry.put("vmSizeMb", vmSizeKb / 1024);

                if (totalMemoryBytes > 0) {
                    double rssBytes = vmRssKb * 1024.0;
                    entry.put("rssPercent", Math.round(rssBytes * 1000.0 / totalMemoryBytes) / 10.0);
                } else {
                    entry.put("rssPercent", 0.0);
                }

                // Read /proc/[pid]/cmdline
                String rawCmd = name;
                File cmdlineFile = new File(pidDir, "cmdline");
                if (cmdlineFile.exists()) {
                    byte[] bytes = java.nio.file.Files.readAllBytes(cmdlineFile.toPath());
                    StringBuilder cmdBuilder = new StringBuilder();
                    for (byte b : bytes) {
                        if (b == 0) {
                            cmdBuilder.append(' ');
                        } else {
                            cmdBuilder.append((char) b);
                        }
                    }
                    rawCmd = cmdBuilder.toString().trim();
                    String sanitized = sanitizeCommand(rawCmd);
                    if (sanitized.length() > 160) {
                        sanitized = sanitized.substring(0, 160) + "...";
                    }
                    entry.put("command", sanitized);
                } else {
                    entry.put("command", name);
                }

                ProcessClassification classification =
                        classifyProcess(name, rawCmd, pid == currentPid);
                entry.put("displayName", classification.displayName());
                entry.put("typeKey", classification.typeKey());
                entry.put("typeLabel", classification.typeLabel());

                procList.add(entry);
            } catch (Exception e) {
                // Skip processes we can't read
            }
        }

        // Sort by rssMb descending, take top 10
        procList.sort((a, b) -> {
            long ra = ((Number) a.getOrDefault("rssMb", 0)).longValue();
            long rb = ((Number) b.getOrDefault("rssMb", 0)).longValue();
            return Long.compare(rb, ra);
        });
        int limit = Math.min(10, procList.size());
        topMemory.addAll(procList.subList(0, limit));

        result.put("topMemory", topMemory);
        return result;
    }

    static ProcessClassification classifyProcess(
            String processName,
            String command,
            boolean currentBackend
    ) {
        String name = processName == null ? "" : processName.trim();
        String normalizedName = name.toLowerCase(Locale.ROOT);
        String normalizedCommand = command == null ? "" : command.toLowerCase(Locale.ROOT);

        if (currentBackend) {
            return new ProcessClassification("Backend API", "backend", "后端服务");
        }
        if (containsAny(normalizedCommand,
                "fabric_wsgi:app",
                "vit_api.py",
                "vit_api",
                "smartlight-fabric-ai",
                "--bind 127.0.0.1:5011")) {
            return new ProcessClassification("Fabric AI", "ai", "AI 服务");
        }
        if (containsAny(normalizedCommand,
                "flow_wsgi:app",
                "flow.py",
                "smartlight-flow-ai",
                "--bind 127.0.0.1:5000")) {
            return new ProcessClassification("People Flow AI", "ai", "AI 服务");
        }
        if (normalizedCommand.contains("server-manager.jar")) {
            return new ProcessClassification(
                    "Server Manager Backend",
                    "manager",
                    "运维管理服务"
            );
        }
        if (normalizedCommand.contains("app.jar")) {
            return new ProcessClassification("Backend API", "backend", "后端服务");
        }
        if (normalizedName.contains("mysqld") || normalizedName.contains("mysql")) {
            return new ProcessClassification("MySQL", "db", "MySQL");
        }
        if (normalizedName.contains("nginx")) {
            return new ProcessClassification("Nginx", "web", "Nginx");
        }
        if (normalizedName.contains("gunicorn")
                || normalizedName.contains("python")
                || normalizedCommand.contains("python")) {
            return new ProcessClassification(
                    name.isBlank() ? "Python Service" : name,
                    "ai",
                    "AI 服务"
            );
        }
        return new ProcessClassification(
                name.isBlank() ? "unknown" : name,
                "system",
                "系统进程"
        );
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    record ProcessClassification(String displayName, String typeKey, String typeLabel) {
    }

    private String sanitizeCommand(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        // Sensitive key patterns
        String[] sensitiveKeys = {
            "password", "passwd", "pwd", "secret", "token", "key",
            "authorization", "auth", "apikey", "api_key", "accesskey",
            "access_key", "credential"
        };
        String result = raw;
        for (String k : sensitiveKeys) {
            result = result.replaceAll("(?i)[\\-\\-]*" + k + "[=: ]['\"]?[^\\s'\"]+", k + "=***");
            result = result.replaceAll("(?i)[\\-\\-]*" + k + "['\"]?\\s+['\"]?[^\\s'\"]+", k + " ***");
        }
        return result;
    }

    private Map<String, Object> collectLogs() {
        Map<String, Object> m = new LinkedHashMap<>();
        String[] logNames = {"backend-important.log", "backend-ws.log", "backend-error.log"};
        String[] keys = {"importantSizeKb", "wsSizeKb", "errorSizeKb"};

        for (int i = 0; i < logNames.length; i++) {
            try {
                File f = new File(LOG_DIR + logNames[i]);
                if (f.exists()) {
                    m.put(keys[i], f.length() / 1024);
                } else {
                    m.put(keys[i], 0);
                }
            } catch (Exception e) {
                m.put(keys[i], -1);
            }
        }

        int warnCount = 0;
        int errorCount = 0;
        try {
            File importantLog = new File(LOG_DIR + "backend-important.log");
            if (importantLog.exists()) {
                Deque<String> deque = new ArrayDeque<>(500);
                try (BufferedReader reader = new BufferedReader(new FileReader(importantLog, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (deque.size() >= 500) deque.removeFirst();
                        deque.addLast(line);
                    }
                }
                for (String line : deque) {
                    if (line.contains(" WARN ")) warnCount++;
                    if (line.contains(" ERROR ")) errorCount++;
                }
            }
        } catch (Exception e) {
            log.warn("[ops-admin] Failed to count recent log levels: {}", e.getMessage());
        }
        m.put("recentWarnCount", warnCount);
        m.put("recentErrorCount", errorCount);
        return m;
    }

    private List<Map<String, Object>> collectServices() {
        List<Map<String, Object>> list = new ArrayList<>();
        String now = DT_FMT.format(Instant.now());

        // Spring Boot Backend
        Map<String, Object> backend = new LinkedHashMap<>();
        backend.put("name", "Spring Boot Backend");
        backend.put("key", "backend");
        backend.put("status", "UP");
        backend.put("detail", "current process");
        backend.put("checkType", "process");
        backend.put("checkTarget", "current JVM");
        backend.put("lastChecked", now);
        try {
            backend.put("pid", ProcessHandle.current().pid());
            backend.put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        } catch (Exception e) {
            backend.put("pid", -1);
            backend.put("uptimeSeconds", -1);
        }
        list.add(backend);

        // Fabric AI Service
        list.add(checkFabricAi(now));

        // People Flow AI Service
        list.add(checkFlowAi(now));

        // Server Manager full chain: Nginx :9080 -> Java backend :9090
        list.add(checkServerManager(now));

        // Nginx
        list.add(checkNginx(now));

        return list;
    }

    private Map<String, Object> checkFabricAi(String now) {
        return checkHttpService(
                "Fabric AI Service",
                "fabric-ai",
                resolveHealthUrl(fabricHealthUrl, aiFabricUrl),
                now,
                HttpMethod.GET,
                false
        );
    }

    private Map<String, Object> checkFlowAi(String now) {
        return checkHttpService(
                "People Flow AI Service",
                "flow-ai",
                resolveHealthUrl(flowHealthUrl, aiFlowUrl),
                now,
                HttpMethod.GET,
                false
        );
    }

    private Map<String, Object> checkServerManager(String now) {
        return checkHttpService(
                "Server Manager (9080 → 9090)",
                "server-manager",
                serverManagerHealthUrl,
                now,
                HttpMethod.GET,
                false
        );
    }

    private Map<String, Object> checkNginx(String now) {
        String effectiveUrl = nginxHealthUrl;
        if (effectiveUrl == null || effectiveUrl.isBlank()) {
            effectiveUrl = "https://archive.genius.show/";
        }
        return checkHttpService(
                "Nginx",
                "nginx",
                effectiveUrl,
                now,
                HttpMethod.HEAD,
                true
        );
    }

    private String resolveHealthUrl(String configuredUrl, String serviceUrl) {
        if (configuredUrl != null && !configuredUrl.isBlank()) {
            return configuredUrl.trim();
        }
        if (serviceUrl == null || serviceUrl.isBlank()) {
            return "";
        }
        String normalized = serviceUrl.trim();
        int queryIndex = normalized.indexOf('?');
        if (queryIndex >= 0) {
            normalized = normalized.substring(0, queryIndex);
        }
        int schemeIndex = normalized.indexOf("://");
        int pathIndex = normalized.indexOf('/', schemeIndex >= 0 ? schemeIndex + 3 : 0);
        String baseUrl = pathIndex >= 0 ? normalized.substring(0, pathIndex) : normalized;
        return baseUrl + "/health";
    }

    private Map<String, Object> checkHttpService(
            String name,
            String key,
            String effectiveUrl,
            String now,
            HttpMethod method,
            boolean acceptClientErrors
    ) {
        Map<String, Object> service = new LinkedHashMap<>();
        service.put("name", name);
        service.put("key", key);
        service.put("checkType", "http");
        service.put("lastChecked", now);
        service.put(
                "checkTarget",
                effectiveUrl != null && !effectiveUrl.isBlank()
                        ? effectiveUrl
                        : "not configured"
        );

        if (effectiveUrl == null || effectiveUrl.isBlank()) {
            service.put("status", "UNKNOWN");
            service.put("detail", "health endpoint not configured");
            return service;
        }

        long start = System.currentTimeMillis();
        try {
            ResponseEntity<String> resp = healthCheckRestTemplate.exchange(
                    effectiveUrl, method, null, String.class);
            long elapsed = System.currentTimeMillis() - start;
            int code = resp.getStatusCode().value();
            service.put("responseTimeMs", elapsed);
            service.put("httpStatus", code);
            boolean successful = resp.getStatusCode().is2xxSuccessful()
                    || (acceptClientErrors && code < 500);
            if (successful) {
                service.put("status", "UP");
                service.put("detail", "health check ok");
            } else {
                service.put("status", "DOWN");
                service.put("detail", "returned " + code);
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            service.put("responseTimeMs", elapsed);
            service.put("status", "DOWN");
            String msg = e.getMessage();
            if (msg != null && (msg.contains("timeout") || msg.contains("Timeout"))) {
                service.put("detail", "connect timeout");
            } else if (msg != null && msg.contains("Connection refused")) {
                service.put("detail", "connection refused");
            } else {
                service.put("detail", "health check failed");
            }
        }
        return service;
    }

    private Map<String, Object> collectSummary(List<Map<String, Object>> services) {
        int upCount = 0;
        int downCount = 0;
        int unknownCount = 0;
        for (Map<String, Object> svc : services) {
            String s = (String) svc.get("status");
            if ("UP".equals(s)) upCount++;
            else if ("DOWN".equals(s)) downCount++;
            else unknownCount++;
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("upCount", upCount);
        summary.put("downCount", downCount);
        summary.put("unknownCount", unknownCount);

        if (downCount > 0) {
            summary.put("status", "ERROR");
            summary.put("message", "存在异常服务");
        } else if (unknownCount > 0) {
            summary.put("status", "WARNING");
            summary.put("message", "存在未配置或未知状态");
        } else {
            summary.put("status", "HEALTHY");
            summary.put("message", "系统运行正常");
        }
        return summary;
    }
}

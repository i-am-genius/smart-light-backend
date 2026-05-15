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
    private final String aiHealthUrl;

    public OpsAdminSystemStatusService(
            @Qualifier("aiRestTemplate") RestTemplate aiRestTemplate,
            @Value("${ops.status.ai-health-url:}") String aiHealthUrl) {
        this.aiRestTemplate = aiRestTemplate;
        this.aiHealthUrl = aiHealthUrl;
    }

    public Map<String, Object> collect() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("serverTime", DT_FMT.format(Instant.now()));
        result.put("backend", collectBackend());
        result.put("jvm", collectJvm());
        result.put("system", collectSystem());
        result.put("disk", collectDisk());
        result.put("logs", collectLogs());
        result.put("services", collectServices());
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

        Map<String, Object> backend = new LinkedHashMap<>();
        backend.put("name", "Spring Boot Backend");
        backend.put("key", "backend");
        backend.put("status", "UP");
        backend.put("detail", "current process");
        list.add(backend);

        Map<String, Object> ai = new LinkedHashMap<>();
        ai.put("name", "Fabric AI Service");
        ai.put("key", "fabric-ai");
        if (aiHealthUrl != null && !aiHealthUrl.isBlank()) {
            try {
                ResponseEntity<String> resp = aiRestTemplate.exchange(
                        aiHealthUrl, HttpMethod.GET, null, String.class);
                if (resp.getStatusCode().is2xxSuccessful()) {
                    ai.put("status", "UP");
                    ai.put("detail", "health check ok");
                } else {
                    ai.put("status", "DOWN");
                    ai.put("detail", "health check returned " + resp.getStatusCode().value());
                }
            } catch (Exception e) {
                ai.put("status", "DOWN");
                ai.put("detail", "health check failed: " + e.getMessage());
            }
        } else {
            ai.put("status", "UNKNOWN");
            ai.put("detail", "health endpoint not configured");
        }
        list.add(ai);

        Map<String, Object> nginx = new LinkedHashMap<>();
        nginx.put("name", "Nginx");
        nginx.put("key", "nginx");
        nginx.put("status", "UNKNOWN");
        nginx.put("detail", "not checked by shell");
        list.add(nginx);

        return list;
    }
}

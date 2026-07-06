package com.genius.smartlight.opsadmin;

import com.genius.smartlight.config.DeviceLogProperties;
import com.genius.smartlight.dto.device.DeviceLogEntryDTO;
import com.genius.smartlight.dto.device.DeviceLogQueryEntry;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class OpsAdminDeviceLogService {

    private final DeviceLogProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * List all device chipIds that have log files.
     */
    public List<String> listDevices() {
        Path basePath = Paths.get(properties.getBasePath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(basePath)) {
            return Collections.emptyList();
        }
        List<String> devices = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(basePath)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                String name = dir.getFileName().toString();
                try (Stream<Path> files = Files.list(dir)) {
                    if (files.anyMatch(f -> f.toString().endsWith(".log") || f.toString().endsWith(".jsonl"))) {
                        devices.add(name);
                    }
                } catch (IOException e) {
                    log.warn("Failed to list log directory: {}", dir, e);
                }
            });
        } catch (IOException e) {
            log.warn("Failed to list base log directory: {}", basePath, e);
        }
        Collections.sort(devices);
        return devices;
    }

    /**
     * Query device logs with filters and pagination.
     */
    public DeviceLogQueryResult queryLogs(String chipId, String level, String keyword,
                                           String date, int offset, int limit) {
        if (limit <= 0 || limit > 500) limit = 100;

        Path basePath = Paths.get(properties.getBasePath()).toAbsolutePath().normalize();

        List<Path> chipDirs = new ArrayList<>();
        if (chipId != null && !chipId.isBlank()) {
            Path dir = basePath.resolve(chipId).normalize();
            if (dir.startsWith(basePath) && Files.isDirectory(dir)) {
                chipDirs.add(dir);
            }
        } else {
            if (!Files.isDirectory(basePath)) {
                return new DeviceLogQueryResult(Collections.emptyList(), 0, false);
            }
            try (Stream<Path> dirs = Files.list(basePath)) {
                dirs.filter(Files::isDirectory).forEach(chipDirs::add);
            } catch (IOException e) {
                log.warn("Failed to list base directory", e);
            }
        }

        LocalDate filterDate = null;
        if (date != null && !date.isBlank()) {
            try {
                filterDate = LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (Exception ignored) {}
        }

        List<PathWithChip> logFiles = new ArrayList<>();
        for (Path chipDir : chipDirs) {
            String chip = chipDir.getFileName().toString();
            try (Stream<Path> files = Files.list(chipDir)) {
                files.filter(f -> f.toString().endsWith(".log") || f.toString().endsWith(".jsonl"))
                     .map(f -> new PathWithChip(f, chip))
                     .forEach(logFiles::add);
            } catch (IOException e) {
                log.warn("Failed to list chip dir: {}", chipDir, e);
            }
        }
        logFiles.sort((a, b) -> b.path.getFileName().toString().compareTo(a.path.getFileName().toString()));

        if (filterDate != null) {
            Instant dayStart = filterDate.atStartOfDay(ZoneId.systemDefault()).toInstant();
            Instant dayEnd = filterDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
            logFiles.removeIf(pw -> {
                try {
                    Instant modified = Files.getLastModifiedTime(pw.path).toInstant();
                    return modified.isBefore(dayStart) || !modified.isBefore(dayEnd);
                } catch (IOException e) {
                    return true;
                }
            });
        }

        String levelUpper = (level != null && !level.isBlank() && !level.equalsIgnoreCase("ALL"))
                ? level.toUpperCase(Locale.ROOT) : null;
        String kwLower = (keyword != null && !keyword.isBlank())
                ? keyword.toLowerCase(Locale.ROOT) : null;

        List<DeviceLogQueryEntry> allEntries = new ArrayList<>();
        for (PathWithChip pw : logFiles) {
            try {
                String content = Files.readString(pw.path, StandardCharsets.UTF_8);
                List<DeviceLogEntryDTO> entries = new ArrayList<>();

                if (pw.path.toString().endsWith(".jsonl")) {
                    // NDJSON format: one JSON per line
                    for (String line : content.split("\\r?\\n", -1)) {
                        if (line.isBlank()) continue;
                        try {
                            entries.add(objectMapper.readValue(line, DeviceLogEntryDTO.class));
                        } catch (JacksonException ignored) {}
                    }
                } else {
                    // JSON array format (legacy .log files)
                    entries = objectMapper.readValue(content,
                            objectMapper.getTypeFactory().constructCollectionType(List.class, DeviceLogEntryDTO.class));
                }

                for (DeviceLogEntryDTO e : entries) {
                    if (levelUpper != null && !levelUpper.equals(e.getLevel())) continue;
                    if (kwLower != null && (e.getMsg() == null || !e.getMsg().toLowerCase(Locale.ROOT).contains(kwLower))) continue;
                    DeviceLogQueryEntry qe = new DeviceLogQueryEntry();
                    qe.setChipId(pw.chip);

                    // 修复：timestamp 必须来自 ts 字段，不是 uptimeMs
                    Long ts = e.getTs();
                    if (ts == null || ts < 946684800000L) {
                        // 旧错误日志无法恢复真实时间，使用文件修改时间
                        try {
                            ts = Files.getLastModifiedTime(pw.path).toMillis();
                        } catch (IOException ex) {
                            ts = null;
                        }
                    }
                    qe.setTimestamp(ts == null ? null : String.valueOf(ts));
                    qe.setUptimeMs(e.getUptimeMs());  // 保留 uptimeMs 字段

                    qe.setLevel(e.getLevel());
                    qe.setModule(e.getModule());
                    qe.setMessage(e.getMsg());
                    allEntries.add(qe);
                }
            } catch (JacksonException e) {
                log.debug("Failed to parse log file: {}", pw.path, e);
            } catch (IOException e) {
                log.warn("Failed to read log file: {}", pw.path, e);
            }
        }

        allEntries.sort((a, b) -> {
            String tsA = a.getTimestamp() != null ? a.getTimestamp() : "";
            String tsB = b.getTimestamp() != null ? b.getTimestamp() : "";
            return tsB.compareTo(tsA);
        });

        int total = allEntries.size();
        boolean hasMore = offset + limit < total;

        int from = Math.min(offset, total);
        int to = Math.min(offset + limit, total);
        List<DeviceLogQueryEntry> page = new ArrayList<>(allEntries.subList(from, to));

        return new DeviceLogQueryResult(page, total, hasMore);
    }

    private record PathWithChip(Path path, String chip) {}

    @Data
    public static class DeviceLogQueryResult {
        private final List<DeviceLogQueryEntry> entries;
        private final int total;
        private final boolean hasMore;
    }
}

package com.genius.smartlight.service.device.impl;

import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.config.DeviceLogProperties;
import com.genius.smartlight.dto.device.DeviceLogEntryDTO;
import com.genius.smartlight.dto.device.DeviceLogParseResult;
import com.genius.smartlight.service.device.DeviceLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceLogServiceImpl implements DeviceLogService {

    private final DeviceLogProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public DeviceLogParseResult parseNdjson(String ndjson) {
        if (ndjson == null || ndjson.isBlank()) {
            return new DeviceLogParseResult(0, 0, List.of());
        }

        String[] lines = ndjson.split("\\r?\\n", -1);
        int maxBatchLines = properties.getMaxBatchLines();
        int maxLineLength = properties.getMaxLineLength();

        int received = 0;
        int invalid = 0;
        List<DeviceLogEntryDTO> entries = new ArrayList<>();

        for (String line : lines) {
            if (received >= maxBatchLines) {
                break;
            }
            if (line.isBlank()) {
                continue;
            }
            received++;

            // 截断超过 maxLineLength 的行
            String trimmed = line.length() > maxLineLength ? line.substring(0, maxLineLength) : line;

            try {
                DeviceLogEntryDTO entry = objectMapper.readValue(trimmed, DeviceLogEntryDTO.class);
                entries.add(entry);
            } catch (JacksonException e) {
                invalid++;
                log.debug("Failed to parse NDJSON line: {}", trimmed, e);
            }
        }

        return new DeviceLogParseResult(received, invalid, entries);
    }

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

    @Override
    public String writeLogs(String chipId, List<DeviceLogEntryDTO> entries) {
        // 校验 chipId 不能为空且不能包含路径分隔符
        if (chipId == null || chipId.isBlank()) {
            throw new ServiceException("chipId 不能为空");
        }
        if (chipId.contains("..") || chipId.contains("/") || chipId.contains("\\")) {
            throw new ServiceException("chipId 包含非法字符");
        }

        Path basePath = Paths.get(properties.getBasePath()).toAbsolutePath().normalize();
        Path chipDir = basePath.resolve(chipId).normalize();

        // 路径穿越校验
        if (!chipDir.startsWith(basePath)) {
            throw new ServiceException("路径越权");
        }

        try {
            Files.createDirectories(chipDir);
        } catch (IOException e) {
            log.error("Failed to create log directory: {}", chipDir, e);
            throw new ServiceException("创建日志目录失败");
        }

        // 文件名使用日期格式: yyyy-MM-dd.jsonl
        String filename = DATE_FMT.format(Instant.now()) + ".jsonl";
        Path logFile = chipDir.resolve(filename).normalize();

        // 再次校验路径穿越
        if (!logFile.startsWith(basePath)) {
            throw new ServiceException("路径越权");
        }

        // 以 NDJSON 格式追加写入（每行一个 JSON 对象）
        long serverNow = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        for (DeviceLogEntryDTO entry : entries) {
            try {
                // 补充真实时间戳 ts
                if (entry.getTs() == null || entry.getTs() < 946684800000L) {
                    entry.setTs(serverNow);
                }
                sb.append(objectMapper.writeValueAsString(entry)).append('\n');
            } catch (JacksonException e) {
                log.warn("Failed to serialize log entry, skipping", e);
            }
        }

        try {
            Files.writeString(logFile, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("Failed to write log file: {}", logFile, e);
            throw new ServiceException("写入日志文件失败");
        }

        return logFile.toString();
    }

    @Override
    public List<DeviceLogEntryDTO> queryLogs(String chipId, int limit, String order) {
        if (chipId == null || chipId.isBlank()) {
            throw new ServiceException("chipId 不能为空");
        }
        if (chipId.contains("..") || chipId.contains("/") || chipId.contains("\\")) {
            throw new ServiceException("chipId 包含非法字符");
        }

        Path basePath = Paths.get(properties.getBasePath()).toAbsolutePath().normalize();
        Path chipDir = basePath.resolve(chipId).normalize();

        if (!chipDir.startsWith(basePath) || !Files.isDirectory(chipDir)) {
            return List.of();
        }

        // 读取所有 .jsonl 文件，按日期倒序排列
        List<Path> logFiles;
        try (Stream<Path> files = Files.list(chipDir)) {
            logFiles = files
                    .filter(p -> p.toString().endsWith(".jsonl"))
                    .sorted((a, b) -> {
                        int cmp = a.getFileName().toString().compareTo(b.getFileName().toString());
                        return "desc".equalsIgnoreCase(order) ? -cmp : cmp;
                    })
                    .toList();
        } catch (IOException e) {
            log.warn("Failed to list log files in: {}", chipDir, e);
            return List.of();
        }

        List<DeviceLogEntryDTO> result = new ArrayList<>();
        for (Path file : logFiles) {
            if (result.size() >= limit) break;
            try {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (String line : lines) {
                    if (result.size() >= limit) break;
                    if (line.isBlank()) continue;
                    try {
                        result.add(objectMapper.readValue(line, DeviceLogEntryDTO.class));
                    } catch (JacksonException e) {
                        log.debug("Failed to parse log line in {}: {}", file, line, e);
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to read log file: {}", file, e);
            }
        }

        // desc 模式下，文件已按日期倒序；行内顺序是追加顺序（旧→新）
        if ("desc".equalsIgnoreCase(order)) {
            java.util.Collections.reverse(result);
        }

        return result;
    }

    @Override
    public List<String> listDevices() {
        Path basePath = Paths.get(properties.getBasePath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(basePath)) {
            return List.of();
        }

        try (Stream<Path> dirs = Files.list(basePath)) {
            return dirs
                    .filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("Failed to list device log directories", e);
            return List.of();
        }
    }

    @Override
    public void cleanupOldLogs() {
        Path basePath = Paths.get(properties.getBasePath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(basePath)) {
            return;
        }

        Instant cutoff = Instant.now().minus(properties.getRetentionDays(), ChronoUnit.DAYS);

        try (Stream<Path> chipDirs = Files.list(basePath)) {
            chipDirs.filter(Files::isDirectory).forEach(chipDir -> {
                try (Stream<Path> logFiles = Files.list(chipDir)) {
                    logFiles.filter(Files::isRegularFile).forEach(logFile -> {
                        try {
                            Instant lastModified = Files.getLastModifiedTime(logFile).toInstant();
                            if (lastModified.isBefore(cutoff)) {
                                Files.deleteIfExists(logFile);
                                log.debug("Deleted expired log file: {}", logFile);
                            }
                        } catch (IOException e) {
                            log.warn("Failed to check/delete log file: {}", logFile, e);
                        }
                    });
                } catch (IOException e) {
                    log.warn("Failed to list log files in: {}", chipDir, e);
                }

                // 删除空目录
                try (Stream<Path> remaining = Files.list(chipDir)) {
                    if (remaining.findFirst().isEmpty()) {
                        Files.deleteIfExists(chipDir);
                        log.debug("Deleted empty chip log directory: {}", chipDir);
                    }
                } catch (IOException e) {
                    log.warn("Failed to check/remove empty directory: {}", chipDir, e);
                }
            });
        } catch (IOException e) {
            log.warn("Failed to list log base directory: {}", basePath, e);
        }
    }
}

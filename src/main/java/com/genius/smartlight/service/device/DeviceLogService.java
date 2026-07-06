package com.genius.smartlight.service.device;

import com.genius.smartlight.dto.device.DeviceLogEntryDTO;
import com.genius.smartlight.dto.device.DeviceLogParseResult;

import java.util.List;

public interface DeviceLogService {

    /**
     * 解析 NDJSON 格式的日志文本，返回解析结果。
     * 会截断超过 maxLineLength 的行，并限制最大解析行数为 maxBatchLines。
     */
    DeviceLogParseResult parseNdjson(String ndjson);

    /**
     * 将解析成功的日志条目写入指定设备的日志文件（NDJSON 格式，按日期分文件）。
     * 文件路径通过 chipId 构建，经过路径穿越校验。
     *
     * @param chipId 设备芯片 ID
     * @param uploadUptimeMs 上传时设备的 uptimeMs，用于回推每条日志的真实时间
     * @param entries 解析成功的日志条目列表
     * @return 实际写入的文件路径
     */
    String writeLogs(String chipId, long uploadUptimeMs, List<DeviceLogEntryDTO> entries);

    /**
     * 查询指定设备的日志。
     *
     * @param chipId 设备芯片 ID
     * @param limit  最大返回条数
     * @param order  排序方式（asc/desc）
     * @return 日志条目列表
     */
    List<DeviceLogEntryDTO> queryLogs(String chipId, int limit, String order);

    /**
     * 列出所有有日志的设备 ID。
     *
     * @return 设备 ID 列表
     */
    List<String> listDevices();

    /**
     * 清理超过保留天数的日志文件。
     */
    void cleanupOldLogs();
}

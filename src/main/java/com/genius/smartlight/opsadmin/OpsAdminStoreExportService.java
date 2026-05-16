package com.genius.smartlight.opsadmin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OpsAdminStoreExportService {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final OpsAdminStoreService storeService;

    public byte[] exportCsv(OpsAdminStorePageReq req) {
        List<OpsAdminStoreResp> rows = storeService.export(req);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (OutputStreamWriter w = new OutputStreamWriter(bos, StandardCharsets.UTF_8)) {
            // UTF-8 BOM
            w.write('﻿');

            // Header
            w.write(csvLine(
                    "店铺ID", "所属用户ID", "店铺名称", "店铺风格", "面积",
                    "省份", "城市", "纬度", "经度", "创建时间", "更新时间",
                    "设备总数", "普通灯数量", "摄像头灯数量", "自动模式设备数", "手动模式设备数",
                    "stable固件数量", "test固件数量", "OTA更新中数量", "OTA失败数量",
                    "平均当前亮度", "平均当前色温", "平均推荐亮度", "平均推荐色温",
                    "自动模式比例", "亮度偏差均值", "色温偏差均值",
                    "最近光照", "最近光照时间", "今日平均光照", "今日最高光照", "今日最低光照", "今日光照记录数",
                    "今日使用时长毫秒", "今日使用时长分钟", "累计使用时长毫秒", "累计使用时长分钟",
                    "今日平均使用时长毫秒", "今日时长记录数", "单位面积今日使用时长",
                    "最近天气", "天气代码", "室外温度", "体感温度", "湿度", "风速",
                    "最高温", "最低温", "天气记录时间",
                    "设备密度", "普通灯密度", "摄像头灯密度", "单位面积光照",
                    "是否有摄像头灯", "是否有自动模式设备", "光照状态", "策略建议"
            ));

            // Data rows
            for (OpsAdminStoreResp r : rows) {
                w.write(csvLine(
                        str(r.getId()), str(r.getUserId()), str(r.getStoreName()), str(r.getStoreStyle()), str(r.getArea()),
                        str(r.getProvince()), str(r.getCity()), str(r.getLatitude()), str(r.getLongitude()),
                        str(r.getCreateTime()), str(r.getUpdateTime()),
                        str(r.getDeviceCount()), str(r.getLampCount()), str(r.getCamlampCount()),
                        str(r.getAutoModeDeviceCount()), str(r.getManualModeDeviceCount()),
                        str(r.getStableFirmwareCount()), str(r.getTestFirmwareCount()),
                        str(r.getOtaUpdatingCount()), str(r.getOtaFailedCount()),
                        str(r.getAvgBrightness()), str(r.getAvgTemp()),
                        str(r.getAvgRecommendedBrightness()), str(r.getAvgRecommendedTemp()),
                        str(r.getAutoModeRatio()), str(r.getBrightnessDeviationAvg()), str(r.getTempDeviationAvg()),
                        str(r.getLatestLux()), str(r.getLatestLuxTime()),
                        str(r.getAvgLuxToday()), str(r.getMaxLuxToday()), str(r.getMinLuxToday()),
                        str(r.getLuxRecordCountToday()),
                        str(r.getDurationToday()), toMinutes(r.getDurationToday()),
                        str(r.getDurationTotal()), toMinutes(r.getDurationTotal()),
                        str(r.getAvgDurationToday()), str(r.getDurationRecordCountToday()),
                        str(r.getDurationPerAreaToday()),
                        str(r.getLatestWeatherText()), str(r.getLatestWeatherCode()),
                        str(r.getLatestOutdoorTemp()), str(r.getLatestApparentTemp()),
                        str(r.getLatestHumidity()), str(r.getLatestWindSpeed()),
                        str(r.getLatestTempMax()), str(r.getLatestTempMin()), str(r.getLatestWeatherTime()),
                        str(r.getDeviceDensity()), str(r.getLampDensity()), str(r.getCamlampDensity()),
                        str(r.getLuxPerArea()),
                        r.isHasCamlamp() ? "是" : "否",
                        r.isHasAutoModeDevices() ? "是" : "否",
                        str(r.getLightLevelStatus()), str(r.getEnergyStrategyHint())
                ));
            }
            w.flush();
        } catch (Exception e) {
            log.error("[ops-admin] Failed to generate store export CSV", e);
            throw new RuntimeException("导出CSV失败: " + e.getMessage());
        }
        log.info("[ops-admin] Store export generated: {} rows, {} bytes", rows.size(), bos.size());
        return bos.toByteArray();
    }

    public String exportFilename() {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        return "stores-strategy-export-" + ts + ".csv";
    }

    private String csvLine(Object... cols) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.length; i++) {
            if (i > 0) sb.append(',');
            String val = cols[i] != null ? cols[i].toString() : "";
            if (val.contains(",") || val.contains("\"") || val.contains("\n")) {
                val = "\"" + val.replace("\"", "\"\"") + "\"";
            }
            sb.append(val);
        }
        sb.append('\n');
        return sb.toString();
    }

    private String str(Object o) {
        return o != null ? o.toString() : "";
    }

    private String toMinutes(Long ms) {
        if (ms == null) return "";
        return String.valueOf(ms / 60000);
    }
}

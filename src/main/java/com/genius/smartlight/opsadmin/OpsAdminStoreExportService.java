package com.genius.smartlight.opsadmin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
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

    public int writeCsv(OpsAdminStorePageReq req, OutputStream outputStream) {
        List<OpsAdminStoreResp> rows = storeService.export(req);
        try {
            outputStream.write(UTF8_BOM);
            OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);

            writer.write(csvLine(
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

            for (OpsAdminStoreResp row : rows) {
                writer.write(csvLine(
                        str(row.getId()), str(row.getUserId()), str(row.getStoreName()), str(row.getStoreStyle()), str(row.getArea()),
                        str(row.getProvince()), str(row.getCity()), str(row.getLatitude()), str(row.getLongitude()),
                        str(row.getCreateTime()), str(row.getUpdateTime()),
                        str(row.getDeviceCount()), str(row.getLampCount()), str(row.getCamlampCount()),
                        str(row.getAutoModeDeviceCount()), str(row.getManualModeDeviceCount()),
                        str(row.getStableFirmwareCount()), str(row.getTestFirmwareCount()),
                        str(row.getOtaUpdatingCount()), str(row.getOtaFailedCount()),
                        str(row.getAvgBrightness()), str(row.getAvgTemp()),
                        str(row.getAvgRecommendedBrightness()), str(row.getAvgRecommendedTemp()),
                        str(row.getAutoModeRatio()), str(row.getBrightnessDeviationAvg()), str(row.getTempDeviationAvg()),
                        str(row.getLatestLux()), str(row.getLatestLuxTime()),
                        str(row.getAvgLuxToday()), str(row.getMaxLuxToday()), str(row.getMinLuxToday()),
                        str(row.getLuxRecordCountToday()),
                        str(row.getDurationToday()), toMinutes(row.getDurationToday()),
                        str(row.getDurationTotal()), toMinutes(row.getDurationTotal()),
                        str(row.getAvgDurationToday()), str(row.getDurationRecordCountToday()),
                        str(row.getDurationPerAreaToday()),
                        str(row.getLatestWeatherText()), str(row.getLatestWeatherCode()),
                        str(row.getLatestOutdoorTemp()), str(row.getLatestApparentTemp()),
                        str(row.getLatestHumidity()), str(row.getLatestWindSpeed()),
                        str(row.getLatestTempMax()), str(row.getLatestTempMin()), str(row.getLatestWeatherTime()),
                        str(row.getDeviceDensity()), str(row.getLampDensity()), str(row.getCamlampDensity()),
                        str(row.getLuxPerArea()),
                        row.isHasCamlamp() ? "是" : "否",
                        row.isHasAutoModeDevices() ? "是" : "否",
                        str(row.getLightLevelStatus()), str(row.getEnergyStrategyHint())
                ));
            }
            writer.flush();
        } catch (Exception e) {
            log.error("[ops-admin] Failed to generate store export CSV", e);
            throw new RuntimeException("Export CSV failed: " + e.getMessage(), e);
        }
        log.info("[ops-admin] Store export generated: {} rows", rows.size());
        return rows.size();
    }

    public String exportFilename() {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        return "stores-strategy-export-" + ts + ".csv";
    }

    private String csvLine(Object... cols) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.length; i++) {
            if (i > 0) sb.append(',');
            String value = cols[i] != null ? cols[i].toString() : "";
            if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
                value = "\"" + value.replace("\"", "\"\"") + "\"";
            }
            sb.append(value);
        }
        sb.append('\n');
        return sb.toString();
    }

    private String str(Object value) {
        return value != null ? value.toString() : "";
    }

    private String toMinutes(Long millis) {
        return millis == null ? "" : String.valueOf(millis / 60000);
    }
}

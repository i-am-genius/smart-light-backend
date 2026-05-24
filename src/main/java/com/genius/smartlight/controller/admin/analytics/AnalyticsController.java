package com.genius.smartlight.controller.admin.analytics;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.genius.smartlight.common.CommonResult;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.dataobject.PersonFlowRecordDO;
import com.genius.smartlight.dal.dataobject.StoreDO;
import com.genius.smartlight.dal.dataobject.WeatherRecordDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.dal.mysql.PersonFlowRecordMapper;
import com.genius.smartlight.dal.mysql.StoreMapper;
import com.genius.smartlight.dal.mysql.WeatherRecordMapper;
import com.genius.smartlight.security.SecurityUtils;
import com.genius.smartlight.vo.analytics.StrategyCompareRespVO;
import com.genius.smartlight.vo.analytics.TempPeopleTrendRespVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "数据分析接口", description = "流量看板分析数据接口")
@RestController
@RequestMapping("/admin/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private static final int TREND_LIMIT = 20;

    private final WeatherRecordMapper weatherRecordMapper;
    private final DeviceMapper deviceMapper;
    private final StoreMapper storeMapper;
    private final PersonFlowRecordMapper personFlowRecordMapper;

    @Operation(
            summary = "查询温度与人流趋势",
            description = "温度数据来自 weather_record.temperature。传入 chipId 时按设备所属 storeId 过滤；未传 chipId 时使用当前用户店铺作为默认门店。"
    )
    @GetMapping("/temp-people-trend")
    public CommonResult<TempPeopleTrendRespVO> getTempPeopleTrend(
            @Parameter(description = "设备 chipId，用于定位门店天气数据", example = "LAMP-37461B")
            @RequestParam(required = false) String chipId) {
        Long storeId = resolveStoreId(chipId);
        if (storeId == null) {
            return CommonResult.success(new TempPeopleTrendRespVO());
        }

        List<WeatherRecordDO> records = weatherRecordMapper.selectList(
                new LambdaQueryWrapper<WeatherRecordDO>()
                        .eq(WeatherRecordDO::getStoreId, storeId)
                        .isNotNull(WeatherRecordDO::getTemperature)
                        .orderByDesc(WeatherRecordDO::getCollectTime)
                        .orderByDesc(WeatherRecordDO::getCreateTime)
                        .orderByDesc(WeatherRecordDO::getId)
                        .last("limit " + TREND_LIMIT)
        );

        if (records == null || records.isEmpty()) {
            return CommonResult.success(new TempPeopleTrendRespVO());
        }

        List<WeatherRecordDO> ordered = new ArrayList<>(records);
        Collections.reverse(ordered);

        LocalDateTime trendStart = ordered.get(0).getCollectTime() != null
                ? ordered.get(0).getCollectTime() : ordered.get(0).getCreateTime();
        LocalDateTime trendEnd = ordered.get(ordered.size() - 1).getCollectTime() != null
                ? ordered.get(ordered.size() - 1).getCollectTime() : ordered.get(ordered.size() - 1).getCreateTime();

        DateTimeFormatter bucketFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00");
        DateTimeFormatter labelFmt = DateTimeFormatter.ofPattern("MM-dd HH:00");

        Map<String, List<Double>> tempByHour = new LinkedHashMap<>();
        for (WeatherRecordDO record : ordered) {
            LocalDateTime refTime = record.getCollectTime() != null ? record.getCollectTime() : record.getCreateTime();
            if (refTime == null) continue;
            String hourKey = refTime.format(bucketFmt);
            tempByHour.computeIfAbsent(hourKey, k -> new ArrayList<>()).add(record.getTemperature().doubleValue());
        }

        Map<String, List<Integer>> peopleByHour = new LinkedHashMap<>();
        if (trendStart != null && trendEnd != null) {
            Long currentUserId = SecurityUtils.getCurrentUserId();
            List<PersonFlowRecordDO> flowRecords = personFlowRecordMapper.selectList(
                    new LambdaQueryWrapper<PersonFlowRecordDO>()
                            .and(w -> w
                                .eq(PersonFlowRecordDO::getStoreId, storeId)
                                .or()
                                .isNull(PersonFlowRecordDO::getStoreId)
                                .eq(currentUserId != null, PersonFlowRecordDO::getUserId, currentUserId)
                            )
                            .ge(PersonFlowRecordDO::getDetectTime, trendStart)
                            .le(PersonFlowRecordDO::getDetectTime, trendEnd.plusHours(1))
                            .orderByAsc(PersonFlowRecordDO::getDetectTime)
            );
            for (PersonFlowRecordDO fr : flowRecords) {
                if (fr.getDetectTime() != null && fr.getPersonCount() != null) {
                    String hourKey = fr.getDetectTime().format(bucketFmt);
                    peopleByHour.computeIfAbsent(hourKey, k -> new ArrayList<>()).add(fr.getPersonCount());
                }
            }
        }

        java.util.Set<String> allKeys = new java.util.LinkedHashSet<>(tempByHour.keySet());
        allKeys.addAll(peopleByHour.keySet());
        List<String> sortedKeys = new ArrayList<>(allKeys);
        Collections.sort(sortedKeys);

        TempPeopleTrendRespVO respVO = new TempPeopleTrendRespVO();
        for (String hourKey : sortedKeys) {
            LocalDateTime bucketTime = LocalDateTime.parse(hourKey, bucketFmt);
            respVO.getLabels().add(bucketTime.format(labelFmt));

            List<Double> temps = tempByHour.get(hourKey);
            if (temps != null && !temps.isEmpty()) {
                double sum = 0;
                for (Double t : temps) sum += t;
                respVO.getTempSeries().add(Math.round(sum / temps.size() * 10.0) / 10.0);
            } else {
                respVO.getTempSeries().add(null);
            }

            List<Integer> peopleValues = peopleByHour.get(hourKey);
            if (peopleValues != null && !peopleValues.isEmpty()) {
                double sum = 0;
                for (Integer v : peopleValues) sum += v;
                respVO.getPeopleSeries().add((int) Math.round(sum / peopleValues.size()));
            } else {
                respVO.getPeopleSeries().add(0);
            }
        }

        return CommonResult.success(respVO);
    }

    @Operation(
            summary = "查询固定策略与智能策略对比",
            description = "当前版本暂未接入真实策略统计，无数据时返回空数组结构，避免看板接口 500。"
    )
    @GetMapping("/strategy-compare")
    public CommonResult<StrategyCompareRespVO> getStrategyCompare(
            @Parameter(description = "设备 chipId，当前版本暂不使用", example = "LAMP-37461B")
            @RequestParam(required = false) String chipId) {
        return CommonResult.success(new StrategyCompareRespVO());
    }

    private Long resolveStoreId(String chipId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        StoreDO currentUserStore = storeMapper.selectOne(
                new LambdaQueryWrapper<StoreDO>()
                        .eq(StoreDO::getUserId, currentUserId)
                        .last("limit 1")
        );

        if (chipId != null && !chipId.isBlank()) {
            DeviceDO device = deviceMapper.selectOne(
                    new LambdaQueryWrapper<DeviceDO>()
                            .eq(DeviceDO::getChipId, chipId)
                            .last("limit 1")
            );
            if (device == null || device.getStoreId() == null) {
                return null;
            }
            // 仅允许查看当前用户店铺所关联设备的数据
            if (currentUserStore != null && device.getStoreId().equals(currentUserStore.getId())) {
                return device.getStoreId();
            }
            return null;
        }

        // 未传 chipId 时使用当前用户店铺
        return currentUserStore != null ? currentUserStore.getId() : null;
    }

}

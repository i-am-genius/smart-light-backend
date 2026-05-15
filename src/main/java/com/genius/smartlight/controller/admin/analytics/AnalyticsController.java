package com.genius.smartlight.controller.admin.analytics;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.genius.smartlight.common.CommonResult;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.dataobject.StoreDO;
import com.genius.smartlight.dal.dataobject.WeatherRecordDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
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
import java.util.List;

@Tag(name = "数据分析接口", description = "流量看板分析数据接口")
@RestController
@RequestMapping("/admin/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private static final int TREND_LIMIT = 20;
    private static final DateTimeFormatter LABEL_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final WeatherRecordMapper weatherRecordMapper;
    private final DeviceMapper deviceMapper;
    private final StoreMapper storeMapper;

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

        TempPeopleTrendRespVO respVO = new TempPeopleTrendRespVO();
        for (WeatherRecordDO record : ordered) {
            respVO.getLabels().add(formatLabel(record));
            respVO.getTempSeries().add(record.getTemperature().doubleValue());
            respVO.getPeopleSeries().add(0);
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

    private String formatLabel(WeatherRecordDO record) {
        LocalDateTime time = record.getCollectTime() != null ? record.getCollectTime() : record.getCreateTime();
        if (time == null) {
            return "";
        }
        return LABEL_FORMATTER.format(time);
    }
}

package com.genius.smartlight.controller.admin.analytics;

import com.genius.smartlight.common.CommonResult;
import com.genius.smartlight.service.analytics.AnalyticsService;
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

@Tag(name = "数据分析接口", description = "流量看板分析数据接口")
@RestController
@RequestMapping("/admin/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @Operation(summary = "查询温度与人流趋势")
    @GetMapping("/temp-people-trend")
    public CommonResult<TempPeopleTrendRespVO> getTempPeopleTrend(
            @Parameter(description = "设备 chipId，用于定位门店天气数据")
            @RequestParam(required = false) String chipId) {
        return CommonResult.success(analyticsService.getTempPeopleTrend(chipId));
    }

    @Operation(summary = "查询固定策略与智能策略对比")
    @GetMapping("/strategy-compare")
    public CommonResult<StrategyCompareRespVO> getStrategyCompare(
            @Parameter(description = "设备 chipId，当前版本暂不使用")
            @RequestParam(required = false) String chipId) {
        return CommonResult.success(analyticsService.getStrategyCompare(chipId));
    }
}

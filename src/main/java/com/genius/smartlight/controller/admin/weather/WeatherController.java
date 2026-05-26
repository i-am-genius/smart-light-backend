package com.genius.smartlight.controller.admin.weather;

import com.genius.smartlight.common.CommonResult;
import com.genius.smartlight.service.store.CurrentStoreService;
import com.genius.smartlight.service.weather.WeatherService;
import com.genius.smartlight.vo.weather.WeatherCurrentRespVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "后台 - 天气采集")
@RestController
@RequestMapping("/admin/weather")
@RequiredArgsConstructor
public class WeatherController {

    private final WeatherService weatherService;
    private final CurrentStoreService currentStoreService;

    @GetMapping("/current")
    @Operation(summary = "获取当前店铺天气")
    public CommonResult<WeatherCurrentRespVO> getCurrentWeather() {
        return CommonResult.success(weatherService.getCurrentWeather(currentStoreService.getCurrentStoreId()));
    }

    @PostMapping("/collect")
    @Operation(summary = "手动采集当前店铺天气")
    public CommonResult<WeatherCurrentRespVO> collectWeather() {
        return CommonResult.success(weatherService.collectWeather(currentStoreService.getCurrentStoreId()));
    }
}

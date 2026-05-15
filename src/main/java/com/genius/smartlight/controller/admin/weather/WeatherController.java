package com.genius.smartlight.controller.admin.weather;

import com.genius.smartlight.common.CommonResult;
import com.genius.smartlight.service.weather.WeatherService;
import com.genius.smartlight.vo.weather.WeatherCurrentRespVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "后台 - 天气采集")
@RestController
@RequestMapping("/admin/weather")
@RequiredArgsConstructor
public class WeatherController {

    private final WeatherService weatherService;

    @GetMapping("/current")
    @Operation(summary = "获取店铺当前天气")
    public CommonResult<WeatherCurrentRespVO> getCurrentWeather(@RequestParam("storeId") Long storeId) {
        return CommonResult.success(weatherService.getCurrentWeather(storeId));
    }

    @PostMapping("/collect/{storeId}")
    @Operation(summary = "手动采集店铺天气")
    public CommonResult<WeatherCurrentRespVO> collectWeather(@PathVariable("storeId") Long storeId) {
        return CommonResult.success(weatherService.collectWeather(storeId));
    }
}

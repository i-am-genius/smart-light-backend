package com.genius.smartlight.schedule;

import com.genius.smartlight.service.weather.WeatherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WeatherCollectScheduler {

    private final WeatherService weatherService;

    @Scheduled(cron = "0 0 * * * *")
    public void collectHourly() {
        log.info("Start hourly weather collection");
        weatherService.collectAllStoresWeather();
    }
}

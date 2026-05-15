package com.genius.smartlight.service.weather;

import com.genius.smartlight.vo.weather.WeatherCurrentRespVO;

public interface WeatherService {

    WeatherCurrentRespVO getCurrentWeather(Long storeId);

    WeatherCurrentRespVO collectWeather(Long storeId);

    void collectAllStoresWeather();
}

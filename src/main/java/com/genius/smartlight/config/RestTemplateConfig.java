package com.genius.smartlight.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RestTemplateConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration WEATHER_READ_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration AI_READ_TIMEOUT = Duration.ofSeconds(20);

    @Bean
    @Primary
    public RestTemplate restTemplate() {
        return createRestTemplate(WEATHER_READ_TIMEOUT);
    }

    @Bean("weatherRestTemplate")
    public RestTemplate weatherRestTemplate() {
        return createRestTemplate(WEATHER_READ_TIMEOUT);
    }

    @Bean("aiRestTemplate")
    public RestTemplate aiRestTemplate() {
        return createRestTemplate(AI_READ_TIMEOUT);
    }

    @Bean("healthCheckRestTemplate")
    public RestTemplate healthCheckRestTemplate() {
        return createRestTemplate(Duration.ofSeconds(2));
    }

    private RestTemplate createRestTemplate(Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(readTimeout);
        return new RestTemplate(factory);
    }
}

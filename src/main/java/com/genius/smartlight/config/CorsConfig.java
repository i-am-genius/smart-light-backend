package com.genius.smartlight.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration webSocketConfig = new CorsConfiguration();
        webSocketConfig.setAllowedOriginPatterns(List.of("*"));
        webSocketConfig.setAllowedMethods(List.of("GET", "OPTIONS"));
        webSocketConfig.setAllowedHeaders(List.of("*"));
        webSocketConfig.setAllowCredentials(true);
        webSocketConfig.setMaxAge(3600L);

        CorsConfiguration config = new CorsConfiguration();

        config.setAllowedOriginPatterns(List.of(
                "https://archive.genius.show",
                "https://genius.show",
                "http://localhost:5173",
                "http://localhost",
                "https://localhost",
                "capacitor://localhost",
                "ionic://localhost",
                "http://localhost:*",
                "https://localhost:*",
                "http://127.0.0.1:*",
                "https://127.0.0.1:*"
        ));

        config.setAllowedMethods(List.of(
                "GET", "POST", "PUT", "DELETE", "OPTIONS"
        ));

        config.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "Accept",
                "Origin",
                "X-Requested-With",
                "Cache-Control",
                "Pragma"
        ));
        config.setExposedHeaders(List.of("Authorization"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/ws", webSocketConfig);
        source.registerCorsConfiguration("/ws/device", webSocketConfig);
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}

package com.genius.smartlight.config;

import com.genius.smartlight.websocket.AppWebSocketHandler;
import com.genius.smartlight.websocket.AppWebSocketHandshakeHandler;
import com.genius.smartlight.websocket.AppWebSocketHandshakeInterceptor;
import com.genius.smartlight.websocket.DeviceWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final AppWebSocketHandler appWebSocketHandler;
    private final AppWebSocketHandshakeHandler appWebSocketHandshakeHandler;
    private final AppWebSocketHandshakeInterceptor appWebSocketHandshakeInterceptor;
    private final DeviceWebSocketHandler deviceWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(appWebSocketHandler, "/ws")
                .addInterceptors(appWebSocketHandshakeInterceptor)
                .setHandshakeHandler(appWebSocketHandshakeHandler)
                .setAllowedOriginPatterns("*");

        registry.addHandler(deviceWebSocketHandler, "/ws/device")
                .setAllowedOriginPatterns("*");
    }
}

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

import java.util.List;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private static final String[] APP_ALLOWED_ORIGIN_PATTERNS = List.of(
            "https://archive.genius.show",
            "https://genius.show",
            "http://localhost",
            "https://localhost",
            "capacitor://localhost",
            "ionic://localhost",
            "http://localhost:*",
            "https://localhost:*",
            "http://127.0.0.1:*",
            "https://127.0.0.1:*"
    ).toArray(String[]::new);

    private final AppWebSocketHandler appWebSocketHandler;
    private final AppWebSocketHandshakeHandler appWebSocketHandshakeHandler;
    private final AppWebSocketHandshakeInterceptor appWebSocketHandshakeInterceptor;
    private final DeviceWebSocketHandler deviceWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(appWebSocketHandler, "/ws")
                .addInterceptors(appWebSocketHandshakeInterceptor)
                .setHandshakeHandler(appWebSocketHandshakeHandler)
                .setAllowedOriginPatterns(APP_ALLOWED_ORIGIN_PATTERNS);

        registry.addHandler(deviceWebSocketHandler, "/ws/device");
    }
}

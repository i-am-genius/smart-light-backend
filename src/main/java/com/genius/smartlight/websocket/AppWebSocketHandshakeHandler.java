package com.genius.smartlight.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.util.List;

@Component
public class AppWebSocketHandshakeHandler extends DefaultHandshakeHandler {

    private static final String APP_PROTOCOL = "smartlight.v1";

    @Override
    protected String selectProtocol(List<String> requestedProtocols, WebSocketHandler webSocketHandler) {
        if (requestedProtocols != null) {
            for (String protocol : requestedProtocols) {
                if (APP_PROTOCOL.equals(protocol)) {
                    return APP_PROTOCOL;
                }
            }
            for (String protocol : requestedProtocols) {
                if (isSafeProtocolName(protocol)) {
                    return protocol;
                }
            }
        }
        return super.selectProtocol(requestedProtocols, webSocketHandler);
    }

    private boolean isSafeProtocolName(String protocol) {
        if (protocol == null || protocol.isBlank()) {
            return false;
        }
        if (protocol.startsWith("Bearer ") || protocol.startsWith("token=") || protocol.startsWith("access_token=")) {
            return false;
        }
        return !looksLikeJwt(protocol);
    }

    private boolean looksLikeJwt(String value) {
        int dotCount = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '.') {
                dotCount++;
            }
        }
        return dotCount == 2;
    }
}

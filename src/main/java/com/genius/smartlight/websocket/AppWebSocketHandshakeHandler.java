package com.genius.smartlight.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.util.List;

@Component
public class AppWebSocketHandshakeHandler extends DefaultHandshakeHandler {

    @Override
    protected String selectProtocol(List<String> requestedProtocols, WebSocketHandler webSocketHandler) {
        if (requestedProtocols != null && !requestedProtocols.isEmpty()) {
            return requestedProtocols.get(0);
        }
        return super.selectProtocol(requestedProtocols, webSocketHandler);
    }
}

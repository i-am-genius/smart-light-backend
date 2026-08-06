package com.genius.smartlight.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genius.smartlight.websocket.fabric.FabricImagePushService;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppWebSocketHandlerTest {

    @Test
    void capabilityUsesAuthenticatedSessionStoreAndStartsOneReplay() throws Exception {
        WebSocketSessionManager sessionManager = mock(WebSocketSessionManager.class);
        FabricImagePushService pushService = mock(FabricImagePushService.class);
        AppWebSocketHandler handler = new AppWebSocketHandler(
                sessionManager, new ObjectMapper(), pushService
        );
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(AppWebSocketHandshakeInterceptor.ATTR_STORE_ID, 7L);
        when(session.getId()).thenReturn("web-1");
        when(session.getAttributes()).thenReturn(attributes);
        when(sessionManager.enableFabricImageBinary("web-1", 1)).thenReturn(true);

        handler.handleTextMessage(session, new TextMessage("""
                {
                  "type": "capabilities",
                  "storeId": 999,
                  "data": {
                    "fabricImageBinary": true,
                    "version": 1
                  }
                }
                """));

        verify(sessionManager).enableFabricImageBinary("web-1", 1);
        verify(sessionManager).send(eq(session), contains("\"capabilitiesAck\""));
        verify(pushService).replayLatestToSession("web-1", 7L);
        verify(pushService, never()).replayLatestToSession("web-1", 999L);
    }

    @Test
    void unsupportedOrDisabledCapabilityDoesNotReplay() throws Exception {
        WebSocketSessionManager sessionManager = mock(WebSocketSessionManager.class);
        FabricImagePushService pushService = mock(FabricImagePushService.class);
        AppWebSocketHandler handler = new AppWebSocketHandler(
                sessionManager, new ObjectMapper(), pushService
        );
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("web-1");
        when(session.getAttributes()).thenReturn(Map.of(
                AppWebSocketHandshakeInterceptor.ATTR_STORE_ID, 7L
        ));

        handler.handleTextMessage(session, new TextMessage("""
                {"type":"capabilities","data":{"fabricImageBinary":false,"version":1}}
                """));

        verify(sessionManager, never()).enableFabricImageBinary("web-1", 1);
        verify(pushService, never()).replayLatestToSession("web-1", 7L);
    }
}

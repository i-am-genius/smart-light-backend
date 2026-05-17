package com.genius.smartlight.websocket;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.genius.smartlight.common.RequestLogUtils;
import com.genius.smartlight.dal.dataobject.StoreDO;
import com.genius.smartlight.dal.mysql.StoreMapper;
import com.genius.smartlight.security.JwtTokenService;
import com.genius.smartlight.security.LoginUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AppWebSocketHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_USER_ID = "userId";
    public static final String ATTR_USERNAME = "username";
    public static final String ATTR_STORE_ID = "storeId";
    private static final String SEC_WEBSOCKET_PROTOCOL = "Sec-WebSocket-Protocol";

    private final JwtTokenService jwtTokenService;
    private final StoreMapper storeMapper;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        String tokenSource = null;
        String token = resolveToken(request, new String[]{null});
        if (token == null || token.isBlank()) {
            tokenSource = "none";
            String wsCtx = buildWsContext(request, "browser");
            log.warn("[ws] event=auth_failed, wsType=browser, reason=missing token, tokenSource=none, {}", wsCtx);
            return true;
        }
        tokenSource = tokenSourceHolder[0];

        try {
            LoginUser loginUser = jwtTokenService.parseToken(token);
            attributes.put(ATTR_USER_ID, loginUser.getUserId());
            attributes.put(ATTR_USERNAME, loginUser.getUsername());

            StoreDO store = storeMapper.selectOne(
                    new LambdaQueryWrapper<StoreDO>()
                            .eq(StoreDO::getUserId, loginUser.getUserId())
                            .last("limit 1")
            );
            if (store != null) {
                attributes.put(ATTR_STORE_ID, store.getId());
            } else {
                log.warn("App WebSocket handshake user has no store, userId={}", loginUser.getUserId());
            }
        } catch (Exception e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (reason.length() > 80) reason = reason.substring(0, 80);
            String wsCtx = buildWsContext(request, "browser");
            log.warn("[ws] event=auth_failed, wsType=browser, reason={}, tokenSource={}, {}", reason, tokenSource, wsCtx);
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        if (exception != null) {
            String wsCtx = buildWsContext(request, "browser");
            log.warn("[ws] event=handshake_error, wsType=browser, errorType={}, {}", exception.getClass().getSimpleName(), wsCtx);
        }
    }

    private String[] tokenSourceHolder = new String[1];

    private String resolveToken(ServerHttpRequest request, String[] sourceOut) {
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            if (sourceOut != null) sourceOut[0] = "header";
            return authHeader.substring(7);
        }

        String protocolToken = resolveTokenFromSubProtocol(request.getHeaders().getFirst(SEC_WEBSOCKET_PROTOCOL));
        if (protocolToken != null) {
            if (sourceOut != null) sourceOut[0] = "sub-protocol";
            return protocolToken;
        }

        String queryToken = UriComponentsBuilder.fromUri(request.getURI())
                .build()
                .getQueryParams()
                .getFirst("token");
        if (queryToken != null && !queryToken.isBlank()) {
            if (sourceOut != null) sourceOut[0] = "query";
            return queryToken;
        }

        if (request instanceof ServletServerHttpRequest servletRequest) {
            String token = servletRequest.getServletRequest().getParameter("token");
            if (token != null && !token.isBlank()) {
                if (sourceOut != null) sourceOut[0] = "query";
                return token;
            }
        }
        return null;
    }

    private String buildWsContext(ServerHttpRequest request, String wsType) {
        String clientIp = "unknown";
        String userAgent = "unknown";
        String uri = request.getURI() != null ? request.getURI().getPath() : "-";
        String query = request.getURI() != null ? RequestLogUtils.sanitizeQueryString(request.getURI().getRawQuery()) : "";

        if (request instanceof ServletServerHttpRequest servletRequest) {
            HttpServletRequest httpReq = servletRequest.getServletRequest();
            clientIp = RequestLogUtils.getClientIp(httpReq);
            userAgent = RequestLogUtils.truncate(httpReq.getHeader("User-Agent"), 150);
        }

        return String.format("clientIp=%s, uri=%s, query=%s, userAgent=%s",
                clientIp, uri, query, userAgent);
    }

    private String resolveTokenFromSubProtocol(String protocolHeader) {
        if (protocolHeader == null || protocolHeader.isBlank()) {
            return null;
        }
        String[] values = protocolHeader.split(",");
        for (String value : values) {
            String candidate = value.trim();
            if (candidate.startsWith("Bearer ")) {
                return candidate.substring(7).trim();
            }
            if (candidate.startsWith("token=")) {
                return candidate.substring("token=".length()).trim();
            }
            if (candidate.startsWith("access_token=")) {
                return candidate.substring("access_token=".length()).trim();
            }
            if (looksLikeJwt(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean looksLikeJwt(String value) {
        if (value == null || value.isBlank() || value.contains(" ")) {
            return false;
        }
        int dotCount = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '.') {
                dotCount++;
            }
        }
        return dotCount == 2;
    }

    private String safePath(ServerHttpRequest request) {
        return request.getURI() == null ? "" : request.getURI().getPath();
    }
}

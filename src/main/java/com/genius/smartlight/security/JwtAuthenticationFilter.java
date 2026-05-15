package com.genius.smartlight.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String SEC_WEBSOCKET_PROTOCOL = "Sec-WebSocket-Protocol";

    private final JwtTokenService jwtTokenService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String token = resolveToken(request);

        if (token != null && !token.isBlank()) {
            try {
                LoginUser loginUser = jwtTokenService.parseToken(token);

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                loginUser,
                                null,
                                AuthorityUtils.NO_AUTHORITIES
                        );

                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (Exception ignored) {
                // Invalid token is ignored and handled later by Spring Security.
            }
        }

        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }

        if (isAppWebSocketRequest(request)) {
            String protocolToken = resolveTokenFromSubProtocol(request.getHeader(SEC_WEBSOCKET_PROTOCOL));
            if (protocolToken != null) {
                return protocolToken;
            }

            String queryToken = request.getParameter("token");
            if (queryToken != null && !queryToken.isBlank()) {
                log.warn("WebSocket token from query parameter is deprecated");
                return queryToken;
            }
        }

        return null;
    }

    private boolean isAppWebSocketRequest(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        if (requestUri == null) {
            return false;
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && requestUri.startsWith(contextPath)) {
            requestUri = requestUri.substring(contextPath.length());
        }
        return "/ws".equals(requestUri);
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
}

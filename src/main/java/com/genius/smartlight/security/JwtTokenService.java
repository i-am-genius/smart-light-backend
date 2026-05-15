package com.genius.smartlight.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Date;

@Slf4j
@Service
public class JwtTokenService {

    private static final String LOCAL_DEV_DEFAULT_SECRET = "smart-light-secret-2026";

    private final Environment environment;

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expire-millis}")
    private long expireMillis;

    public JwtTokenService(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void validateSecret() {
        String value = secret == null ? "" : secret.trim();
        if (value.isEmpty()) {
            throw new IllegalStateException("jwt.secret cannot be empty");
        }

        boolean prodProfile = Arrays.stream(environment.getActiveProfiles())
                .map(String::trim)
                .anyMatch(profile -> "prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile));
        String envSecret = environment.getProperty("JWT_SECRET");
        if (prodProfile && (envSecret == null || envSecret.isBlank())) {
            throw new IllegalStateException("JWT_SECRET must be configured in prod profile");
        }

        if (value.length() < 32) {
            log.warn("JWT secret length is less than 32 characters; configure JWT_SECRET with a stronger value");
        }
        if (LOCAL_DEV_DEFAULT_SECRET.equals(value)) {
            log.warn("JWT secret uses local development default; set JWT_SECRET before production deployment");
        }
    }

    public String createToken(Long userId, String username) {
        Date now = new Date();
        Date expireAt = new Date(now.getTime() + expireMillis);

        return JWT.create()
                .withClaim("userId", userId)
                .withClaim("username", username)
                .withIssuedAt(now)
                .withExpiresAt(expireAt)
                .sign(Algorithm.HMAC256(secret));
    }

    public LoginUser parseToken(String token) {
        var decoded = JWT.require(Algorithm.HMAC256(secret)).build().verify(token);
        Long userId = decoded.getClaim("userId").asLong();
        String username = decoded.getClaim("username").asString();
        return new LoginUser(userId, username);
    }
}

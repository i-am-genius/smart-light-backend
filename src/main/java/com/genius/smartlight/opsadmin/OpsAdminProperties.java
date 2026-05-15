package com.genius.smartlight.opsadmin;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Getter
@Component
public class OpsAdminProperties {

    private String username;
    private String passwordHash;
    private String jwtSecret;
    private boolean configured;

    @PostConstruct
    public void load() {
        username = nullToEmpty(System.getenv("OPS_ADMIN_USERNAME"));
        passwordHash = nullToEmpty(System.getenv("OPS_ADMIN_PASSWORD_HASH"));
        jwtSecret = nullToEmpty(System.getenv("OPS_ADMIN_JWT_SECRET"));

        configured = !username.isEmpty() && !passwordHash.isEmpty() && !jwtSecret.isEmpty();

        if (!configured) {
            log.warn("[ops-admin] Environment variables not configured (OPS_ADMIN_USERNAME / OPS_ADMIN_PASSWORD_HASH / OPS_ADMIN_JWT_SECRET). OpsAdmin login will be unavailable.");
        } else {
            log.info("[ops-admin] Configured for user: {}", username);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}

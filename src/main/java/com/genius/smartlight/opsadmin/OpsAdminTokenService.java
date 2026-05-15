package com.genius.smartlight.opsadmin;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
@RequiredArgsConstructor
public class OpsAdminTokenService {

    private static final long EXPIRE_MILLIS = 24L * 60 * 60 * 1000;

    private final OpsAdminProperties properties;

    public String createToken(String username) {
        Date now = new Date();
        Date expireAt = new Date(now.getTime() + EXPIRE_MILLIS);

        return JWT.create()
                .withClaim("username", username)
                .withClaim("role", "OPS_ADMIN")
                .withIssuedAt(now)
                .withExpiresAt(expireAt)
                .sign(Algorithm.HMAC256(properties.getJwtSecret()));
    }

    public OpsAdminPrincipal parseToken(String token) {
        var decoded = JWT.require(Algorithm.HMAC256(properties.getJwtSecret()))
                .build()
                .verify(token);
        String username = decoded.getClaim("username").asString();
        String role = decoded.getClaim("role").asString();
        return new OpsAdminPrincipal(username, role);
    }
}

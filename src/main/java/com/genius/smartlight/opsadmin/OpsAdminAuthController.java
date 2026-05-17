package com.genius.smartlight.opsadmin;

import com.genius.smartlight.common.ApiResponse;
import com.genius.smartlight.common.RequestLogUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/ops-admin/auth")
@RequiredArgsConstructor
public class OpsAdminAuthController {

    private final OpsAdminProperties properties;
    private final OpsAdminTokenService tokenService;
    private final PasswordEncoder passwordEncoder;

    @PostMapping("/login")
    public ApiResponse<?> login(@RequestBody OpsAdminLoginReq req, HttpServletRequest request) {
        String ctx = RequestLogUtils.logContext(request);
        if (!properties.isConfigured()) {
            log.warn("[ops-admin] Login attempted but not configured, {}", ctx);
            return new ApiResponse<>(503, null, "服务不可用");
        }

        if (!properties.getUsername().equals(req.getUsername())) {
            log.warn("[ops-admin] Login failed: unknown username '{}', {}", req.getUsername(), ctx);
            return new ApiResponse<>(401, null, "用户名或密码错误");
        }

        if (!passwordEncoder.matches(req.getPassword(), properties.getPasswordHash())) {
            log.warn("[ops-admin] Login failed: wrong password for '{}', {}", req.getUsername(), ctx);
            return new ApiResponse<>(401, null, "用户名或密码错误");
        }

        String token = tokenService.createToken(req.getUsername());
        log.info("[ops-admin] Login success for '{}', {}", req.getUsername(), ctx);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", token);
        data.put("username", req.getUsername());
        data.put("role", "OPS_ADMIN");
        return ApiResponse.success(data);
    }
}

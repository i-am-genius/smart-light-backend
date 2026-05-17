package com.genius.smartlight.controller.admin.auth;

import com.genius.smartlight.common.ApiResponse;
import com.genius.smartlight.common.RequestLogUtils;
import com.genius.smartlight.service.auth.AuthService;
import com.genius.smartlight.service.auth.LoginAttemptService;
import com.genius.smartlight.service.auth.RegisterAttemptService;
import com.genius.smartlight.vo.auth.LoginReqVO;
import com.genius.smartlight.vo.auth.LoginRespVO;
import com.genius.smartlight.vo.auth.RegisterReqVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "认证接口", description = "用户注册、登录、登录返回 token 与当前用户店铺信息")
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final LoginAttemptService loginAttemptService;
    private final RegisterAttemptService registerAttemptService;

    @Operation(summary = "账号注册")
    @PostMapping("/register")
    public ApiResponse<String> register(@Valid @RequestBody RegisterReqVO reqVO, HttpServletRequest request) {
        registerAttemptService.checkIpRateLimit(RequestLogUtils.getClientIp(request));
        authService.register(reqVO);
        return ApiResponse.success("注册成功");
    }

    @Operation(summary = "账号登录")
    @PostMapping("/login")
    public ApiResponse<LoginRespVO> login(@Valid @RequestBody LoginReqVO reqVO, HttpServletRequest request) {
        String username = reqVO == null ? null : reqVO.getUsername();
        loginAttemptService.checkIpRateLimit(RequestLogUtils.getClientIp(request));
        loginAttemptService.checkUsernameLocked(username);
        try {
            LoginRespVO respVO = authService.login(reqVO);
            loginAttemptService.recordSuccess(username);
            log.info("Login success, username={}, {}", username, RequestLogUtils.logContext(request));
            return ApiResponse.success(respVO);
        } catch (RuntimeException e) {
            loginAttemptService.recordFailure(username);
            log.warn("Login failed, username={}, reason={}, {}",
                    username != null ? username : "null",
                    e.getMessage() != null ? e.getMessage().substring(0, Math.min(60, e.getMessage().length())) : "unknown",
                    RequestLogUtils.logContext(request));
            throw e;
        }
    }
}

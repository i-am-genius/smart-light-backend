package com.genius.smartlight.controller.admin.auth;

import com.genius.smartlight.common.ApiResponse;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "认证接口", description = "用户注册、登录、登录返回 token 与当前用户店铺信息")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final LoginAttemptService loginAttemptService;
    private final RegisterAttemptService registerAttemptService;

    @Operation(summary = "账号注册")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "统一返回 {code,msg,data}",
            content = @Content(schema = @Schema(implementation = String.class))
    )
    @PostMapping("/register")
    public ApiResponse<String> register(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "注册请求参数",
                    required = true,
                    content = @Content(schema = @Schema(implementation = RegisterReqVO.class))
            )
            @Valid @RequestBody RegisterReqVO reqVO,
            HttpServletRequest request) {
        registerAttemptService.checkIpRateLimit(resolveClientIp(request));
        authService.register(reqVO);
        return ApiResponse.success("注册成功");
    }

    @Operation(summary = "账号登录")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "统一返回 {code,msg,data}，成功时 data 为登录结果",
            content = @Content(schema = @Schema(implementation = LoginRespVO.class))
    )
    @PostMapping("/login")
    public ApiResponse<LoginRespVO> login(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "登录请求参数",
                    required = true,
                    content = @Content(schema = @Schema(implementation = LoginReqVO.class))
            )
            @Valid @RequestBody LoginReqVO reqVO,
            HttpServletRequest request) {
        String username = reqVO == null ? null : reqVO.getUsername();
        loginAttemptService.checkIpRateLimit(resolveClientIp(request));
        loginAttemptService.checkUsernameLocked(username);
        try {
            LoginRespVO respVO = authService.login(reqVO);
            loginAttemptService.recordSuccess(username);
            return ApiResponse.success(respVO);
        } catch (RuntimeException e) {
            loginAttemptService.recordFailure(username);
            throw e;
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}

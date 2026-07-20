package com.genius.smartlight.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final com.genius.smartlight.opsadmin.OpsAdminAuthFilter opsAdminAuthFilter;
    private final JsonAuthenticationEntryPoint jsonAuthenticationEntryPoint;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(jsonAuthenticationEntryPoint)
                )
                .authorizeHttpRequests(auth -> auth
                        // CORS 预检请求放行
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // 登录注册放行
                        .requestMatchers(
                                "/api/auth/login",
                                "/api/auth/register",
                                "/ops-admin/auth/login",
                                "/admin/device/ping"
                        ).permitAll()

                        .requestMatchers("/ops-admin/**").hasRole("OPS_ADMIN")

                        // 设备端 WebSocket：必须放在 /ws/** 前面
                        .requestMatchers("/ws/device").permitAll()

                        // 设备主动上报接口：放行
                        .requestMatchers(HttpMethod.POST,
                                "/admin/device/announce",
                                "/admin/device/state-report",
                                "/admin/device/logs/batch",
                                "/admin/lux/create",
                                "/admin/duration/create"
                        ).permitAll()

                        // 设备日志查询接口：需要认证（前端通过 /ops-admin/ 路径访问）
                        // .requestMatchers(HttpMethod.GET,
                        //         "/admin/device/logs/query",
                        //         "/admin/device/logs/devices"
                        // ).permitAll()

                        // OTA 如果后面要用，也先放行
                        .requestMatchers(
                                "/ota/**"
                        ).permitAll()

                        // 浏览器端 websocket：继续走 JWT
                        // Device capture uploads use a one-time task token validated by DeviceCamService.
                        .requestMatchers(HttpMethod.POST,
                                "/device/cam/capture-task/*/photo",
                                "/device/cam/flow-photo"
                        ).permitAll()

                        .requestMatchers("/ws", "/ws/**").authenticated()

                        // 其余全部要求用户登录
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(opsAdminAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}

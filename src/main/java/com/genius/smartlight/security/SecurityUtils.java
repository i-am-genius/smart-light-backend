package com.genius.smartlight.security;

import org.springframework.security.core.context.SecurityContextHolder;

public class SecurityUtils {

    public static Long getCurrentUserId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof LoginUser loginUser) {
            return loginUser.getUserId();
        }
        throw new RuntimeException("未获取到当前登录用户");
    }

    /**
     * 获取当前登录用户 ID，未登录时返回 null（不抛异常）。
     * 用于设备端请求等 permitAll 场景。
     */
    public static Long getCurrentUserIdOrNull() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof LoginUser loginUser) {
            return loginUser.getUserId();
        }
        return null;
    }

    public static String getCurrentUsername() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof LoginUser loginUser) {
            return loginUser.getUsername();
        }
        throw new RuntimeException("未获取到当前登录用户");
    }
}
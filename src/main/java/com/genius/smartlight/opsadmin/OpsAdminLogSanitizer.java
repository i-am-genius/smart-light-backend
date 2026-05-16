package com.genius.smartlight.opsadmin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Slf4j
@Component
public class OpsAdminLogSanitizer {

    private static final Pattern[] PATTERNS = {
            Pattern.compile("Authorization:\\s*Bearer\\s+\\S+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Bearer\\s+[A-Za-z0-9\\-._~+/]+=*"),
            Pattern.compile("(?:jwt|JWT)_?SECRET\\s*[=:]\\s*\\S+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:password|passwd|pwd)\\s*[=:]\\s*\\S+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:secret|SECRET)\\s*[=:]\\s*\\S+"),
            Pattern.compile("(?:apiKey|api_key|apikey)\\s*[=:]\\s*\\S+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:access_token|accessToken)\\s*[=:]\\s*\\S+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:refresh_token|refreshToken)\\s*[=:]\\s*\\S+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:set-cookie|cookie)\\s*[=:]\\s*\\S+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("mysql://\\S+:\\S+@"),
            Pattern.compile("jdbc:[a-z]+://\\S+:\\S+@"),
            Pattern.compile("\\b[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}\\b"),
            Pattern.compile("\\b1[3-9]\\d{9}\\b"),
    };

    private static final String[] REPLACEMENTS = {
            "Authorization: Bearer ***",
            "Bearer ***",
            "JWT_SECRET=***",
            "password=***",
            "secret=***",
            "apiKey=***",
            "access_token=***",
            "refresh_token=***",
            "cookie=***",
            "mysql://***:***@",
            "jdbc:***:***@",
            "***@***.***",
            "***",
    };

    public String sanitize(String line) {
        if (line == null || line.isEmpty()) return line;
        String result = line;
        for (int i = 0; i < PATTERNS.length; i++) {
            result = PATTERNS[i].matcher(result).replaceAll(REPLACEMENTS[i]);
        }
        return result;
    }
}

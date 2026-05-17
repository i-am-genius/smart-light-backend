package com.genius.smartlight.opsadmin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@Component
public class OpsAdminLogRuleAnalyzer {

    // --- Rule definitions ---

    private static Rule r(String severity, String title, String reason, String impact, String suggestion, LogLineMatcher matcher) {
        return new Rule(matcher, severity, title, reason, impact, suggestion);
    }

    private final List<Rule> rules = List.of(
        // === Critical ===
        r("critical", "服务启动失败",
          "后端应用启动过程中出现致命错误，服务不可用。",
          "服务不可用，所有功能中断。",
          "检查 systemd 环境变量、application.yaml、端口占用、DataSource/JWT/Bean 配置。使用 journalctl 查看完整启动异常。",
          line -> containsAny(line, "APPLICATION FAILED TO START", "Failed to configure a DataSource",
              "Unable to start embedded Tomcat", "BeanCreationException", "UnsatisfiedDependencyException",
              "WebServerException")
        ),
        r("critical", "端口冲突",
          "目标端口已被占用，服务无法绑定端口启动。",
          "服务无法启动。",
          "执行 lsof -i:3000 检查端口占用进程；检查是否有旧进程未退出；重启 systemd 服务。",
          line -> containsAny(line, "Port", "already in use", "Address already in use", "BindException") && (line.contains("3000") || line.contains("tomcat") || line.contains("http-nio"))
        ),
        r("critical", "关键配置缺失导致启动失败",
          "Could not resolve placeholder 导致应用无法启动。",
          "服务不可用。",
          "检查 application.yaml 中的占位符是否都有对应环境变量或默认值；检查 systemd drop-in 和 daemon-reload。",
          line -> containsAny(line, "Could not resolve placeholder", "PlaceholderResolutionException")
              && containsAny(line, "APPLICATION FAILED TO START", "BeanCreationException")
        ),

        // === High: Database ===
        r("high", "数据库认证失败",
          "数据库拒绝当前用户登录，通常是连接配置中的用户名或密码错误，或该数据库用户的认证方式/权限不匹配。",
          "所有依赖数据库的接口将不可用。",
          "1. 检查后端实际生效的数据库用户名、密码和连接地址；2. 使用相同账号手动连接数据库验证；3. 检查数据库用户是否存在、密码是否正确、权限是否匹配；4. 线上建议使用专用业务数据库用户。",
          line -> line.contains("Access denied for user")
        ),
        r("high", "数据库连接失败",
          "后端无法连接 MySQL 数据库，可能服务未运行、网络不通或连接数耗尽。",
          "所有依赖数据库的接口将不可用。",
          "检查 MySQL 服务是否运行、MYSQL_URL 配置是否正确、端口 3306 是否可达、连接数是否耗尽。",
          line -> containsAny(line, "CannotGetJdbcConnectionException", "Failed to obtain JDBC Connection",
              "Communications link failure", "Unknown database", "Too many connections", "HikariPool")
              && !line.contains("Access denied")
        ),
        r("high", "SQL 语法错误",
          "应用执行 SQL 时出现语法错误，功能异常。",
          "对应的数据库操作失败。",
          "检查 SQL 语句拼写、MyBatis 映射和数据库表结构。",
          line -> line.contains("SQLSyntaxErrorException")
        ),

        // === High: Security / Auth ===
        r("high", "JWT_SECRET 使用开发默认值",
          "线上环境未配置正式 JWT_SECRET，仍使用开发默认值，存在 token 被伪造的安全风险。",
          "存在 token 被伪造的安全风险。",
          "在 systemd 或环境变量中配置长度不少于 32 位的强随机 JWT_SECRET，并重启后端服务。",
          line -> line.contains("JWT secret uses local development default")
        ),
        r("high", "JWT_SECRET 长度不足",
          "线上 JWT_SECRET 长度小于 32 字符，容易被暴力破解。",
          "存在 token 被暴力破解的安全风险。",
          "使用不少于 32 字符的随机密钥，建议 64 字符以上。",
          line -> line.contains("JWT secret length is less than 32 characters")
        ),
        r("high", "服务连接失败",
          "连接被目标服务拒绝，目标服务可能未运行或端口错误。",
          "依赖服务不可用。",
          "确认目标服务正在运行且端口配置正确。",
          line -> containsAny(line, "Connection refused", "connection refused") && !line.contains("JDBC")
        ),
        r("high", "JVM 内存溢出",
          "JVM 发生 OutOfMemoryError / Java heap space / GC overhead / Metaspace 错误，应用可能崩溃。",
          "应用崩溃或性能严重下降，服务可能中断。",
          "检查 JVM -Xmx/-Xms 配置、内存占用进程、图片/日志/集合是否未释放、服务器内存和 swap。必要时重启并增大堆内存。",
          line -> containsAny(line, "OutOfMemoryError", "Java heap space", "GC overhead limit exceeded",
              "Metaspace", "unable to create native thread")
        ),

        // === High: External dependencies ===
        r("high", "面料识别 AI 服务异常",
          "Python 面料识别服务（fabric-ai）不可用或响应异常。",
          "面料识别功能不可用，影响图片分析。",
          "检查 smartlight-fabric-ai.service 是否运行、5011 端口 health 是否可用、Conda env segserver 是否正常、模型文件是否存在。",
          line -> containsAny(line, "fabric-ai", "vit_api", "5011") && containsAny(line, "Connection refused", "Read timed out", "DOWN", "health check failed")
        ),
        r("high", "天气采集主备源全部失败",
          "天气采集主源（Open-Meteo）和备用源全部失败，天气数据可能中断。",
          "天气数据无法更新，光照策略可能受影响。",
          "检查 Open-Meteo 是否大范围故障；检查 WEATHER_BACKUP_ENABLED 和 WEATHER_BACKUP_API_KEY 是否正确配置。",
          line -> containsAny(line, "Weather API all retries exhausted", "Backup weather provider failed") || (line.contains("weather") && line.contains("fallback") && line.contains("latest valid"))
        ),

        // === Medium ===
        r("medium", "接口出现 500 异常",
          "HTTP 接口返回 500 状态码，存在未处理的异常。",
          "用户请求失败。",
          "排查对应接口的异常堆栈，修复代码缺陷。多次出现需优先处理。",
          line -> line.contains(" 500 ") || line.contains("\"status\":500")
        ),
        r("medium", "JWT 认证失败",
          "JWT token 无效或过期，请求被拒绝。",
          "用户或设备请求失败，常见于 token 过期或使用旧 token。",
          "清理前端 localStorage 重新登录；如果高频出现同一 clientIp，检查是否在试探 token；如果是 /ops-admin/** 被普通 JWT filter 误判，检查过滤器是否跳过 /ops-admin/**。",
          line -> containsAny(line, "Auth failed", "Token's Signature resulted invalid", "token expired",
              "token invalid", "signature invalid") || (line.contains("401") && line.contains("token"))
        ),
        r("medium", "权限拒绝",
          "访问被 Spring Security 拒绝，可能是权限配置或 token 问题。",
          "用户或请求被拒绝访问。",
          "检查权限配置、用户角色和 token 有效性。",
          line -> containsAny(line, "AccessDeniedException", "Access is denied", "Forbidden", "insufficient permissions")
              || (line.contains("403") && !line.contains("open-meteo"))
        ),
        r("medium", "登录失败/账号试探",
          "登录接口出现失败，可能是密码错误或用户不存在。",
          "正常用户可能无法登录；高频出现可能是暴力尝试。",
          "如果同一 IP 高频失败，启用 IP 限流；不要在前端暴露具体账号是否存在。",
          line -> containsAny(line, "Login failed", "bad credentials", "wrong password", "unknown username",
              "登录失败", "密码错误") || (line.contains("too many attempts"))
        ),
        r("medium", "天气采集失败",
          "天气数据采集失败，但有备用源或历史 fallback 可用。",
          "光照策略可能因天气数据缺失而不准确，但影响有限。",
          "检查 Open-Meteo 是否超时/502；检查 WEATHER_BACKUP_ENABLED 和 WEATHER_BACKUP_API_KEY；如果使用 fallback，数据可能不是最新。",
          line -> containsAny(line, "Collect weather failed", "Weather request failed", "Open-Meteo",
              "open-meteo", "OpenWeatherMap", "weather backup")
              && !line.contains("all retries exhausted") && !line.contains("Backup weather provider failed")
        ),
        r("medium", "天气接口超时",
          "天气 API 请求超时，重试中。",
          "天气数据可能短暂延迟。",
          "检查网络连通性；Open-Meteo 偶发超时属正常波动。",
          line -> containsAny(line, "Read timed out", "ResourceAccessException") && (line.contains("open-meteo") || line.contains("weather"))
        ),
        r("medium", "日志 AI 调用异常",
          "外部 AI API（如 DeepSeek）调用失败，已使用本地规则兜底。",
          "AI 增强分析不可用，但本地规则仍可工作。",
          "检查 OPS_AI_API_KEY、OPS_AI_MODEL、OPS_AI_BASE_URL 配置；不影响核心业务。",
          line -> containsAny(line, "AI analysis failed", "deepseek", "DeepSeek", "chat/completions",
              "invalid_request_error", "using rule fallback", "fallbackUsed")
        ),
        r("medium", "WebSocket 连接异常",
          "WebSocket 连接异常或设备断连。",
          "设备可能离线，实时状态不更新。",
          "检查设备供电和 Wi-Fi；检查 Nginx WebSocket Upgrade 配置；检查心跳间隔和在线 TTL。",
          line -> containsAny(line, "WebSocket", "/ws", "session closed", "connection closed",
              "send failed", "broken pipe", "ping timeout", "device disconnected")
        ),
        r("medium", "OTA 升级异常",
          "OTA 固件升级失败。",
          "设备固件版本可能不匹配。",
          "检查 file_url 是否设备可访问；检查 /ota/** 和 Nginx；检查固件 version_code/channel；检查设备 Flash 空间。",
          line -> containsAny(line, "OTA", "ota_status", "otaProgress", "update failed",
              "firmware download") && !line.contains("/firmware/list")
        ),
        r("medium", "文件或图片处理异常",
          "文件上传或图片处理失败。",
          "面料识别图片可能无法上传或处理。",
          "检查上传目录权限、multipart max-file-size、/opt/smartlight/uploads、Python AI 是否能访问图片路径。",
          line -> containsAny(line, "Maximum upload size exceeded", "NoSuchFileException",
              "FileNotFoundException", "upload") && !line.contains("INFO")
        ),
        r("medium", "请求超时",
          "对外部服务的请求超时，可能是网络问题或目标服务响应慢。",
          "外部服务调用失败。",
          "检查网络连通性、目标服务响应情况，考虑增加超时或添加重试。",
          line -> containsAny(line, "timeout") && containsAny(line, "read", "connect") && !line.contains("weather") && !line.contains("open-meteo")
        ),
        r("medium", "配置缺失",
          "应用配置缺失，占位符未解析或注入失败。",
          "依赖配置的功能可能不可用。",
          "检查 application.yaml、systemd drop-in、daemon-reload、环境变量拼写、jar 是否包含 application.yaml。",
          line -> containsAny(line, "Could not resolve placeholder", "PlaceholderResolutionException",
              "Injection of autowired dependencies failed")
        ),
        r("medium", "数据导出异常",
          "数据导出（CSV/time-series）过程中出现异常。",
          "导出功能不可用。",
          "检查导出时间范围、查询数据量、响应流写入、前端下载逻辑。",
          line -> containsAny(line, "export", "time-series", "CSV") && containsAny(line, "Exception", "ERROR", "failed")
        ),
        r("medium", "扫描请求",
          "访问了不存在的敏感路径，可能是扫描器在探测漏洞。",
          "无实际影响，但需关注是否在探测安全漏洞。",
          "在 Nginx 拦截 /.env、/wp-admin、/phpmyadmin、/actuator、/adminer、/.git 等常见扫描路径；对高频 IP 做限流。",
          line -> containsAny(line, "NoResourceFoundException", "No static resource")
              && containsAny(line, ".env", "/wp-admin", "/phpmyadmin", "/actuator", "/adminer", "/.git")
        ),

        // === Low ===
        r("low", "WebSocket token 仍通过 URL query 传递",
          "WebSocket 鉴权 token 通过 URL query parameter 传递，可能被日志或代理记录泄露。",
          "token 可能被日志或中间代理记录泄露。",
          "后续改成首帧认证或更安全的鉴权方式。",
          line -> line.toLowerCase(Locale.ROOT).contains("websocket token") && line.toLowerCase(Locale.ROOT).contains("query parameter")
        ),
        r("low", "无害静态资源请求",
          "访问 /、/favicon.ico、/robots.txt 等无害静态资源时未找到。",
          "无实际影响。",
          "无需处理，属正常浏览器行为。",
          line -> containsAny(line, "NoResourceFoundException", "No static resource")
              && !containsAny(line, ".env", "/wp-admin", "/phpmyadmin", "/actuator", "/adminer", "/.git")
        )
    );

    // --- Analyze ---

    public AnalysisResult analyze(List<String> lines, String analysisMode) {
        AnalysisResult result = new AnalysisResult();
        Map<String, List<String>> evidenceByTitle = new LinkedHashMap<>();

        for (String line : lines) {
            for (Rule rule : rules) {
                if (rule.matcher.matches(line)) {
                    evidenceByTitle.computeIfAbsent(rule.title, k -> new ArrayList<>()).add(sanitizeEvidence(line));
                }
            }
        }

        List<OpsAdminLogAiAnalysisResp.LogProblem> problems = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : evidenceByTitle.entrySet()) {
            String title = entry.getKey();
            Rule rule = rules.stream().filter(r -> r.title.equals(title)).findFirst().orElse(null);
            if (rule == null) continue;

            List<String> evidence = entry.getValue();
            int count = evidence.size();
            List<String> displayEvidence = count > 3 ? evidence.subList(0, 3) : evidence;

            OpsAdminLogAiAnalysisResp.LogProblem problem = new OpsAdminLogAiAnalysisResp.LogProblem();
            problem.setTitle(count > 1 ? title + "（出现 " + count + " 次）" : title);
            problem.setSeverity(rule.severity);
            problem.setEvidence(displayEvidence);
            problem.setReason(count > 1 ? rule.reason + " 该问题共出现 " + count + " 次。" : rule.reason);
            problem.setImpact(rule.impact);
            problem.setSuggestion(rule.suggestion);
            problems.add(problem);
        }

        // Dedup: same root title (before count suffix), keep highest severity
        Map<String, OpsAdminLogAiAnalysisResp.LogProblem> dedup = new LinkedHashMap<>();
        for (OpsAdminLogAiAnalysisResp.LogProblem p : problems) {
            String base = p.getTitle().replaceFirst("（出现 \\d+ 次）$", "");
            OpsAdminLogAiAnalysisResp.LogProblem existing = dedup.get(base);
            if (existing == null || severityOrder(p.getSeverity()) < severityOrder(existing.getSeverity())) {
                dedup.put(base, p);
            }
        }
        problems = new ArrayList<>(dedup.values());

        problems.sort((a, b) -> Integer.compare(severityOrder(a.getSeverity()), severityOrder(b.getSeverity())));

        result.problems = problems;
        result.suggestions = problems.stream().map(OpsAdminLogAiAnalysisResp.LogProblem::getSuggestion).distinct().toList();

        List<String> related = new ArrayList<>();
        for (OpsAdminLogAiAnalysisResp.LogProblem p : problems) {
            if (p.getEvidence() != null) related.addAll(p.getEvidence());
        }
        if (related.size() > 20) related = related.subList(0, 20);
        result.relatedLogs = related;

        if (problems.isEmpty()) {
            result.summary = "当前日志未发现明显致命错误。";
            result.level = "normal";
        } else {
            boolean hasCritical = problems.stream().anyMatch(p -> "critical".equals(p.getSeverity()));
            boolean hasHigh = problems.stream().anyMatch(p -> "high".equals(p.getSeverity()));
            if (hasCritical) {
                result.summary = "日志发现 " + problems.size() + " 个问题，包含严重级别，需立即处理。";
                result.level = "error";
            } else if (hasHigh) {
                result.summary = "日志发现 " + problems.size() + " 个问题，包含高风险项，建议尽快处理。";
                result.level = "warning";
            } else {
                result.summary = "日志发现 " + problems.size() + " 个轻微问题，建议关注。";
                result.level = "warning";
            }
        }

        return result;
    }

    // --- Helpers ---

    private boolean containsAny(String line, String... keywords) {
        for (String kw : keywords) {
            if (line.contains(kw)) return true;
        }
        return false;
    }

    private String sanitizeEvidence(String line) {
        if (line == null) return "";
        return line.replaceAll("password[=:][^\\s,;]+", "password=***")
                   .replaceAll("token=[^&\\s]+", "token=****")
                   .replaceAll("appid=[^&\\s]+", "appid=***")
                   .replaceAll("api[Kk]ey=[^&\\s]+", "apiKey=***")
                   .replaceAll("(?i)authorization[=:][^\\s,;]+", "authorization=***");
    }

    private int severityOrder(String s) {
        return switch (s) {
            case "critical" -> 0;
            case "high" -> 1;
            case "medium" -> 2;
            case "low" -> 3;
            default -> 4;
        };
    }

    // --- Types ---

    public static class AnalysisResult {
        public String summary;
        public String level;
        public List<OpsAdminLogAiAnalysisResp.LogProblem> problems = Collections.emptyList();
        public List<String> suggestions = Collections.emptyList();
        public List<String> relatedLogs = Collections.emptyList();
    }

    private static class Rule {
        final LogLineMatcher matcher;
        final String severity;
        final String title;
        final String reason;
        final String impact;
        final String suggestion;

        Rule(LogLineMatcher matcher, String severity, String title, String reason, String impact, String suggestion) {
            this.matcher = matcher;
            this.severity = severity;
            this.title = title;
            this.reason = reason;
            this.impact = impact;
            this.suggestion = suggestion;
        }
    }

    @FunctionalInterface
    private interface LogLineMatcher {
        boolean matches(String line);
    }
}

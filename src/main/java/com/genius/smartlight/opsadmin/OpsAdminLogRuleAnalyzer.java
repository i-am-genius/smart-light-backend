package com.genius.smartlight.opsadmin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@Component
public class OpsAdminLogRuleAnalyzer {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private final List<Rule> rules = List.of(
            new Rule(
                    line -> line.contains("JWT secret uses local development default"),
                    "high", "JWT_SECRET 使用开发默认值",
                    "线上环境未配置正式 JWT_SECRET，仍使用开发默认值，存在 token 被伪造的安全风险。",
                    "存在 token 被伪造的安全风险。",
                    "在 systemd 或环境变量中配置长度不少于 32 位的强随机 JWT_SECRET，并重启后端服务。"
            ),
            new Rule(
                    line -> line.contains("JWT secret length is less than 32 characters"),
                    "high", "JWT_SECRET 长度不足",
                    "线上 JWT_SECRET 长度小于 32 字符，容易被暴力破解。",
                    "存在 token 被暴力破解的安全风险。",
                    "使用不少于 32 字符的随机密钥，建议 64 字符以上。"
            ),
            new Rule(
                    line -> line.toLowerCase(Locale.ROOT).contains("websocket token")
                            && line.toLowerCase(Locale.ROOT).contains("query parameter"),
                    "low", "WebSocket token 仍通过 URL query 传递",
                    "WebSocket 鉴权 token 通过 URL query parameter 传递，可能被日志或代理记录泄露。",
                    "token 可能被日志或中间代理记录泄露。",
                    "后续改成首帧认证或更安全的鉴权方式。"
            ),
            new Rule(
                    line -> line.toLowerCase(Locale.ROOT).contains("collect weather failed"),
                    "medium", "天气采集失败",
                    "天气数据采集失败，可能影响光照策略。",
                    "光照策略可能因天气数据缺失而不准确。",
                    "检查天气 API key、店铺城市、经纬度、网络连通性和 API 返回。"
            ),
            new Rule(
                    line -> line.contains("SQLSyntaxErrorException"),
                    "high", "SQL 语法错误",
                    "应用执行 SQL 时出现语法错误，功能异常。",
                    "对应的数据库操作失败。",
                    "检查 SQL 语句拼写、MyBatis 映射和数据库表结构。"
            ),
            new Rule(
                    line -> line.contains("AccessDeniedException"),
                    "medium", "权限拒绝",
                    "访问被 Spring Security 拒绝，可能是权限配置或 token 问题。",
                    "用户或请求被拒绝访问。",
                    "检查权限配置、用户角色和 token 有效性。"
            ),
            new Rule(
                    line -> (line.contains("Connection refused") || line.contains("connection refused")),
                    "high", "服务连接失败",
                    "连接被目标服务拒绝，目标服务可能未运行或端口错误。",
                    "依赖服务不可用。",
                    "确认目标服务正在运行且端口配置正确。"
            ),
            new Rule(
                    line -> (line.toLowerCase(Locale.ROOT).contains("timeout")
                            && (line.toLowerCase(Locale.ROOT).contains("read")
                            || line.toLowerCase(Locale.ROOT).contains("connect"))),
                    "medium", "请求超时",
                    "对外部服务的请求超时，可能是网络问题或目标服务响应慢。",
                    "外部服务调用失败，用户体验受影响。",
                    "检查网络连通性、目标服务响应情况，考虑增加超时或添加重试。"
            ),
            new Rule(
                    line -> line.contains("OutOfMemoryError"),
                    "critical", "JVM 内存溢出",
                    "JVM 发生 OutOfMemoryError，应用可能崩溃。",
                    "应用崩溃，服务中断。",
                    "立即检查 JVM 堆配置、内存泄漏点、大对象分配，考虑重启并增大堆内存。"
            ),
            new Rule(
                    line -> line.contains(" 500 ") || line.contains("\"status\":500"),
                    "medium", "接口出现 500 异常",
                    "HTTP 接口返回 500 状态码，存在未处理的异常。",
                    "用户请求失败。",
                    "排查对应接口的异常堆栈，修复代码缺陷。"
            )
    );

    public AnalysisResult analyze(List<String> lines, String analysisMode) {
        AnalysisResult result = new AnalysisResult();
        List<OpsAdminLogAiAnalysisResp.LogProblem> problems = new ArrayList<>();
        Map<String, List<String>> evidenceByTitle = new LinkedHashMap<>();

        for (String line : lines) {
            for (Rule rule : rules) {
                if (rule.matcher.matches(line)) {
                    evidenceByTitle.computeIfAbsent(rule.title, k -> new ArrayList<>()).add(line);
                }
            }
        }

        for (Map.Entry<String, List<String>> entry : evidenceByTitle.entrySet()) {
            String title = entry.getKey();
            Rule rule = rules.stream().filter(r -> r.title.equals(title)).findFirst().orElse(null);
            if (rule == null) continue;

            List<String> evidence = entry.getValue().size() > 5
                    ? entry.getValue().subList(0, 5)
                    : entry.getValue();

            OpsAdminLogAiAnalysisResp.LogProblem problem = new OpsAdminLogAiAnalysisResp.LogProblem();
            problem.setTitle(rule.title);
            problem.setSeverity(rule.severity);
            problem.setEvidence(evidence);
            problem.setReason(rule.reason);
            problem.setImpact(rule.impact);
            problem.setSuggestion(rule.suggestion);
            problems.add(problem);
        }

        problems.sort((a, b) -> {
            int sa = severityOrder(a.getSeverity());
            int sb = severityOrder(b.getSeverity());
            return Integer.compare(sa, sb);
        });

        result.problems = problems;
        result.suggestions = problems.stream()
                .map(OpsAdminLogAiAnalysisResp.LogProblem::getSuggestion)
                .distinct()
                .toList();

        List<String> related = new ArrayList<>();
        for (OpsAdminLogAiAnalysisResp.LogProblem p : problems) {
            if (p.getEvidence() != null) {
                for (String e : p.getEvidence()) {
                    if (!related.contains(e)) related.add(e);
                }
            }
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

    private int severityOrder(String s) {
        return switch (s) {
            case "critical" -> 0;
            case "high" -> 1;
            case "medium" -> 2;
            case "low" -> 3;
            default -> 4;
        };
    }

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

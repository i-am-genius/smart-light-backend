package com.genius.smartlight.opsadmin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class OpsAdminLogAiAnalysisService {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OpsAdminLogReadService logReadService;
    private final OpsAdminLogRuleAnalyzer ruleAnalyzer;
    private final OpsAdminLogSanitizer sanitizer;

    private boolean aiEnabled;
    private String aiBaseUrl;
    private String aiApiKey;
    private String aiModel;
    private int aiTimeoutSeconds;

    private RestTemplate aiRestTemplate;

    @PostConstruct
    public void init() {
        aiEnabled = boolEnv("OPS_AI_ENABLED");
        aiBaseUrl = envOr("OPS_AI_BASE_URL", "");
        aiApiKey = envOr("OPS_AI_API_KEY", "");
        aiModel = envOr("OPS_AI_MODEL", "gpt-3.5-turbo");
        aiTimeoutSeconds = intEnv("OPS_AI_TIMEOUT_SECONDS", 20);

        if (aiEnabled && !aiBaseUrl.isBlank() && !aiApiKey.isBlank()) {
            log.info("[ops-admin] AI analysis enabled: baseUrl={}, model={}", aiBaseUrl, aiModel);
        } else {
            log.info("[ops-admin] AI analysis disabled (OPS_AI_ENABLED={}, hasKey={})", aiEnabled, !aiApiKey.isBlank());
        }
    }

    public OpsAdminLogAiAnalysisResp analyze(OpsAdminLogAiAnalysisReq req) {
        if (!logReadService.isAllowedType(req.getLogType())) {
            throw new IllegalArgumentException("unsupported log type: " + req.getLogType());
        }

        int maxLines = req.getMaxLines() > 0 ? Math.min(req.getMaxLines(), 2000) : 500;
        if (maxLines < 1) maxLines = 500;

        OpsAdminLogReadService.LogReadRequest readReq = new OpsAdminLogReadService.LogReadRequest();
        readReq.setLogType(req.getLogType());
        readReq.setStartTime(req.getStartTime());
        readReq.setEndTime(req.getEndTime());
        readReq.setLevels(req.getLevels());
        readReq.setKeyword(req.getKeyword());
        readReq.setMaxLines(maxLines);
        readReq.setSanitizer(sanitizer);

        OpsAdminLogReadService.LogReadResult readResult;
        if (req.getVisibleLogs() != null && !req.getVisibleLogs().isEmpty()) {
            readResult = logReadService.readFromVisibleLogs(req.getVisibleLogs(), readReq);
        } else {
            readResult = logReadService.read(readReq);
        }
        List<String> lines = readResult.lines;

        OpsAdminLogRuleAnalyzer.AnalysisResult ruleResult = ruleAnalyzer.analyze(lines, req.getAnalysisMode());

        OpsAdminLogAiAnalysisResp resp = new OpsAdminLogAiAnalysisResp();
        resp.setSummary(ruleResult.summary);
        resp.setLevel(ruleResult.level);
        resp.setProblems(ruleResult.problems);
        resp.setSuggestions(ruleResult.suggestions);
        resp.setRelatedLogs(ruleResult.relatedLogs);
        resp.setAnalyzedLineCount(readResult.analyzedLineCount);
        resp.setTruncated(readResult.truncated);
        resp.setAnalysisTime(DT_FMT.format(LocalDateTime.now()));

        boolean aiActuallyUsed = false;
        if (isAiAvailable()) {
            try {
                aiAugment(resp, lines, req.getAnalysisMode());
                aiActuallyUsed = true;
            } catch (Exception e) {
                log.warn("[ops-admin] AI analysis failed, using rule fallback. {}", e.getMessage());
            }
        }

        resp.setAiEnabled(aiActuallyUsed);
        resp.setFallbackUsed(!aiActuallyUsed);
        return resp;
    }

    private boolean isAiAvailable() {
        return aiEnabled && !aiBaseUrl.isBlank() && !aiApiKey.isBlank();
    }

    // ─── AI call + parse ────────────────────────────────────────────

    private void aiAugment(OpsAdminLogAiAnalysisResp resp, List<String> lines, String mode) {
        String aiText = callAi(lines, mode);
        Map<String, Object> parsed = parseAiJson(aiText);

        if (parsed == null) {
            log.warn("[ops-admin] AI returned non-JSON, using rule result");
            return;
        }

        String aiSummary = str(parsed, "summary");
        if (aiSummary != null && !aiSummary.isBlank()) {
            resp.setSummary(aiSummary);
        }

        String aiLevel = str(parsed, "level");
        if (aiLevel != null && !aiLevel.isBlank()) {
            resp.setLevel(normalizeLevel(aiLevel));
        }

        List<Map<String, Object>> rawProblems = list(parsed, "problems");
        if (rawProblems != null && !rawProblems.isEmpty()) {
            List<OpsAdminLogAiAnalysisResp.LogProblem> problems = new ArrayList<>();
            for (Map<String, Object> rp : rawProblems) {
                OpsAdminLogAiAnalysisResp.LogProblem p = new OpsAdminLogAiAnalysisResp.LogProblem();
                p.setTitle(str(rp, "title"));
                p.setSeverity(normalizeSeverity(str(rp, "severity")));
                p.setReason(str(rp, "reason"));
                p.setImpact(str(rp, "impact"));
                p.setSuggestion(str(rp, "suggestion"));
                p.setEvidence(stringList(rp, "evidence"));
                problems.add(p);
            }
            resp.setProblems(problems);
        }

        List<String> aiSuggestions = stringList(parsed, "suggestions");
        if (!aiSuggestions.isEmpty()) {
            resp.setSuggestions(aiSuggestions);
        }

        List<String> aiRelated = stringList(parsed, "relatedLogs");
        if (!aiRelated.isEmpty()) {
            resp.setRelatedLogs(aiRelated);
        }
    }

    private String callAi(List<String> lines, String mode) {
        String systemPrompt = buildSystemPrompt(mode);
        String userPrompt = buildUserPrompt(lines, mode);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", aiModel);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        body.put("temperature", 0.3);
        body.put("max_tokens", 2000);

        String url = aiBaseUrl;
        if (!url.endsWith("/v1/chat/completions") && !url.endsWith("/chat/completions")) {
            url = url.replaceAll("/$", "") + "/v1/chat/completions";
        }

        if (aiRestTemplate == null) {
            aiRestTemplate = new RestTemplate();
        }

        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.setBearerAuth(aiApiKey);

        org.springframework.http.HttpEntity<Map<String, Object>> entity =
                new org.springframework.http.HttpEntity<>(body, headers);

        @SuppressWarnings("unchecked")
        Map<String, Object> aiResp = aiRestTemplate.postForObject(url, entity, Map.class);

        if (aiResp != null && aiResp.containsKey("choices")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) aiResp.get("choices");
            if (choices != null && !choices.isEmpty()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                if (message != null) {
                    String content = (String) message.get("content");
                    if (content != null && !content.isBlank()) {
                        return content;
                    }
                }
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseAiJson(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String json = raw.trim();

        // Strip ```json ... ``` code fences
        if (json.startsWith("```")) {
            int start = json.indexOf('\n');
            int end = json.lastIndexOf("```");
            if (start >= 0 && end > start) {
                json = json.substring(start, end).trim();
            }
        }

        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.debug("[ops-admin] AI response is not valid JSON, raw length={}", raw.length());
            return null;
        }
    }

    // ─── Prompt builders ────────────────────────────────────────────

    private String buildSystemPrompt(String mode) {
        return "你是服务器运维日志分析助手。请根据日志内容输出严格 JSON，不要输出 Markdown，不要输出代码块，不要输出多余解释文字。\n"
                + buildModeInstruction(mode)
                + "\n\n返回格式必须是：\n"
                + "{\n"
                + "  \"summary\": \"不超过120字的总体结论\",\n"
                + "  \"level\": \"normal|warning|danger\",\n"
                + "  \"problems\": [\n"
                + "    {\n"
                + "      \"title\": \"问题标题\",\n"
                + "      \"severity\": \"low|medium|high|critical\",\n"
                + "      \"reason\": \"原因\",\n"
                + "      \"impact\": \"影响\",\n"
                + "      \"suggestion\": \"处理建议\",\n"
                + "      \"evidence\": [\"相关日志片段，原样引用，不超过3条\"]\n"
                + "    }\n"
                + "  ],\n"
                + "  \"suggestions\": [\"综合建议1\", \"综合建议2\"],\n"
                + "  \"relatedLogs\": [\"关键日志原文，不超过10条\"]\n"
                + "}\n\n"
                + "如果没有发现明显问题，返回：\n"
                + "{\"summary\":\"当前日志未发现明显致命错误。\",\"level\":\"normal\",\"problems\":[],\"suggestions\":[],\"relatedLogs\":[]}";
    }

    private String buildModeInstruction(String mode) {
        return switch (mode != null ? mode : "diagnose") {
            case "summary" ->
                "【概览总结模式】总结日志整体状态，不要展开太多细节。summary 控制在120字以内，problems 最多3个。只关注最突出的问题。";

            case "diagnose" ->
                "【故障诊断模式】重点找出故障原因和异常链路，给出具体修复步骤。problems 要详细，包含 reason/impact/suggestion。注意关联错误与上游调用，找出根因。";

            case "security" ->
                "【安全风险分析模式】只关注安全相关问题：token 泄露、password/secret 明文、鉴权失败(401/403)、暴力尝试、敏感信息泄露、权限绕过。忽略普通业务日志和一般性告警。如果没有发现安全风险，明确说未发现明显安全风险。";

            case "performance" ->
                "【性能问题分析模式】只关注性能：timeout、慢请求(slow)、高耗时、连接池耗尽、OOM、GC停顿、CPU飙升、数据库慢查询、大对象分配。如果没有性能瓶颈，明确说未发现明显性能瓶颈。";

            default ->
                "【故障诊断模式】重点找出故障原因和异常链路，给出具体修复步骤。problems 要详细。";
        };
    }

    private String buildUserPrompt(List<String> lines, String mode) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下是服务器日志，共 ").append(lines.size()).append(" 行：\n\n```\n");
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        sb.append("```\n\n");
        sb.append("请严格按照要求的 JSON 格式输出分析结果，不要输出其他内容。");
        return sb.toString();
    }

    // ─── Helpers ─────────────────────────────────────────────────────

    private String normalizeLevel(String lv) {
        if (lv == null) return "warning";
        return switch (lv.toLowerCase(Locale.ROOT)) {
            case "normal", "info", "ok" -> "normal";
            case "warning", "warn" -> "warning";
            case "danger", "error", "critical" -> "error";
            default -> "warning";
        };
    }

    private String normalizeSeverity(String s) {
        if (s == null) return "medium";
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "low", "info" -> "low";
            case "medium", "warn", "warning" -> "medium";
            case "high" -> "high";
            case "critical", "fatal" -> "critical";
            default -> "medium";
        };
    }

    @SuppressWarnings("unchecked")
    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof String ? (String) v : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof List<?> list) {
            if (!list.isEmpty() && list.get(0) instanceof Map) {
                return (List<Map<String, Object>>) list;
            }
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof String s) result.add(s);
                else if (item instanceof Map) result.add(item.toString());
            }
            return result;
        }
        return Collections.emptyList();
    }

    private static boolean boolEnv(String key) {
        String v = System.getenv(key);
        if (v == null) v = System.getProperty(key);
        return "true".equalsIgnoreCase(v) || "1".equals(v) || "yes".equalsIgnoreCase(v);
    }

    private static String envOr(String key, String fallback) {
        String v = System.getenv(key);
        if (v != null && !v.isBlank()) return v.trim();
        v = System.getProperty(key);
        if (v != null && !v.isBlank()) return v.trim();
        return fallback;
    }

    private static int intEnv(String key, int fallback) {
        String v = System.getenv(key);
        if (v == null) v = System.getProperty(key);
        if (v != null && !v.isBlank()) {
            try { return Integer.parseInt(v.trim()); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }
}

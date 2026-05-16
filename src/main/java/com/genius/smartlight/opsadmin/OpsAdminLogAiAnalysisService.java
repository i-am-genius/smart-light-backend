package com.genius.smartlight.opsadmin;

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

        OpsAdminLogReadService.LogReadResult readResult = logReadService.read(readReq);
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

    private void aiAugment(OpsAdminLogAiAnalysisResp resp, List<String> lines, String mode) {
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
                        resp.setSummary(resp.getSummary() + " [AI] " + content);
                    }
                }
            }
        }
    }

    private String buildSystemPrompt(String mode) {
        String base = "You are a DevOps log analysis expert. Analyze the following logs and provide concise diagnosis.";
        return switch (mode != null ? mode : "diagnose") {
            case "security" -> base + " Focus on security risks: auth failures, permissions, sensitive info leaks.";
            case "performance" -> base + " Focus on performance: slow queries, timeouts, memory, connection pools.";
            case "summary" -> base + " Provide overall summary and key metrics.";
            default -> base + " Focus on errors and exceptions, root cause and fix suggestions.";
        };
    }

    private String buildUserPrompt(List<String> lines, String mode) {
        StringBuilder sb = new StringBuilder();
        sb.append("Analyze following server log lines, total ").append(lines.size()).append(" lines:\n\n");
        sb.append("```\n");
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        sb.append("```\n");
        sb.append("Return JSON: {\"summary\":\"...\", \"findings\":[...]}");
        return sb.toString();
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

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
            log.info("[ops-admin] AI analysis enabled: baseUrl={}, model={}, timeout={}s", aiBaseUrl, aiModel, aiTimeoutSeconds);
            org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                    new org.springframework.http.client.SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(java.time.Duration.ofSeconds(aiTimeoutSeconds));
            factory.setReadTimeout(java.time.Duration.ofSeconds(aiTimeoutSeconds));
            aiRestTemplate = new RestTemplate(factory);
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

        // Group into events first, then filter out internal AI events at event level
        List<OpsAdminLogService.LogEvent> events = OpsAdminLogService.groupLogEvents(String.join("\n", lines));
        events = events.stream().filter(e -> !isInternalAiEvent(e)).toList();
        lines = events.stream().flatMap(e -> e.lines.stream()).toList();

        if (!lines.isEmpty() && !events.isEmpty()) {
            String firstLine = lines.get(0);
            String lastLine = lines.get(lines.size() - 1);
            log.info("[ops-admin] AI input lines snapshot, inputLines={}, inputEvents={}, firstEventTime={}, lastEventTime={}, firstLineHash={}, lastLineHash={}, allLinesHash={}",
                    lines.size(), events.size(),
                    extractTimePrefix(events.get(0).firstLine),
                    extractTimePrefix(events.get(events.size() - 1).firstLine),
                    Math.abs(firstLine.hashCode()), Math.abs(lastLine.hashCode()),
                    Math.abs(String.join("", lines).hashCode()));
        }
        String diagnosticLines = OpsAdminLogService.buildDiagnosticContext(events, 4000);
        List<String> diagList = List.of(diagnosticLines.split("\n"));

        boolean detailMode = Boolean.TRUE.equals(req.getDetailMode());
        OpsAdminLogRuleAnalyzer.AnalysisResult ruleResult = ruleAnalyzer.analyze(diagList, req.getAnalysisMode());

        boolean hasStacks = diagnosticLines.lines().anyMatch(this::isStackTraceLine);
        log.info("[ops-admin] AI analysis context prepared, hasStacks={}, eventCount={}, contextLineCount={}, rawLineCount={}, detailMode={}, logType={}",
                hasStacks, events.size(), diagList.size(), lines.size(), detailMode, req.getLogType());

        if (log.isDebugEnabled()) {
            String preview = diagnosticLines.length() > 200
                    ? diagnosticLines.substring(0, 200) : diagnosticLines;
            log.debug("[ops-admin] AI contextPreview={}", sanitizeLog(preview).replace('\n', ' '));
        }

        OpsAdminLogAiAnalysisResp resp = new OpsAdminLogAiAnalysisResp();
        resp.setSummary(ruleResult.summary);
        resp.setLevel(ruleResult.level);
        resp.setProblems(ruleResult.problems);
        resp.setSuggestions(ruleResult.suggestions);
        resp.setRelatedLogs(ruleResult.relatedLogs);
        resp.setAnalyzedLineCount(readResult.analyzedLineCount);
        resp.setAnalyzedEventCount(readResult.analyzedEventCount > 0 ? readResult.analyzedEventCount : events.size());
        resp.setTruncated(readResult.truncated);
        resp.setAnalysisTime(DT_FMT.format(LocalDateTime.now()));

        boolean aiActuallyUsed = false;
        String fallbackReason = null;
        String skipReason = null;
        boolean aiAvailable = isAiAvailable();

        if (aiAvailable) {
            boolean needAi = (detailMode && hasStacks) || !isRuleEnough(ruleResult, hasStacks);
            if (needAi) {
                log.info("[ops-admin] Rule analysis not enough, calling AI, hasStacks={}, detailMode={}, ruleLevel={}, ruleProblems={}, inputLines={}",
                        hasStacks, detailMode, ruleResult.level, ruleResult.problems.size(), lines.size());
                try {
                    String displayOrder = req.getDisplayOrder() != null ? req.getDisplayOrder() : "oldestFirst";
                    boolean onlyErrorWarn = Boolean.TRUE.equals(req.getOnlyErrorWarn());
                    aiActuallyUsed = aiAugment(resp, lines, req.getAnalysisMode(), detailMode, req.getLogType(),
                            displayOrder, onlyErrorWarn);
                    if (!aiActuallyUsed) fallbackReason = "AI_RETURNED_NON_JSON";
                } catch (Exception e) {
                    String errMsg = e.getMessage() != null ? e.getMessage() : "";
                    String errLower = errMsg.toLowerCase();
                    fallbackReason = errLower.contains("timed out") || errLower.contains("timeout")
                            ? "DEEPSEEK_TIMEOUT"
                            : "AI_CALL_FAILED";
                    log.warn("[ops-admin] AI analysis failed, using rule fallback. {}", errMsg);
                }
            } else {
                skipReason = "RULE_ENOUGH";
                log.info("[ops-admin] AI skipped because rule analysis is enough, reason={}, ruleLevel={}, ruleProblems={}, hasStacks={}, detailMode={}, inputLines={}",
                        skipReason, ruleResult.level, ruleResult.problems.size(), hasStacks, detailMode, lines.size());
            }
        } else {
            fallbackReason = "AI_UNAVAILABLE";
        }

        resp.setAiEnabled(aiAvailable);
        resp.setFallbackUsed(!aiActuallyUsed && fallbackReason != null);
        resp.setAnalysisEngine(aiActuallyUsed ? "ai" : "rule");
        resp.setFallbackReason(fallbackReason != null ? fallbackReason : skipReason);

        // Fallback traceAnalysis from RuleAnalyzer if AI didn't provide one
        if (resp.getTraceAnalysis() == null && detailMode && diagnosticLines.lines().anyMatch(this::isStackTraceLine)) {
            resp.setTraceAnalysis(buildTraceAnalysisFromLogs(lines, events));
        }

        return resp;
    }

    public OpsAdminDeepSeekBalanceResp getBalance() {
        OpsAdminDeepSeekBalanceResp resp = new OpsAdminDeepSeekBalanceResp();
        resp.setUpdateTime(DT_FMT.format(LocalDateTime.now()));

        if (!aiEnabled || aiApiKey.isBlank()) {
            resp.setConfigured(false);
            resp.setAvailable(false);
            resp.setMessage("DeepSeek API Key 未配置");
            return resp;
        }

        resp.setConfigured(true);

        try {
            String balanceUrl = "https://api.deepseek.com/user/balance";

            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setBearerAuth(aiApiKey);

            org.springframework.http.HttpEntity<Void> entity =
                    new org.springframework.http.HttpEntity<>(headers);

            org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                    new org.springframework.http.client.SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(java.time.Duration.ofSeconds(5));
            factory.setReadTimeout(java.time.Duration.ofSeconds(5));
            RestTemplate restTemplate = new RestTemplate(factory);

            org.springframework.http.ResponseEntity<Map> responseEntity;
            try {
                responseEntity = restTemplate.exchange(
                        balanceUrl,
                        org.springframework.http.HttpMethod.GET,
                        entity,
                        Map.class
                );
            } catch (org.springframework.web.client.HttpClientErrorException e) {
                int sc = e.getStatusCode().value();
                if (sc == 401 || sc == 403) {
                    resp.setMessage("DeepSeek API Key 无效或无权限");
                } else if (sc == 404) {
                    resp.setMessage("DeepSeek 余额接口地址可能错误");
                } else {
                    resp.setMessage("DeepSeek 余额查询失败");
                }
                resp.setAvailable(false);
                log.warn("[ops-admin] DeepSeek balance query HTTP {}, status={}", e.getMessage(), sc);
                return resp;
            } catch (org.springframework.web.client.ResourceAccessException e) {
                resp.setAvailable(false);
                resp.setMessage("DeepSeek 余额查询超时");
                log.warn("[ops-admin] DeepSeek balance query timeout");
                return resp;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> dsResp = responseEntity.getBody();

            if (dsResp == null) {
                resp.setAvailable(false);
                resp.setMessage("DeepSeek 余额查询返回为空");
                return resp;
            }

            resp.setAvailable(Boolean.TRUE.equals(dsResp.get("is_available")));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawInfos = (List<Map<String, Object>>) dsResp.get("balance_infos");
            if (rawInfos != null) {
                List<OpsAdminDeepSeekBalanceResp.BalanceInfo> infos = new java.util.ArrayList<>();
                for (Map<String, Object> raw : rawInfos) {
                    OpsAdminDeepSeekBalanceResp.BalanceInfo info = new OpsAdminDeepSeekBalanceResp.BalanceInfo();
                    info.setCurrency(strVal(raw, "currency"));
                    info.setTotalBalance(strVal(raw, "total_balance"));
                    info.setGrantedBalance(strVal(raw, "granted_balance"));
                    info.setToppedUpBalance(strVal(raw, "topped_up_balance"));
                    infos.add(info);
                }
                resp.setBalanceInfos(infos);
            }

            log.info("[ops-admin] DeepSeek balance queried, available={}, currencyCount={}",
                    resp.isAvailable(), resp.getBalanceInfos() != null ? resp.getBalanceInfos().size() : 0);
        } catch (Exception e) {
            resp.setAvailable(false);
            resp.setMessage("DeepSeek 余额查询失败");
            log.warn("[ops-admin] DeepSeek balance query failed: {}", e.getMessage());
        }

        return resp;
    }

    private static String strVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? String.valueOf(v) : null;
    }

    private boolean isAiAvailable() {
        return aiEnabled && !aiBaseUrl.isBlank() && !aiApiKey.isBlank();
    }

    private boolean isRuleEnough(OpsAdminLogRuleAnalyzer.AnalysisResult ruleResult, boolean hasStacks) {
        // Rule found concrete problems: enough without AI
        if (!ruleResult.problems.isEmpty()
                && ruleResult.level != null
                && !"normal".equals(ruleResult.level)
                && !ruleResult.suggestions.isEmpty()
                && ruleResult.summary != null && !ruleResult.summary.isBlank()
                && !ruleResult.relatedLogs.isEmpty()) {
            return true;
        }
        // Rule says normal and has no problems: clean log, AI not needed
        if ("normal".equals(ruleResult.level)
                && ruleResult.problems.isEmpty()
                && !hasStacks) {
            return true;
        }
        // Otherwise rule is not enough
        return false;
    }

    // ─── AI call + parse ────────────────────────────────────────────

    private boolean aiAugment(OpsAdminLogAiAnalysisResp resp, List<String> lines, String mode, boolean detailMode,
                               String logType, String displayOrder, boolean onlyErrorWarn) {
        String aiText = callAi(lines, mode, detailMode, logType, displayOrder, onlyErrorWarn);
        Map<String, Object> parsed = parseAiJson(aiText);

        if (parsed == null) {
            String text = aiText != null ? aiText : "";
            int len = text.length();
            log.warn("[ops-admin] AI returned non-JSON, using rule result, contentLength={}, startsWithBrace={}, endsWithBrace={}, containsJsonFence={}, firstBraceIndex={}, lastBraceIndex={}, model={}, analysisMode={}, detailMode={}, logType={}",
                    len, len > 0 && text.charAt(0) == '{', len > 0 && text.charAt(len - 1) == '}',
                    text.contains("```json") || text.contains("```"),
                    text.indexOf('{'), text.lastIndexOf('}'),
                    aiModel, mode, detailMode, logType);
            return false;
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

        // Parse traceAnalysis from AI response
        Map<String, Object> taMap = map(parsed, "traceAnalysis");
        if (taMap != null) {
            OpsAdminLogAiAnalysisResp.TraceAnalysis ta = new OpsAdminLogAiAnalysisResp.TraceAnalysis();
            ta.setEntryPoint(str(taMap, "entryPoint"));
            ta.setProjectCallChain(stringList(taMap, "projectCallChain"));
            ta.setLayerType(str(taMap, "layerType"));
            ta.setRepeatedLocation(str(taMap, "repeatedLocation"));
            ta.setRootCauseCategory(str(taMap, "rootCauseCategory"));
            ta.setStackSummary(str(taMap, "stackSummary"));
            resp.setTraceAnalysis(ta);
        }
        return true;
    }

    private String callAi(List<String> lines, String mode, boolean detailMode, String logType,
                           String displayOrder, boolean onlyErrorWarn) {
        String systemPrompt = FIXED_SYSTEM_PROMPT;
        String userPrompt = buildUserPrompt(lines, mode, detailMode, logType, displayOrder, onlyErrorWarn);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", aiModel);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        body.put("temperature", 0.3);
        body.put("max_tokens", 2000);
        body.put("response_format", Map.of("type", "json_object"));

        String url = aiBaseUrl;
        if (!url.endsWith("/v1/chat/completions") && !url.endsWith("/chat/completions")) {
            url = url.replaceAll("/$", "") + "/v1/chat/completions";
        }

        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.setBearerAuth(aiApiKey);

        org.springframework.http.HttpEntity<Map<String, Object>> entity =
                new org.springframework.http.HttpEntity<>(body, headers);

        @SuppressWarnings("unchecked")
        Map<String, Object> aiResp = aiRestTemplate.postForObject(url, entity, Map.class);

        if (aiResp != null) {
            logCacheStats(aiResp, mode, detailMode, logType, lines.size());
            if (aiResp.containsKey("choices")) {
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
        }
        return "";
    }

    private static final java.math.BigDecimal MILLION = new java.math.BigDecimal("1000000");
    private static final java.math.BigDecimal HIT_PRICE  = new java.math.BigDecimal("0.02");
    private static final java.math.BigDecimal MISS_PRICE = new java.math.BigDecimal("1");
    private static final java.math.BigDecimal OUTPUT_PRICE = new java.math.BigDecimal("2");

    private void logCacheStats(Map<String, Object> aiResp, String mode, boolean detailMode, String logType, int inputLines) {
        @SuppressWarnings("unchecked")
        Map<String, Object> usage = (Map<String, Object>) aiResp.get("usage");
        if (usage == null) {
            log.info("[ops-admin] DeepSeek cache stats unavailable, model={}, analysisMode={}, detailMode={}, logType={}",
                    aiModel, mode, detailMode, logType);
            return;
        }

        Long hitTokens = toLong(usage.get("prompt_cache_hit_tokens"));
        Long missTokens = toLong(usage.get("prompt_cache_miss_tokens"));
        if (hitTokens == null || missTokens == null) {
            log.info("[ops-admin] DeepSeek cache stats unavailable, model={}, analysisMode={}, detailMode={}, logType={}",
                    aiModel, mode, detailMode, logType);
            return;
        }

        long hit = hitTokens;
        long miss = missTokens;
        long total = hit + miss;
        double hitRate = total > 0 ? (double) hit / total * 100.0 : 0.0;
        long completion = toLong(usage.get("completion_tokens"), 0L);

        java.math.BigDecimal hitCost = new java.math.BigDecimal(hit).multiply(HIT_PRICE).divide(MILLION, 8, java.math.RoundingMode.HALF_UP);
        java.math.BigDecimal missCost = new java.math.BigDecimal(miss).multiply(MISS_PRICE).divide(MILLION, 8, java.math.RoundingMode.HALF_UP);
        java.math.BigDecimal outputCost = new java.math.BigDecimal(completion).multiply(OUTPUT_PRICE).divide(MILLION, 8, java.math.RoundingMode.HALF_UP);
        java.math.BigDecimal totalCost = hitCost.add(missCost).add(outputCost);

        log.info("[ops-admin] DeepSeek cache stats, hitTokens={}, missTokens={}, completionTokens={}, hitRate={}%, totalPromptCacheTokens={}, cacheHitCostCny={}, cacheMissCostCny={}, outputCostCny={}, estimatedCostCny={}, model={}, analysisMode={}, detailMode={}, logType={}, inputLines={}",
                hit, miss, completion, String.format("%.2f", hitRate), total,
                String.format("%.8f", hitCost), String.format("%.8f", missCost),
                String.format("%.8f", outputCost), String.format("%.8f", totalCost),
                aiModel, mode, detailMode, logType, inputLines);
    }

    private static Long toLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private static long toLong(Object value, long defaultValue) {
        Long v = toLong(value);
        return v != null ? v : defaultValue;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseAiJson(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String fullText = raw.trim();

        // Stage 1: try parsing full content directly
        Map<String, Object> result = tryParseJson(fullText);
        if (result != null) return result;

        // Stage 2: extract from ```json ... ``` or ``` ... ``` code fences
        int fenceStart = fullText.indexOf("```");
        if (fenceStart >= 0) {
            int contentStart = fullText.indexOf('\n', fenceStart);
            if (contentStart < 0) contentStart = fenceStart + 3;
            int fenceEnd = fullText.indexOf("```", contentStart);
            if (fenceEnd > contentStart) {
                String inner = fullText.substring(contentStart, fenceEnd).trim();
                result = tryParseJson(inner);
                if (result != null) return result;
            }
        }

        // Stage 3: extract from first { to last }
        int braceStart = fullText.indexOf('{');
        int braceEnd = fullText.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            String braceJson = fullText.substring(braceStart, braceEnd + 1).trim();
            result = tryParseJson(braceJson);
            if (result != null) return result;
        }

        log.debug("[ops-admin] AI response is not valid JSON, raw length={}", raw.length());
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> tryParseJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    // ─── Fixed system prompt (never changes — enables DeepSeek prefix cache) ─

    private static final String FIXED_SYSTEM_PROMPT =
        "你是服务器运维日志分析助手。请根据日志内容输出严格 JSON，不要输出 Markdown、不要输出代码块、不要输出多余解释文字。\n"
        + "不要编造日志中没有出现的服务、账号、配置项或外部依赖。单个接口失败不要扩大为系统整体不可用。不泄露敏感信息。\n\n"
        + "## 分析模式\n"
        + "根据请求中指定的 analysisMode 选择分析重点：\n"
        + "- diagnose（故障诊断）：重点找出故障原因和异常链路，给出具体修复步骤。problems 要详细。\n"
        + "- security（安全风险）：关注认证失败、权限不足、token 异常、扫描请求、403/401 等安全问题。\n"
        + "- performance（性能问题）：关注 timeout、慢请求、连接池耗尽、OOM、GC 停顿、数据库慢查询。\n"
        + "- summary（概览总结）：总结日志整体状态，summary 不超过 120 字，problems 最多 3 个。\n\n"
        + "## 显示模式\n"
        + "根据请求中指定的 detailMode：\n"
        + "- detailMode=false（简洁模式）：日志已隐藏框架 at 堆栈，只保留关键异常原因。"
        + "请只输出核心问题，不分析完整调用链、不分析异常重复位置、不展开框架包装层级。\n"
        + "- detailMode=true 且存在堆栈（详细模式）：日志包含完整异常堆栈。请输出 traceAnalysis。\n\n"
        + "## 严重级别\n"
        + "- critical（致命）：服务启动失败、关键配置缺失导致启动失败、端口冲突\n"
        + "- high（严重）：数据库认证/连接失败、JWT 密钥不足、JVM 内存溢出、主备天气源全失败且连续多次、AI 服务不可用且无 fallback\n"
        + "- medium（中）：接口 500、JWT 认证失败、权限拒绝、登录失败、天气采集失败（有 fallback）、AI 调用异常（有 RuleAnalyzer fallback）、WebSocket 异常、OTA 失败、请求超时、配置缺失\n"
        + "- low（低）：无害静态资源请求、WebSocket token query 方式提醒\n\n"
        + "## 返回格式\n"
        + "{\n"
        + "  \"summary\": \"不超过 120 字的总体结论\",\n"
        + "  \"level\": \"normal|warning|error|critical\",\n"
        + "  \"problems\": [{\n"
        + "    \"title\": \"问题标题\",\n"
        + "    \"severity\": \"low|medium|high|critical\",\n"
        + "    \"reason\": \"原因\",\n"
        + "    \"impact\": \"影响\",\n"
        + "    \"suggestion\": \"处理建议\",\n"
        + "    \"evidence\": [\"相关日志片段，不长于 200 字，不超过 3 条\"]\n"
        + "  }],\n"
        + "  \"suggestions\": [\"综合建议\"],\n"
        + "  \"relatedLogs\": [\"关键日志原文，不超过 10 条\"],\n"
        + "  \"traceAnalysis\": null\n"
        + "}\n\n"
        + "如果 detailMode=true 且存在堆栈，traceAnalysis 替换为：\n"
        + "\"traceAnalysis\": {\n"
        + "  \"entryPoint\": \"错误从项目哪个类/方法附近抛出（优先 com.genius.smartlight）\",\n"
        + "  \"projectCallChain\": [\"调用链经过的业务代码，最多 5 条\"],\n"
        + "  \"layerType\": \"Controller|Service|Mapper|Scheduler|WebSocket|Filter|Unknown\",\n"
        + "  \"repeatedLocation\": \"同类异常是否重复出现在相同位置\",\n"
        + "  \"rootCauseCategory\": \"Database|Network|FileIO|Permission|Configuration|ThirdParty|BusinessLogic|Unknown\",\n"
        + "  \"stackSummary\": \"不超过 80 字的堆栈关键路径摘要\"\n"
        + "}\n\n"
        + "没有严重问题时返回：\n"
        + "{\"summary\":\"当前日志未发现明显问题。\",\"level\":\"normal\",\"problems\":[],\"suggestions\":[],\"relatedLogs\":[],\"traceAnalysis\":null}";

    private boolean isStackTraceLine(String line) {
        if (line == null) return false;
        return line.matches("^\\s+at\\s+.+")
                || line.matches("^\\s*Caused by:.*")
                || line.matches("^\\s*Suppressed:.*")
                || line.contains("common frames omitted");
    }

    private String buildUserPrompt(List<String> lines, String mode, boolean detailMode, String logType,
                                    String displayOrder, boolean onlyErrorWarn) {
        StringBuilder sb = new StringBuilder();
        sb.append("<LOGS>\n");
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        sb.append("</LOGS>\n\n");
        sb.append("<TASK>\n");
        sb.append("analysisMode: ").append(mode != null ? mode : "diagnose").append("\n");
        sb.append("detailMode: ").append(detailMode).append("\n");
        sb.append("logType: ").append(logType != null ? logType : "backend").append("\n");
        sb.append("logLineCount: ").append(lines.size()).append("\n");
        sb.append("displayOrder: ").append(displayOrder != null ? displayOrder : "oldestFirst").append("\n");
        sb.append("onlyErrorWarn: ").append(onlyErrorWarn).append("\n");
        sb.append("日志按时间正序排列，旧日志在前，新日志在后。");
        sb.append("最新事件位于日志末尾。");
        sb.append("请重点分析日志末尾（最近时间）的事件，同时参考前面的上下文判断根因。\n");
        sb.append("请只输出一个合法 JSON 对象。不要输出 Markdown。不要输出代码块。");
        sb.append("不要输出解释文字。不要在 JSON 前后添加任何内容。");
        sb.append("JSON 必须以 { 开头，以 } 结尾。所有字段名和字符串必须使用双引号。\n");
        sb.append("</TASK>");
        return sb.toString();
    }

    // ─── Helpers ─────────────────────────────────────────────────────

    private OpsAdminLogAiAnalysisResp.TraceAnalysis buildTraceAnalysisFromLogs(List<String> lines, List<OpsAdminLogService.LogEvent> events) {
        OpsAdminLogAiAnalysisResp.TraceAnalysis ta = new OpsAdminLogAiAnalysisResp.TraceAnalysis();

        // Extract entryPoint: first com.genius.smartlight at line, or ERROR event's logger
        String entry = null;
        List<String> projectChain = new ArrayList<>();
        for (String line : lines) {
            if (isStackTraceLine(line) && line.contains("com.genius.smartlight")) {
                if (entry == null) entry = line.replaceFirst("^\\s+at\\s+", "").trim();
                if (projectChain.size() < 5) projectChain.add(line.replaceFirst("^\\s+at\\s+", "").trim());
            }
            // Also capture MyBatis mapper lines
            if (line.contains("### The error may involve") && projectChain.size() < 5) {
                projectChain.add(line.trim());
            }
        }
        if (entry == null) {
            for (OpsAdminLogService.LogEvent ev : events) {
                if (ev.isErrorOrWarn() && ev.logger != null) { entry = ev.logger; break; }
            }
        }
        ta.setEntryPoint(entry != null ? entry : "无法确定");
        ta.setProjectCallChain(projectChain.isEmpty() ? List.of("无项目调用链信息") : projectChain);

        // layerType
        String layerType = "Unknown";
        if (entry != null) {
            if (entry.toLowerCase().contains("controller")) layerType = "Controller";
            else if (entry.toLowerCase().contains("service") || entry.toLowerCase().contains("impl")) layerType = "Service";
            else if (entry.toLowerCase().contains("mapper") || entry.toLowerCase().contains("dal")) layerType = "Mapper/DAO";
            else if (entry.toLowerCase().contains("schedule") || entry.toLowerCase().contains("scheduler")) layerType = "Scheduler";
            else if (entry.toLowerCase().contains("websocket") || entry.toLowerCase().contains("ws")) layerType = "WebSocket";
            else if (entry.toLowerCase().contains("filter") || entry.toLowerCase().contains("security")) layerType = "Filter";
        }
        ta.setLayerType(layerType);
        ta.setRepeatedLocation("未发现重复位置证据");

        // rootCauseCategory
        String rootCause = "Unknown";
        String allText = String.join("\n", lines);
        if (allText.contains("Access denied") || allText.contains("SQLException") || allText.contains("JDBC Connection")) rootCause = "Database";
        else if (allText.contains("Connection refused") || allText.contains("timeout") || allText.contains("502")) rootCause = "Network";
        else if (allText.contains("403") || allText.contains("Forbidden") || allText.contains("AccessDenied")) rootCause = "Permission";
        else if (allText.contains("Could not resolve placeholder") || allText.contains("PlaceholderResolution")) rootCause = "Configuration";
        else if (allText.contains("open-meteo") || allText.contains("deepseek") || allText.contains("API")) rootCause = "ThirdParty";
        ta.setRootCauseCategory(rootCause);

        ta.setStackSummary(projectChain.isEmpty() ? "未提取到项目堆栈信息" : "主要定位至 " + entry);
        return ta;
    }

    private String sanitizeLog(String s) {
        if (s == null) return "";
        return s.replaceAll("password[=:][^\\s,;]+", "password=***")
                .replaceAll("appid=[^&\\s]+", "appid=***");
    }

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
    private static Map<String, Object> map(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Map ? (Map<String, Object>) v : null;
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

    private boolean isInternalAiEvent(OpsAdminLogService.LogEvent event) {
        return event.firstLine != null
                && event.firstLine.contains("OpsAdminLogAiAnalysisService");
    }

    private String extractTimePrefix(String line) {
        if (line == null || line.length() < 23) return "-";
        return line.substring(0, 23);
    }
}

# DeepSeek Prompt Cache Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restructure the AI analysis prompt so systemPrompt is a fixed constant and userPrompt has fixed metadata followed by dynamic log content at the end, maximizing DeepSeek automatic prefix/context cache hit rate.

**Architecture:** Replace the variable `buildSystemPrompt(mode)` with a single `private static final String FIXED_SYSTEM_PROMPT`. Replace `buildUserPrompt(lines, mode, detailMode)` with a fixed-template version that outputs metadata lines first and log content last. Remove `buildModeInstruction()` entirely.

**Tech Stack:** Java 17+, existing `OpsAdminLogAiAnalysisService.java`, no new dependencies, no DTO changes, no frontend changes.

---

## File Structure

| File | Action |
|---|---|
| `OpsAdminLogAiAnalysisService.java` | Modify — prompt methods only |
| No other files | Unchanged |

---

### Task 1: Define FIXED_SYSTEM_PROMPT constant and replace buildSystemPrompt

**Files:**
- Modify: `E:\smart-light-backend\src\main\java\com\genius\smartlight\opsadmin\OpsAdminLogAiAnalysisService.java:258-302`

- [ ] **Step 1: Replace buildSystemPrompt() and buildModeInstruction() with a static constant**

Delete `buildModeInstruction()` (lines 275-302) entirely. Delete the existing `buildSystemPrompt(String mode)` (lines 261-274). Replace with:

```java
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
    + "  \"suggestions\": [\"综合建议1\", \"综合建议2\"],\n"
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
```

- [ ] **Step 2: Update callAi() to use the constant**

Find line ~187 `callAi(List<String> lines, String mode, boolean detailMode)`. Change:

```java
// Before:
String systemPrompt = buildSystemPrompt(mode);

// After:
String systemPrompt = FIXED_SYSTEM_PROMPT;
```

- [ ] **Step 3: Compile backend to verify**

```bash
cd E:\smart-light-backend && ./mvnw.cmd compile
```
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
cd E:\smart-light-backend
git add src/main/java/com/genius/smartlight/opsadmin/OpsAdminLogAiAnalysisService.java
git commit -m "refactor: use fixed system prompt for DeepSeek prefix cache"
```

---

### Task 2: Restructure userPrompt with fixed template

**Files:**
- Modify: `E:\smart-light-backend\src\main\java\com\genius\smartlight\opsadmin\OpsAdminLogAiAnalysisService.java:321-349`

- [ ] **Step 1: Replace buildUserPrompt() with fixed-template version**

Replace the existing `buildUserPrompt(List<String> lines, String mode, boolean detailMode)` (lines 321-349) with:

```java
private String buildUserPrompt(List<String> lines, String mode, boolean detailMode, String logType) {
    StringBuilder sb = new StringBuilder();
    sb.append("analysisMode: ").append(mode != null ? mode : "diagnose").append("\n");
    sb.append("detailMode: ").append(detailMode).append("\n");
    sb.append("logType: ").append(logType != null ? logType : "backend").append("\n");
    sb.append("logLineCount: ").append(lines.size()).append("\n\n");
    sb.append("<LOGS>\n");
    for (String line : lines) {
        sb.append(line).append('\n');
    }
    sb.append("</LOGS>");
    return sb.toString();
}
```

Note: The `mode` parameter is still used in the metadata line but the system prompt no longer varies per mode. The `detailMode` is also included as metadata. The `logType` parameter must be added to the method signature.

- [ ] **Step 2: Add logType parameter and update callAi()**

Find line ~187 `callAi(List<String> lines, String mode, boolean detailMode)`. Add `String logType` parameter:

```java
// Before:
private String callAi(List<String> lines, String mode, boolean detailMode) {

// After:
private String callAi(List<String> lines, String mode, boolean detailMode, String logType) {
```

And update the userPrompt call inside:

```java
// Before:
String userPrompt = buildUserPrompt(lines, mode, detailMode);

// After:
String userPrompt = buildUserPrompt(lines, mode, detailMode, logType);
```

- [ ] **Step 3: Update callAi() call sites**

Find line ~123 `aiAugment(resp, lines, req.getAnalysisMode(), detailMode)` which calls `callAi(lines, mode, detailMode)` at line ~129. Update:

```java
// Before (in aiAugment):
String aiText = callAi(lines, mode, detailMode);

// After:
String aiText = callAi(lines, mode, detailMode, "backend");
```

But we should pass the real logType. Add it to `aiAugment`'s signature:

```java
// Before:
private void aiAugment(OpsAdminLogAiAnalysisResp resp, List<String> lines, String mode, boolean detailMode) {

// After:
private void aiAugment(OpsAdminLogAiAnalysisResp resp, List<String> lines, String mode, boolean detailMode, String logType) {
```

And in the call:

```java
String aiText = callAi(lines, mode, detailMode, logType);
```

Then update the call site at line ~104:

```java
// Before:
aiAugment(resp, lines, req.getAnalysisMode(), detailMode);

// After:
aiAugment(resp, lines, req.getAnalysisMode(), detailMode, req.getLogType());
```

- [ ] **Step 4: Remove now-unused mode parameter from buildUserPrompt**

The `mode` and `detailMode` parameters to `buildUserPrompt` are now only used as metadata values. Keep them — they provide useful context to the AI.

Verify `hasStackTraces` detection is no longer needed in `buildUserPrompt` since the concise/detailed mode instructions are now in the fixed system prompt. The `isStackTraceLine()` method can remain (still used by `buildTraceAnalysisFromLogs`).

- [ ] **Step 5: Remove imports no longer needed**

Check if any imports were only used by `buildModeInstruction()`. The method used `switch` and string return — no special imports. No cleanup needed.

- [ ] **Step 6: Compile backend to verify**

```bash
cd E:\smart-light-backend && ./mvnw.cmd compile
```
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
cd E:\smart-light-backend
git add src/main/java/com/genius/smartlight/opsadmin/OpsAdminLogAiAnalysisService.java
git commit -m "refactor: restructure user prompt with fixed template for DeepSeek cache"
```

---

### Task 3: Validation — verify prefix stability and functionality

- [ ] **Step 1: Full backend build**

```bash
cd E:\smart-light-backend && ./mvnw.cmd -DskipTests package
```
Expected: BUILD SUCCESS

- [ ] **Step 2: Verify jar**

```bash
jar tf target/smart-light-backend-0.0.1-SNAPSHOT.jar | findstr application.yaml
```
Expected: `BOOT-INF/classes/application.yaml`

- [ ] **Step 3: Verify systemPrompt is a constant**

```bash
grep -n "FIXED_SYSTEM_PROMPT" E:\smart-light-backend\src\main\java\com\genius\smartlight\opsadmin\OpsAdminLogAiAnalysisService.java
```
Expected: One declaration + one usage in callAi()

- [ ] **Step 4: Verify buildModeInstruction is removed**

```bash
grep -n "buildModeInstruction" E:\smart-light-backend\src\main\java\com\genius\smartlight\opsadmin\OpsAdminLogAiAnalysisService.java
```
Expected: No results (or only in comments)

- [ ] **Step 5: Verify userPrompt structure is fixed**

Confirm the userPrompt always starts with the four metadata lines followed by `<LOGS>`, and log content is always last. Check that no variable text (except the metadata values themselves) appears before the log block.

- [ ] **Step 6: Manual cache-hit verification (informal)**

Send two consecutive requests with:
- Same analysisMode, same detailMode, same logType
- Different log content (different date/filter)
Expected behavior: DeepSeek should cache the prefix (systemPrompt + userPrompt metadata) and only process the log content. This is verified by shorter response times on the second request.

- [ ] **Step 7: Verify concise/detailed mode still works**

Send a concise mode request: `detailMode: false`. Verify:
- systemPrompt contains concise mode rules
- AI does NOT output traceAnalysis details
- RuleAnalyzer fallback still works

Send a detailed mode request: `detailMode: true` with stack traces. Verify:
- systemPrompt contains detailed mode rules
- AI outputs traceAnalysis when stacks exist
- Frontend displays traceAnalysis if returned

- [ ] **Step 8: Commit final validation**

```bash
cd E:\smart-light-backend
git add -A && git commit -m "chore: validate prompt cache optimization"
```

---

## Verification Checklist

- [ ] `buildSystemPrompt()` replaced by `FIXED_SYSTEM_PROMPT` constant
- [ ] `buildModeInstruction()` removed entirely
- [ ] `buildUserPrompt()` uses fixed template: metadata first, logs last
- [ ] `callAi()` signature updated for `logType` parameter
- [ ] `aiAugment()` signature updated for `logType` parameter
- [ ] No DTO changes
- [ ] No frontend changes
- [ ] No RuleAnalyzer changes
- [ ] `isStackTraceLine()` still present (used by `buildTraceAnalysisFromLogs`)
- [ ] `normalizeLevel()` unchanged
- [ ] `buildDiagnosticContext()` unchanged
- [ ] `aiTimeoutSeconds` RestTemplate unchanged (already fixed in previous work)
- [ ] Backend compile passes
- [ ] Backend package passes
- [ ] Jar contains application.yaml

## Risk Mitigation

| Risk | Mitigation |
|---|---|
| Fixed systemPrompt too large (~1400 tokens) | Acceptable trade-off: larger but cacheable prefix is better than smaller but variable prefix |
| logType parameter missed in some call path | Compile-time check — method signature change forces all callers to update |
| Concise mode prompt no longer varies per response | All mode rules are now in systemPrompt; AI selects based on metadata fields |
| traceAnalysis schema in systemPrompt might not match actual DTO | Schema is a subset of DTO fields; AI may return extra fields which are ignored |

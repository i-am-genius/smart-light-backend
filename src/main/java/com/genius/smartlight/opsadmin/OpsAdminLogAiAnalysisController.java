package com.genius.smartlight.opsadmin;

import com.genius.smartlight.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/ops-admin/logs")
@RequiredArgsConstructor
public class OpsAdminLogAiAnalysisController {

    private final OpsAdminLogAiAnalysisService aiAnalysisService;

    @PostMapping("/ai-analysis")
    public ApiResponse<OpsAdminLogAiAnalysisResp> analyze(@Valid @RequestBody OpsAdminLogAiAnalysisReq req) {
        try {
            OpsAdminLogAiAnalysisResp resp = aiAnalysisService.analyze(req);
            return ApiResponse.success(resp);
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        } catch (Exception e) {
            log.error("[ops-admin] AI analysis failed", e);
            return ApiResponse.fail("分析失败，请稍后重试");
        }
    }

    @GetMapping("/deepseek-balance")
    public ApiResponse<OpsAdminDeepSeekBalanceResp> getDeepSeekBalance() {
        try {
            OpsAdminDeepSeekBalanceResp resp = aiAnalysisService.getBalance();
            return ApiResponse.success(resp);
        } catch (Exception e) {
            log.error("[ops-admin] DeepSeek balance query failed", e);
            return ApiResponse.fail("余额查询失败");
        }
    }
}

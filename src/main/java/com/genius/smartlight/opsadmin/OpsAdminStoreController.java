package com.genius.smartlight.opsadmin;

import com.genius.smartlight.common.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

@Slf4j
@RestController
@RequestMapping("/ops-admin/stores")
@RequiredArgsConstructor
public class OpsAdminStoreController {

    private final OpsAdminStoreService storeService;
    private final OpsAdminStoreExportService exportService;

    @GetMapping("/page")
    public ApiResponse<Map<String, Object>> page(@ModelAttribute OpsAdminStorePageReq req) {
        try {
            List<OpsAdminStoreResp> rows = storeService.page(req);
            int total = storeService.count(req);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("rows", rows);
            result.put("total", total);
            result.put("page", req.getPage());
            result.put("pageSize", req.getPageSize());
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("[ops-admin] Failed to query stores page", e);
            return ApiResponse.fail("查询店铺列表失败: " + e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public ApiResponse<OpsAdminStoreDetailResp> detail(@PathVariable Long id) {
        try {
            OpsAdminStoreDetailResp detail = storeService.detail(id);
            if (detail == null) {
                return new ApiResponse<>(404, null, "店铺不存在");
            }
            return ApiResponse.success(detail);
        } catch (Exception e) {
            log.warn("[ops-admin] Failed to query store detail id={}: {}", id, e.getMessage());
            return ApiResponse.fail("查询店铺详情失败: " + e.getMessage());
        }
    }

    @GetMapping("/export")
    public void export(@ModelAttribute OpsAdminStorePageReq req, HttpServletResponse response) {
        try {
            byte[] csv = exportService.exportCsv(req);
            String filename = exportService.exportFilename();
            response.setContentType("text/csv;charset=UTF-8");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + filename + "\"");
            response.setContentLength(csv.length);
            response.getOutputStream().write(csv);
            response.getOutputStream().flush();
            log.info("[ops-admin] Store export downloaded: {} ({} bytes)", filename, csv.length);
        } catch (Exception e) {
            log.error("[ops-admin] Failed to export stores CSV", e);
            try {
                response.setStatus(500);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":500,\"msg\":\"导出失败: " + e.getMessage() + "\"}");
            } catch (Exception ignored) {}
        }
    }

    @PostMapping("/export/time-series")
    public ApiResponse<List<Map<String, Object>>> exportTimeSeries(@RequestBody OpsAdminTimeSeriesExportReq req) {
        try {
            if (req.getStoreIds() == null || req.getStoreIds().isEmpty()) {
                return ApiResponse.fail("请选择要导出的店铺");
            }
            List<Map<String, Object>> rows = storeService.timeSeriesExport(req);
            log.info("[ops-admin] Time-series export: {} stores, {} rows", req.getStoreIds().size(), rows.size());
            return ApiResponse.success(rows);
        } catch (Exception e) {
            log.error("[ops-admin] Failed to export time-series data", e);
            return ApiResponse.fail("导出时间序列数据失败: " + e.getMessage());
        }
    }

    @GetMapping("/{storeId}/timeline")
    public ApiResponse<OpsAdminStoreTimelineResp> timeline(
            @PathVariable Long storeId,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(required = false) String granularity) {
        try {
            OpsAdminStoreTimelineResp resp = storeService.timeline(storeId, startTime, endTime, granularity);
            if (resp == null) return new ApiResponse<>(404, null, "店铺不存在");
            return ApiResponse.success(resp);
        } catch (Exception e) {
            log.warn("[ops-admin] Failed to query store timeline storeId={}: {}", storeId, e.getMessage());
            return ApiResponse.fail("查询时间线数据失败: " + e.getMessage());
        }
    }
}

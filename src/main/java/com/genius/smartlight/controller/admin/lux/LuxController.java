package com.genius.smartlight.controller.admin.lux;

import com.genius.smartlight.common.CommonResult;
import com.genius.smartlight.service.lux.LuxService;
import com.genius.smartlight.service.lux.MultiLuxService;
import com.genius.smartlight.vo.lux.LuxCreateReqVO;
import com.genius.smartlight.vo.lux.LuxRespVO;
import com.genius.smartlight.vo.lux.MultiLuxRespVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "光照数据接口", description = "设备光照值上报与查询接口，核心字段包括 chipId、luxValue、collectTime")
@RestController
@RequestMapping("/admin/lux")
@RequiredArgsConstructor
public class LuxController {

    private final LuxService luxService;
    private final MultiLuxService multiLuxService;

    @Operation(summary = "新增光照记录", description = "设备端上报光照值。请求体包含 chipId、luxValue，可选 collectTime；collectTime 为空时由服务端按业务逻辑处理")
    @PostMapping("/create")
    public CommonResult<Long> createLuxRecord(@Valid @RequestBody LuxCreateReqVO reqVO) {
        return CommonResult.success(luxService.createLuxRecord(reqVO));
    }

    @Operation(summary = "查询设备最新光照", description = "根据 chipId 查询该设备最近一次光照记录，返回 luxValue 和 collectTime")
    @GetMapping("/get-latest")
    public CommonResult<LuxRespVO> getLatestLuxRecord(
            @Parameter(description = "芯片唯一ID", example = "ABC123456")
            @RequestParam String chipId) {
        return CommonResult.success(luxService.getLatestLuxRecord(chipId));
    }

    @Operation(summary = "查询设备光照记录列表", description = "根据 chipId 查询该设备历史光照记录列表")
    @GetMapping("/list")
    public CommonResult<List<LuxRespVO>> getLuxRecordList(
            @Parameter(description = "芯片唯一ID", example = "ABC123456")
            @RequestParam String chipId) {
        return CommonResult.success(luxService.getLuxRecordList(chipId));
    }

    @Operation(summary = "查询多设备光照趋势", description = "查询多个设备的光照趋势数据，用于仪表盘趋势图")
    @GetMapping("/multi-trend")
    public CommonResult<MultiLuxRespVO> getMultiLux() {
        return CommonResult.success(multiLuxService.getMultiLux());
    }
}

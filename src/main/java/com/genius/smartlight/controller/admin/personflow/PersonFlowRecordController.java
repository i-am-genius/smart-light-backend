package com.genius.smartlight.controller.admin.personflow;

import com.genius.smartlight.common.CommonResult;
import com.genius.smartlight.service.personflow.PersonFlowRecordService;
import com.genius.smartlight.vo.personflow.PersonFlowRecordRespVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@Tag(name = "人流检测记录接口", description = "人流检测历史记录查询")
@RestController
@RequestMapping("/admin/person-flow-record")
@RequiredArgsConstructor
public class PersonFlowRecordController {

    private final PersonFlowRecordService personFlowRecordService;

    @Operation(summary = "最近人流检测记录", description = "返回最近 N 条人流检测记录，按检测时间倒序")
    @GetMapping("/recent")
    public CommonResult<List<PersonFlowRecordRespVO>> recent(
            @Parameter(description = "返回条数，默认 10，最大 50", example = "10")
            @RequestParam(defaultValue = "10") int limit) {
        return CommonResult.success(personFlowRecordService.getRecentRecords(limit));
    }

    @Operation(summary = "分页查询人流检测记录", description = "按时间范围、设备等条件分页查询人流检测历史记录")
    @GetMapping("/list")
    public CommonResult<List<PersonFlowRecordRespVO>> list(
            @Parameter(description = "开始时间，格式 yyyy-MM-dd HH:mm:ss", example = "2026-05-01 00:00:00")
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @Parameter(description = "结束时间，格式 yyyy-MM-dd HH:mm:ss", example = "2026-05-25 23:59:59")
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime,
            @Parameter(description = "设备芯片ID", example = "ABC123456")
            @RequestParam(required = false) String chipId,
            @Parameter(description = "页码，从 1 开始", example = "1")
            @RequestParam(defaultValue = "1") int pageNo,
            @Parameter(description = "每页条数，默认 10", example = "10")
            @RequestParam(defaultValue = "10") int pageSize) {
        return CommonResult.success(
                personFlowRecordService.getList(startTime, endTime, chipId, pageNo, pageSize));
    }
}

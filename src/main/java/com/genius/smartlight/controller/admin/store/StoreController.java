package com.genius.smartlight.controller.admin.store;

import com.genius.smartlight.common.ApiResponse;
import com.genius.smartlight.service.store.StoreService;
import com.genius.smartlight.vo.store.StoreRespVO;
import com.genius.smartlight.vo.store.StoreSaveReqVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "店铺管理", description = "当前登录用户的店铺信息获取与设置")
@RestController
@RequestMapping("/api/store")
@RequiredArgsConstructor
public class StoreController {

    private final StoreService storeService;

    @Operation(
            summary = "获取当前店铺信息",
            description = "获取当前登录用户绑定的店铺信息，用于前端初始化店铺配置状态"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "获取成功",
            content = @Content(schema = @Schema(implementation = StoreRespVO.class))
    )
    @GetMapping("/current")
    public ApiResponse<StoreRespVO> current() {
        return ApiResponse.success(storeService.getCurrentStore());
    }

    @Operation(
            summary = "设置店铺信息",
            description = "首次进入系统或修改配置时，设置当前登录用户的店铺名称、地址、行业类型等基础信息"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "设置成功，返回最新店铺信息",
            content = @Content(schema = @Schema(implementation = StoreRespVO.class))
    )
    @PostMapping("/setup")
    public ApiResponse<StoreRespVO> setup(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "店铺保存参数",
                    required = true,
                    content = @Content(schema = @Schema(implementation = StoreSaveReqVO.class))
            )
            @Valid @RequestBody StoreSaveReqVO reqVO) {
        return ApiResponse.success(storeService.setupCurrentStore(reqVO));
    }
}
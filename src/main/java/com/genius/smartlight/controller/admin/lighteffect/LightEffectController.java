package com.genius.smartlight.controller.admin.lighteffect;

import com.genius.smartlight.common.CommonResult;
import com.genius.smartlight.service.lighteffect.LightEffectService;
import com.genius.smartlight.vo.lighteffect.LightEffectStateReqVO;
import com.genius.smartlight.vo.lighteffect.LightEffectStateRespVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Light Effect", description = "Backend-saved wave light effect state")
@RestController
@RequestMapping("/admin/light-effect")
@RequiredArgsConstructor
public class LightEffectController {

    private final LightEffectService lightEffectService;

    @Operation(summary = "Get light effect state")
    @GetMapping("/state")
    public CommonResult<LightEffectStateRespVO> getState() {
        return CommonResult.success(lightEffectService.getState());
    }

    @Operation(summary = "Save or enable light effect state")
    @PostMapping("/state")
    public CommonResult<LightEffectStateRespVO> saveState(@RequestBody LightEffectStateReqVO reqVO) {
        return CommonResult.success(lightEffectService.saveState(reqVO));
    }

    @Operation(summary = "Close light effect")
    @PostMapping("/close")
    public CommonResult<LightEffectStateRespVO> close() {
        return CommonResult.success(lightEffectService.close());
    }
}

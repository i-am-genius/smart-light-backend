package com.genius.smartlight.controller.admin.device;

import com.genius.smartlight.common.CommonResult;
import com.genius.smartlight.service.device.DeviceOtaService;
import com.genius.smartlight.service.device.DeviceService;
import com.genius.smartlight.vo.device.DeviceFirmwareChannelReqVO;
import com.genius.smartlight.vo.device.DeviceOtaCheckRespVO;
import com.genius.smartlight.vo.device.DeviceOtaStartReqVO;
import com.genius.smartlight.vo.device.DeviceRespVO;
import com.genius.smartlight.vo.device.DeviceSaveReqVO;
import com.genius.smartlight.vo.device.LightEffectReqVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "设备管理", description = "设备基础信息维护、店铺绑定、灯光效果、定位和 OTA 升级相关接口")
@RestController
@RequestMapping("/admin/device")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;
    private final DeviceOtaService deviceOtaService;

    @Operation(summary = "连通性测试", description = "用于确认后端设备模块接口可访问")
    @GetMapping("/ping")
    public CommonResult<String> ping() {
        return CommonResult.success("ok");
    }

    @Operation(summary = "创建设备", description = "创建设备基础信息。重点字段包括 chipId、deviceType、deviceNo、displayName、ip、brightness、temp、autoMode 等")
    @PostMapping("/create")
    public CommonResult<Long> createDevice(@Valid @RequestBody DeviceSaveReqVO reqVO) {
        return CommonResult.success(deviceService.createDevice(reqVO));
    }

    @Operation(summary = "更新设备", description = "根据设备主键ID更新设备基础信息和灯光/AI状态字段")
    @PutMapping("/update/{id}")
    public CommonResult<Boolean> updateDevice(
            @Parameter(description = "设备主键ID", example = "1") @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean lightControl,
            @Valid @RequestBody DeviceSaveReqVO reqVO) {
        deviceService.updateDevice(id, reqVO, lightControl);
        return CommonResult.success(true);
    }

    @Operation(summary = "删除设备", description = "根据设备主键ID删除设备")
    @DeleteMapping("/delete/{id}")
    public CommonResult<Boolean> deleteDevice(
            @Parameter(description = "设备主键ID", example = "1") @PathVariable Long id) {
        deviceService.deleteDevice(id);
        return CommonResult.success(true);
    }

    @Operation(summary = "根据ID查询设备", description = "返回设备基础信息、灯光状态、AI 推荐字段和固件信息")
    @GetMapping("/get/{id}")
    public CommonResult<DeviceRespVO> getDevice(
            @Parameter(description = "设备主键ID", example = "1") @PathVariable Long id) {
        return CommonResult.success(deviceService.getDevice(id));
    }

    @Operation(summary = "查询设备列表（管理员全量）", description = "查询系统内全部设备，返回 DeviceRespVO 列表")
    @GetMapping("/list")
    public CommonResult<List<DeviceRespVO>> getDeviceList() {
        return CommonResult.success(deviceService.getDeviceList());
    }

    @Operation(summary = "根据芯片ID查询设备", description = "根据 chipId 查询单个设备详情")
    @GetMapping("/by-chip-id")
    public CommonResult<DeviceRespVO> getDeviceByChipId(
            @Parameter(description = "芯片唯一ID", example = "ABC123456")
            @RequestParam String chipId) {
        return CommonResult.success(deviceService.getDeviceByChipId(chipId));
    }

    @Operation(summary = "查询当前登录用户所属店铺的设备列表", description = "按当前登录用户的店铺过滤设备列表")
    @GetMapping("/my-list")
    public CommonResult<List<DeviceRespVO>> getMyDeviceList() {
        return CommonResult.success(deviceService.getCurrentUserDeviceList());
    }

    @Operation(summary = "将设备绑定到当前登录用户所属店铺", description = "根据 chipId 将设备绑定到当前用户店铺，可同时设置 displayName")
    @PostMapping("/bind-current-store")
    public CommonResult<Boolean> bindCurrentStore(@Valid @RequestBody BindCurrentStoreReqVO reqVO) {
        deviceService.bindDeviceToCurrentStore(reqVO.getChipId(), reqVO.getDisplayName());
        return CommonResult.success(true);
    }

    @Data
    @Schema(description = "绑定设备到当前店铺请求参数")
    public static class BindCurrentStoreReqVO {

        @Schema(description = "芯片唯一ID", example = "ABC123456", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "芯片ID不能为空")
        private String chipId;

        @Schema(description = "设备显示名称", example = "入口灯")
        private String displayName;
    }

    @Operation(summary = "定位设备", description = "向指定 chipId 设备下发定位提示指令")
    @PostMapping("/locate/{chipId}")
    public CommonResult<Boolean> locateDevice(
            @Parameter(description = "芯片唯一ID", example = "ABC123456") @PathVariable String chipId) {
        deviceService.locateDevice(chipId);
        return CommonResult.success(true);
    }

    @Operation(summary = "下发灯光效果", description = "向指定 chipId 设备下发灯光效果参数")
    @PostMapping("/effect/{chipId}")
    public CommonResult<Boolean> sendLightEffect(
            @Parameter(description = "芯片唯一ID", example = "ABC123456") @PathVariable String chipId,
            @RequestBody LightEffectReqVO reqVO
    ) {
        deviceService.sendLightEffect(chipId, reqVO);
        return CommonResult.success(true);
    }

    @Operation(summary = "更新设备固件通道", description = "设置设备 OTA 通道，例如 stable 或 test")
    @PutMapping("/{chipId}/firmware-channel")
    public CommonResult<Boolean> updateFirmwareChannel(
            @Parameter(description = "芯片唯一ID", example = "ABC123456") @PathVariable String chipId,
            @Valid @RequestBody DeviceFirmwareChannelReqVO reqVO) {
        deviceService.updateFirmwareChannel(chipId, reqVO.getChannel());
        return CommonResult.success(true);
    }

    @Operation(summary = "检查 OTA 更新", description = "根据设备 chipId 和可选 channel 查询可用固件版本")
    @GetMapping("/{chipId}/ota/check")
    public CommonResult<DeviceOtaCheckRespVO> checkOtaUpdate(
            @Parameter(description = "芯片唯一ID", example = "ABC123456") @PathVariable String chipId,
            @Parameter(description = "固件通道，可选，例如 stable 或 test", example = "stable")
            @RequestParam(required = false) String channel) {
        return CommonResult.success(deviceOtaService.checkUpdate(chipId, channel));
    }

    @Operation(summary = "启动 OTA 更新", description = "向指定设备下发 OTA 更新任务")
    @PostMapping("/{chipId}/ota/update")
    public CommonResult<DeviceOtaCheckRespVO> startOtaUpdate(
            @Parameter(description = "芯片唯一ID", example = "ABC123456") @PathVariable String chipId,
            @RequestBody(required = false) DeviceOtaStartReqVO reqVO) {
        return CommonResult.success(deviceOtaService.startUpdate(chipId, reqVO));
    }
}

package com.genius.smartlight.controller.admin.device;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.genius.smartlight.common.CommonResult;
import com.genius.smartlight.common.DeviceTypeUtil;
import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.dataobject.StoreDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.dal.mysql.StoreMapper;
import com.genius.smartlight.security.SecurityUtils;
import com.genius.smartlight.service.device.DeviceControlService;
import com.genius.smartlight.service.device.SliderMotionStateService;
import com.genius.smartlight.vo.device.DeviceAnnounceReqVO;
import com.genius.smartlight.vo.device.DeviceAnnounceRespVO;
import com.genius.smartlight.vo.device.DeviceArmControlReqVO;
import com.genius.smartlight.vo.device.DeviceCamAimTargetReqVO;
import com.genius.smartlight.vo.device.DeviceCamPtzReqVO;
import com.genius.smartlight.vo.device.DeviceFlowUploadReqVO;
import com.genius.smartlight.vo.device.DeviceRespVO;
import com.genius.smartlight.vo.device.DeviceStateSyncReqVO;
import com.genius.smartlight.websocket.DeviceAnnounceNotifier;
import com.genius.smartlight.websocket.DeviceSessionManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Tag(name = "设备指令与网关接口", description = "设备上线通告、云台/机械臂控制、图片上传指令、人流上传开关和设备状态同步接口")
@RestController
@RequestMapping("/admin/device")
@RequiredArgsConstructor
public class DeviceGatewayController {

    private static final Set<String> LAMP_ARM_ACTIONS = Set.of(
            "up", "down", "left", "right", "center", "home", "aim_person", "aim_cloth", "stop"
    );

    private static final Set<String> CAM_ARM_ACTIONS = Set.of(
            "up", "down", "left", "right", "center", "home",
            "slide_left", "slide_right", "slide_stop",
            "slider_position",
            "cam_person", "cam_cloth",
            "go_lamp_1", "go_lamp_2", "go_lamp_3",
            "stop"
    );

    private static final Set<String> CAPTURE_ARM_ACTIONS = Set.of(
            "up", "down", "left", "right", "center", "home", "stop"
    );

    private static final Set<String> ARM_SPEEDS = Set.of("slow", "normal", "fast");
    private static final Set<String> PTZ_AXES = Set.of("yaw", "pitch", "roll", "all");
    private static final Set<String> PTZ_DIRECTIONS = Set.of("left", "right", "up", "down", "cw", "ccw", "center");
    private static final float ARM_PAN_MIN = -90f;
    private static final float ARM_PAN_MAX = 90f;
    private static final float ARM_TILT_MIN = -90f;
    private static final float ARM_TILT_MAX = 90f;
    private static final float SG90_MIN = 0f;
    private static final float SG90_MAX = 180f;
    private static final float CAM_PITCH_MIN = -90f;
    private static final float CAM_PITCH_MAX = 90f;
    private static final float SLIDER_MIN_MM = 0f;
    private static final float SLIDER_MAX_MM = 2500f;

    private final DeviceMapper deviceMapper;
    private final StoreMapper storeMapper;
    private final DeviceSessionManager deviceSessionManager;
    private final DeviceAnnounceNotifier deviceAnnounceNotifier;
    private final ObjectMapper objectMapper;
    private final DeviceControlService deviceControlService;
    private final SliderMotionStateService sliderMotionStateService;

    @Operation(summary = "设备上线通告", description = "设备启动或重连后调用。请求体包含 chipId、deviceType、ip；返回 added 表示设备是否已添加并绑定店铺，同时推送上线通告给浏览器端。")
    @PostMapping("/announce")
    public CommonResult<DeviceAnnounceRespVO> announce(
            @Valid @RequestBody DeviceAnnounceReqVO reqVO) {
        DeviceDO exist = deviceMapper.selectOne(
                new LambdaQueryWrapper<DeviceDO>()
                        .eq(DeviceDO::getChipId, reqVO.getChipId())
        );

        boolean added = exist != null && exist.getStoreId() != null;

        DeviceAnnounceRespVO respVO = new DeviceAnnounceRespVO();
        respVO.setAdded(added);

        // 仅推送给该设备所属店铺的浏览器客户端
        Long storeId = exist != null ? exist.getStoreId() : null;
        deviceAnnounceNotifier.pushAsync(
                reqVO.getChipId(),
                reqVO.getIp(),
                reqVO.getDeviceType(),
                added,
                storeId
        );

        return CommonResult.success(respVO);
    }

    /**
     * 按 chipId 查询设备并校验是否属于当前用户店铺。
     */
    private DeviceDO getDeviceByChipIdForCurrentStore(String chipId) {
        Long userId = SecurityUtils.getCurrentUserId();
        StoreDO store = storeMapper.selectOne(
                new LambdaQueryWrapper<StoreDO>()
                        .eq(StoreDO::getUserId, userId)
        );
        if (store == null) {
            throw new ServiceException("当前用户未绑定店铺");
        }
        DeviceDO device = deviceMapper.selectOne(
                new LambdaQueryWrapper<DeviceDO>()
                        .eq(DeviceDO::getChipId, chipId)
        );
        if (device == null) {
            throw new ServiceException("设备不存在");
        }
        if (device.getStoreId() == null || !device.getStoreId().equals(store.getId())) {
            throw new ServiceException("无权操作该设备");
        }
        return device;
    }

    @Operation(summary = "控制摄像头设备三轴云台", description = "支持 deviceType=cam 或 cam_capture。下发 ptzControl 指令，不携带亮度、色温或自动模式字段。")
    @PostMapping("/cam/ptz")
    public CommonResult<Boolean> camPtzControl(@Valid @RequestBody DeviceCamPtzReqVO reqVO) {
        DeviceDO device = getDeviceByChipIdForCurrentStore(reqVO.getChipId());
        if (!DeviceTypeUtil.isCam(device.getDeviceType())
                && !DeviceTypeUtil.isCaptureController(device.getDeviceType())) {
            throw new ServiceException("Only cam or cam_capture devices support this PTZ endpoint");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "ptzControl");

        if (reqVO.getYaw() != null || reqVO.getPitch() != null || reqVO.getRoll() != null) {
            validateCamPtzAbsolute(reqVO);
            if (reqVO.getYaw() != null) payload.put("yaw", reqVO.getYaw());
            if (reqVO.getPitch() != null) payload.put("pitch", reqVO.getPitch());
            if (reqVO.getRoll() != null) payload.put("roll", reqVO.getRoll());
        } else {
            String axis = normalizePtzAxis(reqVO.getAxis());
            String direction = normalizePtzDirection(reqVO.getDirection());
            payload.put("axis", axis);
            payload.put("direction", direction);
            payload.put("step", normalizePtzStep(reqVO.getStep()));
        }

        sendToDevice(reqVO.getChipId(), payload);
        return CommonResult.success(true);
    }

    @Operation(summary = "控制 cam 转向拍摄目标灯", description = "仅支持 deviceType=cam 的独立摄像头设备。下发 cameraAimTarget 指令，不携带亮度、色温或自动模式字段。")
    @PostMapping("/cam/aim-target")
    public CommonResult<Boolean> camAimTarget(@Valid @RequestBody DeviceCamAimTargetReqVO reqVO) {
        DeviceDO camDevice = getDeviceByChipIdForCurrentStore(reqVO.getCamChipId());
        if (!DeviceTypeUtil.isCam(camDevice.getDeviceType())) {
            throw new ServiceException("Only cam devices support this aim-target endpoint");
        }

        String targetChipId = normalizeOptionalChipId(reqVO.getTargetChipId());
        if (targetChipId != null) {
            DeviceDO targetDevice = getDeviceByChipIdForCurrentStore(targetChipId);
            if (!DeviceTypeUtil.isLampLike(targetDevice.getDeviceType())) {
                throw new ServiceException("Target device must be lamp or camlamp");
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "cameraAimTarget");
        payload.put("camChipId", reqVO.getCamChipId());
        if (targetChipId != null) {
            payload.put("targetChipId", targetChipId);
        }
        payload.put("targetIndex", normalizeTargetIndex(reqVO.getTargetIndex()));

        sendToDevice(reqVO.getCamChipId(), payload);
        return CommonResult.success(true);
    }

    @Operation(summary = "控制设备云台方向/速度/摇杆", description = "根据 chipId 向设备 WebSocket 下发云台/机械臂控制指令。支持 arm_joystick(摇杆连续)、arm_stop(停止)、arm_position(精确位置)、arm_speed(切速度)、arm(方向动作/旧协议兼容)。")
    @PostMapping("/arm/{chipId}")
    public CommonResult<Boolean> armControl(
            @Parameter(description = "芯片唯一ID", example = "ABC123456")
            @PathVariable String chipId,
            @Valid @RequestBody DeviceArmControlReqVO reqVO) {
        DeviceDO device = getDeviceByChipIdForCurrentStore(chipId);

        String deviceType = normalizeArmDeviceType(device.getDeviceType());
        String messageType = normalizeText(reqVO.getType());
        // 默认为 arm，兼容旧客户端
        if (messageType == null) {
            messageType = "arm";
        }

        Map<String, Object> payload = new LinkedHashMap<>();

        switch (messageType) {
            case "arm_joystick": {
                // 摇杆连续控制 — 透传，不补 speed
                payload.put("type", "arm_joystick");
                payload.put("x", reqVO.getX() != null ? reqVO.getX() : 0f);
                payload.put("y", reqVO.getY() != null ? reqVO.getY() : 0f);
                payload.put("durationMs", reqVO.getDurationMs() != null ? reqVO.getDurationMs() : 500);
                break;
            }
            case "arm_stop": {
                // 摇杆停止 — 透传
                payload.put("type", "arm_stop");
                break;
            }
            case "arm_position": {
                // 精确位置控制 — 透传，允许部分字段
                payload.put("type", "arm_position");
                validateArmPosition(deviceType, reqVO);
                if (reqVO.getPan() != null) payload.put("pan", reqVO.getPan());
                if (reqVO.getTilt() != null) payload.put("tilt", reqVO.getTilt());
                if (reqVO.getSlider() != null) payload.put("slider", reqVO.getSlider());
                break;
            }
            case "arm_speed": {
                // 单独发送速度，不触发方向动作
                String speed = normalizeArmSpeed(reqVO.getSpeed());
                payload.put("type", "arm_speed");
                payload.put("speed", speed);
                break;
            }
            default: {

                String action = resolveArmAction(reqVO);
                validateArmAction(deviceType, action);
                validateSliderPosition(deviceType, action, reqVO.getPosition());

                payload.put("type", "arm");
                payload.put("action", action);
                payload.put("deviceType", "camlamp".equals(deviceType) ? "cam" : deviceType);
                if ("slider_position".equals(action)) {
                    payload.put("position", reqVO.getPosition());
                }
                // 不再在 arm 消息里附带 speed
                break;
            }
        }

        if ("arm_speed".equals(messageType)) {
            rememberSliderControl(device, messageType, reqVO);
        }
        sendToDevice(chipId, payload);
        if (!"arm_speed".equals(messageType)) {
            rememberSliderControl(device, messageType, reqVO);
        }
        return CommonResult.success(true);
    }

    @Operation(summary = "下发服装图片上传指令", description = "向指定 chipId 的设备下发 upload_cloth 指令，触发摄像头设备上传服装图片用于 AI 面料识别。")
    @PostMapping("/cloth-upload/{chipId}")
    public CommonResult<Boolean> clothUpload(
            @Parameter(description = "芯片唯一ID", example = "ABC123456")
            @PathVariable String chipId) {
        getDeviceByChipIdForCurrentStore(chipId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "command");
        payload.put("cmd", "upload_cloth");

        sendToDevice(chipId, payload);
        return CommonResult.success(true);
    }

    @Operation(summary = "下发人流上传开关指令", description = "向指定 chipId 的设备下发 flow_upload 指令，通过请求体 enabled 控制人流图片/检测上传开关。")
    @PostMapping("/flow-upload/{chipId}")
    public CommonResult<Boolean> flowUpload(
            @Parameter(description = "芯片唯一ID", example = "ABC123456")
            @PathVariable String chipId,
            @Valid @RequestBody DeviceFlowUploadReqVO reqVO) {
        getDeviceByChipIdForCurrentStore(chipId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "command");
        payload.put("cmd", "flow_upload");
        payload.put("enabled", reqVO.getEnabled());

        sendToDevice(chipId, payload);
        return CommonResult.success(true);
    }

    @Operation(summary = "同步设备状态到终端", description = "保存并下发设备灯光状态。请求体可包含 brightness、temp、autoMode、recommendedBrightness、recommendedTemp、fabric、mainColorRgb。")
    @PostMapping("/state-sync/{chipId}")
    public CommonResult<DeviceRespVO> stateSync(
            @Parameter(description = "芯片唯一ID", example = "ABC123456")
            @PathVariable String chipId,
            @Valid @RequestBody DeviceStateSyncReqVO reqVO) {
        return CommonResult.success(deviceControlService.syncStateToDevice(chipId, reqVO));
    }

    private void sendToDevice(String chipId, Object payload) {
        if (!deviceSessionManager.isOnline(chipId)) {
            throw new ServiceException("设备未连接或已离线");
        }

        try {
            String text = objectMapper.writeValueAsString(payload);
            boolean sent = deviceSessionManager.sendToDevice(chipId, text);
            if (!sent) {
                throw new ServiceException("指令发送失败");
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("指令序列化失败：" + e.getMessage());
        }
    }

    private String resolveArmAction(DeviceArmControlReqVO reqVO) {
        String action = normalizeText(reqVO.getAction());
        if (action == null) {
            action = normalizeText(reqVO.getDirection());
        }
        if (action == null) {
            throw new ServiceException("云台动作不能为空");
        }
        return action;
    }

    private String normalizeArmSpeed(String value) {
        String speed = normalizeText(value);
        if (speed == null) {
            return "normal";
        }
        if (!ARM_SPEEDS.contains(speed)) {
            throw new ServiceException("云台速度只能是 slow、normal 或 fast");
        }
        return speed;
    }

    private String normalizeArmDeviceType(String value) {
        String deviceType = normalizeText(value);
        if (DeviceTypeUtil.isCaptureController(deviceType)) {
            return "cam_capture";
        }
        if ("lamp".equals(deviceType) || "cam".equals(deviceType)
                || "camlamp".equals(deviceType)) {
            return deviceType;
        }
        throw new ServiceException("设备类型不支持云台控制");
    }

    private void validateArmAction(String deviceType, String action) {
        Set<String> allowedActions;
        if ("lamp".equals(deviceType)) {
            allowedActions = LAMP_ARM_ACTIONS;
        } else if ("cam_capture".equals(deviceType)) {
            allowedActions = CAPTURE_ARM_ACTIONS;
        } else {
            allowedActions = CAM_ARM_ACTIONS;
        }
        if (!allowedActions.contains(action)) {
            throw new ServiceException("当前设备类型不支持云台动作：" + action);
        }
    }

    private void validateArmPosition(String deviceType, DeviceArmControlReqVO reqVO) {
        boolean sg90CaptureController = "cam_capture".equals(deviceType);
        float panMin = sg90CaptureController ? SG90_MIN : ARM_PAN_MIN;
        float panMax = sg90CaptureController ? SG90_MAX : ARM_PAN_MAX;
        float tiltMin = sg90CaptureController ? SG90_MIN : ARM_TILT_MIN;
        float tiltMax = sg90CaptureController ? SG90_MAX : ARM_TILT_MAX;
        validateRange("pan", reqVO.getPan(), panMin, panMax);
        validateRange("tilt", reqVO.getTilt(), tiltMin, tiltMax);
        validateSliderRange(reqVO.getSlider());
    }

    private void validateCamPtzAbsolute(DeviceCamPtzReqVO reqVO) {
        validateRange("pitch", reqVO.getPitch(), CAM_PITCH_MIN, CAM_PITCH_MAX);
    }

    private void validateRange(String field, Float value, float min, float max) {
        if (value == null) {
            return;
        }
        if (!Float.isFinite(value) || value < min || value > max) {
            throw new ServiceException(field + " range must be " + min + " to " + max + " degrees");
        }
    }

    private void validateSliderPosition(String deviceType, String action, Integer position) {
        if (!"slider_position".equals(action)) {
            return;
        }
        if ("lamp".equals(deviceType)) {
            throw new ServiceException("lamp 设备不支持滑轨位置控制");
        }
        if (position == null) {
            throw new ServiceException("滑轨位置不能为空");
        }
        if (position < SLIDER_MIN_MM || position > SLIDER_MAX_MM) {
            throw new ServiceException("滑轨位置范围必须是 0 到 2500 mm");
        }
    }

    private void validateSliderRange(Float value) {
        if (value == null) {
            return;
        }
        if (!Float.isFinite(value) || value < SLIDER_MIN_MM || value > SLIDER_MAX_MM) {
            throw new ServiceException("slider range must be 0.0 to 2500.0 mm");
        }
    }

    private void rememberSliderControl(DeviceDO device, String messageType, DeviceArmControlReqVO reqVO) {
        if ("arm_speed".equals(messageType)) {
            sliderMotionStateService.updateSpeedMode(
                    device.getChipId(),
                    device.getStoreId(),
                    normalizeArmSpeed(reqVO.getSpeed())
            );
            return;
        }
        if ("arm_position".equals(messageType) && reqVO.getSlider() != null) {
            sliderMotionStateService.recordCommandedPosition(
                    device.getChipId(),
                    device.getStoreId(),
                    reqVO.getSlider()
            );
            return;
        }
        if ("slider_position".equals(resolveOptionalArmAction(reqVO)) && reqVO.getPosition() != null) {
            sliderMotionStateService.recordCommandedPosition(
                    device.getChipId(),
                    device.getStoreId(),
                    reqVO.getPosition()
            );
        }
    }

    private String resolveOptionalArmAction(DeviceArmControlReqVO reqVO) {
        String action = normalizeText(reqVO.getAction());
        return action == null ? normalizeText(reqVO.getDirection()) : action;
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizePtzAxis(String value) {
        String axis = normalizeText(value);
        if (axis == null) {
            axis = "all";
        }
        if (!PTZ_AXES.contains(axis)) {
            throw new ServiceException("PTZ axis must be yaw, pitch, roll, or all");
        }
        return axis;
    }

    private String normalizePtzDirection(String value) {
        String direction = normalizeText(value);
        if (direction == null) {
            direction = "center";
        }
        if (!PTZ_DIRECTIONS.contains(direction)) {
            throw new ServiceException("PTZ direction is not supported");
        }
        return direction;
    }

    private int normalizePtzStep(Integer value) {
        if (value == null) {
            return 5;
        }
        return Math.max(1, Math.min(30, value));
    }

    private String normalizeOptionalChipId(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private int normalizeTargetIndex(Integer value) {
        if (value == null) {
            return 1;
        }
        return Math.max(1, Math.min(3, value));
    }
}

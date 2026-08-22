package com.genius.smartlight.service.device.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.dal.mysql.DurationRecordMapper;
import com.genius.smartlight.service.ai.AiService;
import com.genius.smartlight.service.device.SliderMotionStateService;
import com.genius.smartlight.service.personflow.PersonFlowRecordService;
import com.genius.smartlight.service.store.CurrentStoreService;
import com.genius.smartlight.vo.device.DeviceCamCaptureBatchReqVO;
import com.genius.smartlight.vo.device.DeviceCamCaptureBatchRespVO;
import com.genius.smartlight.vo.device.DeviceCamCaptureTaskReqVO;
import com.genius.smartlight.vo.device.DeviceCamCaptureTaskRespVO;
import com.genius.smartlight.vo.device.DeviceCamRoiConfigVO;
import com.genius.smartlight.vo.device.DeviceCamRoiItemVO;
import com.genius.smartlight.vo.device.DeviceCamSliderMoveTimeVO;
import com.genius.smartlight.vo.device.DeviceCamStatusReqVO;
import com.genius.smartlight.vo.device.DeviceCamStatusRespVO;
import com.genius.smartlight.vo.device.DeviceCamTrackingControlReqVO;
import com.genius.smartlight.vo.device.DeviceLampClothStateReqVO;
import com.genius.smartlight.vo.device.DeviceLampClothStateRespVO;
import com.genius.smartlight.vo.device.DeviceLampProximityStateReqVO;
import com.genius.smartlight.vo.device.DeviceLampProximityStateRespVO;
import com.genius.smartlight.vo.device.DeviceSliderStatusReqVO;
import com.genius.smartlight.vo.device.DeviceTrackingStatusReqVO;
import com.genius.smartlight.vo.device.DeviceTrackingStatusRespVO;
import com.genius.smartlight.websocket.DeviceSessionManager;
import com.genius.smartlight.websocket.WebSocketPushService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceCamServiceImplTest {

    private static final String MANUAL_CAM_CHIP_ID = "CAM-MANUAL-TRACKING-TEST";
    private static final String MANUAL_LAMP_CHIP_ID = "LAMP-MANUAL-TRACKING-TEST";
    private static final String SLIDER_LAMP_CHIP_ID = "LAMP-SLIDER-TEST";
    private static final String CAPTURE_CONTROLLER_CHIP_ID = "CAM-CAPTURE-TEST";

    private DeviceMapper deviceMapper;
    private CurrentStoreService currentStoreService;
    private WebSocketPushService webSocketPushService;
    private DeviceSessionManager deviceSessionManager;
    private AiService aiService;
    private SliderMotionStateService sliderMotionStateService;
    private final List<Path> testUploadPaths = new ArrayList<>();
    private DeviceCamServiceImpl service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "device-cam-service-test"),
                DeviceDO.class
        );
        deviceMapper = mock(DeviceMapper.class);
        currentStoreService = mock(CurrentStoreService.class);
        webSocketPushService = mock(WebSocketPushService.class);
        deviceSessionManager = mock(DeviceSessionManager.class);
        aiService = mock(AiService.class);
        sliderMotionStateService = mock(SliderMotionStateService.class);

        service = new DeviceCamServiceImpl(
                deviceMapper,
                currentStoreService,
                webSocketPushService,
                deviceSessionManager,
                mock(PersonFlowRecordService.class),
                mock(DurationRecordMapper.class),
                aiService,
                sliderMotionStateService,
                objectMapper
        );

        DeviceDO cam = device("CAM-001", "cam");
        DeviceDO lamp = device("LAMP-001", "lamp");
        DeviceDO sliderLamp = device(SLIDER_LAMP_CHIP_ID, "lamp");
        DeviceDO captureController = device(CAPTURE_CONTROLLER_CHIP_ID, "cam_capture");
        stubDevices(cam, lamp, sliderLamp, captureController);
        when(currentStoreService.getCurrentStoreId()).thenReturn(1L);
        when(deviceSessionManager.isOnline("CAM-001")).thenReturn(true);
        when(deviceSessionManager.isOnline("LAMP-001")).thenReturn(true);
        when(deviceSessionManager.isOnline(SLIDER_LAMP_CHIP_ID)).thenReturn(true);
        when(deviceSessionManager.isOnline(CAPTURE_CONTROLLER_CHIP_ID)).thenReturn(true);
        when(deviceSessionManager.sendToDevice(eq("CAM-001"), any(String.class))).thenReturn(true);
        when(deviceSessionManager.sendToDevice(eq("LAMP-001"), any(String.class))).thenReturn(true);
        when(deviceSessionManager.sendToDevice(eq(SLIDER_LAMP_CHIP_ID), any(String.class))).thenReturn(true);
        when(deviceSessionManager.sendToDevice(eq(CAPTURE_CONTROLLER_CHIP_ID), any(String.class))).thenReturn(true);
        when(sliderMotionStateService.getSnapshot(any(String.class), any()))
                .thenReturn(new SliderMotionStateService.SliderStateSnapshot(
                        0D, 0D, "normal", null, null
                ));
        writeDefaultCaptureConfig();
    }

    @AfterEach
    void tearDown() {
        service.shutdownCaptureTimeoutExecutor();
        try {
            Files.deleteIfExists(manualConfigPath());
            Files.deleteIfExists(defaultConfigPath());
            for (Path uploadPath : testUploadPaths) {
                Files.deleteIfExists(uploadPath);
            }
            testUploadPaths.clear();
        } catch (Exception ignored) {
            // The unique test config must not mask the assertion result during cleanup.
        }
    }

    @Test
    void manualStart_bypassesPresenceAndClothState() throws Exception {
        configureManualTrackingDevices(true, true, "192.168.1.88");
        writeManualTrackingConfig();

        DeviceTrackingStatusRespVO result = service.startTrackingManually(manualTrackingRequest(1));

        assertThat(result.getTrackingStatus()).isEqualTo("waiting_motion");
        verify(deviceSessionManager, never()).sendToDevice(eq(MANUAL_CAM_CHIP_ID), any(String.class));
        verify(deviceSessionManager, never()).sendToDevice(
                eq(MANUAL_LAMP_CHIP_ID),
                argThat(payload -> payload.contains("\"type\":\"lampTrackingStart\""))
        );

        service.triggerPendingTracking(MANUAL_CAM_CHIP_ID);

        InOrder commandOrder = inOrder(deviceSessionManager);
        commandOrder.verify(deviceSessionManager)
                .sendToDevice(eq(MANUAL_LAMP_CHIP_ID), org.mockito.ArgumentMatchers.argThat(
                        payload -> payload.contains("\"type\":\"arm_position\"")
                                && payload.contains("\"source\":\"camera_tracking\"")
                                && payload.contains("\"slider\":600.0")
                ));
        commandOrder.verify(deviceSessionManager)
                .sendToDevice(eq(MANUAL_LAMP_CHIP_ID), org.mockito.ArgumentMatchers.argThat(
                        payload -> payload.contains("\"type\":\"lampTrackingStart\"")
                                && payload.contains("\"camChipId\":\"" + MANUAL_CAM_CHIP_ID + "\"")
                ));

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        commandOrder.verify(deviceSessionManager)
                .sendToDevice(eq(MANUAL_CAM_CHIP_ID), payloadCaptor.capture());
        JsonNode command = objectMapper.readTree(payloadCaptor.getValue());
        assertThat(command.path("type").asText()).isEqualTo("cameraStartTracking");
        assertThat(command.path("targetIndex").asInt()).isEqualTo(1);
        assertThat(command.path("targetChipId").asText()).isEqualTo(MANUAL_LAMP_CHIP_ID);
        assertThat(command.path("lampIp").asText()).isEqualTo("192.168.1.88");
    }

    @Test
    void terminalCameraStatus_releasesDevicesAndReturnsCameraToMonitoring() throws Exception {
        configureManualTrackingDevices(true, true, "192.168.1.88");
        writeManualTrackingConfig();
        service.startTrackingManually(manualTrackingRequest(1));
        service.triggerPendingTracking(MANUAL_CAM_CHIP_ID);

        DeviceTrackingStatusReqVO status = new DeviceTrackingStatusReqVO();
        status.setChipId(MANUAL_CAM_CHIP_ID);
        status.setRole("cam");
        status.setTrackingStatus("lost");
        status.setCamChipId(MANUAL_CAM_CHIP_ID);
        status.setLampChipId(MANUAL_LAMP_CHIP_ID);
        status.setTargetIndex(1);
        service.reportTrackingStatus(status);

        assertThat(service.getTrackingStatus(MANUAL_CAM_CHIP_ID)).isEqualTo("stopped");
        assertThat(service.getTrackingStatus(MANUAL_LAMP_CHIP_ID)).isEqualTo("stopped");

        ArgumentCaptor<String> lampPayloads = ArgumentCaptor.forClass(String.class);
        verify(deviceSessionManager, times(3))
                .sendToDevice(eq(MANUAL_LAMP_CHIP_ID), lampPayloads.capture());
        assertThat(objectMapper.readTree(lampPayloads.getAllValues().get(0)).path("type").asText())
                .isEqualTo("arm_position");
        assertThat(objectMapper.readTree(lampPayloads.getAllValues().get(1)).path("type").asText())
                .isEqualTo("lampTrackingStart");
        JsonNode stop = objectMapper.readTree(lampPayloads.getAllValues().get(2));
        assertThat(stop.path("type").asText()).isEqualTo("lampTrackingStop");
        assertThat(stop.path("clearClothTaken").asBoolean()).isTrue();

        verify(deviceSessionManager).sendToDevice(
                eq(MANUAL_CAM_CHIP_ID),
                argThat(payload -> payload.contains("\"type\":\"cameraReturnCenter\""))
        );
        verify(webSocketPushService).pushCamStatus(
                argThat(payload -> payload instanceof DeviceCamStatusRespVO status
                        && "returning_center".equals(status.getWorkStatus())),
                eq(1L)
        );

        DeviceCamStatusReqVO monitoring = new DeviceCamStatusReqVO();
        monitoring.setCamChipId(MANUAL_CAM_CHIP_ID);
        monitoring.setWorkStatus("monitoring");
        service.reportStatus(monitoring);

        assertThat(service.getTrackingStatus(MANUAL_CAM_CHIP_ID)).isEqualTo("monitoring");
        assertThat(service.getTrackingStatus(MANUAL_LAMP_CHIP_ID)).isEqualTo("monitoring");
    }

    @Test
    void monitoringStatus_doesNotCancelPendingSliderAlignment() throws Exception {
        configureManualTrackingDevices(true, true, "192.168.1.88");
        writeManualTrackingConfig();
        service.startTrackingManually(manualTrackingRequest(1));

        DeviceCamStatusReqVO monitoring = new DeviceCamStatusReqVO();
        monitoring.setCamChipId(MANUAL_CAM_CHIP_ID);
        monitoring.setWorkStatus("monitoring");
        service.reportStatus(monitoring);
        service.triggerPendingTracking(MANUAL_CAM_CHIP_ID);

        assertThat(service.getTrackingStatus(MANUAL_CAM_CHIP_ID)).isEqualTo("tracking");
        verify(deviceSessionManager).sendToDevice(
                eq(MANUAL_CAM_CHIP_ID),
                argThat(payload -> payload.contains("\"type\":\"cameraStartTracking\""))
        );
    }

    @Test
    void manualStop_sendsCommandsWhenActiveCacheIsEmpty() throws Exception {
        configureManualTrackingDevices(true, true, "192.168.1.88");

        DeviceTrackingStatusRespVO result = service.stopTrackingManually(manualTrackingRequest(1));

        assertThat(result.getTrackingStatus()).isEqualTo("stopped");
        assertSentCommand(MANUAL_CAM_CHIP_ID, "cameraReturnCenter");
        assertSentCommand(MANUAL_LAMP_CHIP_ID, "lampTrackingStop");
    }

    @Test
    void proximityStateBroadcastsBooleanWithoutDistance() {
        DeviceLampProximityStateReqVO request = new DeviceLampProximityStateReqVO();
        request.setChipId("LAMP-001");
        request.setNearby(true);

        DeviceLampProximityStateRespVO result = service.reportLampProximityState(request);

        assertThat(result.getNearby()).isTrue();
        verify(webSocketPushService).pushLampProximityState(result, 1L);
    }

    @Test
    void takenUsesServerTimeAndClearingStatePreservesLastTakenTime() {
        LocalDateTime before = LocalDateTime.now();
        DeviceLampClothStateReqVO taken = new DeviceLampClothStateReqVO();
        taken.setChipId("LAMP-001");
        taken.setClothState("taken");
        taken.setLastTakenAt("2000-01-01T00:00:00");

        DeviceLampClothStateRespVO takenResult = service.reportLampClothState(taken);

        assertThat(takenResult.getLastTakenAt()).isAfterOrEqualTo(before);

        DeviceLampClothStateReqVO cleared = new DeviceLampClothStateReqVO();
        cleared.setChipId("LAMP-001");
        cleared.setClothState("on_rack");
        DeviceLampClothStateRespVO clearedResult = service.reportLampClothState(cleared);

        assertThat(clearedResult.getLastTakenAt()).isEqualTo(takenResult.getLastTakenAt());
    }

    @Test
    void manualStart_rejectsOfflineLamp() throws Exception {
        configureManualTrackingDevices(true, false, "192.168.1.88");
        writeManualTrackingConfig();

        assertThatThrownBy(() -> service.startTrackingManually(manualTrackingRequest(1)))
                .hasMessageContaining("目标灯离线");
    }

    @Test
    void manualStart_rejectsInvalidLampIp() throws Exception {
        configureManualTrackingDevices(true, true, "192.168.1.88:8080");
        writeManualTrackingConfig();

        assertThatThrownBy(() -> service.startTrackingManually(manualTrackingRequest(1)))
                .hasMessageContaining("灯 IP");
    }

    @Test
    void createCaptureTask_rejectsOfflineSliderController() {
        when(deviceSessionManager.isOnline(SLIDER_LAMP_CHIP_ID)).thenReturn(false);
        DeviceCamCaptureTaskReqVO request = new DeviceCamCaptureTaskReqVO();
        request.setCamChipId("CAM-001");
        request.setTargetChipId("LAMP-001");
        request.setTargetIndex(1);

        assertThatThrownBy(() -> service.createCaptureTask(request))
                .hasMessageContaining("滑轨控制灯离线");
        verify(deviceSessionManager, never()).sendToDevice(eq("CAM-001"), any(String.class));
        verify(deviceSessionManager, never()).sendToDevice(eq(SLIDER_LAMP_CHIP_ID), any(String.class));
    }

    @Test
    void createCaptureTask_allowsOfflineTargetWhenSliderControllerIsOnline() {
        when(deviceSessionManager.isOnline("LAMP-001")).thenReturn(false);
        DeviceCamCaptureTaskReqVO request = new DeviceCamCaptureTaskReqVO();
        request.setCamChipId("CAM-001");
        request.setTargetChipId("LAMP-001");
        request.setTargetIndex(1);

        DeviceCamCaptureTaskRespVO task = service.createCaptureTask(request);

        assertThat(task.getStatus()).isEqualTo("waiting_motion");
        verify(deviceSessionManager, never()).isOnline("LAMP-001");
        verify(deviceSessionManager).sendToDevice(eq(SLIDER_LAMP_CHIP_ID), any(String.class));
    }

    @Test
    void createCaptureTask_allowsOfflineTrackingCameraWhenCaptureControllerIsOnline() {
        when(deviceSessionManager.isOnline("CAM-001")).thenReturn(false);
        DeviceCamCaptureTaskReqVO request = new DeviceCamCaptureTaskReqVO();
        request.setCamChipId("CAM-001");
        request.setTargetChipId("LAMP-001");
        request.setTargetIndex(1);

        DeviceCamCaptureTaskRespVO task = service.createCaptureTask(request);

        assertThat(task.getStatus()).isEqualTo("waiting_motion");
        verify(deviceSessionManager).sendToDevice(eq(SLIDER_LAMP_CHIP_ID), any(String.class));
    }

    @Test
    void createCaptureTask_rejectsOfflineCaptureControllerBeforeMovingSlider() {
        when(deviceSessionManager.isOnline(CAPTURE_CONTROLLER_CHIP_ID)).thenReturn(false);
        DeviceCamCaptureTaskReqVO request = new DeviceCamCaptureTaskReqVO();
        request.setCamChipId("CAM-001");
        request.setTargetChipId("LAMP-001");
        request.setTargetIndex(1);

        assertThatThrownBy(() -> service.createCaptureTask(request))
                .hasMessageContaining("拍照控制器离线");
        verify(deviceSessionManager, never()).sendToDevice(eq(SLIDER_LAMP_CHIP_ID), any(String.class));
    }

    @Test
    void createCaptureTask_rejectsMissingCaptureControllerBindingBeforeMovingSlider() throws Exception {
        DeviceCamRoiConfigVO config = objectMapper.readValue(defaultConfigPath().toFile(), DeviceCamRoiConfigVO.class);
        config.setCaptureControllerChipId(null);
        objectMapper.writeValue(defaultConfigPath().toFile(), config);

        DeviceCamCaptureTaskReqVO request = new DeviceCamCaptureTaskReqVO();
        request.setCamChipId("CAM-001");
        request.setTargetChipId("LAMP-001");
        request.setTargetIndex(1);

        assertThatThrownBy(() -> service.createCaptureTask(request))
                .hasMessageContaining("拍照控制器未绑定");
        verify(deviceSessionManager, never()).sendToDevice(eq(SLIDER_LAMP_CHIP_ID), any(String.class));
    }

    @Test
    void saveRoiConfig_rejectsNonCaptureControllerBinding() throws Exception {
        DeviceCamRoiConfigVO config = objectMapper.readValue(defaultConfigPath().toFile(), DeviceCamRoiConfigVO.class);
        config.setCaptureControllerChipId("LAMP-001");

        assertThatThrownBy(() -> service.saveRoiConfig("CAM-001", config))
                .hasMessageContaining("cam_capture");
    }

    @Test
    void roiContract_usesSeparateGarmentAndPersonCapturePoses() throws Exception {
        DeviceCamRoiItemVO roi = new DeviceCamRoiItemVO();
        roi.setTargetIndex(1);
        roi.setTargetChipId("LAMP-001");
        roi.setAreaName("入口区");
        roi.setX(0.1);
        roi.setY(0.2);
        roi.setW(0.3);
        roi.setH(0.4);

        DeviceCamRoiConfigVO config = new DeviceCamRoiConfigVO();
        config.setCamChipId("CAM-001");
        config.setSliderLampChipId(SLIDER_LAMP_CHIP_ID);
        config.setCaptureControllerChipId(CAPTURE_CONTROLLER_CHIP_ID);
        config.setGarmentCapturePan(-12D);
        config.setGarmentCaptureTilt(240D);
        config.setPersonCapturePan(35D);
        config.setPersonCaptureTilt(145D);
        config.setFlowUploadEnabled(true);
        config.setFlowUploadIntervalSeconds(45);
        config.setRois(List.of(roi));
        config.setSliderPresets(Map.of("1", 320.0));

        ObjectMapper mapper = new ObjectMapper();
        DeviceCamRoiConfigVO normalized = service.normalizeConfig("CAM-001", config);
        String json = mapper.writeValueAsString(normalized);

        assertThat(json).contains(
                "\"sliderLampChipId\":\"" + SLIDER_LAMP_CHIP_ID + "\"",
                "\"captureControllerChipId\":\"" + CAPTURE_CONTROLLER_CHIP_ID + "\"",
                "\"garmentCapturePan\":0.0",
                "\"garmentCaptureTilt\":180.0",
                "\"personCapturePan\":35.0",
                "\"personCaptureTilt\":145.0",
                "\"flowUploadEnabled\":true",
                "\"flowUploadIntervalSeconds\":45",
                "\"sliderPresets\":{\"1\":320.0}"
        );
        assertThat(json).doesNotContain(
                "capturePresets", "trackingPresets", "yaw", "pitch", "roll",
                "centerPreset", "trackingLostTimeoutSeconds",
                "dwellSeconds", "leaveDelaySeconds", "confidenceThreshold", "udpIp", "udpPort"
        );
        assertThat(mapper.readTree(json).path("sliderPresets").path("1").asDouble()).isEqualTo(320.0);
    }

    @Test
    void captureControllerReconnect_receivesPersistedBindingAndFlowConfig() throws Exception {
        service.pushCaptureControllerConfigForDevice(CAPTURE_CONTROLLER_CHIP_ID);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(deviceSessionManager).sendToDevice(
                eq(CAPTURE_CONTROLLER_CHIP_ID),
                payloadCaptor.capture()
        );
        JsonNode payload = objectMapper.readTree(payloadCaptor.getValue());
        assertThat(payload.path("type").asText()).isEqualTo("captureControllerConfig");
        assertThat(payload.path("camChipId").asText()).isEqualTo("CAM-001");
        assertThat(payload.path("garmentCapturePreset").path("pan").asDouble()).isEqualTo(120D);
        assertThat(payload.path("personCapturePreset").path("pan").asDouble()).isEqualTo(90D);
        assertThat(payload.path("flowUploadIntervalSeconds").asInt()).isEqualTo(30);
        assertThat(payload.path("flowUploadUrl").asText())
                .contains("captureControllerChipId=" + CAPTURE_CONTROLLER_CHIP_ID);
    }

    @Test
    void legacyRoiJson_isMigratedToSafeNewCoordinatesAndCleanOutput() throws Exception {
        String legacyJson = """
                {
                  "camChipId":"CAM-001",
                  "centerPreset":{"yaw":90,"pitch":0,"roll":90},
                  "trackingLostTimeoutSeconds":5,
                  "udpPort":4211,
                  "rois":[{
                    "targetIndex":1,"targetChipId":"LAMP-001","areaName":"入口区",
                    "x":0.1,"y":0.2,"w":0.3,"h":0.4,
                    "dwellSeconds":2,"leaveDelaySeconds":3,"confidenceThreshold":0.6,
                    "udpIp":"192.168.1.88","udpPort":4211
                  }],
                  "capturePresets":{"1":{"yaw":90,"pitch":-5,"roll":120}},
                  "trackingPresets":{"1":{"yaw":45,"pitch":10,"roll":80}}
                }
                """;
        ObjectMapper mapper = new ObjectMapper();
        DeviceCamRoiConfigVO legacy = mapper.readValue(legacyJson, DeviceCamRoiConfigVO.class);

        DeviceCamRoiConfigVO normalized = service.normalizeConfig("CAM-001", legacy);
        String json = mapper.writeValueAsString(normalized);

        assertThat(normalized.getGarmentCapturePan()).isEqualTo(90.0);
        assertThat(normalized.getGarmentCaptureTilt()).isEqualTo(90.0);
        assertThat(normalized.getPersonCapturePan()).isEqualTo(90.0);
        assertThat(normalized.getPersonCaptureTilt()).isEqualTo(90.0);
        assertThat(normalized.getFlowUploadEnabled()).isFalse();
        assertThat(normalized.getFlowUploadIntervalSeconds()).isEqualTo(30);
        assertThat(normalized.getSliderPresets().get("1")).isEqualTo(0.0);
        assertThat(json).doesNotContain(
                "capturePresets", "trackingPresets", "pan", "tilt", "yaw", "pitch", "roll",
                "centerPreset", "trackingLostTimeoutSeconds",
                "dwellSeconds", "leaveDelaySeconds", "confidenceThreshold", "udpIp", "udpPort"
        );
    }

    @Test
    void createCaptureTask_sendsSliderToLampAndWaitsBeforeCameraCapture() throws Exception {
        DeviceCamCaptureTaskReqVO request = new DeviceCamCaptureTaskReqVO();
        request.setCamChipId("CAM-001");
        request.setTargetChipId("LAMP-001");
        request.setTargetIndex(1);

        DeviceCamCaptureTaskRespVO result = service.createCaptureTask(request);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(deviceSessionManager).sendToDevice(eq(SLIDER_LAMP_CHIP_ID), payloadCaptor.capture());
        JsonNode payload = new ObjectMapper().readTree(payloadCaptor.getValue());
        assertThat(payload.path("type").asText()).isEqualTo("arm_position");
        assertThat(payload.path("source").asText()).isEqualTo("camera_capture");
        assertThat(payload.path("taskId").asText()).isEqualTo(result.getTaskId());
        assertThat(payload.path("slider").asDouble()).isEqualTo(320.0);
        assertThat(result.getStatus()).isEqualTo("waiting_motion");
        verify(deviceSessionManager, never()).sendToDevice(eq("CAM-001"), any(String.class));
    }

    @Test
    void estimatedMoveTime_sendsCameraCaptureToBoundCaptureControllerExactlyOnce() throws Exception {
        DeviceCamCaptureTaskReqVO request = new DeviceCamCaptureTaskReqVO();
        request.setCamChipId("CAM-001");
        request.setTargetChipId("LAMP-001");
        request.setTargetIndex(1);
        DeviceCamCaptureTaskRespVO task = service.createCaptureTask(request);

        DeviceSliderStatusReqVO arrival = sliderArrival(task, SLIDER_LAMP_CHIP_ID, 320.0);
        service.reportSliderStatus(arrival);
        service.reportSliderStatus(arrival);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(deviceSessionManager, timeout(1500).times(1))
                .sendToDevice(eq(CAPTURE_CONTROLLER_CHIP_ID), payloadCaptor.capture());
        JsonNode payload = objectMapper.readTree(payloadCaptor.getValue());
        assertThat(payload.path("type").asText()).isEqualTo("cameraCapture");
        assertThat(payload.path("taskId").asText()).isEqualTo(task.getTaskId());
        assertThat(payload.path("camChipId").asText()).isEqualTo("CAM-001");
        assertThat(payload.path("captureControllerChipId").asText()).isEqualTo(CAPTURE_CONTROLLER_CHIP_ID);
        assertThat(payload.path("motionReady").asBoolean()).isTrue();
        assertThat(payload.path("capturePreset").path("pan").asDouble()).isEqualTo(120D);
        assertThat(payload.path("capturePreset").path("tilt").asDouble()).isEqualTo(72D);
        assertThat(payload.path("captureKind").asText()).isEqualTo("garment");
        assertThat(task.getStatus()).isEqualTo("capturing");
    }

    @Test
    void flowPhotoUpload_acceptsBoundCaptureControllerTokenAndRunsServerDetection() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "flow.jpg", "image/jpeg", new byte[]{1, 2, 3}
        );
        when(deviceSessionManager.validateUploadToken(CAPTURE_CONTROLLER_CHIP_ID, "flow-token"))
                .thenReturn(true);

        service.uploadFlowPhotoByDevice(
                "CAM-001",
                CAPTURE_CONTROLLER_CHIP_ID,
                "flow-token",
                null,
                null,
                null,
                file
        );

        verify(aiService).personDetect("CAM-001", file);
    }

    @Test
    void flowPhotoUpload_rejectsControllerThatIsNotBoundToLogicalCam() {
        DeviceDO otherController = device("CAM-CAPTURE-OTHER", "cam_capture");
        stubDevices(
                device("CAM-001", "cam"),
                device("LAMP-001", "lamp"),
                device(SLIDER_LAMP_CHIP_ID, "lamp"),
                device(CAPTURE_CONTROLLER_CHIP_ID, "cam_capture"),
                otherController
        );
        when(deviceSessionManager.validateUploadToken("CAM-CAPTURE-OTHER", "flow-token"))
                .thenReturn(true);
        MockMultipartFile file = new MockMultipartFile(
                "file", "flow.jpg", "image/jpeg", new byte[]{1, 2, 3}
        );

        assertThatThrownBy(() -> service.uploadFlowPhotoByDevice(
                "CAM-001",
                "CAM-CAPTURE-OTHER",
                "flow-token",
                null,
                null,
                null,
                file
        )).hasMessageContaining("未绑定");

        verify(aiService, never()).personDetect(any(), any());
    }

    @Test
    void sliderArrival_withWrongTargetDoesNotTriggerCameraBeforeTimer() {
        DeviceCamCaptureTaskReqVO request = new DeviceCamCaptureTaskReqVO();
        request.setCamChipId("CAM-001");
        request.setTargetChipId("LAMP-001");
        request.setTargetIndex(1);
        DeviceCamCaptureTaskRespVO task = service.createCaptureTask(request);

        service.reportSliderStatus(sliderArrival(task, SLIDER_LAMP_CHIP_ID, 50.0));

        assertThat(task.getStatus()).isEqualTo("waiting_motion");
        verify(deviceSessionManager, never()).sendToDevice(eq("CAM-001"), any(String.class));
    }

    @Test
    void sliderArrival_fromDifferentLampDoesNotTriggerCameraBeforeTimer() {
        DeviceCamCaptureTaskReqVO request = new DeviceCamCaptureTaskReqVO();
        request.setCamChipId("CAM-001");
        request.setTargetChipId("LAMP-001");
        request.setTargetIndex(1);
        DeviceCamCaptureTaskRespVO task = service.createCaptureTask(request);

        service.reportSliderStatus(sliderArrival(task, "LAMP-OTHER", 320.0));

        assertThat(task.getStatus()).isEqualTo("waiting_motion");
        verify(deviceSessionManager, never()).sendToDevice(eq("CAM-001"), any(String.class));
    }

    @Test
    void createCaptureTask_withoutTargetIndex_resolvesCameraPresetFromSelectedLamp() throws Exception {
        configureManualTrackingDevices(true, true, "192.168.1.88");
        writeManualCaptureConfig(2);

        DeviceCamCaptureTaskReqVO request = new DeviceCamCaptureTaskReqVO();
        request.setCamChipId(MANUAL_CAM_CHIP_ID);
        request.setTargetChipId(MANUAL_LAMP_CHIP_ID);

        DeviceCamCaptureTaskRespVO result = service.createCaptureTask(request);

        assertThat(result.getTargetIndex()).isEqualTo(2);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(deviceSessionManager).sendToDevice(eq(MANUAL_LAMP_CHIP_ID), payloadCaptor.capture());
        JsonNode payload = objectMapper.readTree(payloadCaptor.getValue());
        assertThat(payload.path("source").asText()).isEqualTo("camera_capture");
        assertThat(payload.path("taskId").asText()).isEqualTo(result.getTaskId());
        assertThat(payload.path("slider").asDouble()).isEqualTo(600.0);
        verify(deviceSessionManager, never()).sendToDevice(eq(MANUAL_CAM_CHIP_ID), any(String.class));
    }

    @Test
    void createCaptureTask_withoutTargetIndex_rejectsLampWithoutCameraPreset() {
        configureManualTrackingDevices(true, true, "192.168.1.88");

        DeviceCamCaptureTaskReqVO request = new DeviceCamCaptureTaskReqVO();
        request.setCamChipId(MANUAL_CAM_CHIP_ID);
        request.setTargetChipId(MANUAL_LAMP_CHIP_ID);

        assertThatThrownBy(() -> service.createCaptureTask(request))
                .hasMessageContaining("Camera 滑轨预设");
    }

    @Test
    void createCaptureBatch_ordersThreeTargetsBySliderPosition() throws Exception {
        writeBatchCaptureConfig();

        DeviceCamCaptureBatchReqVO request = new DeviceCamCaptureBatchReqVO();
        request.setCamChipId("CAM-001");

        DeviceCamCaptureBatchRespVO batch = service.createCaptureBatch(request);

        assertThat(batch.getTasks()).extracting(DeviceCamCaptureTaskRespVO::getTargetIndex)
                .containsExactly(3, 1, 2);
        assertThat(batch.getTasks()).extracting(DeviceCamCaptureTaskRespVO::getSequence)
                .containsExactly(1, 2, 3);
        assertThat(batch.getTasks()).extracting(DeviceCamCaptureTaskRespVO::getStatus)
                .containsExactly("waiting_motion", "queued", "queued");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(deviceSessionManager).sendToDevice(eq(SLIDER_LAMP_CHIP_ID), payloadCaptor.capture());
        JsonNode payload = objectMapper.readTree(payloadCaptor.getValue());
        assertThat(payload.path("source").asText()).isEqualTo("camera_capture");
        assertThat(payload.path("taskId").asText()).isEqualTo(batch.getTasks().get(0).getTaskId());
        assertThat(payload.path("slider").asDouble()).isEqualTo(120.0);
    }

    @Test
    void automaticGarmentDetectionCreatesOneBatchAndStaysDetectingUntilAiFinishes() throws Exception {
        writeBatchCaptureConfig();
        when(deviceMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                device("CAM-001", "cam"),
                device("LAMP-001", "lamp"),
                device("LAMP-002", "lamp"),
                device("LAMP-003", "lamp"),
                device(SLIDER_LAMP_CHIP_ID, "lamp")
        ));

        service.startAutomaticGarmentDetection(1L);
        service.startAutomaticGarmentDetection(1L);

        assertThat(service.getGarmentDetectionStatus(1L)).isEqualTo("detecting");
        verify(deviceSessionManager, times(1)).sendToDevice(
                eq(SLIDER_LAMP_CHIP_ID),
                argThat(payload -> payload.contains("\"type\":\"arm_position\""))
        );
        verify(webSocketPushService).pushGarmentDetectionStatus(
                argThat(payload -> payload.toString().contains("status=detecting")),
                eq(1L)
        );

        service.resetAutomaticGarmentDetection(1L);
        assertThat(service.getGarmentDetectionStatus(1L)).isEqualTo("not_detected");

        service.startAutomaticGarmentDetection(1L);
        assertThat(service.getGarmentDetectionStatus(1L)).isEqualTo("detecting");
        verify(deviceSessionManager, times(2)).sendToDevice(
                eq(SLIDER_LAMP_CHIP_ID),
                argThat(payload -> payload.contains("\"type\":\"arm_position\""))
        );
    }

    @Test
    void batchUpload_returnsImageReceivedAndStartsNextMotionBeforeAiFinishes() throws Exception {
        writeBatchCaptureConfig();
        CountDownLatch aiStarted = new CountDownLatch(1);
        CountDownLatch releaseAi = new CountDownLatch(1);
        when(aiService.fabricRecognize(eq("LAMP-003"), any())).thenAnswer(invocation -> {
            aiStarted.countDown();
            releaseAi.await(5, TimeUnit.SECONDS);
            return null;
        });

        DeviceCamCaptureBatchReqVO request = new DeviceCamCaptureBatchReqVO();
        request.setCamChipId("CAM-001");
        DeviceCamCaptureBatchRespVO batch = service.createCaptureBatch(request);
        DeviceCamCaptureTaskRespVO first = batch.getTasks().get(0);
        awaitCameraCapture(first.getTaskId());

        DeviceCamCaptureTaskRespVO response = service.uploadCapturePhoto(
                first.getTaskId(),
                new MockMultipartFile("file", "zone-3.jpg", "image/jpeg", new byte[]{1, 2, 3})
        );
        rememberTestUpload(response);

        assertThat(response.getStatus()).isEqualTo("image_received");
        assertThat(aiStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(batch.getTasks().get(1).getStatus()).isEqualTo("waiting_motion");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(deviceSessionManager, times(2))
                .sendToDevice(eq(SLIDER_LAMP_CHIP_ID), payloadCaptor.capture());
        JsonNode nextMotion = objectMapper.readTree(payloadCaptor.getAllValues().get(1));
        assertThat(nextMotion.path("taskId").asText()).isEqualTo(batch.getTasks().get(1).getTaskId());
        assertThat(nextMotion.path("slider").asDouble()).isEqualTo(320.0);

        releaseAi.countDown();
    }

    @Test
    void batchReturnsToTargetTwoAfterThirdImageAndCompletesOnArrival() throws Exception {
        writeBatchCaptureConfig();
        DeviceCamCaptureBatchReqVO request = new DeviceCamCaptureBatchReqVO();
        request.setCamChipId("CAM-001");
        DeviceCamCaptureBatchRespVO batch = service.createCaptureBatch(request);

        for (int index = 0; index < batch.getTasks().size(); index++) {
            DeviceCamCaptureTaskRespVO task = batch.getTasks().get(index);
            awaitCameraCapture(task.getTaskId());
            DeviceCamCaptureTaskRespVO upload = service.uploadCapturePhoto(
                    task.getTaskId(),
                    new MockMultipartFile("file", "zone-" + task.getTargetIndex() + ".jpg",
                            "image/jpeg", new byte[]{1, 2, 3})
            );
            rememberTestUpload(upload);
            assertThat(upload.getStatus()).isEqualTo("image_received");
        }

        assertThat(batch.getStatus()).isEqualTo("returning_target_2");
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(deviceSessionManager, times(4))
                .sendToDevice(eq(SLIDER_LAMP_CHIP_ID), payloadCaptor.capture());
        JsonNode returnMotion = objectMapper.readTree(payloadCaptor.getAllValues().get(3));
        assertThat(returnMotion.path("source").asText()).isEqualTo("camera_batch_return");
        assertThat(returnMotion.path("taskId").asText()).isEqualTo(batch.getBatchId());
        assertThat(returnMotion.path("slider").asDouble()).isEqualTo(640.0);
        verify(deviceSessionManager, times(3)).sendToDevice(eq(CAPTURE_CONTROLLER_CHIP_ID), any());
        verify(deviceSessionManager, never()).sendToDevice(eq("CAM-001"), any());
        verify(deviceSessionManager, never()).sendToDevice(eq("LAMP-001"), any());
        verify(deviceSessionManager, never()).sendToDevice(eq("LAMP-002"), any());
        verify(deviceSessionManager, never()).sendToDevice(eq("LAMP-003"), any());

        awaitBatchStatus(batch, "completed");
    }

    @Test
    void cameraStartTrackingCommand_usesHttpWithoutPortOrLegacyTuningFields() throws Exception {
        String json = service.buildCameraStartTrackingCommand(
                "CAM-001", "LAMP-001", 2, "192.168.1.88"
        );
        JsonNode payload = new ObjectMapper().readTree(json);

        assertThat(payload.path("transport").asText()).isEqualTo("http");
        assertThat(payload.path("lampIp").asText()).isEqualTo("192.168.1.88");
        assertThat(payload.has("trackingPreset")).isFalse();
        assertThat(json).doesNotContain(
                "udpIp", "udpPort", "confidenceThreshold", "trackingLostTimeoutSeconds",
                "yaw", "pitch", "roll"
        );
    }

    @Test
    void cameraStartTrackingCommand_rejectsBlankOrPortBearingLampIp() {
        assertThatThrownBy(() -> service.buildCameraStartTrackingCommand(
                "CAM-001", "LAMP-001", 1, ""
        )).hasMessageContaining("灯 IP");
        assertThatThrownBy(() -> service.buildCameraStartTrackingCommand(
                "CAM-001", "LAMP-001", 1, "192.168.1.88:8080"
        )).hasMessageContaining("灯 IP");
    }

    @Test
    void lampIpPayload_mapsEachRoiIndexToItsHttpTarget() {
        DeviceCamRoiItemVO roi = new DeviceCamRoiItemVO();
        roi.setTargetIndex(2);
        roi.setTargetChipId("LAMP-001");
        DeviceCamRoiConfigVO config = new DeviceCamRoiConfigVO();
        config.setRois(List.of(roi));

        DeviceDO lamp = device("LAMP-001", "lamp");
        lamp.setIp("192.168.1.88");
        Map<String, DeviceDO> targets = new LinkedHashMap<>();
        targets.put(lamp.getChipId(), lamp);

        Map<String, Object> payload = service.buildLampIpPayload(config, targets);

        assertThat(payload.get("lampIps")).isEqualTo(List.of("192.168.1.88"));
        assertThat(payload.get("targets")).isEqualTo(List.of(Map.of(
                "targetIndex", 2,
                "targetChipId", "LAMP-001",
                "lampIp", "192.168.1.88"
        )));
    }

    @Test
    void lampIpPayload_supportsClearingAllMappings() {
        DeviceCamRoiConfigVO config = new DeviceCamRoiConfigVO();

        Map<String, Object> payload = service.buildLampIpPayload(config, Map.of());

        assertThat(payload.get("lampIps")).isEqualTo(List.of());
        assertThat(payload.get("targets")).isEqualTo(List.of());
    }

    @Test
    void lampIpPayload_excludesAddressesContainingPorts() {
        DeviceCamRoiItemVO roi = new DeviceCamRoiItemVO();
        roi.setTargetIndex(1);
        roi.setTargetChipId("LAMP-001");
        DeviceCamRoiConfigVO config = new DeviceCamRoiConfigVO();
        config.setRois(List.of(roi));

        DeviceDO lamp = device("LAMP-001", "lamp");
        lamp.setIp("192.168.1.88:8080");

        Map<String, Object> payload = service.buildLampIpPayload(config, Map.of(lamp.getChipId(), lamp));

        assertThat(payload.get("lampIps")).isEqualTo(List.of());
        assertThat(payload.get("targets")).isEqualTo(List.of());
    }

    private void configureManualTrackingDevices(boolean camOnline, boolean lampOnline, String lampIp) {
        DeviceDO cam = device(MANUAL_CAM_CHIP_ID, "cam");
        DeviceDO lamp = device(MANUAL_LAMP_CHIP_ID, "lamp");
        DeviceDO captureController = device(CAPTURE_CONTROLLER_CHIP_ID, "cam_capture");
        lamp.setIp(lampIp);
        stubDevices(cam, lamp, captureController);
        when(deviceSessionManager.isOnline(MANUAL_CAM_CHIP_ID)).thenReturn(camOnline);
        when(deviceSessionManager.isOnline(MANUAL_LAMP_CHIP_ID)).thenReturn(lampOnline);
        when(deviceSessionManager.isOnline(CAPTURE_CONTROLLER_CHIP_ID)).thenReturn(true);
        when(deviceSessionManager.sendToDevice(eq(MANUAL_CAM_CHIP_ID), any(String.class))).thenReturn(true);
        when(deviceSessionManager.sendToDevice(eq(MANUAL_LAMP_CHIP_ID), any(String.class))).thenReturn(true);
        when(deviceSessionManager.sendToDevice(eq(CAPTURE_CONTROLLER_CHIP_ID), any(String.class))).thenReturn(true);
    }

    private DeviceCamTrackingControlReqVO manualTrackingRequest(int targetIndex) {
        DeviceCamTrackingControlReqVO request = new DeviceCamTrackingControlReqVO();
        request.setCamChipId(MANUAL_CAM_CHIP_ID);
        request.setTargetChipId(MANUAL_LAMP_CHIP_ID);
        request.setTargetIndex(targetIndex);
        return request;
    }

    private void writeManualTrackingConfig() throws Exception {
        DeviceCamRoiItemVO roi = new DeviceCamRoiItemVO();
        roi.setTargetIndex(1);
        roi.setTargetChipId(MANUAL_LAMP_CHIP_ID);
        roi.setAreaName("手动追踪测试区");
        roi.setX(0.1);
        roi.setY(0.1);
        roi.setW(0.3);
        roi.setH(0.3);

        DeviceCamRoiConfigVO config = new DeviceCamRoiConfigVO();
        config.setCamChipId(MANUAL_CAM_CHIP_ID);
        config.setSliderLampChipId(MANUAL_LAMP_CHIP_ID);
        config.setCaptureControllerChipId(CAPTURE_CONTROLLER_CHIP_ID);
        config.setConfigured(true);
        config.setRois(List.of(roi));
        config.setSliderPresets(Map.of("1", 600.0));
        config.setSliderMoveTimes(moveTimes(1));

        Path path = manualConfigPath();
        Files.createDirectories(path.getParent());
        objectMapper.writeValue(path.toFile(), config);
    }

    private void writeManualCaptureConfig(int targetIndex) throws Exception {
        DeviceCamRoiItemVO target = new DeviceCamRoiItemVO();
        target.setTargetIndex(targetIndex);
        target.setTargetChipId(MANUAL_LAMP_CHIP_ID);

        DeviceCamRoiConfigVO config = new DeviceCamRoiConfigVO();
        config.setCamChipId(MANUAL_CAM_CHIP_ID);
        config.setSliderLampChipId(MANUAL_LAMP_CHIP_ID);
        config.setCaptureControllerChipId(CAPTURE_CONTROLLER_CHIP_ID);
        config.setRois(List.of(target));
        config.setSliderPresets(Map.of(String.valueOf(targetIndex), 600.0));
        config.setSliderMoveTimes(moveTimes(targetIndex));

        Path path = manualConfigPath();
        Files.createDirectories(path.getParent());
        objectMapper.writeValue(path.toFile(), config);
    }

    private void assertSentCommand(String chipId, String expectedType) throws Exception {
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(deviceSessionManager).sendToDevice(eq(chipId), payloadCaptor.capture());
        assertThat(objectMapper.readTree(payloadCaptor.getValue()).path("type").asText()).isEqualTo(expectedType);
    }

    private DeviceSliderStatusReqVO sliderArrival(
            DeviceCamCaptureTaskRespVO task,
            String sliderLampChipId,
            double targetMm) {
        DeviceSliderStatusReqVO status = new DeviceSliderStatusReqVO();
        status.setChipId(sliderLampChipId);
        status.setTaskId(task.getTaskId());
        status.setStatus("arrived");
        status.setTargetMm(targetMm);
        status.setPositionSteps(0L);
        status.setUptimeMs(1000L);
        return status;
    }

    private Path manualConfigPath() {
        return Path.of("data", "cam-config", MANUAL_CAM_CHIP_ID + ".json").toAbsolutePath().normalize();
    }

    private void writeDefaultCaptureConfig() {
        DeviceCamRoiItemVO target = new DeviceCamRoiItemVO();
        target.setTargetIndex(1);
        target.setTargetChipId("LAMP-001");

        DeviceCamRoiConfigVO config = new DeviceCamRoiConfigVO();
        config.setCamChipId("CAM-001");
        config.setSliderLampChipId(SLIDER_LAMP_CHIP_ID);
        config.setCaptureControllerChipId(CAPTURE_CONTROLLER_CHIP_ID);
        config.setGarmentCapturePan(120D);
        config.setGarmentCaptureTilt(72D);
        config.setPersonCapturePan(90D);
        config.setPersonCaptureTilt(90D);
        config.setFlowUploadEnabled(false);
        config.setFlowUploadIntervalSeconds(30);
        config.setRois(List.of(target));
        config.setSliderPresets(Map.of("1", 320.0));
        config.setSliderMoveTimes(moveTimes(1));

        try {
            Files.createDirectories(defaultConfigPath().getParent());
            objectMapper.writeValue(defaultConfigPath().toFile(), config);
        } catch (Exception e) {
            throw new IllegalStateException("failed to create default camera config", e);
        }
    }

    private void writeBatchCaptureConfig() throws Exception {
        DeviceCamRoiItemVO target1 = new DeviceCamRoiItemVO();
        target1.setTargetIndex(1);
        target1.setTargetChipId("LAMP-001");
        DeviceCamRoiItemVO target2 = new DeviceCamRoiItemVO();
        target2.setTargetIndex(2);
        target2.setTargetChipId("LAMP-002");
        DeviceCamRoiItemVO target3 = new DeviceCamRoiItemVO();
        target3.setTargetIndex(3);
        target3.setTargetChipId("LAMP-003");

        DeviceCamRoiConfigVO config = new DeviceCamRoiConfigVO();
        config.setCamChipId("CAM-001");
        config.setSliderLampChipId(SLIDER_LAMP_CHIP_ID);
        config.setCaptureControllerChipId(CAPTURE_CONTROLLER_CHIP_ID);
        config.setGarmentCapturePan(120D);
        config.setGarmentCaptureTilt(72D);
        config.setPersonCapturePan(90D);
        config.setPersonCaptureTilt(90D);
        config.setFlowUploadEnabled(false);
        config.setFlowUploadIntervalSeconds(30);
        config.setConfigured(true);
        config.setRois(List.of(target1, target2, target3));
        config.setSliderPresets(Map.of("1", 320.0, "2", 640.0, "3", 120.0));
        Map<String, DeviceCamSliderMoveTimeVO> batchMoveTimes = new LinkedHashMap<>();
        batchMoveTimes.putAll(moveTimes(1));
        batchMoveTimes.putAll(moveTimes(2));
        batchMoveTimes.putAll(moveTimes(3));
        config.setSliderMoveTimes(batchMoveTimes);
        objectMapper.writeValue(defaultConfigPath().toFile(), config);

        stubDevices(
                device("CAM-001", "cam"),
                device("LAMP-001", "lamp"),
                device("LAMP-002", "lamp"),
                device("LAMP-003", "lamp"),
                device(SLIDER_LAMP_CHIP_ID, "lamp"),
                device(CAPTURE_CONTROLLER_CHIP_ID, "cam_capture")
        );
    }

    private Path defaultConfigPath() {
        return Path.of("data", "cam-config", "CAM-001.json").toAbsolutePath().normalize();
    }

    private Map<String, DeviceCamSliderMoveTimeVO> moveTimes(int targetIndex) {
        DeviceCamSliderMoveTimeVO value = new DeviceCamSliderMoveTimeVO();
        value.setSlow(0.001D);
        value.setNormal(0.001D);
        value.setFast(0.001D);
        return Map.of(String.valueOf(targetIndex), value);
    }

    private void awaitCameraCapture(String taskId) {
        verify(deviceSessionManager, timeout(1500).times(1)).sendToDevice(
                eq(CAPTURE_CONTROLLER_CHIP_ID),
                argThat(payload -> payload.contains("\"type\":\"cameraCapture\"")
                        && payload.contains("\"taskId\":\"" + taskId + "\""))
        );
    }

    private void awaitBatchStatus(DeviceCamCaptureBatchRespVO batch, String expectedStatus) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!expectedStatus.equals(batch.getStatus()) && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertThat(batch.getStatus()).isEqualTo(expectedStatus);
    }

    private void rememberTestUpload(DeviceCamCaptureTaskRespVO task) {
        if (task.getImageName() != null) {
            testUploadPaths.add(Path.of("data", "cam-upload", task.getImageName()).toAbsolutePath().normalize());
        }
    }

    private DeviceDO device(String chipId, String deviceType) {
        DeviceDO device = new DeviceDO();
        device.setChipId(chipId);
        device.setDeviceType(deviceType);
        device.setStoreId(1L);
        return device;
    }

    @SuppressWarnings("unchecked")
    private void stubDevices(DeviceDO... devices) {
        Map<String, DeviceDO> byChipId = new LinkedHashMap<>();
        for (DeviceDO device : devices) {
            byChipId.put(device.getChipId(), device);
        }
        org.mockito.stubbing.Answer<DeviceDO> answer = invocation -> {
            LambdaQueryWrapper<DeviceDO> wrapper = invocation.getArgument(0);
            wrapper.getSqlSegment();
            return wrapper.getParamNameValuePairs().values().stream()
                    .map(String::valueOf)
                    .map(byChipId::get)
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        };
        doAnswer(answer).when(deviceMapper).selectOne(any(LambdaQueryWrapper.class));
        doAnswer(answer).when(deviceMapper).selectOne(any(LambdaQueryWrapper.class), anyBoolean());
    }
}

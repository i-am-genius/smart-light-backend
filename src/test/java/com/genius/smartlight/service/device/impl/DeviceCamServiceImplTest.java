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
import com.genius.smartlight.service.personflow.PersonFlowRecordService;
import com.genius.smartlight.service.store.CurrentStoreService;
import com.genius.smartlight.vo.device.DeviceCamCaptureBatchReqVO;
import com.genius.smartlight.vo.device.DeviceCamCaptureBatchRespVO;
import com.genius.smartlight.vo.device.DeviceCamCaptureTaskReqVO;
import com.genius.smartlight.vo.device.DeviceCamCaptureTaskRespVO;
import com.genius.smartlight.vo.device.DeviceCamRoiConfigVO;
import com.genius.smartlight.vo.device.DeviceCamRoiItemVO;
import com.genius.smartlight.vo.device.DeviceCamTrackingControlReqVO;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceCamServiceImplTest {

    private static final String MANUAL_CAM_CHIP_ID = "CAM-MANUAL-TRACKING-TEST";
    private static final String MANUAL_LAMP_CHIP_ID = "LAMP-MANUAL-TRACKING-TEST";
    private static final String SLIDER_LAMP_CHIP_ID = "LAMP-SLIDER-TEST";

    private DeviceMapper deviceMapper;
    private CurrentStoreService currentStoreService;
    private WebSocketPushService webSocketPushService;
    private DeviceSessionManager deviceSessionManager;
    private AiService aiService;
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

        service = new DeviceCamServiceImpl(
                deviceMapper,
                currentStoreService,
                webSocketPushService,
                deviceSessionManager,
                mock(PersonFlowRecordService.class),
                mock(DurationRecordMapper.class),
                aiService,
                objectMapper
        );

        DeviceDO cam = device("CAM-001", "cam");
        DeviceDO lamp = device("LAMP-001", "lamp");
        DeviceDO sliderLamp = device(SLIDER_LAMP_CHIP_ID, "lamp");
        stubDevices(cam, lamp, sliderLamp);
        when(currentStoreService.getCurrentStoreId()).thenReturn(1L);
        when(deviceSessionManager.isOnline("CAM-001")).thenReturn(true);
        when(deviceSessionManager.isOnline("LAMP-001")).thenReturn(true);
        when(deviceSessionManager.isOnline(SLIDER_LAMP_CHIP_ID)).thenReturn(true);
        when(deviceSessionManager.sendToDevice(eq("CAM-001"), any(String.class))).thenReturn(true);
        when(deviceSessionManager.sendToDevice(eq("LAMP-001"), any(String.class))).thenReturn(true);
        when(deviceSessionManager.sendToDevice(eq(SLIDER_LAMP_CHIP_ID), any(String.class))).thenReturn(true);
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

        assertThat(result.getTrackingStatus()).isEqualTo("tracking");
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
    void terminalCameraStatus_releasesLampBackToGarmentOrDefaultAim() throws Exception {
        configureManualTrackingDevices(true, true, "192.168.1.88");
        writeManualTrackingConfig();
        service.startTrackingManually(manualTrackingRequest(1));

        DeviceTrackingStatusReqVO status = new DeviceTrackingStatusReqVO();
        status.setChipId(MANUAL_CAM_CHIP_ID);
        status.setRole("cam");
        status.setTrackingStatus("lost");
        status.setCamChipId(MANUAL_CAM_CHIP_ID);
        status.setLampChipId(MANUAL_LAMP_CHIP_ID);
        status.setTargetIndex(1);
        service.reportTrackingStatus(status);

        ArgumentCaptor<String> lampPayloads = ArgumentCaptor.forClass(String.class);
        verify(deviceSessionManager, times(3))
                .sendToDevice(eq(MANUAL_LAMP_CHIP_ID), lampPayloads.capture());
        assertThat(objectMapper.readTree(lampPayloads.getAllValues().get(0)).path("type").asText())
                .isEqualTo("arm_position");
        assertThat(objectMapper.readTree(lampPayloads.getAllValues().get(1)).path("type").asText())
                .isEqualTo("lampTrackingStart");
        assertThat(objectMapper.readTree(lampPayloads.getAllValues().get(2)).path("type").asText())
                .isEqualTo("lampTrackingStop");
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
    void roiContract_usesSingleSliderPresetAndOmitsCameraPosePresets() throws Exception {
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
        config.setRois(List.of(roi));
        config.setSliderPresets(Map.of("1", 320.0));

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(config);

        assertThat(json).contains(
                "\"sliderLampChipId\":\"" + SLIDER_LAMP_CHIP_ID + "\"",
                "\"sliderPresets\":{\"1\":320.0}"
        );
        assertThat(json).doesNotContain(
                "capturePresets", "trackingPresets", "pan", "tilt", "yaw", "pitch", "roll",
                "centerPreset", "trackingLostTimeoutSeconds",
                "dwellSeconds", "leaveDelaySeconds", "confidenceThreshold", "udpIp", "udpPort"
        );
        assertThat(mapper.readTree(json).path("sliderPresets").path("1").asDouble()).isEqualTo(320.0);
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
    void sliderArrival_sendsBackwardCompatibleCameraCaptureExactlyOnce() throws Exception {
        DeviceCamCaptureTaskReqVO request = new DeviceCamCaptureTaskReqVO();
        request.setCamChipId("CAM-001");
        request.setTargetChipId("LAMP-001");
        request.setTargetIndex(1);
        DeviceCamCaptureTaskRespVO task = service.createCaptureTask(request);

        DeviceSliderStatusReqVO arrival = sliderArrival(task, SLIDER_LAMP_CHIP_ID, 320.0);
        service.reportSliderStatus(arrival);
        service.reportSliderStatus(arrival);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(deviceSessionManager, times(1)).sendToDevice(eq("CAM-001"), payloadCaptor.capture());
        JsonNode payload = objectMapper.readTree(payloadCaptor.getValue());
        assertThat(payload.path("type").asText()).isEqualTo("cameraCapture");
        assertThat(payload.path("taskId").asText()).isEqualTo(task.getTaskId());
        assertThat(payload.path("motionReady").asBoolean()).isTrue();
        assertThat(payload.has("capturePreset")).isFalse();
        assertThat(task.getStatus()).isEqualTo("capturing");
    }

    @Test
    void sliderArrival_withWrongTargetDoesNotTriggerCamera() {
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
    void sliderArrival_fromDifferentLampDoesNotTriggerCamera() {
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
        service.reportSliderStatus(sliderArrival(first, SLIDER_LAMP_CHIP_ID, 120.0));

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

        double[] orderedPositions = {120.0, 320.0, 640.0};
        for (int index = 0; index < batch.getTasks().size(); index++) {
            DeviceCamCaptureTaskRespVO task = batch.getTasks().get(index);
            service.reportSliderStatus(sliderArrival(task, SLIDER_LAMP_CHIP_ID, orderedPositions[index]));
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
        verify(deviceSessionManager, times(3)).sendToDevice(eq("CAM-001"), any());
        verify(deviceSessionManager, never()).sendToDevice(eq("LAMP-001"), any());
        verify(deviceSessionManager, never()).sendToDevice(eq("LAMP-002"), any());
        verify(deviceSessionManager, never()).sendToDevice(eq("LAMP-003"), any());

        DeviceSliderStatusReqVO returnArrival = new DeviceSliderStatusReqVO();
        returnArrival.setChipId(SLIDER_LAMP_CHIP_ID);
        returnArrival.setTaskId(batch.getBatchId());
        returnArrival.setStatus("arrived");
        returnArrival.setTargetMm(640.0);
        service.reportSliderStatus(returnArrival);

        assertThat(batch.getStatus()).isEqualTo("completed");
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
        lamp.setIp(lampIp);
        stubDevices(cam, lamp);
        when(deviceSessionManager.isOnline(MANUAL_CAM_CHIP_ID)).thenReturn(camOnline);
        when(deviceSessionManager.isOnline(MANUAL_LAMP_CHIP_ID)).thenReturn(lampOnline);
        when(deviceSessionManager.sendToDevice(eq(MANUAL_CAM_CHIP_ID), any(String.class))).thenReturn(true);
        when(deviceSessionManager.sendToDevice(eq(MANUAL_LAMP_CHIP_ID), any(String.class))).thenReturn(true);
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
        config.setConfigured(true);
        config.setRois(List.of(roi));
        config.setSliderPresets(Map.of("1", 600.0));

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
        config.setRois(List.of(target));
        config.setSliderPresets(Map.of(String.valueOf(targetIndex), 600.0));

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
        config.setRois(List.of(target));
        config.setSliderPresets(Map.of("1", 320.0));

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
        config.setConfigured(true);
        config.setRois(List.of(target1, target2, target3));
        config.setSliderPresets(Map.of("1", 320.0, "2", 640.0, "3", 120.0));
        objectMapper.writeValue(defaultConfigPath().toFile(), config);

        stubDevices(
                device("CAM-001", "cam"),
                device("LAMP-001", "lamp"),
                device("LAMP-002", "lamp"),
                device("LAMP-003", "lamp"),
                device(SLIDER_LAMP_CHIP_ID, "lamp")
        );
    }

    private Path defaultConfigPath() {
        return Path.of("data", "cam-config", "CAM-001.json").toAbsolutePath().normalize();
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

package com.genius.smartlight.service.device.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.dal.mysql.DurationRecordMapper;
import com.genius.smartlight.service.ai.AiService;
import com.genius.smartlight.service.personflow.PersonFlowRecordService;
import com.genius.smartlight.service.store.CurrentStoreService;
import com.genius.smartlight.vo.device.DeviceCamCaptureTaskReqVO;
import com.genius.smartlight.vo.device.DeviceCamCaptureTaskRespVO;
import com.genius.smartlight.vo.device.DeviceCamPresetVO;
import com.genius.smartlight.vo.device.DeviceCamRoiConfigVO;
import com.genius.smartlight.vo.device.DeviceCamRoiItemVO;
import com.genius.smartlight.vo.device.DeviceCamTrackingControlReqVO;
import com.genius.smartlight.vo.device.DeviceTrackingStatusRespVO;
import com.genius.smartlight.websocket.DeviceSessionManager;
import com.genius.smartlight.websocket.WebSocketPushService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class DeviceCamServiceImplTest {

    private static final String MANUAL_CAM_CHIP_ID = "CAM-MANUAL-TRACKING-TEST";
    private static final String MANUAL_LAMP_CHIP_ID = "LAMP-MANUAL-TRACKING-TEST";

    private DeviceMapper deviceMapper;
    private CurrentStoreService currentStoreService;
    private WebSocketPushService webSocketPushService;
    private DeviceSessionManager deviceSessionManager;
    private DeviceCamServiceImpl service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        deviceMapper = mock(DeviceMapper.class);
        currentStoreService = mock(CurrentStoreService.class);
        webSocketPushService = mock(WebSocketPushService.class);
        deviceSessionManager = mock(DeviceSessionManager.class);

        service = new DeviceCamServiceImpl(
                deviceMapper,
                currentStoreService,
                webSocketPushService,
                deviceSessionManager,
                mock(PersonFlowRecordService.class),
                mock(DurationRecordMapper.class),
                mock(AiService.class),
                objectMapper
        );

        DeviceDO cam = device("CAM-001", "cam");
        DeviceDO lamp = device("LAMP-001", "lamp");
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(cam, lamp);
        when(currentStoreService.getCurrentStoreId()).thenReturn(1L);
        when(deviceSessionManager.isOnline("CAM-001")).thenReturn(true);
        when(deviceSessionManager.isOnline("LAMP-001")).thenReturn(false);
        when(deviceSessionManager.sendToDevice(eq("CAM-001"), any(String.class))).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        service.shutdownCaptureTimeoutExecutor();
        try {
            Files.deleteIfExists(manualConfigPath());
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
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(deviceSessionManager).sendToDevice(eq(MANUAL_CAM_CHIP_ID), payloadCaptor.capture());
        JsonNode command = objectMapper.readTree(payloadCaptor.getValue());
        assertThat(command.path("type").asText()).isEqualTo("cameraStartTracking");
        assertThat(command.path("targetIndex").asInt()).isEqualTo(1);
        assertThat(command.path("targetChipId").asText()).isEqualTo(MANUAL_LAMP_CHIP_ID);
        assertThat(command.path("lampIp").asText()).isEqualTo("192.168.1.88");
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
    void createCaptureTask_allowsOfflineTargetLamp() {
        DeviceCamCaptureTaskReqVO request = new DeviceCamCaptureTaskReqVO();
        request.setCamChipId("CAM-001");
        request.setTargetChipId("LAMP-001");
        request.setTargetIndex(1);

        DeviceCamCaptureTaskRespVO result = service.createCaptureTask(request);

        assertThat(result.getCamChipId()).isEqualTo("CAM-001");
        assertThat(result.getTargetChipId()).isEqualTo("LAMP-001");
        assertThat(result.getStatus()).isEqualTo("created");
        verify(deviceSessionManager).isOnline("CAM-001");
        verify(deviceSessionManager, never()).isOnline("LAMP-001");
        verify(deviceSessionManager).sendToDevice(eq("CAM-001"), any(String.class));
    }

    @Test
    void roiContract_usesPanTiltSliderAndOmitsRemovedFields() throws Exception {
        DeviceCamPresetVO preset = new DeviceCamPresetVO();
        preset.setPan(10.0);
        preset.setTilt(-5.0);
        preset.setSlider(320.0);

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
        config.setRois(List.of(roi));
        config.setCapturePresets(Map.of("1", preset));
        config.setTrackingPresets(Map.of("1", preset));

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(config);

        assertThat(json).contains("\"pan\":10.0", "\"tilt\":-5.0", "\"slider\":320.0");
        assertThat(json).doesNotContain(
                "yaw", "pitch", "roll", "centerPreset", "trackingLostTimeoutSeconds",
                "dwellSeconds", "leaveDelaySeconds", "confidenceThreshold", "udpIp", "udpPort"
        );
        assertThat(mapper.readTree(json).path("capturePresets").path("1").size()).isEqualTo(3);
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

        assertThat(normalized.getCapturePresets().get("1").getPan()).isEqualTo(0.0);
        assertThat(normalized.getCapturePresets().get("1").getTilt()).isEqualTo(-5.0);
        assertThat(normalized.getCapturePresets().get("1").getSlider()).isEqualTo(0.0);
        assertThat(normalized.getTrackingPresets().get("1").getPan()).isEqualTo(-45.0);
        assertThat(json).doesNotContain(
                "yaw", "pitch", "roll", "centerPreset", "trackingLostTimeoutSeconds",
                "dwellSeconds", "leaveDelaySeconds", "confidenceThreshold", "udpIp", "udpPort"
        );
    }

    @Test
    void createCaptureTask_sendsNewCapturePreset() throws Exception {
        DeviceCamCaptureTaskReqVO request = new DeviceCamCaptureTaskReqVO();
        request.setCamChipId("CAM-001");
        request.setTargetChipId("LAMP-001");
        request.setTargetIndex(1);

        service.createCaptureTask(request);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(deviceSessionManager).sendToDevice(eq("CAM-001"), payloadCaptor.capture());
        JsonNode payload = new ObjectMapper().readTree(payloadCaptor.getValue());
        assertThat(payload.path("capturePreset").path("pan").asDouble()).isEqualTo(0.0);
        assertThat(payload.path("capturePreset").path("tilt").asDouble()).isEqualTo(0.0);
        assertThat(payload.path("capturePreset").path("slider").asDouble()).isEqualTo(0.0);
        assertThat(payload.toString()).doesNotContain("yaw", "pitch", "roll");
    }

    @Test
    void createCaptureTask_withoutTargetIndex_resolvesCameraPresetFromSelectedLamp() throws Exception {
        configureManualTrackingDevices(true, false, "192.168.1.88");
        writeManualCaptureConfig(2);

        DeviceCamCaptureTaskReqVO request = new DeviceCamCaptureTaskReqVO();
        request.setCamChipId(MANUAL_CAM_CHIP_ID);
        request.setTargetChipId(MANUAL_LAMP_CHIP_ID);

        DeviceCamCaptureTaskRespVO result = service.createCaptureTask(request);

        assertThat(result.getTargetIndex()).isEqualTo(2);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(deviceSessionManager).sendToDevice(eq(MANUAL_CAM_CHIP_ID), payloadCaptor.capture());
        JsonNode payload = objectMapper.readTree(payloadCaptor.getValue());
        assertThat(payload.path("targetChipId").asText()).isEqualTo(MANUAL_LAMP_CHIP_ID);
        assertThat(payload.path("targetIndex").asInt()).isEqualTo(2);
        assertThat(payload.path("capturePreset").path("slider").asDouble()).isEqualTo(600.0);
    }

    @Test
    void createCaptureTask_withoutTargetIndex_rejectsLampWithoutCameraPreset() {
        configureManualTrackingDevices(true, false, "192.168.1.88");

        DeviceCamCaptureTaskReqVO request = new DeviceCamCaptureTaskReqVO();
        request.setCamChipId(MANUAL_CAM_CHIP_ID);
        request.setTargetChipId(MANUAL_LAMP_CHIP_ID);

        assertThatThrownBy(() -> service.createCaptureTask(request))
                .hasMessageContaining("Camera 拍摄预设");
    }

    @Test
    void cameraStartTrackingCommand_usesHttpWithoutPortOrLegacyTuningFields() throws Exception {
        DeviceCamPresetVO preset = new DeviceCamPresetVO();
        preset.setPan(-20.0);
        preset.setTilt(15.0);
        preset.setSlider(600.0);

        String json = service.buildCameraStartTrackingCommand(
                "CAM-001", "LAMP-001", 2, "192.168.1.88", preset
        );
        JsonNode payload = new ObjectMapper().readTree(json);

        assertThat(payload.path("transport").asText()).isEqualTo("http");
        assertThat(payload.path("lampIp").asText()).isEqualTo("192.168.1.88");
        assertThat(payload.path("trackingPreset").path("pan").asDouble()).isEqualTo(-20.0);
        assertThat(payload.path("trackingPreset").path("tilt").asDouble()).isEqualTo(15.0);
        assertThat(payload.path("trackingPreset").path("slider").asDouble()).isEqualTo(600.0);
        assertThat(json).doesNotContain(
                "udpIp", "udpPort", "confidenceThreshold", "trackingLostTimeoutSeconds",
                "yaw", "pitch", "roll"
        );
    }

    @Test
    void cameraStartTrackingCommand_rejectsBlankOrPortBearingLampIp() {
        DeviceCamPresetVO preset = new DeviceCamPresetVO();

        assertThatThrownBy(() -> service.buildCameraStartTrackingCommand(
                "CAM-001", "LAMP-001", 1, "", preset
        )).hasMessageContaining("灯 IP");
        assertThatThrownBy(() -> service.buildCameraStartTrackingCommand(
                "CAM-001", "LAMP-001", 1, "192.168.1.88:8080", preset
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
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(cam, lamp, cam, lamp, cam, lamp);
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

        DeviceCamPresetVO preset = new DeviceCamPresetVO();
        preset.setPan(-20.0);
        preset.setTilt(15.0);
        preset.setSlider(600.0);

        DeviceCamRoiConfigVO config = new DeviceCamRoiConfigVO();
        config.setCamChipId(MANUAL_CAM_CHIP_ID);
        config.setConfigured(true);
        config.setRois(List.of(roi));
        config.setTrackingPresets(Map.of("1", preset));

        Path path = manualConfigPath();
        Files.createDirectories(path.getParent());
        objectMapper.writeValue(path.toFile(), config);
    }

    private void writeManualCaptureConfig(int targetIndex) throws Exception {
        DeviceCamRoiItemVO target = new DeviceCamRoiItemVO();
        target.setTargetIndex(targetIndex);
        target.setTargetChipId(MANUAL_LAMP_CHIP_ID);

        DeviceCamPresetVO preset = new DeviceCamPresetVO();
        preset.setPan(0.0);
        preset.setTilt(0.0);
        preset.setSlider(600.0);

        DeviceCamRoiConfigVO config = new DeviceCamRoiConfigVO();
        config.setCamChipId(MANUAL_CAM_CHIP_ID);
        config.setRois(List.of(target));
        config.setCapturePresets(Map.of(String.valueOf(targetIndex), preset));

        Path path = manualConfigPath();
        Files.createDirectories(path.getParent());
        objectMapper.writeValue(path.toFile(), config);
    }

    private void assertSentCommand(String chipId, String expectedType) throws Exception {
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(deviceSessionManager).sendToDevice(eq(chipId), payloadCaptor.capture());
        assertThat(objectMapper.readTree(payloadCaptor.getValue()).path("type").asText()).isEqualTo(expectedType);
    }

    private Path manualConfigPath() {
        return Path.of("data", "cam-config", MANUAL_CAM_CHIP_ID + ".json").toAbsolutePath().normalize();
    }

    private DeviceDO device(String chipId, String deviceType) {
        DeviceDO device = new DeviceDO();
        device.setChipId(chipId);
        device.setDeviceType(deviceType);
        device.setStoreId(1L);
        return device;
    }
}

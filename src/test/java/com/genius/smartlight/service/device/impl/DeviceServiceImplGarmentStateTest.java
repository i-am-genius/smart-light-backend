package com.genius.smartlight.service.device.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.dataobject.StoreDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.dal.mysql.StoreMapper;
import com.genius.smartlight.security.LoginUser;
import com.genius.smartlight.service.ai.GarmentResultCodec;
import com.genius.smartlight.service.device.OtaProgressStore;
import com.genius.smartlight.service.lighteffect.LightEffectService;
import com.genius.smartlight.vo.ai.FabricRecognizeRespVO;
import com.genius.smartlight.vo.ai.GarmentPartRespVO;
import com.genius.smartlight.vo.device.DeviceRespVO;
import com.genius.smartlight.vo.device.DeviceSaveReqVO;
import com.genius.smartlight.websocket.DeviceSessionManager;
import com.genius.smartlight.websocket.WebSocketPushService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceServiceImplGarmentStateTest {

    private static final long USER_ID = 7L;
    private static final long STORE_ID = 3L;
    private static final long DEVICE_ID = 11L;
    private static final String CHIP_ID = "lamp-structured";

    private DeviceMapper deviceMapper;
    private WebSocketPushService webSocketPushService;
    private LightEffectService lightEffectService;
    private DeviceServiceImpl service;
    private AtomicReference<String> garmentJsonAtMapperUpdate;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new LoginUser(USER_ID, "tester"),
                        null
                )
        );

        deviceMapper = mock(DeviceMapper.class);
        StoreMapper storeMapper = mock(StoreMapper.class);
        webSocketPushService = mock(WebSocketPushService.class);
        lightEffectService = mock(LightEffectService.class);
        service = new DeviceServiceImpl(
                webSocketPushService,
                mock(DeviceSessionManager.class),
                deviceMapper,
                storeMapper,
                new ObjectMapper(),
                new OtaProgressStore(),
                lightEffectService
        );

        StoreDO store = new StoreDO();
        store.setId(STORE_ID);
        store.setUserId(USER_ID);
        when(storeMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(store);

        DeviceDO storedDevice = new DeviceDO();
        storedDevice.setId(DEVICE_ID);
        storedDevice.setChipId(CHIP_ID);
        storedDevice.setDeviceType("lamp");
        storedDevice.setDeviceNo("1");
        storedDevice.setDisplayName("Structured lamp");
        storedDevice.setStoreId(STORE_ID);
        storedDevice.setBrightness(60);
        storedDevice.setTemp(4200);
        storedDevice.setAutoMode(false);
        storedDevice.setFabric("legacy-fabric");
        storedDevice.setMainColorRgb("9,9,9");
        storedDevice.setGarmentResultJson(separatesJson());
        storedDevice.setCreateTime(LocalDateTime.of(2026, 1, 1, 0, 0));
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(storedDevice);

        garmentJsonAtMapperUpdate = new AtomicReference<>("mapper-not-called");
        when(deviceMapper.updateById(any(DeviceDO.class))).thenAnswer(invocation -> {
            DeviceDO update = invocation.getArgument(0);
            garmentJsonAtMapperUpdate.set(update.getGarmentResultJson());
            return 1;
        });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest(name = "lightControl={0}")
    @ValueSource(booleans = {false, true})
    void keepsSeparatesGarmentsInBrowserStateForOrdinaryAndSliderUpdates(
            boolean lightControl) {
        service.updateDevice(DEVICE_ID, updateRequest(), lightControl);

        assertThat(garmentJsonAtMapperUpdate.get())
                .as("ordinary updates must not rewrite the AI snapshot column")
                .isNull();

        ArgumentCaptor<DeviceRespVO> browserState =
                ArgumentCaptor.forClass(DeviceRespVO.class);
        verify(webSocketPushService).pushState(browserState.capture());
        DeviceRespVO state = browserState.getValue();
        assertThat(state.getOutfitType()).isEqualTo("separates");
        assertThat(state.getGarments())
                .extracting(
                        GarmentPartRespVO::getPosition,
                        GarmentPartRespVO::getCategory,
                        GarmentPartRespVO::getFabric,
                        GarmentPartRespVO::getMainColorRgb
                )
                .containsExactly(
                        tuple("upper", "upper", "cotton", "10,20,30"),
                        tuple("lower", "pants", "denim", "40,50,60")
                );

        ArgumentCaptor<DeviceRespVO> deviceState =
                ArgumentCaptor.forClass(DeviceRespVO.class);
        verify(webSocketPushService)
                .pushStateToDevice(eq(CHIP_ID), deviceState.capture());
        assertThat(deviceState.getValue()).isSameAs(state);

        if (lightControl) {
            verify(lightEffectService).closeForLightControl(STORE_ID);
        } else {
            verify(lightEffectService, never()).closeForLightControl(any());
        }
    }

    @ParameterizedTest(name = "lightControl={0}")
    @ValueSource(booleans = {false, true})
    void pushesGarmentSnapshotReadAfterTheControlUpdate(boolean lightControl) {
        DeviceDO latestRow = updatedDeviceWith(dressJson());
        AtomicReference<DeviceDO> databaseRow = new AtomicReference<>(
                deviceMapper.selectById(DEVICE_ID)
        );
        when(deviceMapper.selectById(DEVICE_ID)).thenAnswer(invocation -> databaseRow.get());
        when(deviceMapper.updateById(any(DeviceDO.class))).thenAnswer(invocation -> {
            DeviceDO update = invocation.getArgument(0);
            garmentJsonAtMapperUpdate.set(update.getGarmentResultJson());
            databaseRow.set(latestRow);
            return 1;
        });

        service.updateDevice(DEVICE_ID, updateRequest(), lightControl);

        assertThat(garmentJsonAtMapperUpdate.get())
                .as("control updates must leave the AI snapshot column untouched")
                .isNull();
        ArgumentCaptor<DeviceRespVO> browserState =
                ArgumentCaptor.forClass(DeviceRespVO.class);
        verify(webSocketPushService).pushState(browserState.capture());
        DeviceRespVO state = browserState.getValue();
        assertThat(state.getBrightness()).isEqualTo(75);
        assertThat(state.getGarmentDefaultPan()).isEqualTo(4D);
        assertThat(state.getGarmentDefaultTilt()).isEqualTo(22D);
        assertThat(state.getPersonDefaultPan()).isEqualTo(-3D);
        assertThat(state.getPersonDefaultTilt()).isEqualTo(-26D);
        assertThat(state.getDisplayName()).isEqualTo("Updated lamp");
        assertThat(state.getOutfitType()).isEqualTo("dress");
        assertThat(state.getGarments())
                .extracting(
                        GarmentPartRespVO::getPosition,
                        GarmentPartRespVO::getCategory,
                        GarmentPartRespVO::getFabric,
                        GarmentPartRespVO::getMainColorRgb
                )
                .containsExactly(
                        tuple("fullBody", "dress", "silk", "70,80,90")
                );
    }

    private static DeviceSaveReqVO updateRequest() {
        DeviceSaveReqVO request = new DeviceSaveReqVO();
        request.setChipId(CHIP_ID);
        request.setDeviceType("lamp");
        request.setDeviceNo("1");
        request.setDisplayName("Updated lamp");
        request.setBrightness(75);
        request.setTemp(4800);
        request.setAutoMode(false);
        request.setGarmentDefaultPan(4D);
        request.setGarmentDefaultTilt(22D);
        request.setPersonDefaultPan(-3D);
        request.setPersonDefaultTilt(-26D);
        request.setRecommendedBrightness(80);
        request.setRecommendedTemp(5000);
        request.setFabric("updated-legacy-fabric");
        request.setMainColorRgb("90,90,90");
        return request;
    }

    private static String separatesJson() {
        FabricRecognizeRespVO result = new FabricRecognizeRespVO();
        result.setResultVersion(1);
        result.setClothDetected(true);
        result.setSegmentationFallback(false);
        result.setOutfitType("separates");
        result.setGarments(List.of(
                garment("upper", "upper", "cotton", "10,20,30"),
                garment("lower", "pants", "denim", "40,50,60")
        ));
        return GarmentResultCodec.encode(
                result,
                LocalDateTime.of(2026, 1, 2, 3, 4, 5)
        );
    }

    private static String dressJson() {
        FabricRecognizeRespVO result = new FabricRecognizeRespVO();
        result.setResultVersion(1);
        result.setClothDetected(true);
        result.setSegmentationFallback(false);
        result.setOutfitType("dress");
        result.setGarments(List.of(
                garment("fullBody", "dress", "silk", "70,80,90")
        ));
        return GarmentResultCodec.encode(
                result,
                LocalDateTime.of(2026, 1, 2, 3, 5, 0)
        );
    }

    private static DeviceDO updatedDeviceWith(String garmentResultJson) {
        DeviceDO device = new DeviceDO();
        device.setId(DEVICE_ID);
        device.setChipId(CHIP_ID);
        device.setDeviceType("lamp");
        device.setDeviceNo("1");
        device.setDisplayName("Updated lamp");
        device.setStoreId(STORE_ID);
        device.setBrightness(75);
        device.setTemp(4800);
        device.setAutoMode(false);
        device.setGarmentDefaultPan(4D);
        device.setGarmentDefaultTilt(22D);
        device.setPersonDefaultPan(-3D);
        device.setPersonDefaultTilt(-26D);
        device.setRecommendedBrightness(80);
        device.setRecommendedTemp(5000);
        device.setFabric("updated-legacy-fabric");
        device.setMainColorRgb("90,90,90");
        device.setGarmentResultJson(garmentResultJson);
        device.setCreateTime(LocalDateTime.of(2026, 1, 1, 0, 0));
        return device;
    }

    private static GarmentPartRespVO garment(
            String position,
            String category,
            String fabric,
            String color) {
        GarmentPartRespVO garment = new GarmentPartRespVO();
        garment.setPosition(position);
        garment.setCategory(category);
        garment.setFabric(fabric);
        garment.setMainColorRgb(color);
        garment.setMaskArea(100);
        return garment;
    }
}

package com.genius.smartlight.service.ai.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.dataobject.StoreDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.dal.mysql.StoreMapper;
import com.genius.smartlight.integration.ai.FabricAiClient;
import com.genius.smartlight.security.LoginUser;
import com.genius.smartlight.service.ai.GarmentRecognitionProcessor;
import com.genius.smartlight.vo.ai.FabricRecognizeRespVO;
import com.genius.smartlight.vo.ai.GarmentPartRespVO;
import com.genius.smartlight.vo.device.DeviceRespVO;
import com.genius.smartlight.websocket.WebSocketPushService;
import com.genius.smartlight.websocket.fabric.FabricImageLiveNotifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.AbstractList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiServiceImplGarmentPersistenceTest {

    private static final long USER_ID = 7L;
    private static final long STORE_ID = 3L;
    private static final String CHIP_ID = "lamp-1";

    @TempDir
    Path tempDir;

    private DeviceMapper deviceMapper;
    private StoreMapper storeMapper;
    private FabricAiClient fabricAiClient;
    private GarmentRecognitionProcessor garmentRecognitionProcessor;
    private FabricImageLiveNotifier fabricImageLiveNotifier;
    private WebSocketPushService webSocketPushService;
    private AiServiceImpl service;
    private DeviceDO device;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new LoginUser(USER_ID, "tester"),
                        null
                )
        );

        deviceMapper = mock(DeviceMapper.class);
        storeMapper = mock(StoreMapper.class);
        fabricAiClient = mock(FabricAiClient.class);
        garmentRecognitionProcessor = mock(GarmentRecognitionProcessor.class);
        fabricImageLiveNotifier = mock(FabricImageLiveNotifier.class);
        webSocketPushService = mock(WebSocketPushService.class);
        service = mock(AiServiceImpl.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "deviceMapper", deviceMapper);
        ReflectionTestUtils.setField(service, "storeMapper", storeMapper);
        ReflectionTestUtils.setField(service, "fabricAiClient", fabricAiClient);
        ReflectionTestUtils.setField(service, "garmentRecognitionProcessor", garmentRecognitionProcessor);
        ReflectionTestUtils.setField(service, "fabricImageLiveNotifier", fabricImageLiveNotifier);
        ReflectionTestUtils.setField(service, "webSocketPushService", webSocketPushService);

        device = new DeviceDO();
        device.setId(11L);
        device.setChipId(CHIP_ID);
        device.setDeviceType("lamp");
        device.setStoreId(STORE_ID);
        device.setFabric("old-fabric");
        device.setMainColorRgb("1,1,1");
        device.setRecommendedBrightness(1);
        device.setRecommendedTemp(2700);
        device.setGarmentResultJson("old-json");
        device.setUpdateTime(LocalDateTime.of(2025, 1, 1, 0, 0));

        StoreDO store = new StoreDO();
        store.setId(STORE_ID);
        store.setUserId(USER_ID);
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(device);
        when(storeMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(store);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void streamsOneOriginalAndUsesOneArchiveIdentityForAllAuthorizedUrls() throws Exception {
        MultipartFile file = multipartFileThatRejectsGetBytes();
        when(deviceMapper.updateById(device)).thenReturn(1);
        when(fabricAiClient.recognize(
                same(file),
                eq(CHIP_ID),
                matches("lamp-1_\\d{8}_\\d{6}_[0-9a-f]{8}"),
                eq(false)
        )).thenAnswer(invocation -> {
            String archiveId = invocation.getArgument(2);
            Path annotatedDirectory = Files.createDirectories(tempDir.resolve("annotated"));
            Path combinedDirectory = Files.createDirectories(tempDir.resolve("combined"));
            Files.write(annotatedDirectory.resolve(archiveId + "_annotated.jpg"), jpegBytes());
            Files.write(combinedDirectory.resolve(archiveId + "_combined.jpg"), jpegBytes());

            FabricRecognizeRespVO result = validResult();
            result.setAnnotatedImagePath(
                    "/opt/smartlight/uploads/fabric/annotated/python-output.jpg"
            );
            result.setAnnotatedImageUrl(
                    "/opt/smartlight/uploads/fabric/annotated/python-output.jpg"
            );
            result.setCombinedImagePath(
                    "/opt/smartlight/uploads/fabric/combined/python-output.jpg"
            );
            result.setCombinedImageUrl(
                    "/opt/smartlight/uploads/fabric/combined/python-output.jpg"
            );
            return result;
        });

        FabricRecognizeRespVO result = service.fabricRecognize(CHIP_ID, file, tempDir);

        ArgumentCaptor<String> archiveIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(fabricAiClient).recognize(
                same(file),
                eq(CHIP_ID),
                archiveIdCaptor.capture(),
                eq(false)
        );
        verify(file, never()).getBytes();

        String archiveId = archiveIdCaptor.getValue();
        assertThat(archiveId).matches("lamp-1_\\d{8}_\\d{6}_[0-9a-f]{8}");
        try (Stream<Path> originals = Files.list(tempDir.resolve("original"))) {
            assertThat(originals.map(path -> path.getFileName().toString()).toList())
                    .containsExactly(archiveId + "_original.jpg");
        }
        assertThat(tempDir.resolve("annotated/" + archiveId + "_annotated.jpg"))
                .isRegularFile();
        assertThat(tempDir.resolve("combined/" + archiveId + "_combined.jpg"))
                .isRegularFile();
        try (Stream<Path> files = Files.walk(tempDir)) {
            assertThat(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")))
                    .isTrue();
        }

        assertThat(result.getOriginalImagePath())
                .isEqualTo("original/" + archiveId + "_original.jpg");
        assertThat(result.getAnnotatedImagePath())
                .isEqualTo("annotated/" + archiveId + "_annotated.jpg");
        assertThat(result.getCombinedImagePath())
                .isEqualTo("combined/" + archiveId + "_combined.jpg");
        assertThat(result.getOriginalImageUrl())
                .isEqualTo(archiveUrl("original", archiveId + "_original.jpg"));
        assertThat(result.getAnnotatedImageUrl())
                .isEqualTo(archiveUrl("annotated", archiveId + "_annotated.jpg"));
        assertThat(result.getCombinedImageUrl())
                .isEqualTo(archiveUrl("combined", archiveId + "_combined.jpg"));
    }

    @Test
    void removesUnavailablePythonFilesystemLocationsBeforeHttpAndWebSocketResponse() throws Exception {
        MultipartFile file = multipartFileThatRejectsGetBytes();
        when(deviceMapper.updateById(device)).thenReturn(1);
        FabricRecognizeRespVO pythonResult = validResult();
        String pythonAnnotatedPath =
                "/opt/smartlight/uploads/fabric/annotated/python-output.jpg";
        pythonResult.setAnnotatedImagePath(pythonAnnotatedPath);
        pythonResult.setAnnotatedImageUrl(pythonAnnotatedPath);
        pythonResult.setCombinedImagePath(
                "/opt/smartlight/uploads/fabric/combined/python-output.jpg"
        );
        pythonResult.setCombinedImageUrl(
                "/opt/smartlight/uploads/fabric/combined/python-output.jpg"
        );
        when(fabricAiClient.recognize(
                same(file),
                eq(CHIP_ID),
                matches("lamp-1_\\d{8}_\\d{6}_[0-9a-f]{8}"),
                eq(false)
        )).thenReturn(pythonResult);

        FabricRecognizeRespVO result = service.fabricRecognize(CHIP_ID, file, tempDir);

        assertThat(result.getAnnotatedImagePath()).isNull();
        assertThat(result.getAnnotatedImageUrl()).isNull();
        assertThat(result.getCombinedImagePath()).isNull();
        assertThat(result.getCombinedImageUrl()).isNull();
        assertThat(result.getOriginalImageUrl())
                .startsWith("/admin/ai/fabric-archive/file?type=original&filename=");
        verify(webSocketPushService).pushFabricRecognize(
                eq(CHIP_ID),
                eq("sample.jpg"),
                same(result),
                eq(STORE_ID)
        );
        verify(fabricImageLiveNotifier).pushIfPresent(
                eq(STORE_ID),
                eq(CHIP_ID),
                eq(tempDir),
                isNull(),
                eq(pythonAnnotatedPath)
        );
    }

    @Test
    void atomicMultipartSaveRejectsTraversalAndDoesNotLeaveTemporaryFile() throws Exception {
        MultipartFile file = multipartFileThatRejectsGetBytes();

        assertThatThrownBy(() -> AiServiceImpl.saveMultipartFileAtomic(
                file,
                tempDir,
                "../outside",
                "escape.jpg"
        )).isInstanceOf(IOException.class)
                .hasMessage("Invalid fabric archive path");

        try (Stream<Path> files = Files.walk(tempDir)) {
            assertThat(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")))
                    .isTrue();
        }
        verify(file, never()).getBytes();
    }

    @Test
    void atomicMultipartSaveCleansTemporaryFileWhenStreamingFails() throws Exception {
        MultipartFile file = multipartFileThatRejectsGetBytes();
        when(file.getInputStream()).thenReturn(new InputStream() {
            private int reads;

            @Override
            public int read() throws IOException {
                if (reads++ == 0) {
                    return 0xFF;
                }
                throw new IOException("stream failed");
            }
        });

        assertThatThrownBy(() -> AiServiceImpl.saveMultipartFileAtomic(
                file,
                tempDir,
                "original",
                "broken.jpg"
        )).isInstanceOf(IOException.class)
                .hasMessage("stream failed");

        assertThat(tempDir.resolve("original/broken.jpg")).doesNotExist();
        assertThat(tempDir.resolve("original/broken.jpg.tmp")).doesNotExist();
        verify(file, never()).getBytes();
    }

    @Test
    void writesLegacyAndStructuredFieldsAtomicallyThenPushesBrowserState() throws Exception {
        FabricRecognizeRespVO result = validResult();
        when(deviceMapper.updateById(device)).thenReturn(1);

        DeviceDO returned = invokeUpdate(result);

        ArgumentCaptor<DeviceDO> updated = ArgumentCaptor.forClass(DeviceDO.class);
        InOrder order = inOrder(deviceMapper, webSocketPushService);
        order.verify(deviceMapper).updateById(updated.capture());
        ArgumentCaptor<DeviceRespVO> state = ArgumentCaptor.forClass(DeviceRespVO.class);
        order.verify(webSocketPushService).pushState(state.capture());
        assertThat(updated.getValue()).isSameAs(device).isSameAs(returned);
        assertThat(device.getFabric()).isEqualTo("cotton");
        assertThat(device.getMainColorRgb()).isEqualTo("10,20,30");
        assertThat(device.getRecommendedBrightness()).isEqualTo(80);
        assertThat(device.getRecommendedTemp()).isEqualTo(5000);
        assertThat(device.getUpdateTime()).isNotNull();
        assertThat(device.getGarmentResultJson())
                .contains("\"resultVersion\":1")
                .contains("\"outfitType\":\"upper_only\"")
                .doesNotContain("secret-base64", "colorSamplePngBase64");
        String recognizedAt = new ObjectMapper()
                .readTree(device.getGarmentResultJson())
                .path("recognizedAt")
                .asText();
        assertThat(LocalDateTime.parse(recognizedAt)).isEqualTo(device.getUpdateTime());
        assertThat(state.getValue().getGarments()).hasSize(1);
        verify(deviceMapper).updateById(any(DeviceDO.class));
    }

    @Test
    void codecFailureDoesNotMutateDeviceUpdateDatabaseOrPushSuccess() {
        FabricRecognizeRespVO result = validResult();
        result.setGarments(new AbstractList<>() {
            @Override
            public GarmentPartRespVO get(int index) {
                throw new IllegalStateException("codec failed");
            }

            @Override
            public int size() {
                return 1;
            }
        });
        LocalDateTime originalUpdateTime = device.getUpdateTime();

        assertThatThrownBy(() -> invokeUpdate(result))
                .isInstanceOf(ServiceException.class);

        assertThat(device.getFabric()).isEqualTo("old-fabric");
        assertThat(device.getMainColorRgb()).isEqualTo("1,1,1");
        assertThat(device.getRecommendedBrightness()).isEqualTo(1);
        assertThat(device.getRecommendedTemp()).isEqualTo(2700);
        assertThat(device.getGarmentResultJson()).isEqualTo("old-json");
        assertThat(device.getUpdateTime()).isEqualTo(originalUpdateTime);
        verify(deviceMapper, never()).updateById(any(DeviceDO.class));
        verifyNoInteractions(webSocketPushService);
    }

    @Test
    void mapperFailureDoesNotPushSuccessfulStateOrFabricRecognition() {
        when(deviceMapper.updateById(any(DeviceDO.class)))
                .thenThrow(new IllegalStateException("database failed"));

        assertThatThrownBy(() -> invokeUpdate(validResult()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database failed");

        verify(deviceMapper).updateById(device);
        verifyNoInteractions(webSocketPushService);
    }

    @Test
    void zeroUpdatedRowsDoesNotPushSuccessfulStateOrFabricRecognition() {
        when(deviceMapper.updateById(device)).thenReturn(0);

        assertThatThrownBy(() -> invokeUpdate(validResult()))
                .isInstanceOf(ServiceException.class);

        verify(deviceMapper).updateById(device);
        verifyNoInteractions(webSocketPushService);
    }

    @Test
    void combinedOnlyBackendArchiveDoesNotOverwriteAiAnnotatedPath() {
        FabricRecognizeRespVO result = validResult();
        result.setAnnotatedImagePath(
                "/opt/smartlight/uploads/fabric/annotated/lamp-1_actual_annotated.jpg"
        );

        AiServiceImpl.applyLocalArchiveLocations(
                result,
                "lamp-1_backend_annotated.jpg",
                "lamp-1_backend_combined.jpg",
                false,
                true
        );

        assertThat(result.getAnnotatedImagePath())
                .isEqualTo("/opt/smartlight/uploads/fabric/annotated/lamp-1_actual_annotated.jpg");
        assertThat(result.getCombinedImagePath())
                .isEqualTo("combined/lamp-1_backend_combined.jpg");
    }

    private DeviceDO invokeUpdate(FabricRecognizeRespVO result) {
        return ReflectionTestUtils.invokeMethod(
                service,
                "updateDeviceAiResult",
                CHIP_ID,
                result
        );
    }

    private static MultipartFile multipartFileThatRejectsGetBytes() throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("sample.jpg");
        when(file.getContentType()).thenReturn("image/jpeg");
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn((long) jpegBytes().length);
        when(file.getInputStream()).thenAnswer(ignored ->
                new ByteArrayInputStream(jpegBytes()));
        doThrow(new AssertionError("fabric hot path must not call MultipartFile#getBytes"))
                .when(file).getBytes();
        return file;
    }

    private static byte[] jpegBytes() {
        return new byte[]{
                (byte) 0xFF, (byte) 0xD8, (byte) 0xFF,
                0x01, 0x02, 0x03
        };
    }

    private static String archiveUrl(String type, String filename) {
        return "/admin/ai/fabric-archive/file?type="
                + type
                + "&filename="
                + filename;
    }

    private static FabricRecognizeRespVO validResult() {
        GarmentPartRespVO part = new GarmentPartRespVO();
        part.setPosition("upper");
        part.setCategory("upper");
        part.setCategoryConfidence(0.9);
        part.setFabric("cotton");
        part.setFabricConfidence(0.8);
        part.setMainColorRgb("10,20,30");
        part.setMaskArea(100);
        part.setX(1);
        part.setY(2);
        part.setW(10);
        part.setH(20);
        part.setColorSamplePngBase64("secret-base64");

        FabricRecognizeRespVO result = new FabricRecognizeRespVO();
        result.setResultVersion(1);
        result.setClothDetected(true);
        result.setSegmentationFallback(false);
        result.setOutfitType("upper_only");
        result.setGarments(List.of(part));
        result.setLabel("cotton");
        result.setMainColorRgb("10,20,30");
        result.setRecommendedBrightness(80);
        result.setRecommendedTemp(5000);
        return result;
    }
}

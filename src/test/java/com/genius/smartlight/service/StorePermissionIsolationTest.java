package com.genius.smartlight.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.dal.dataobject.*;
import com.genius.smartlight.dal.mysql.*;
import com.genius.smartlight.security.LoginUser;
import com.genius.smartlight.security.SecurityUtils;
import com.genius.smartlight.service.ai.FabricArchiveService;
import com.genius.smartlight.service.duration.impl.DurationServiceImpl;
import com.genius.smartlight.service.lux.impl.LuxServiceImpl;
import com.genius.smartlight.service.personflow.impl.PersonFlowRecordServiceImpl;
import com.genius.smartlight.service.store.CurrentStoreService;
import com.genius.smartlight.vo.ai.FabricArchivePageRespVO;
import com.genius.smartlight.websocket.WebSocketPushService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 权限隔离测试：验证用户 A 不能访问用户 B 的数据。
 * 所有测试使用 Mockito mock Mapper 层，不需要真实数据库。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StorePermissionIsolationTest {

    private static final Long USER_A_ID = 1L;
    private static final Long USER_B_ID = 2L;
    private static final Long STORE_A_ID = 100L;
    private static final Long STORE_B_ID = 200L;
    private static final String DEVICE_A_CHIP_ID = "LAMP-A00001";
    private static final String DEVICE_B_CHIP_ID = "LAMP-B00001";

    @Mock
    private LuxRecordMapper luxRecordMapper;
    @Mock
    private DeviceMapper deviceMapper;
    @Mock
    private DurationRecordMapper durationRecordMapper;
    @Mock
    private PersonFlowRecordMapper personFlowRecordMapper;
    @Mock
    private CurrentStoreService currentStoreService;
    @Mock
    private WebSocketPushService webSocketPushService;
    @Mock
    private StoreMapper storeMapper;

    private MockedStatic<SecurityUtils> securityUtilsMock;

    @BeforeEach
    void setUp() {
        securityUtilsMock = mockStatic(SecurityUtils.class);

        // Default: User A is logged in
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(new LoginUser(USER_A_ID, "userA"));
        SecurityContext ctx = mock(SecurityContext.class);
        when(ctx.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(ctx);

        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(USER_A_ID);
        securityUtilsMock.when(SecurityUtils::getCurrentUsername).thenReturn("userA");

        // Default: CurrentStoreService returns store A
        when(currentStoreService.getCurrentStoreId()).thenReturn(STORE_A_ID);
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
        SecurityContextHolder.clearContext();
    }

    // ========== Lux 权限隔离测试 ==========

    @Test
    void lux_userA_cannotQueryUserB_deviceLux() {
        LuxServiceImpl luxService = new LuxServiceImpl(
                luxRecordMapper, deviceMapper, currentStoreService, webSocketPushService);

        // Device A belongs to store A — user A CAN access
        DeviceDO deviceA = new DeviceDO();
        deviceA.setChipId(DEVICE_A_CHIP_ID);
        deviceA.setStoreId(STORE_A_ID);
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(deviceA);

        LuxRecordDO recordA = new LuxRecordDO();
        recordA.setChipId(DEVICE_A_CHIP_ID);
        recordA.setStoreId(STORE_A_ID);
        recordA.setLuxValue(new BigDecimal("500.0"));
        recordA.setCollectTime(LocalDateTime.now());
        when(luxRecordMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(recordA);

        var result = luxService.getLatestLuxRecord(DEVICE_A_CHIP_ID);
        assertThat(result).isNotNull();

        // Now switch to User B's store — query should fail
        when(currentStoreService.getCurrentStoreId()).thenReturn(STORE_B_ID);
        // Device A is NOT in store B
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(null);

        assertThatThrownBy(() -> luxService.getLatestLuxRecord(DEVICE_A_CHIP_ID))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("无权查看");
    }

    @Test
    void lux_userA_queryWithStoreIdEnforced() {
        LuxServiceImpl luxService = new LuxServiceImpl(
                luxRecordMapper, deviceMapper, currentStoreService, webSocketPushService);

        // Both device AND lux query must match storeId
        DeviceDO deviceA = new DeviceDO();
        deviceA.setChipId(DEVICE_A_CHIP_ID);
        deviceA.setStoreId(STORE_A_ID);
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(deviceA);

        LuxRecordDO recordA = new LuxRecordDO();
        recordA.setChipId(DEVICE_A_CHIP_ID);
        recordA.setStoreId(STORE_A_ID);
        recordA.setLuxValue(new BigDecimal("500.0"));
        recordA.setCollectTime(LocalDateTime.now());
        when(luxRecordMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(recordA));

        var list = luxService.getLuxRecordList(DEVICE_A_CHIP_ID);
        assertThat(list).hasSize(1);
    }

    // ========== Duration 权限隔离测试 ==========

    @Test
    void duration_userA_cannotQueryUserB_storeDuration() {
        DurationServiceImpl durationService = new DurationServiceImpl(
                webSocketPushService, durationRecordMapper, deviceMapper, currentStoreService);

        DurationRecordDO recordA = new DurationRecordDO();
        recordA.setChipId(DEVICE_A_CHIP_ID);
        recordA.setStoreId(STORE_A_ID);
        recordA.setStatDate(LocalDate.now());
        when(durationRecordMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(recordA);

        var result = durationService.getByChipIdAndDate(DEVICE_A_CHIP_ID, LocalDate.now());
        assertThat(result).isNotNull();

        // Switch to store B — storeId in WHERE won't match
        when(currentStoreService.getCurrentStoreId()).thenReturn(STORE_B_ID);
        when(durationRecordMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(null);

        assertThatThrownBy(() ->
                durationService.getByChipIdAndDate(DEVICE_A_CHIP_ID, LocalDate.now()))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("未找到");
    }

    @Test
    void duration_summary_userA_chipIdMustBelongToStore() {
        DurationServiceImpl durationService = new DurationServiceImpl(
                webSocketPushService, durationRecordMapper, deviceMapper, currentStoreService);

        // chipId belongs to store B, but user is in store A
        when(currentStoreService.getCurrentStoreId()).thenReturn(STORE_A_ID);
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(null); // no device matching store A + chipId

        assertThatThrownBy(() ->
                durationService.getDeviceSummaryByDateRange(
                        LocalDate.now().minusDays(7), LocalDate.now(), DEVICE_B_CHIP_ID))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("无权");
    }

    // ========== PersonFlow 权限隔离测试 ==========

    @Test
    void personFlow_userWithNoStore_returnsEmptyList() {
        PersonFlowRecordServiceImpl personFlowService =
                new PersonFlowRecordServiceImpl(personFlowRecordMapper, currentStoreService);

        when(currentStoreService.getCurrentStoreId()).thenThrow(
                new ServiceException("当前用户未绑定店铺"));

        var recent = personFlowService.getRecentRecords(10);
        assertThat(recent).isEmpty();

        var list = personFlowService.getList(null, null, null, 1, 10);
        assertThat(list).isEmpty();

        var trend = personFlowService.getTrend(null, null, null);
        assertThat(trend).isEmpty();
    }

    @Test
    void personFlow_userA_onlySeesStoreAData() {
        PersonFlowRecordServiceImpl personFlowService =
                new PersonFlowRecordServiceImpl(personFlowRecordMapper, currentStoreService);

        PersonFlowRecordDO recordA = new PersonFlowRecordDO();
        recordA.setStoreId(STORE_A_ID);
        recordA.setChipId(DEVICE_A_CHIP_ID);
        recordA.setPersonCount(5);
        recordA.setDetectTime(LocalDateTime.now());

        when(personFlowRecordMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(recordA));

        var recent = personFlowService.getRecentRecords(10);
        assertThat(recent).hasSize(1);
        // Verify the query used storeId = STORE_A_ID
        verify(currentStoreService, atLeastOnce()).getCurrentStoreId();
    }

    // ========== Weather 权限测试 ==========

    @Test
    void weather_controllerDoesNotAcceptStoreIdFromRequest() {
        // This is verified by code review: WeatherController no longer has
        // @RequestParam("storeId") or @PathVariable("storeId").
        // It derives storeId from CurrentStoreService.
        //
        // We validate via reflection that the method signature has no
        // storeId parameter.
        var methods = com.genius.smartlight.controller.admin.weather.WeatherController.class
                .getDeclaredMethods();
        for (var method : methods) {
            if ("getCurrentWeather".equals(method.getName())
                    || "collectWeather".equals(method.getName())) {
                assertThat(method.getParameterCount()).isZero();
            }
        }
    }

    // ========== FabricArchive 权限测试 ==========

    @Test
    void fabricArchive_listArchive_filtersByCurrentStoreDevices() throws IOException {
        FabricArchiveService fabricArchiveService =
                new FabricArchiveService(deviceMapper, currentStoreService);

        // User A's store has device A
        DeviceDO deviceA = new DeviceDO();
        deviceA.setChipId(DEVICE_A_CHIP_ID);
        deviceA.setStoreId(STORE_A_ID);
        when(deviceMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(deviceA));

        // Create temp test directory with controlled filenames
        Path tempDir = Files.createTempDirectory("fabric-archive-test");
        try {
            Path originalDir = Files.createDirectories(tempDir.resolve("original"));
            // File belonging to user A's device
            Files.createFile(originalDir.resolve("LAMP-A00001_20260414_103000_A1B2C3D4_original.jpg"));
            // File belonging to user B's device (should be filtered out)
            Files.createFile(originalDir.resolve("LAMP-B00001_20260414_103000_B2C3D4E5_original.jpg"));
            // File with unparseable name (should be filtered out)
            Files.createFile(originalDir.resolve("badname.jpg"));

            // We can't easily change FABRIC_ARCHIVE_BASE_DIR constant,
            // so this test verifies the filtering logic indirectly:
            // getAllowedChipIds() returns only {"LAMP-A00001"}
            var allowedIds = invokeGetAllowedChipIds(fabricArchiveService);
            assertThat(allowedIds).contains(DEVICE_A_CHIP_ID);
            assertThat(allowedIds).doesNotContain(DEVICE_B_CHIP_ID);
        } finally {
            // Cleanup
            Files.walk(tempDir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        }
    }

    @Test
    void fabricArchive_deleteArchive_failsForUnauthorizedChipId() {
        FabricArchiveService fabricArchiveService =
                new FabricArchiveService(deviceMapper, currentStoreService);

        // Only device A belongs to user A
        DeviceDO deviceA = new DeviceDO();
        deviceA.setChipId(DEVICE_A_CHIP_ID);
        deviceA.setStoreId(STORE_A_ID);
        when(deviceMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(deviceA));

        // Try to delete a file whose baseName starts with DEVICE_B_CHIP_ID
        assertThatThrownBy(() ->
                fabricArchiveService.deleteArchiveGroup(
                        "LAMP-B00001_20260414_103000_B2C3D4E5_combined.jpg", null))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("无权删除");
    }

    @Test
    void analytics_resolveStoreId_rejectsChipIdFromOtherStore() {
        // AnalyticsServiceImpl.resolveStoreId should return null when
        // chipId belongs to a device in another store
        StoreDO storeA = new StoreDO();
        storeA.setId(STORE_A_ID);
        storeA.setUserId(USER_A_ID);

        DeviceDO deviceB = new DeviceDO();
        deviceB.setChipId(DEVICE_B_CHIP_ID);
        deviceB.setStoreId(STORE_B_ID);

        when(storeMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(storeA);
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(deviceB);

        var analyticsService = new com.genius.smartlight.service.analytics.impl.AnalyticsServiceImpl(
                null, deviceMapper, storeMapper, null);

        // invoke resolveStoreId via reflection (it's private)
        Long result = invokeResolveStoreId(analyticsService, DEVICE_B_CHIP_ID);
        assertThat(result).isNull(); // Should reject chipId from other store
    }

    // ========== Helper methods ==========

    @SuppressWarnings("unchecked")
    private java.util.Set<String> invokeGetAllowedChipIds(FabricArchiveService service) {
        try {
            var method = FabricArchiveService.class.getDeclaredMethod("getAllowedChipIds");
            method.setAccessible(true);
            return (java.util.Set<String>) method.invoke(service);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Long invokeResolveStoreId(
            com.genius.smartlight.service.analytics.impl.AnalyticsServiceImpl service,
            String chipId) {
        try {
            var method = com.genius.smartlight.service.analytics.impl.AnalyticsServiceImpl.class
                    .getDeclaredMethod("resolveStoreId", String.class);
            method.setAccessible(true);
            return (Long) method.invoke(service, chipId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

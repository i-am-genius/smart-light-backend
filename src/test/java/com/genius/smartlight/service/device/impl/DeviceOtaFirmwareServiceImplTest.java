package com.genius.smartlight.service.device.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.dal.dataobject.OtaFirmwareDO;
import com.genius.smartlight.dal.mysql.OtaFirmwareMapper;
import com.genius.smartlight.service.device.OtaDownloadSecurityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockMultipartFile;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeviceOtaFirmwareServiceImplTest {

    private static final String SECRET = "12345678901234567890123456789012";
    private static final byte[] OLD_FIRMWARE = new byte[]{(byte) 0xE9, 1, 2, 3, 4, 5};
    private static final byte[] NEW_FIRMWARE = new byte[]{(byte) 0xE9, 9, 8, 7, 6, 5};

    private int versionCode;
    private Path versionDir;
    private Path targetFile;
    private OtaFirmwareMapper otaFirmwareMapper;
    private DeviceOtaFirmwareServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        versionCode = 1_900_000_000 + (int) (System.nanoTime() % 10_000_000);
        versionDir = OtaDownloadSecurityService.OTA_BASE_DIR
                .resolve("lamp")
                .resolve("test")
                .resolve(String.valueOf(versionCode));
        targetFile = versionDir.resolve("firmware.bin");
        Files.createDirectories(versionDir);

        Environment environment = mock(Environment.class);
        when(environment.getProperty("ota.download.secret")).thenReturn(SECRET);
        when(environment.getProperty("jwt.secret")).thenReturn(SECRET);
        when(environment.getProperty("app.public-base-url")).thenReturn("https://api.genius.show");

        OtaDownloadSecurityService securityService = new OtaDownloadSecurityService(environment);
        securityService.init();

        otaFirmwareMapper = mock(OtaFirmwareMapper.class);
        service = new DeviceOtaFirmwareServiceImpl(otaFirmwareMapper, securityService);
        setMaxSizeMb(service, 4);

        when(otaFirmwareMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingFirmware());
        when(otaFirmwareMapper.updateById(any(OtaFirmwareDO.class))).thenReturn(1);
        when(otaFirmwareMapper.update(any(OtaFirmwareDO.class), any())).thenReturn(1);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (Files.exists(versionDir)) {
            Files.walk(versionDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                        }
                    });
        }
    }

    @Test
    void uploadFirmware_keepsExistingTargetWhenProvidedMd5DoesNotMatch() throws Exception {
        Files.write(targetFile, OLD_FIRMWARE);

        assertThatThrownBy(() -> upload(NEW_FIRMWARE, "00000000000000000000000000000000"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("Firmware MD5 mismatch");

        assertThat(Files.readAllBytes(targetFile)).isEqualTo(OLD_FIRMWARE);
        assertThat(uploadingTempFiles()).isEmpty();
    }

    @Test
    void uploadFirmware_replacesTargetOnlyAfterMd5Matches() throws Exception {
        Files.write(targetFile, OLD_FIRMWARE);

        upload(NEW_FIRMWARE, md5Hex(NEW_FIRMWARE));

        assertThat(Files.readAllBytes(targetFile)).isEqualTo(NEW_FIRMWARE);
        assertThat(uploadingTempFiles()).isEmpty();
    }

    private void upload(byte[] content, String md5) {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "firmware.bin",
                "application/octet-stream",
                content
        );
        service.uploadFirmware(file, "lamp", "test", "1.0.0", versionCode, null, md5, "api.genius.show");
    }

    private OtaFirmwareDO existingFirmware() {
        OtaFirmwareDO firmware = new OtaFirmwareDO();
        firmware.setId(1L);
        firmware.setDeviceType("lamp");
        firmware.setChannel("test");
        firmware.setVersion("1.0.0");
        firmware.setVersionCode(versionCode);
        firmware.setFileUrl("https://api.genius.show/ota/lamp/test/" + versionCode + "/firmware.bin");
        firmware.setEnabled(true);
        return firmware;
    }

    private java.util.List<Path> uploadingTempFiles() throws Exception {
        try (var stream = Files.list(versionDir)) {
            return stream
                    .filter(path -> path.getFileName().toString().startsWith(".uploading-"))
                    .toList();
        }
    }

    private String md5Hex(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(content));
    }

    private void setMaxSizeMb(DeviceOtaFirmwareServiceImpl service, int maxSizeMb) throws Exception {
        Field field = DeviceOtaFirmwareServiceImpl.class.getDeclaredField("maxSizeMb");
        field.setAccessible(true);
        field.set(service, maxSizeMb);
    }
}

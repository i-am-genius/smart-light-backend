package com.genius.smartlight.websocket.fabric;

import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FabricImageArchiveStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void findsLatestAnnotatedImageForEveryLampInStore() throws Exception {
        DeviceMapper deviceMapper = mock(DeviceMapper.class);
        when(deviceMapper.selectList(any())).thenReturn(List.of(
                device("lamp-1", "lamp", 7L),
                device("lamp-2", "camlamp", 7L),
                device("cam-1", "cam", 7L)
        ));
        Path annotated = Files.createDirectories(tempDir.resolve("annotated"));
        Path olderLampOne = image(annotated,
                "lamp-1_20260723_110000_A1B2C3D4_annotated.jpg", 1_000);
        Path newerLampOne = image(annotated,
                "lamp-1_20260723_120000_A1B2C3D5_annotated.jpg", 2_000);
        Path lampTwo = image(annotated,
                "lamp-2_20260723_120100_A1B2C3D6_annotated.png", 3_000);
        image(annotated, "cam-1_20260723_120200_A1B2C3D7_annotated.jpg", 4_000);
        image(annotated, "other-store_20260723_120300_A1B2C3D8_annotated.jpg", 5_000);
        image(annotated, "lamp-1_20260723_120400_A1B2C3D9_original.jpg", 6_000);

        FabricImageArchiveStore store = new FabricImageArchiveStore(deviceMapper, tempDir);
        List<FabricImageArchiveStore.ArchivedFabricImage> images = store.findLatestForStore(7L);

        assertThat(images)
                .extracting(
                        FabricImageArchiveStore.ArchivedFabricImage::chipId,
                        FabricImageArchiveStore.ArchivedFabricImage::imageId,
                        FabricImageArchiveStore.ArchivedFabricImage::mimeType
                )
                .containsExactly(
                        tuple("lamp-1", newerLampOne.getFileName().toString(), "image/jpeg"),
                        tuple("lamp-2", lampTwo.getFileName().toString(), "image/png")
                );
        assertThat(images).noneMatch(image -> image.path().equals(olderLampOne));
    }

    @Test
    void returnsEmptyWhenAnnotatedDirectoryDoesNotExist() {
        DeviceMapper deviceMapper = mock(DeviceMapper.class);
        when(deviceMapper.selectList(any())).thenReturn(List.of(device("lamp-1", "lamp", 7L)));

        FabricImageArchiveStore store = new FabricImageArchiveStore(deviceMapper, tempDir);

        assertThat(store.findLatestForStore(7L)).isEmpty();
    }

    private Path image(Path directory, String filename, long modifiedMillis) throws Exception {
        Path path = Files.write(directory.resolve(filename), new byte[]{1, 2, 3});
        Files.setLastModifiedTime(path, FileTime.fromMillis(modifiedMillis));
        return path;
    }

    private DeviceDO device(String chipId, String deviceType, Long storeId) {
        DeviceDO device = new DeviceDO();
        device.setChipId(chipId);
        device.setDeviceType(deviceType);
        device.setStoreId(storeId);
        return device;
    }
}

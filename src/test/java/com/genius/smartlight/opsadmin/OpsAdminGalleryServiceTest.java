package com.genius.smartlight.opsadmin;

import com.genius.smartlight.service.ai.FabricArchiveService;
import com.genius.smartlight.vo.ai.FabricArchiveItemRespVO;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class OpsAdminGalleryServiceTest {

    private static final String OPS_URL_PREFIX = "/ops-admin/gallery/images/file";

    @Test
    void listImages_returnsSortedImagesWithoutMutatingImmutableStreamList() throws IOException {
        FabricArchiveService fabricArchiveService = mock(FabricArchiveService.class);
        OpsAdminGalleryService galleryService = new OpsAdminGalleryService(fabricArchiveService);

        Path targetDir = Path.of("/opt/smartlight/uploads/fabric").resolve("combined").normalize();
        Path older = targetDir.resolve("LAMP-A00001_20260414_103000_A1B2C3D4_combined.jpg");
        Path newer = targetDir.resolve("LAMP-B00001_20260414_103000_B2C3D4E5_combined.jpg");

        when(fabricArchiveService.buildArchiveItem(eq(older), eq("combined"), eq(OPS_URL_PREFIX)))
                .thenReturn(item(older));
        when(fabricArchiveService.buildArchiveItem(eq(newer), eq("combined"), eq(OPS_URL_PREFIX)))
                .thenReturn(item(newer));

        try (MockedStatic<Files> files = mockStatic(Files.class)) {
            files.when(() -> Files.isDirectory(targetDir)).thenReturn(true);
            files.when(() -> Files.list(targetDir)).thenReturn(Stream.of(older, newer));
            files.when(() -> Files.isRegularFile(older)).thenReturn(true);
            files.when(() -> Files.isRegularFile(newer)).thenReturn(true);

            var result = galleryService.listImages("combined", 1, 30);

            assertThat(result.getList())
                    .extracting(FabricArchiveItemRespVO::getFilename)
                    .containsExactly(
                            "LAMP-B00001_20260414_103000_B2C3D4E5_combined.jpg",
                            "LAMP-A00001_20260414_103000_A1B2C3D4_combined.jpg"
                    );
        }
    }

    @Test
    void listImages_appendsOpsTokenToImageUrlsWhenProvided() throws IOException {
        FabricArchiveService fabricArchiveService = mock(FabricArchiveService.class);
        OpsAdminGalleryService galleryService = new OpsAdminGalleryService(fabricArchiveService);

        Path targetDir = Path.of("/opt/smartlight/uploads/fabric").resolve("combined").normalize();
        Path image = targetDir.resolve("LAMP-A00001_20260414_103000_A1B2C3D4_combined.jpg");
        FabricArchiveItemRespVO item = item(image);
        item.setUrl(OPS_URL_PREFIX + "?type=combined&filename=" + image.getFileName());

        when(fabricArchiveService.buildArchiveItem(eq(image), eq("combined"), eq(OPS_URL_PREFIX)))
                .thenReturn(item);

        try (MockedStatic<Files> files = mockStatic(Files.class)) {
            files.when(() -> Files.isDirectory(targetDir)).thenReturn(true);
            files.when(() -> Files.list(targetDir)).thenReturn(Stream.of(image));
            files.when(() -> Files.isRegularFile(image)).thenReturn(true);

            var result = galleryService.listImages("combined", 1, 30, "abc.123/+=");

            assertThat(result.getList().get(0).getUrl())
                    .endsWith("&token=abc.123%2F%2B%3D");
        }
    }

    @Test
    void listImages_prefixesPublicBaseUrlForBrowserImageRequests() throws IOException {
        FabricArchiveService fabricArchiveService = mock(FabricArchiveService.class);
        OpsAdminGalleryService galleryService = new OpsAdminGalleryService(fabricArchiveService);

        Path targetDir = Path.of("/opt/smartlight/uploads/fabric").resolve("combined").normalize();
        Path image = targetDir.resolve("LAMP-A00001_20260414_103000_A1B2C3D4_combined.jpg");
        FabricArchiveItemRespVO item = item(image);
        item.setUrl(OPS_URL_PREFIX + "?type=combined&filename=" + image.getFileName());

        when(fabricArchiveService.buildArchiveItem(eq(image), eq("combined"), eq(OPS_URL_PREFIX)))
                .thenReturn(item);

        try (MockedStatic<Files> files = mockStatic(Files.class)) {
            files.when(() -> Files.isDirectory(targetDir)).thenReturn(true);
            files.when(() -> Files.list(targetDir)).thenReturn(Stream.of(image));
            files.when(() -> Files.isRegularFile(image)).thenReturn(true);

            var result = galleryService.listImages(
                    "combined",
                    1,
                    30,
                    "ops-token",
                    "https://api.genius.show/"
            );

            assertThat(result.getList().get(0).getUrl())
                    .startsWith("https://api.genius.show/ops-admin/gallery/images/file?")
                    .endsWith("&token=ops-token");
        }
    }

    private FabricArchiveItemRespVO item(Path path) {
        FabricArchiveItemRespVO item = new FabricArchiveItemRespVO();
        item.setFilename(path.getFileName().toString());
        return item;
    }
}

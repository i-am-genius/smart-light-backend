package com.genius.smartlight.websocket.fabric;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class FabricImageLiveNotifierTest {

    @TempDir
    Path tempDir;

    @Test
    void existingAnnotatedArchiveIsEnqueuedForLivePush() throws Exception {
        FabricImagePushService pushService = mock(FabricImagePushService.class);
        FabricImageLiveNotifier notifier = new FabricImageLiveNotifier(pushService);
        String filename = "lamp-1_20260723_120000_A1B2C3D4_annotated.jpg";
        Path annotatedDir = Files.createDirectories(tempDir.resolve("annotated"));
        Path annotatedPath = Files.write(annotatedDir.resolve(filename), new byte[]{1});

        boolean enqueued = notifier.pushIfPresent(7L, "lamp-1", tempDir, filename);

        assertThat(enqueued).isTrue();
        verify(pushService).pushLiveToStore(7L, "lamp-1", annotatedPath.toAbsolutePath().normalize());
    }

    @Test
    void aiReturnedAnnotatedPathIsUsedWhenBackendGeneratedFilenameDoesNotExist() throws Exception {
        FabricImagePushService pushService = mock(FabricImagePushService.class);
        FabricImageLiveNotifier notifier = new FabricImageLiveNotifier(pushService);
        Path annotatedDir = Files.createDirectories(tempDir.resolve("annotated"));
        Path actualAiPath = Files.write(
                annotatedDir.resolve("lamp-1_20260724_171817_A1B2C3D4_annotated.jpg"),
                new byte[]{1}
        );

        boolean enqueued = notifier.pushIfPresent(
                7L,
                "lamp-1",
                tempDir,
                "lamp-1_20260724_171816_DEADBEEF_annotated.jpg",
                actualAiPath.toString()
        );

        assertThat(enqueued).isTrue();
        verify(pushService).pushLiveToStore(
                7L,
                "lamp-1",
                actualAiPath.toAbsolutePath().normalize()
        );
    }

    @Test
    void missingOrEscapingArchiveIsNotEnqueued() throws Exception {
        FabricImagePushService pushService = mock(FabricImagePushService.class);
        FabricImageLiveNotifier notifier = new FabricImageLiveNotifier(pushService);
        Path outsideAnnotatedRoot = Files.write(tempDir.resolve("outside.jpg"), new byte[]{1});
        Path nestedAnnotatedPath = Files.write(
                Files.createDirectories(tempDir.resolve("annotated").resolve("nested"))
                        .resolve("nested.jpg"),
                new byte[]{1}
        );

        assertThat(notifier.pushIfPresent(7L, "lamp-1", tempDir, "missing.jpg")).isFalse();
        assertThat(notifier.pushIfPresent(7L, "lamp-1", tempDir, "../escape.jpg")).isFalse();
        assertThat(notifier.pushIfPresent(
                7L,
                "lamp-1",
                tempDir,
                "missing.jpg",
                outsideAnnotatedRoot.toString()
        )).isFalse();
        assertThat(notifier.pushIfPresent(
                7L,
                "lamp-1",
                tempDir,
                "missing.jpg",
                nestedAnnotatedPath.toString()
        )).isFalse();

        verify(pushService, never()).pushLiveToStore(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }
}

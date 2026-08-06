package com.genius.smartlight.websocket.fabric;

import com.genius.smartlight.websocket.WebSocketSessionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.task.SyncTaskExecutor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FabricImagePushServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void replaySendsEveryFrameOnlyToRequestedSession() throws Exception {
        FabricImageArchiveStore archiveStore = mock(FabricImageArchiveStore.class);
        FabricImageBinaryProtocol protocol = mock(FabricImageBinaryProtocol.class);
        WebSocketSessionManager sessionManager = mock(WebSocketSessionManager.class);
        Path imagePath = Files.write(tempDir.resolve("lamp-1_annotated.jpg"),
                new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1});
        byte[] frameOne = {1, 2};
        byte[] frameTwo = {3, 4};
        when(archiveStore.findLatestForStore(7L)).thenReturn(List.of(
                new FabricImageArchiveStore.ArchivedFabricImage(
                        imagePath.getFileName().toString(),
                        "lamp-1",
                        "image/jpeg",
                        imagePath,
                        1L
                )
        ));
        when(protocol.encode(
                eq(imagePath.getFileName().toString()),
                eq("lamp-1"),
                eq("image/jpeg"),
                eq(FabricImageBinaryProtocol.Source.REPLAY),
                any(byte[].class)
        )).thenReturn(List.of(frameOne, frameTwo));
        when(sessionManager.sendBinary(eq("web-1"), any(byte[].class))).thenReturn(true);
        FabricImagePushService service = new FabricImagePushService(
                archiveStore, protocol, sessionManager, new SyncTaskExecutor(), tempDir
        );

        service.replayLatestToSession("web-1", 7L);

        verify(sessionManager).sendBinary(eq("web-1"), aryEq(frameOne));
        verify(sessionManager).sendBinary(eq("web-1"), aryEq(frameTwo));
        verify(sessionManager, never()).broadcastBinaryToCapableStore(any(), any());
    }

    @Test
    void replayStopsWhenTargetSessionIsNoLongerAvailable() throws Exception {
        FabricImageArchiveStore archiveStore = mock(FabricImageArchiveStore.class);
        FabricImageBinaryProtocol protocol = mock(FabricImageBinaryProtocol.class);
        WebSocketSessionManager sessionManager = mock(WebSocketSessionManager.class);
        Path imagePath = Files.write(tempDir.resolve("lamp-1_annotated.jpg"),
                new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1});
        when(archiveStore.findLatestForStore(7L)).thenReturn(List.of(
                new FabricImageArchiveStore.ArchivedFabricImage(
                        imagePath.getFileName().toString(), "lamp-1", "image/jpeg", imagePath, 1L)
        ));
        when(protocol.encode(any(), any(), any(), any(), any()))
                .thenReturn(List.of(new byte[]{1}, new byte[]{2}));
        when(sessionManager.sendBinary("web-1", new byte[]{1})).thenReturn(false);
        FabricImagePushService service = new FabricImagePushService(
                archiveStore, protocol, sessionManager, new SyncTaskExecutor(), tempDir
        );

        service.replayLatestToSession("web-1", 7L);

        verify(sessionManager).sendBinary(eq("web-1"), aryEq(new byte[]{1}));
        verify(sessionManager, never()).sendBinary(eq("web-1"), aryEq(new byte[]{2}));
    }

    @Test
    void livePushBroadcastsEveryFrameToCapableSessionsInStore() throws Exception {
        FabricImageArchiveStore archiveStore = mock(FabricImageArchiveStore.class);
        FabricImageBinaryProtocol protocol = mock(FabricImageBinaryProtocol.class);
        WebSocketSessionManager sessionManager = mock(WebSocketSessionManager.class);
        Path imagePath = Files.write(tempDir.resolve("lamp-1_live_annotated.png"),
                new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1});
        byte[] frameOne = {5, 6};
        byte[] frameTwo = {7, 8};
        when(protocol.encode(
                eq(imagePath.getFileName().toString()),
                eq("lamp-1"),
                eq("image/png"),
                eq(FabricImageBinaryProtocol.Source.LIVE),
                any(byte[].class)
        )).thenReturn(List.of(frameOne, frameTwo));
        FabricImagePushService service = new FabricImagePushService(
                archiveStore, protocol, sessionManager, new SyncTaskExecutor(), tempDir
        );

        service.pushLiveToStore(7L, "lamp-1", imagePath);

        verify(sessionManager).broadcastBinaryToCapableStore(eq(7L), aryEq(frameOne));
        verify(sessionManager).broadcastBinaryToCapableStore(eq(7L), aryEq(frameTwo));
        verify(sessionManager, never()).sendBinary(any(), any());
    }

    @Test
    void replayUsesDetectedMimeWhenArchiveExtensionIsMisleading() throws Exception {
        FabricImageArchiveStore archiveStore = mock(FabricImageArchiveStore.class);
        FabricImageBinaryProtocol protocol = mock(FabricImageBinaryProtocol.class);
        WebSocketSessionManager sessionManager = mock(WebSocketSessionManager.class);
        Path imagePath = Files.write(tempDir.resolve("lamp-1_annotated.jpg"),
                new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1});
        when(archiveStore.findLatestForStore(7L)).thenReturn(List.of(
                new FabricImageArchiveStore.ArchivedFabricImage(
                        imagePath.getFileName().toString(), "lamp-1", "image/jpeg", imagePath, 1L)
        ));
        when(protocol.encode(
                eq(imagePath.getFileName().toString()),
                eq("lamp-1"),
                eq("image/png"),
                eq(FabricImageBinaryProtocol.Source.REPLAY),
                any(byte[].class)
        )).thenReturn(List.of(new byte[]{9}));
        when(sessionManager.sendBinary(eq("web-1"), any(byte[].class))).thenReturn(true);
        FabricImagePushService service = new FabricImagePushService(
                archiveStore, protocol, sessionManager, new SyncTaskExecutor(), tempDir
        );

        service.replayLatestToSession("web-1", 7L);

        verify(sessionManager).sendBinary(eq("web-1"), aryEq(new byte[]{9}));
    }

    @Test
    void invalidImageMagicIsIgnored() throws Exception {
        FabricImageArchiveStore archiveStore = mock(FabricImageArchiveStore.class);
        FabricImageBinaryProtocol protocol = mock(FabricImageBinaryProtocol.class);
        WebSocketSessionManager sessionManager = mock(WebSocketSessionManager.class);
        Path imagePath = Files.write(tempDir.resolve("lamp-1_annotated.jpg"), new byte[]{1, 2, 3});
        FabricImagePushService service = new FabricImagePushService(
                archiveStore, protocol, sessionManager, new SyncTaskExecutor(), tempDir
        );

        service.pushLiveToStore(7L, "lamp-1", imagePath);

        verify(protocol, never()).encode(any(), any(), any(), any(), any());
        verify(sessionManager, never()).broadcastBinaryToCapableStore(any(), any());
    }

    @Test
    void imageOutsideConfiguredAnnotatedRootIsIgnoredAtSendTime() throws Exception {
        FabricImageArchiveStore archiveStore = mock(FabricImageArchiveStore.class);
        FabricImageBinaryProtocol protocol = mock(FabricImageBinaryProtocol.class);
        WebSocketSessionManager sessionManager = mock(WebSocketSessionManager.class);
        Path annotatedRoot = Files.createDirectories(tempDir.resolve("annotated"));
        Path outsideImage = Files.write(tempDir.resolve("outside.jpg"),
                new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1});
        FabricImagePushService service = new FabricImagePushService(
                archiveStore,
                protocol,
                sessionManager,
                new SyncTaskExecutor(),
                annotatedRoot
        );

        service.pushLiveToStore(7L, "lamp-1", outsideImage);

        verify(protocol, never()).encode(any(), any(), any(), any(), any());
        verify(sessionManager, never()).broadcastBinaryToCapableStore(any(), any());
    }
}

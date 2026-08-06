package com.genius.smartlight.websocket.fabric;

import com.genius.smartlight.websocket.WebSocketSessionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
@Service
public class FabricImagePushService {

    private static final Path DEFAULT_ANNOTATED_ROOT =
            Path.of("/opt/smartlight/uploads/fabric/annotated");

    private final FabricImageArchiveStore archiveStore;
    private final FabricImageBinaryProtocol protocol;
    private final WebSocketSessionManager sessionManager;
    private final TaskExecutor taskExecutor;
    private final Path annotatedRoot;

    @Autowired
    public FabricImagePushService(
            FabricImageArchiveStore archiveStore,
            FabricImageBinaryProtocol protocol,
            WebSocketSessionManager sessionManager,
            @Qualifier("fabricImagePushExecutor") TaskExecutor taskExecutor
    ) {
        this(archiveStore, protocol, sessionManager, taskExecutor, DEFAULT_ANNOTATED_ROOT);
    }

    FabricImagePushService(
            FabricImageArchiveStore archiveStore,
            FabricImageBinaryProtocol protocol,
            WebSocketSessionManager sessionManager,
            TaskExecutor taskExecutor,
            Path annotatedRoot
    ) {
        this.archiveStore = Objects.requireNonNull(archiveStore, "archiveStore");
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
        this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor");
        this.annotatedRoot = Objects.requireNonNull(annotatedRoot, "annotatedRoot")
                .toAbsolutePath()
                .normalize();
    }

    public void replayLatestToSession(String sessionId, Long storeId) {
        if (sessionId == null || sessionId.isBlank() || storeId == null) {
            return;
        }
        submit("replay", sessionId, () -> replay(sessionId, storeId));
    }

    public void pushLiveToStore(Long storeId, String chipId, Path annotatedFile) {
        if (storeId == null || chipId == null || chipId.isBlank() || annotatedFile == null) {
            return;
        }
        submit("live", chipId, () -> pushLive(storeId, chipId, annotatedFile));
    }

    private void replay(String sessionId, Long storeId) {
        for (FabricImageArchiveStore.ArchivedFabricImage image : archiveStore.findLatestForStore(storeId)) {
            try {
                ValidatedImage validated = readValidated(image.path());
                List<byte[]> frames = protocol.encode(
                        image.imageId(),
                        image.chipId(),
                        validated.mimeType(),
                        FabricImageBinaryProtocol.Source.REPLAY,
                        validated.bytes()
                );
                for (byte[] frame : frames) {
                    if (!sessionManager.sendBinary(sessionId, frame)) {
                        return;
                    }
                }
            } catch (Exception e) {
                log.warn("[fabric-image] event=replay_file_failed, sessionId={}, storeId={}, imageId={}, errorType={}",
                        sessionId, storeId, image.imageId(), e.getClass().getSimpleName());
            }
        }
    }

    private void pushLive(Long storeId, String chipId, Path annotatedFile) {
        String imageId = annotatedFile.getFileName() == null
                ? ""
                : annotatedFile.getFileName().toString();
        try {
            ValidatedImage validated = readValidated(annotatedFile);
            List<byte[]> frames = protocol.encode(
                    imageId,
                    chipId,
                    validated.mimeType(),
                    FabricImageBinaryProtocol.Source.LIVE,
                    validated.bytes()
            );
            for (byte[] frame : frames) {
                sessionManager.broadcastBinaryToCapableStore(storeId, frame);
            }
        } catch (Exception e) {
            log.warn("[fabric-image] event=live_file_failed, storeId={}, chipId={}, imageId={}, errorType={}",
                    storeId, chipId, imageId, e.getClass().getSimpleName());
        }
    }

    private ValidatedImage readValidated(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(annotatedRoot)) {
            throw new IOException("分割图路径不在 annotated 目录内");
        }
        Set<OpenOption> options = Set.of(
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS
        );
        byte[] bytes;
        try (SeekableByteChannel channel = Files.newByteChannel(normalized, options);
             InputStream input = Channels.newInputStream(channel)) {
            long size = channel.size();
            if (size <= 0 || size > FabricImageBinaryProtocol.MAX_IMAGE_BYTES) {
                throw new IOException("分割图文件大小不合法");
            }
            bytes = input.readNBytes(FabricImageBinaryProtocol.MAX_IMAGE_BYTES + 1);
        }
        if (bytes.length == 0 || bytes.length > FabricImageBinaryProtocol.MAX_IMAGE_BYTES) {
            throw new IOException("分割图读取大小不合法");
        }
        return new ValidatedImage(bytes, detectMimeType(bytes));
    }

    private String detectMimeType(byte[] bytes) throws IOException {
        if (bytes.length >= 3
                && Byte.toUnsignedInt(bytes[0]) == 0xff
                && Byte.toUnsignedInt(bytes[1]) == 0xd8
                && Byte.toUnsignedInt(bytes[2]) == 0xff) {
            return "image/jpeg";
        }
        if (bytes.length >= 8
                && Byte.toUnsignedInt(bytes[0]) == 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4e
                && bytes[3] == 0x47
                && bytes[4] == 0x0d
                && bytes[5] == 0x0a
                && bytes[6] == 0x1a
                && bytes[7] == 0x0a) {
            return "image/png";
        }
        if (bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        throw new IOException("分割图格式签名不合法");
    }

    private void submit(String source, String target, Runnable task) {
        try {
            taskExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            log.warn("[fabric-image] event=task_rejected, source={}, target={}", source, target);
        }
    }

    private record ValidatedImage(byte[] bytes, String mimeType) {
    }
}

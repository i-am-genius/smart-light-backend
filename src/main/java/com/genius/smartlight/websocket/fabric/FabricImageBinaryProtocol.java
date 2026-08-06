package com.genius.smartlight.websocket.fabric;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Component
public final class FabricImageBinaryProtocol {

    public static final int PROTOCOL_VERSION = 1;
    public static final int CHUNK_BYTES = 256 * 1024;
    public static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    private static final byte[] MAGIC = {'S', 'L', 'F', 'I'};
    private static final Set<String> MIME_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp");

    private final ObjectMapper objectMapper;

    public FabricImageBinaryProtocol(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public List<byte[]> encode(String imageId,
                               String chipId,
                               String mimeType,
                               Source source,
                               byte[] imageBytes) {
        validate(imageId, chipId, mimeType, source, imageBytes);
        int totalChunks = (imageBytes.length + CHUNK_BYTES - 1) / CHUNK_BYTES;
        List<byte[]> frames = new ArrayList<>(totalChunks);
        for (int index = 0; index < totalChunks; index++) {
            int from = index * CHUNK_BYTES;
            int to = Math.min(from + CHUNK_BYTES, imageBytes.length);

            ObjectNode header = objectMapper.createObjectNode();
            header.put("type", "fabricRecognizeImageChunk");
            header.put("imageId", imageId);
            header.put("chipId", chipId);
            header.put("mimeType", mimeType);
            header.put("chunkIndex", index);
            header.put("totalChunks", totalChunks);
            header.put("totalBytes", imageBytes.length);
            header.put("source", source.wireValue);

            byte[] headerBytes = writeHeader(header);
            ByteBuffer frame = ByteBuffer.allocate(9 + headerBytes.length + to - from);
            frame.put(MAGIC);
            frame.put((byte) PROTOCOL_VERSION);
            frame.putInt(headerBytes.length);
            frame.put(headerBytes);
            frame.put(imageBytes, from, to - from);
            frames.add(frame.array());
        }
        return frames;
    }

    private void validate(String imageId,
                          String chipId,
                          String mimeType,
                          Source source,
                          byte[] imageBytes) {
        if (imageId == null || imageId.isBlank()) {
            throw new IllegalArgumentException("imageId 不能为空");
        }
        if (chipId == null || chipId.isBlank()) {
            throw new IllegalArgumentException("chipId 不能为空");
        }
        if (!MIME_TYPES.contains(mimeType)) {
            throw new IllegalArgumentException("不支持的分割图 MIME 类型");
        }
        if (source == null) {
            throw new IllegalArgumentException("source 不能为空");
        }
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("分割图内容不能为空");
        }
        if (imageBytes.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("分割图超过 10 MiB 限制");
        }
    }

    private byte[] writeHeader(ObjectNode header) {
        try {
            return objectMapper.writeValueAsBytes(header);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法编码分割图帧头", e);
        }
    }

    public enum Source {
        LIVE("live"),
        REPLAY("replay");

        private final String wireValue;

        Source(String wireValue) {
            this.wireValue = wireValue;
        }
    }
}

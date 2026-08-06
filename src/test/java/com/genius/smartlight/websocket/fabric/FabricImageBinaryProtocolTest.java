package com.genius.smartlight.websocket.fabric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FabricImageBinaryProtocolTest {

    private static final byte[] MAGIC = {'S', 'L', 'F', 'I'};

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FabricImageBinaryProtocol protocol = new FabricImageBinaryProtocol(objectMapper);

    @Test
    void encodeCreatesSelfDescribingChunksThatRestoreOriginalBytes() throws Exception {
        byte[] image = new byte[FabricImageBinaryProtocol.CHUNK_BYTES + 17];
        Arrays.fill(image, (byte) 0x5a);

        List<byte[]> frames = protocol.encode(
                "lamp-1_20260723_120000_A1B2C3D4_annotated.jpg",
                "lamp-1",
                "image/jpeg",
                FabricImageBinaryProtocol.Source.LIVE,
                image
        );

        assertThat(frames).hasSize(2);
        DecodedFrame first = decode(frames.get(0));
        DecodedFrame second = decode(frames.get(1));
        assertThat(first.header().path("type").asText()).isEqualTo("fabricRecognizeImageChunk");
        assertThat(first.header().path("imageId").asText())
                .isEqualTo("lamp-1_20260723_120000_A1B2C3D4_annotated.jpg");
        assertThat(first.header().path("chipId").asText()).isEqualTo("lamp-1");
        assertThat(first.header().path("mimeType").asText()).isEqualTo("image/jpeg");
        assertThat(first.header().path("source").asText()).isEqualTo("live");
        assertThat(first.header().path("chunkIndex").asInt()).isZero();
        assertThat(second.header().path("chunkIndex").asInt()).isEqualTo(1);
        assertThat(first.header().path("totalChunks").asInt()).isEqualTo(2);
        assertThat(first.header().path("totalBytes").asInt()).isEqualTo(image.length);
        assertThat(concat(first.payload(), second.payload())).containsExactly(image);
    }

    @Test
    void encodeRejectsInvalidMetadataAndImages() {
        assertThatThrownBy(() -> protocol.encode("x.jpg", "lamp-1", "text/plain",
                FabricImageBinaryProtocol.Source.LIVE, new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> protocol.encode("x.jpg", "lamp-1", "image/jpeg",
                FabricImageBinaryProtocol.Source.LIVE,
                new byte[FabricImageBinaryProtocol.MAX_IMAGE_BYTES + 1]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> protocol.encode(" ", "lamp-1", "image/jpeg",
                FabricImageBinaryProtocol.Source.LIVE, new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> protocol.encode("x.jpg", " ", "image/jpeg",
                FabricImageBinaryProtocol.Source.LIVE, new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> protocol.encode("x.jpg", "lamp-1", "image/jpeg",
                FabricImageBinaryProtocol.Source.LIVE, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private DecodedFrame decode(byte[] encoded) throws Exception {
        ByteBuffer frame = ByteBuffer.wrap(encoded);
        byte[] magic = new byte[MAGIC.length];
        frame.get(magic);
        assertThat(magic).containsExactly(MAGIC);
        assertThat(Byte.toUnsignedInt(frame.get())).isEqualTo(FabricImageBinaryProtocol.PROTOCOL_VERSION);
        int headerLength = frame.getInt();
        byte[] headerBytes = new byte[headerLength];
        frame.get(headerBytes);
        byte[] payload = new byte[frame.remaining()];
        frame.get(payload);
        JsonNode header = objectMapper.readTree(new String(headerBytes, StandardCharsets.UTF_8));
        return new DecodedFrame(header, payload);
    }

    private byte[] concat(byte[] first, byte[] second) {
        byte[] joined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    private record DecodedFrame(JsonNode header, byte[] payload) {
    }
}

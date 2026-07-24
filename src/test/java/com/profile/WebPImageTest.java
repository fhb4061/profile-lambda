package com.profile;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WebPImageTest {

    @Test
    void readsCanvasDimensionsFromAnExtendedVp8xHeader() {
        // VP8X payload: 1 byte flags, 3 bytes reserved, 3 bytes (width-1) LE, 3 bytes (height-1) LE
        byte[] vp8xPayload = new byte[10];
        writeUint24LE(vp8xPayload, 4, 1919); // width  = 1920
        writeUint24LE(vp8xPayload, 7, 1079); // height = 1080
        byte[] data = riffContainer("VP8X", vp8xPayload);

        WebPImage.Dimensions dimensions = WebPImage.readDimensions(data);

        assertEquals(1920, dimensions.width());
        assertEquals(1080, dimensions.height());
    }

    @Test
    void readsDimensionsFromALosslessVp8lHeader() {
        // VP8L payload: signature byte 0x2F, then 4 bytes packing (width-1) in bits 0-13
        // and (height-1) in bits 14-27, little-endian.
        int widthMinusOne = 639; // width  = 640
        int heightMinusOne = 479; // height = 480
        long bits = (widthMinusOne & 0x3FFFL) | ((heightMinusOne & 0x3FFFL) << 14);
        byte[] vp8lPayload = new byte[5];
        vp8lPayload[0] = 0x2F;
        vp8lPayload[1] = (byte) (bits & 0xFF);
        vp8lPayload[2] = (byte) ((bits >> 8) & 0xFF);
        vp8lPayload[3] = (byte) ((bits >> 16) & 0xFF);
        vp8lPayload[4] = (byte) ((bits >> 24) & 0xFF);
        byte[] data = riffContainer("VP8L", vp8lPayload);

        WebPImage.Dimensions dimensions = WebPImage.readDimensions(data);

        assertEquals(640, dimensions.width());
        assertEquals(480, dimensions.height());
    }

    @Test
    void readsDimensionsFromALossySimpleVp8Header() {
        // VP8 payload: 3-byte frame tag, 3-byte start code (0x9D 0x01 0x2A),
        // then width/height as little-endian uint16 (low 14 bits significant).
        byte[] vp8Payload = new byte[10];
        vp8Payload[3] = (byte) 0x9D;
        vp8Payload[4] = 0x01;
        vp8Payload[5] = 0x2A;
        vp8Payload[6] = (byte) (320 & 0xFF);
        vp8Payload[7] = (byte) ((320 >> 8) & 0xFF);
        vp8Payload[8] = (byte) (240 & 0xFF);
        vp8Payload[9] = (byte) ((240 >> 8) & 0xFF);
        byte[] data = riffContainer("VP8 ", vp8Payload);

        WebPImage.Dimensions dimensions = WebPImage.readDimensions(data);

        assertEquals(320, dimensions.width());
        assertEquals(240, dimensions.height());
    }

    @Test
    void returnsNullForDataMissingTheRiffMagicBytes() {
        byte[] data = "not a real webp file at all, just plain text padding.....".getBytes(StandardCharsets.UTF_8);

        assertNull(WebPImage.readDimensions(data));
    }

    @Test
    void returnsNullForARiffWebpContainerWithAnUnrecognizedChunkType() {
        byte[] data = riffContainer("ANIM", new byte[10]);

        assertNull(WebPImage.readDimensions(data));
    }

    private static void writeUint24LE(byte[] buffer, int offset, int value) {
        buffer[offset] = (byte) (value & 0xFF);
        buffer[offset + 1] = (byte) ((value >> 8) & 0xFF);
        buffer[offset + 2] = (byte) ((value >> 16) & 0xFF);
    }

    private static byte[] riffContainer(String fourCc, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(new byte[4]); // RIFF size, unused by the parser
        out.writeBytes("WEBP".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(fourCc.getBytes(StandardCharsets.US_ASCII));
        byte[] chunkSize = new byte[4];
        writeUint24LE(chunkSize, 0, payload.length);
        out.writeBytes(chunkSize);
        out.writeBytes(payload);
        return out.toByteArray();
    }
}

package com.profile;

import java.nio.charset.StandardCharsets;

/**
 * Minimal WebP (RIFF/VP8) header reader. The JDK's ImageIO has no built-in WebP
 * reader, so magic-byte and dimension checks for this format have to be hand-rolled —
 * this only reads the fixed-offset header fields, never full pixel data.
 */
public final class WebPImage {

    private static final int CHUNK_FOURCC_OFFSET = 12;
    private static final int CHUNK_PAYLOAD_OFFSET = 20;

    private WebPImage() {
    }

    public record Dimensions(int width, int height) {
    }

    public static Dimensions readDimensions(byte[] data) {
        if (data.length < CHUNK_PAYLOAD_OFFSET || !ascii(data, 0, 4).equals("RIFF")
                || !ascii(data, 8, 4).equals("WEBP")) {
            return null;
        }
        String fourCc = ascii(data, CHUNK_FOURCC_OFFSET, 4);
        return switch (fourCc) {
            case "VP8 " -> readLossy(data);
            case "VP8L" -> readLossless(data);
            case "VP8X" -> readExtended(data);
            default -> null;
        };
    }

    private static Dimensions readLossy(byte[] data) {
        int startCode = CHUNK_PAYLOAD_OFFSET + 3;
        if (data.length < startCode + 7) {
            return null;
        }
        if ((data[startCode] & 0xFF) != 0x9D || (data[startCode + 1] & 0xFF) != 0x01
                || (data[startCode + 2] & 0xFF) != 0x2A) {
            return null;
        }
        int width = readUint16LE(data, startCode + 3) & 0x3FFF;
        int height = readUint16LE(data, startCode + 5) & 0x3FFF;
        return new Dimensions(width, height);
    }

    private static Dimensions readLossless(byte[] data) {
        if (data.length < CHUNK_PAYLOAD_OFFSET + 5) {
            return null;
        }
        if ((data[CHUNK_PAYLOAD_OFFSET] & 0xFF) != 0x2F) {
            return null;
        }
        long bits = readUint32LE(data, CHUNK_PAYLOAD_OFFSET + 1);
        int width = (int) (bits & 0x3FFF) + 1;
        int height = (int) ((bits >> 14) & 0x3FFF) + 1;
        return new Dimensions(width, height);
    }

    private static Dimensions readExtended(byte[] data) {
        if (data.length < CHUNK_PAYLOAD_OFFSET + 10) {
            return null;
        }
        int width = readUint24LE(data, CHUNK_PAYLOAD_OFFSET + 4) + 1;
        int height = readUint24LE(data, CHUNK_PAYLOAD_OFFSET + 7) + 1;
        return new Dimensions(width, height);
    }

    private static String ascii(byte[] data, int offset, int length) {
        return new String(data, offset, length, StandardCharsets.US_ASCII);
    }

    private static int readUint16LE(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static int readUint24LE(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8) | ((data[offset + 2] & 0xFF) << 16);
    }

    private static long readUint32LE(byte[] data, int offset) {
        return (data[offset] & 0xFFL) | ((data[offset + 1] & 0xFFL) << 8)
                | ((data[offset + 2] & 0xFFL) << 16) | ((data[offset + 3] & 0xFFL) << 24);
    }
}

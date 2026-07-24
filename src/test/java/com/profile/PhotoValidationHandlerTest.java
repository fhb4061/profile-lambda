package com.profile;

import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhotoValidationHandlerTest {

    private static final String TABLE = "profiles-test";
    private static final String BUCKET = "photo-bucket-test";

    private InMemoryDynamoDb db;
    private InMemoryS3 s3;
    private PhotoValidationHandler handler;

    @BeforeEach
    void setUp() {
        db = new InMemoryDynamoDb();
        s3 = new InMemoryS3();
        handler = new PhotoValidationHandler(s3, db, TABLE);
        db.seed(Map.of(
                "sub", AttributeValue.fromS("sub-123"),
                "email", AttributeValue.fromS("amy@example.com"),
                "givenName", AttributeValue.fromS("Amy"),
                "familyName", AttributeValue.fromS("Pond")));
    }

    @Test
    void validJpegUploadSetsTheProfilesPhotoKey() throws Exception {
        String key = "photos/sub-123/upload-1";
        s3.seed(key, jpegBytes(10, 10));

        handler.handleRequest(s3Event(BUCKET, key), null);

        assertEquals(key, db.item("sub-123").get("photoKey").s());
    }

    @Test
    void nonImageBytesAreDeletedAndDynamoDbIsUntouched() {
        String key = "photos/sub-123/upload-1";
        s3.seed(key, "not an image, just plain text bytes".getBytes());

        handler.handleRequest(s3Event(BUCKET, key), null);

        assertTrue(s3.wasDeleted(key));
        assertFalse(db.item("sub-123").containsKey("photoKey"));
    }

    @Test
    void imageWiderThan4096PixelsIsRejectedAndDeleted() throws Exception {
        String key = "photos/sub-123/upload-1";
        s3.seed(key, jpegBytes(4097, 10));

        handler.handleRequest(s3Event(BUCKET, key), null);

        assertTrue(s3.wasDeleted(key));
        assertFalse(db.item("sub-123").containsKey("photoKey"));
    }

    @Test
    void replacingAPhotoDeletesTheOldObjectAndPointsAtTheNewOne() throws Exception {
        String oldKey = "photos/sub-123/upload-1";
        db.seed(Map.of(
                "sub", AttributeValue.fromS("sub-123"),
                "email", AttributeValue.fromS("amy@example.com"),
                "givenName", AttributeValue.fromS("Amy"),
                "familyName", AttributeValue.fromS("Pond"),
                "photoKey", AttributeValue.fromS(oldKey)));
        s3.seed(oldKey, jpegBytes(10, 10));

        String newKey = "photos/sub-123/upload-2";
        s3.seed(newKey, jpegBytes(10, 10));

        handler.handleRequest(s3Event(BUCKET, newKey), null);

        assertTrue(s3.wasDeleted(oldKey), "old photo object must be cleaned up");
        assertFalse(s3.wasDeleted(newKey), "newly uploaded object must be kept");
        assertEquals(newKey, db.item("sub-123").get("photoKey").s());
    }

    @Test
    void uploadForASubWithNoProfileRowIsDeletedAndNeverCreatesOne() throws Exception {
        String key = "photos/sub-ghost/upload-1";
        s3.seed(key, jpegBytes(10, 10));

        handler.handleRequest(s3Event(BUCKET, key), null);

        assertTrue(s3.wasDeleted(key));
        assertEquals(1, db.size(), "must never create a profile row for an unknown sub");
    }

    @Test
    void validWebpUploadSetsTheProfilesPhotoKey() throws Exception {
        String key = "photos/sub-123/upload-1";
        s3.seed(key, webpBytes(10, 10));

        handler.handleRequest(s3Event(BUCKET, key), null);

        assertEquals(key, db.item("sub-123").get("photoKey").s());
    }

    /** Minimal VP8X (extended) WebP header: RIFF/WEBP container + 10-byte VP8X payload
     *  encoding canvas width/height as 24-bit little-endian (value - 1). No pixel data
     *  needed — the validator only reads this fixed-offset header. */
    private static byte[] webpBytes(int width, int height) {
        byte[] payload = new byte[10];
        writeUint24LE(payload, 4, width - 1);
        writeUint24LE(payload, 7, height - 1);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("RIFF".getBytes());
        out.writeBytes(new byte[4]);
        out.writeBytes("WEBP".getBytes());
        out.writeBytes("VP8X".getBytes());
        byte[] chunkSize = new byte[4];
        writeUint24LE(chunkSize, 0, payload.length);
        out.writeBytes(chunkSize);
        out.writeBytes(payload);
        return out.toByteArray();
    }

    private static void writeUint24LE(byte[] buffer, int offset, int value) {
        buffer[offset] = (byte) (value & 0xFF);
        buffer[offset + 1] = (byte) ((value >> 8) & 0xFF);
        buffer[offset + 2] = (byte) ((value >> 16) & 0xFF);
    }

    private static byte[] jpegBytes(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private static S3Event s3Event(String bucket, String key) {
        S3EventNotification.S3ObjectEntity object =
                new S3EventNotification.S3ObjectEntity(key, 0L, null, null, null);
        S3EventNotification.S3BucketEntity bucketEntity =
                new S3EventNotification.S3BucketEntity(bucket, null, null);
        S3EventNotification.S3Entity s3Entity =
                new S3EventNotification.S3Entity(null, bucketEntity, object, null);
        S3EventNotification.S3EventNotificationRecord record = new S3EventNotification.S3EventNotificationRecord(
                null, "ObjectCreated:Put", null, null, null, null, null, s3Entity, null);
        return new S3Event(List.of(record));
    }
}

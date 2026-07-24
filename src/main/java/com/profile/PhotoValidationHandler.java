package com.profile;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification;
import com.profile.model.Profile;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;

/**
 * S3 ObjectCreated trigger on the photos/ prefix: verifies an uploaded object is
 * really an image (magic bytes / real dimensions — never the client-declared
 * content-type, which the presigned POST only checked as a header) before linking
 * it to the uploader's profile row. Invalid or oversized uploads are deleted and
 * never touch DynamoDB.
 */
public class PhotoValidationHandler implements RequestHandler<S3Event, Void> {

    private static final int MAX_DIMENSION = 4096;

    private final S3Client s3;
    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public PhotoValidationHandler() {
        this(S3Client.builder()
                        .httpClientBuilder(UrlConnectionHttpClient.builder())
                        .build(),
                DynamoDbClient.builder()
                        .httpClientBuilder(UrlConnectionHttpClient.builder())
                        .build(),
                System.getenv("PROFILE_TABLE"));
    }

    PhotoValidationHandler(S3Client s3, DynamoDbClient dynamoDb, String tableName) {
        this.s3 = s3;
        this.dynamoDb = dynamoDb;
        this.tableName = tableName;
    }

    @Override
    public Void handleRequest(S3Event event, Context context) {
        for (S3EventNotification.S3EventNotificationRecord record : event.getRecords()) {
            handleRecord(record);
        }
        return null;
    }

    private void handleRecord(S3EventNotification.S3EventNotificationRecord record) {
        String bucket = record.getS3().getBucket().getName();
        String key = record.getS3().getObject().getUrlDecodedKey();
        String sub = parseSub(key);

        byte[] bytes = s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();

        int[] dimensions = readDimensions(bytes);
        if (dimensions == null || dimensions[0] > MAX_DIMENSION || dimensions[1] > MAX_DIMENSION) {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            return;
        }

        GetItemResponse existing = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("sub", AttributeValue.fromS(sub)))
                .build());
        if (!existing.hasItem()) {
            // No profile row to attach this photo to — shouldn't happen since only an
            // authenticated sub can request an upload, but leave no orphaned object behind.
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            return;
        }

        Profile current = Profile.fromItem(existing.item());
        if (current.photoKey() != null && !current.photoKey().equals(key)) {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(current.photoKey()).build());
        }

        dynamoDb.updateItem(UpdateItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("sub", AttributeValue.fromS(sub)))
                .updateExpression("SET #photoKey = :photoKey")
                .expressionAttributeNames(Map.of("#photoKey", "photoKey"))
                .expressionAttributeValues(Map.of(":photoKey", AttributeValue.fromS(key)))
                .build());
    }

    /** Key is always "photos/{sub}/{uuid}" — sub is the path segment right after the prefix. */
    private static String parseSub(String key) {
        return key.split("/")[1];
    }

    /** Tries the JDK's built-in JPEG/PNG/GIF/BMP readers first, then falls back to the
     *  hand-rolled WebP header parser (ImageIO ships no WebP reader). Returns null if
     *  nothing recognizes the bytes as a real image. */
    private static int[] readDimensions(byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (readers.hasNext()) {
                ImageReader reader = readers.next();
                try {
                    reader.setInput(in);
                    return new int[] {reader.getWidth(0), reader.getHeight(0)};
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException malformed) {
            return null;
        }
        WebPImage.Dimensions webp = WebPImage.readDimensions(bytes);
        return webp == null ? null : new int[] {webp.width(), webp.height()};
    }
}

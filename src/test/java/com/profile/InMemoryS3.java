package com.profile;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** In-memory stand-in for the photo bucket. Implements just the operations
 *  PhotoValidationHandler is allowed to use (GetObject/DeleteObject). */
class InMemoryS3 implements S3Client {

    private final Map<String, byte[]> objects = new HashMap<>();
    private final Set<String> deletedKeys = new HashSet<>();

    void seed(String key, byte[] bytes) {
        objects.put(key, bytes);
    }

    boolean exists(String key) {
        return objects.containsKey(key);
    }

    boolean wasDeleted(String key) {
        return deletedKeys.contains(key);
    }

    @Override
    public String serviceName() {
        return SERVICE_NAME;
    }

    @Override
    public void close() {
    }

    @Override
    public ResponseBytes<GetObjectResponse> getObjectAsBytes(GetObjectRequest request) {
        byte[] bytes = objects.get(request.key());
        if (bytes == null) {
            throw NoSuchKeyException.builder().message("no such key: " + request.key()).build();
        }
        return ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), bytes);
    }

    @Override
    public DeleteObjectResponse deleteObject(DeleteObjectRequest request) {
        objects.remove(request.key());
        deletedKeys.add(request.key());
        return DeleteObjectResponse.builder().build();
    }
}

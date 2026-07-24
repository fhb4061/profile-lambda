package com.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresignedPostPolicyTest {

    private static final AwsBasicCredentials CREDENTIALS =
            AwsBasicCredentials.create("AKIAEXAMPLE", "secretExampleKey");
    private static final Instant NOW = Instant.parse("2024-01-15T10:00:00Z");

    @Test
    void urlIsVirtualHostedStyleForTheBucketAndRegion() {
        PresignedPostPolicy.Result result = PresignedPostPolicy.generate(
                "my-bucket", "ap-southeast-2", "photos/sub-123/abc",
                CREDENTIALS, NOW, Duration.ofMinutes(5),
                5 * 1024 * 1024, "image/");

        assertEquals("https://my-bucket.s3.ap-southeast-2.amazonaws.com/", result.url());
    }

    @Test
    void fieldsContainTheKeyAndSigningMaterial() {
        PresignedPostPolicy.Result result = PresignedPostPolicy.generate(
                "my-bucket", "ap-southeast-2", "photos/sub-123/abc",
                CREDENTIALS, NOW, Duration.ofMinutes(5),
                5 * 1024 * 1024, "image/");

        assertEquals("photos/sub-123/abc", result.fields().get("key"));
        assertEquals("AWS4-HMAC-SHA256", result.fields().get("x-amz-algorithm"));
        assertEquals("AKIAEXAMPLE/20240115/ap-southeast-2/s3/aws4_request",
                result.fields().get("x-amz-credential"));
        assertEquals("20240115T100000Z", result.fields().get("x-amz-date"));
        assertTrue(result.fields().containsKey("policy"));
        assertTrue(result.fields().containsKey("x-amz-signature"));
        assertTrue(result.fields().get("x-amz-signature").matches("[0-9a-f]{64}"),
                "signature must be lowercase hex-encoded HMAC-SHA256 output");
    }

    @Test
    void policyDocumentEncodesTheContentLengthRangeAndContentTypePrefix() throws Exception {
        PresignedPostPolicy.Result result = PresignedPostPolicy.generate(
                "my-bucket", "ap-southeast-2", "photos/sub-123/abc",
                CREDENTIALS, NOW, Duration.ofMinutes(5),
                5 * 1024 * 1024, "image/");

        JsonNode policy = decodePolicy(result);
        List<JsonNode> conditions = StreamSupport.stream(policy.get("conditions").spliterator(), false)
                .collect(Collectors.toList());

        assertTrue(conditions.stream().anyMatch(c ->
                        c.isArray() && c.get(0).asText().equals("content-length-range")
                                && c.get(1).asLong() == 0 && c.get(2).asLong() == 5 * 1024 * 1024),
                "expected a content-length-range condition of [0, 5242880]");
        assertTrue(conditions.stream().anyMatch(c ->
                        c.isArray() && c.get(0).asText().equals("starts-with")
                                && c.get(1).asText().equals("$Content-Type") && c.get(2).asText().equals("image/")),
                "expected a starts-with condition restricting Content-Type to image/*");
        assertTrue(conditions.stream().anyMatch(c ->
                        c.isArray() && c.get(0).asText().equals("eq")
                                && c.get(1).asText().equals("$key") && c.get(2).asText().equals("photos/sub-123/abc")),
                "expected an eq condition pinning the exact object key");
    }

    @Test
    void sessionTokenIsIncludedInFieldsAndConditionsWhenCredentialsAreTemporary() throws Exception {
        AwsSessionCredentials sessionCredentials =
                AwsSessionCredentials.create("AKIAEXAMPLE", "secretExampleKey", "example-session-token");

        PresignedPostPolicy.Result result = PresignedPostPolicy.generate(
                "my-bucket", "ap-southeast-2", "photos/sub-123/abc",
                sessionCredentials, NOW, Duration.ofMinutes(5),
                5 * 1024 * 1024, "image/");

        assertEquals("example-session-token", result.fields().get("x-amz-security-token"));
        JsonNode policy = decodePolicy(result);
        boolean hasTokenCondition = StreamSupport.stream(policy.get("conditions").spliterator(), false)
                .anyMatch(c -> c.isObject() && "example-session-token".equals(
                        c.path("x-amz-security-token").asText(null)));
        assertTrue(hasTokenCondition, "expected an x-amz-security-token condition matching the temporary token");
    }

    @Test
    void basicCredentialsOmitTheSessionTokenField() {
        PresignedPostPolicy.Result result = PresignedPostPolicy.generate(
                "my-bucket", "ap-southeast-2", "photos/sub-123/abc",
                CREDENTIALS, NOW, Duration.ofMinutes(5),
                5 * 1024 * 1024, "image/");

        assertFalse(result.fields().containsKey("x-amz-security-token"));
    }

    @Test
    void signatureChangesWhenTheSecretKeyDiffers() {
        AwsBasicCredentials otherCredentials = AwsBasicCredentials.create("AKIAEXAMPLE", "aDifferentSecretKey");

        PresignedPostPolicy.Result first = PresignedPostPolicy.generate(
                "my-bucket", "ap-southeast-2", "photos/sub-123/abc",
                CREDENTIALS, NOW, Duration.ofMinutes(5), 5 * 1024 * 1024, "image/");
        PresignedPostPolicy.Result second = PresignedPostPolicy.generate(
                "my-bucket", "ap-southeast-2", "photos/sub-123/abc",
                otherCredentials, NOW, Duration.ofMinutes(5), 5 * 1024 * 1024, "image/");

        assertNotEquals(first.fields().get("x-amz-signature"), second.fields().get("x-amz-signature"));
    }

    @Test
    void sameInputsProduceTheSameSignatureDeterministically() {
        PresignedPostPolicy.Result first = PresignedPostPolicy.generate(
                "my-bucket", "ap-southeast-2", "photos/sub-123/abc",
                CREDENTIALS, NOW, Duration.ofMinutes(5), 5 * 1024 * 1024, "image/");
        PresignedPostPolicy.Result second = PresignedPostPolicy.generate(
                "my-bucket", "ap-southeast-2", "photos/sub-123/abc",
                CREDENTIALS, NOW, Duration.ofMinutes(5), 5 * 1024 * 1024, "image/");

        assertEquals(first.fields().get("x-amz-signature"), second.fields().get("x-amz-signature"));
    }

    private static JsonNode decodePolicy(PresignedPostPolicy.Result result) throws Exception {
        byte[] decoded = Base64.getDecoder().decode(result.fields().get("policy"));
        return new ObjectMapper().readTree(decoded);
    }
}

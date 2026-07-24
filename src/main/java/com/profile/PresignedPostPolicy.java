package com.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hand-rolled AWS SigV4 presigned-POST policy signer. Neither AWS SDK v1 nor v2
 * has a built-in API for browser-form POST uploads (S3Presigner only covers
 * GET/PUT/multipart) — this is the same base64-policy-document +
 * HMAC-SHA256 key-derivation-chain algorithm boto3's generate_presigned_post
 * implements under the hood.
 */
public final class PresignedPostPolicy {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private PresignedPostPolicy() {
    }

    public record Result(String url, Map<String, String> fields) {
    }

    public static Result generate(
            String bucket, String region, String key,
            AwsCredentials credentials, Instant now, Duration expiresIn,
            long maxContentLength, String contentTypePrefix) {

        String amzDate = AMZ_DATE.format(now);
        String dateStamp = DATE_STAMP.format(now);
        String credentialScope = dateStamp + "/" + region + "/s3/aws4_request";
        String credential = credentials.accessKeyId() + "/" + credentialScope;
        String sessionToken = credentials instanceof AwsSessionCredentials session
                ? session.sessionToken()
                : null;

        ObjectNode policy = JSON.createObjectNode();
        policy.put("expiration", isoExpiration(now.plus(expiresIn)));
        ArrayNode conditions = policy.putArray("conditions");
        conditions.addObject().put("bucket", bucket);
        conditions.addArray().add("eq").add("$key").add(key);
        conditions.addArray().add("content-length-range").add(0).add(maxContentLength);
        conditions.addArray().add("starts-with").add("$Content-Type").add(contentTypePrefix);
        conditions.addObject().put("x-amz-algorithm", "AWS4-HMAC-SHA256");
        conditions.addObject().put("x-amz-credential", credential);
        conditions.addObject().put("x-amz-date", amzDate);
        if (sessionToken != null) {
            conditions.addObject().put("x-amz-security-token", sessionToken);
        }

        String policyBase64 = Base64.getEncoder().encodeToString(
                policy.toString().getBytes(StandardCharsets.UTF_8));

        byte[] kDate = hmac(("AWS4" + credentials.secretAccessKey()).getBytes(StandardCharsets.UTF_8), dateStamp);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, "s3");
        byte[] kSigning = hmac(kService, "aws4_request");
        String signature = HexFormat.of().formatHex(hmac(kSigning, policyBase64));

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("key", key);
        fields.put("x-amz-algorithm", "AWS4-HMAC-SHA256");
        fields.put("x-amz-credential", credential);
        fields.put("x-amz-date", amzDate);
        if (sessionToken != null) {
            fields.put("x-amz-security-token", sessionToken);
        }
        fields.put("policy", policyBase64);
        fields.put("x-amz-signature", signature);

        String url = "https://" + bucket + ".s3." + region + ".amazonaws.com/";
        return new Result(url, fields);
    }

    private static String isoExpiration(Instant instant) {
        return DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS));
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}

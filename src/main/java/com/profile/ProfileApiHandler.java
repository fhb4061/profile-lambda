package com.profile;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.profile.model.Profile;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * API monolith behind the API Gateway Cognito authorizer. Caller identity is
 * ALWAYS the "sub" claim from the verified JWT — never from body or path.
 */
public class ProfileApiHandler
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public ProfileApiHandler() {
        this(DynamoDbClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build(), System.getenv("PROFILE_TABLE"));
    }

    ProfileApiHandler(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = dynamoDb;
        this.tableName = tableName;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        try {
            String callerSub = callerSub(event);
            String route = event.getHttpMethod() + " " + event.getResource();
            return switch (route) {
                case "GET /profile" -> getOwnProfile(callerSub);
                case "PUT /profile" -> updateOwnProfile(callerSub, event.getBody());
                case "GET /profiles" -> listProfiles(event.getQueryStringParameters());
                case "GET /profiles/{sub}" -> getPublicProfile(event.getPathParameters().get("sub"));
                default -> json(404, "{\"message\":\"not found\"}");
            };
        } catch (Exception e) {
            // Proxy integration means an uncaught exception skips json() (and its CORS
            // headers) entirely, leaving the browser with a headerless response it blocks.
            e.printStackTrace();
            return json(500, "{\"message\":\"internal error\"}");
        }
    }

    private APIGatewayProxyResponseEvent getOwnProfile(String callerSub) {
        GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("sub", AttributeValue.fromS(callerSub)))
                .build());
        if (!response.hasItem()) {
            return json(404, "{\"message\":\"profile not found\"}");
        }
        return json(200, ownView(Profile.fromItem(response.item())).toString());
    }

    private static ObjectNode ownView(Profile profile) {
        ObjectNode node = JSON.createObjectNode();
        node.put("sub", profile.sub());
        node.put("email", profile.email());
        node.put("givenName", profile.givenName());
        node.put("familyName", profile.familyName());
        node.put("initials", profile.initials());
        return node;
    }

    private APIGatewayProxyResponseEvent getPublicProfile(String sub) {
        GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("sub", AttributeValue.fromS(sub)))
                .build());
        if (!response.hasItem()) {
            return json(404, "{\"message\":\"profile not found\"}");
        }
        return json(200, publicView(Profile.fromItem(response.item())).toString());
    }

    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final int MAX_PAGE_SIZE = 100;

    private APIGatewayProxyResponseEvent listProfiles(Map<String, String> queryParameters) {
        Map<String, String> query = queryParameters == null ? Map.of() : queryParameters;
        int limit = DEFAULT_PAGE_SIZE;
        if (query.get("limit") != null) {
            try {
                limit = Math.min(Math.max(Integer.parseInt(query.get("limit")), 1), MAX_PAGE_SIZE);
            } catch (NumberFormatException e) {
                return json(400, "{\"message\":\"limit must be a number\"}");
            }
        }
        ScanRequest.Builder scan = ScanRequest.builder()
                .tableName(tableName)
                .limit(limit);
        String nextToken = query.get("nextToken");
        if (nextToken != null) {
            String lastSub;
            try {
                lastSub = new String(Base64.getUrlDecoder().decode(nextToken), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return json(400, "{\"message\":\"invalid nextToken\"}");
            }
            scan.exclusiveStartKey(Map.of("sub", AttributeValue.fromS(lastSub)));
        }
        ScanResponse response = dynamoDb.scan(scan.build());
        ObjectNode body = JSON.createObjectNode();
        ArrayNode items = body.putArray("items");
        response.items().forEach(item -> items.add(publicView(Profile.fromItem(item))));
        if (response.hasLastEvaluatedKey()) {
            String lastSub = response.lastEvaluatedKey().get("sub").s();
            body.put("nextToken", Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(lastSub.getBytes(StandardCharsets.UTF_8)));
        }
        return json(200, body.toString());
    }

    /** Attributes safe to show other users — email is PII and stays private. */
    private static ObjectNode publicView(Profile profile) {
        ObjectNode node = JSON.createObjectNode();
        node.put("sub", profile.sub());
        node.put("givenName", profile.givenName());
        node.put("familyName", profile.familyName());
        node.put("initials", profile.initials());
        return node;
    }

    /** Only these attributes are client-editable; sub/email in the body are ignored. */
    private static final List<String> EDITABLE_FIELDS = List.of("givenName", "familyName");

    private APIGatewayProxyResponseEvent updateOwnProfile(String callerSub, String body) {
        JsonNode requested;
        try {
            requested = JSON.readTree(body == null ? "" : body);
        } catch (JsonProcessingException e) {
            return json(400, "{\"message\":\"invalid JSON body\"}");
        }
        Map<String, String> edits = new HashMap<>();
        for (String field : EDITABLE_FIELDS) {
            JsonNode value = requested.get(field);
            if (value != null && value.isTextual()) {
                edits.put(field, value.asText());
            }
        }
        if (edits.isEmpty()) {
            return json(400, "{\"message\":\"no editable fields in body\"}");
        }

        GetItemResponse existing = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("sub", AttributeValue.fromS(callerSub)))
                .build());
        if (!existing.hasItem()) {
            return json(404, "{\"message\":\"profile not found\"}");
        }
        Profile current = Profile.fromItem(existing.item());
        Profile updated = new Profile(
                current.sub(), current.email(),
                edits.getOrDefault("givenName", current.givenName()),
                edits.getOrDefault("familyName", current.familyName()));

        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(updated.toItem())
                .build());
        return json(200, ownView(updated).toString());
    }

    @SuppressWarnings("unchecked")
    private static String callerSub(APIGatewayProxyRequestEvent event) {
        Map<String, Object> authorizer = event.getRequestContext().getAuthorizer();
        Map<String, String> claims = (Map<String, String>) authorizer.get("claims");
        return claims.get("sub");
    }

    /** API Gateway uses Lambda proxy integration, so it passes the response through as-is
     *  — these headers must be set on every response here, not left to API Gateway. */
    private static final Map<String, String> CORS_HEADERS = Map.of(
            "Access-Control-Allow-Origin", "*",
            "Access-Control-Allow-Methods", "GET,PUT,OPTIONS",
            "Access-Control-Allow-Headers", "Content-Type,Authorization");

    private static APIGatewayProxyResponseEvent json(int statusCode, String body) {
        Map<String, String> headers = new HashMap<>(CORS_HEADERS);
        headers.put("Content-Type", "application/json");
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(statusCode)
                .withHeaders(headers)
                .withBody(body);
    }
}

package com.profile;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileApiHandlerTest {

    private static final String TABLE = "profiles-test";
    private static final ObjectMapper JSON = new ObjectMapper();

    private InMemoryDynamoDb db;
    private ProfileApiHandler handler;

    @BeforeEach
    void setUp() {
        db = new InMemoryDynamoDb();
        handler = new ProfileApiHandler(db, TABLE);
        db.seed(Map.of(
                "sub", AttributeValue.fromS("sub-123"),
                "email", AttributeValue.fromS("amy@example.com"),
                "givenName", AttributeValue.fromS("Amy"),
                "familyName", AttributeValue.fromS("Pond")));
    }

    @Test
    void getOwnProfileReturnsAllFields() throws Exception {
        APIGatewayProxyResponseEvent response =
                handler.handleRequest(request("GET", "/profile", "sub-123"), null);

        assertEquals(200, response.getStatusCode());
        JsonNode body = JSON.readTree(response.getBody());
        assertEquals("sub-123", body.get("sub").asText());
        assertEquals("amy@example.com", body.get("email").asText());
        assertEquals("Amy", body.get("givenName").asText());
        assertEquals("Pond", body.get("familyName").asText());
    }

    @Test
    void getOwnProfileReturns404WhenRowMissing() {
        APIGatewayProxyResponseEvent response =
                handler.handleRequest(request("GET", "/profile", "sub-unknown"), null);

        assertEquals(404, response.getStatusCode());
    }

    @Test
    void putProfileUpdatesOwnNamesAndIgnoresSubAndEmailInBody() throws Exception {
        String body = """
                {"givenName":"Amelia","familyName":"Williams",
                 "sub":"sub-victim","email":"attacker@example.com"}""";

        APIGatewayProxyResponseEvent putResponse =
                handler.handleRequest(request("PUT", "/profile", "sub-123").withBody(body), null);

        assertEquals(200, putResponse.getStatusCode());
        JsonNode updated = JSON.readTree(
                handler.handleRequest(request("GET", "/profile", "sub-123"), null).getBody());
        assertEquals("Amelia", updated.get("givenName").asText());
        assertEquals("Williams", updated.get("familyName").asText());
        assertEquals("amy@example.com", updated.get("email").asText());
        assertEquals("sub-123", updated.get("sub").asText());
        assertNull(db.item("sub-victim"), "sub from body must never be written");
    }

    @Test
    void putProfileReturns404WhenRowMissing() {
        APIGatewayProxyResponseEvent response = handler.handleRequest(
                request("PUT", "/profile", "sub-unknown").withBody("{\"givenName\":\"X\"}"), null);

        assertEquals(404, response.getStatusCode());
    }

    @Test
    void getAnotherUsersProfileReturnsPublicFieldsOnlyNeverEmail() throws Exception {
        APIGatewayProxyResponseEvent response = handler.handleRequest(
                request("GET", "/profiles/{sub}", "sub-456")
                        .withPathParameters(Map.of("sub", "sub-123")),
                null);

        assertEquals(200, response.getStatusCode());
        JsonNode body = JSON.readTree(response.getBody());
        assertEquals("sub-123", body.get("sub").asText());
        assertEquals("Amy", body.get("givenName").asText());
        assertEquals("Pond", body.get("familyName").asText());
        assertFalse(body.has("email"), "email is PII and must never appear in public views");
    }

    @Test
    void getAnotherUsersProfileReturns404WhenRowMissing() {
        APIGatewayProxyResponseEvent response = handler.handleRequest(
                request("GET", "/profiles/{sub}", "sub-456")
                        .withPathParameters(Map.of("sub", "sub-ghost")),
                null);

        assertEquals(404, response.getStatusCode());
    }

    @Test
    void listProfilesReturnsPublicFieldsOnlyForEveryItem() throws Exception {
        db.seed(Map.of(
                "sub", AttributeValue.fromS("sub-456"),
                "email", AttributeValue.fromS("rory@example.com"),
                "givenName", AttributeValue.fromS("Rory"),
                "familyName", AttributeValue.fromS("Williams")));

        APIGatewayProxyResponseEvent response =
                handler.handleRequest(request("GET", "/profiles", "sub-456"), null);

        assertEquals(200, response.getStatusCode());
        JsonNode items = JSON.readTree(response.getBody()).get("items");
        assertEquals(2, items.size());
        for (JsonNode item : items) {
            assertFalse(item.has("email"), "directory must never leak email");
        }
        assertEquals("Amy", items.get(0).get("givenName").asText());
        assertEquals("Rory", items.get(1).get("givenName").asText());
    }

    @Test
    void listProfilesPaginatesWithLimitAndNextToken() throws Exception {
        for (int i = 2; i <= 5; i++) {
            db.seed(Map.of(
                    "sub", AttributeValue.fromS("sub-" + i),
                    "email", AttributeValue.fromS("user" + i + "@example.com"),
                    "givenName", AttributeValue.fromS("User" + i),
                    "familyName", AttributeValue.fromS("Test")));
        }

        Set<String> seen = new HashSet<>();
        String nextToken = null;
        int pages = 0;
        do {
            Map<String, String> query = new HashMap<>(Map.of("limit", "2"));
            if (nextToken != null) {
                query.put("nextToken", nextToken);
            }
            APIGatewayProxyResponseEvent response = handler.handleRequest(
                    request("GET", "/profiles", "sub-123").withQueryStringParameters(query), null);
            assertEquals(200, response.getStatusCode());
            JsonNode body = JSON.readTree(response.getBody());
            for (JsonNode item : body.get("items")) {
                assertTrue(seen.add(item.get("sub").asText()), "no duplicates across pages");
            }
            nextToken = body.has("nextToken") ? body.get("nextToken").asText() : null;
            pages++;
        } while (nextToken != null && pages < 10);

        assertEquals(5, seen.size(), "pagination must reach every profile");
        assertTrue(pages >= 3, "limit=2 over 5 rows needs at least 3 pages");
    }

    @Test
    void thereIsNoDeleteOrCreateEndpoint() {
        assertEquals(404, handler.handleRequest(request("DELETE", "/profile", "sub-123"), null).getStatusCode());
        assertEquals(404, handler.handleRequest(request("POST", "/profiles", "sub-123"), null).getStatusCode());
    }

    @Test
    void everyResponseHasCorsHeadersOnSuccessAnd404Paths() {
        List<APIGatewayProxyResponseEvent> responses = List.of(
                handler.handleRequest(request("GET", "/profile", "sub-123"), null),
                handler.handleRequest(request("GET", "/profile", "sub-unknown"), null),
                handler.handleRequest(request("DELETE", "/profile", "sub-123"), null));

        for (APIGatewayProxyResponseEvent response : responses) {
            assertEquals("*", response.getHeaders().get("Access-Control-Allow-Origin"));
            assertEquals("GET,PUT,OPTIONS", response.getHeaders().get("Access-Control-Allow-Methods"));
            assertEquals("Content-Type,Authorization", response.getHeaders().get("Access-Control-Allow-Headers"));
        }
    }

    @Test
    void uncaughtExceptionStillReturns500WithCorsHeaders() {
        // No requestContext/authorizer set, so callerSub() throws before any route runs.
        APIGatewayProxyRequestEvent malformed = new APIGatewayProxyRequestEvent()
                .withHttpMethod("GET")
                .withResource("/profile");

        APIGatewayProxyResponseEvent response = handler.handleRequest(malformed, null);

        assertEquals(500, response.getStatusCode());
        assertEquals("*", response.getHeaders().get("Access-Control-Allow-Origin"));
    }

    /** Request as API Gateway delivers it after the Cognito authorizer has verified the JWT. */
    static APIGatewayProxyRequestEvent request(String method, String resource, String callerSub) {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent()
                .withHttpMethod(method)
                .withResource(resource);
        APIGatewayProxyRequestEvent.ProxyRequestContext requestContext =
                new APIGatewayProxyRequestEvent.ProxyRequestContext();
        requestContext.setAuthorizer(Map.of("claims", Map.of("sub", callerSub)));
        event.setRequestContext(requestContext);
        return event;
    }
}

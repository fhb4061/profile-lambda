package com.profile;

import com.amazonaws.services.lambda.runtime.events.CognitoUserPoolPostConfirmationEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class PostConfirmationHandlerTest {

    private static final String TABLE = "profiles-test";

    private InMemoryDynamoDb db;
    private PostConfirmationHandler handler;

    @BeforeEach
    void setUp() {
        db = new InMemoryDynamoDb();
        handler = new PostConfirmationHandler(db, TABLE);
    }

    @Test
    void signUpConfirmationCreatesProfileRowAndReturnsEventUnchanged() {
        CognitoUserPoolPostConfirmationEvent event =
                event("PostConfirmation_ConfirmSignUp", "sub-123", "amy@example.com", "Amy", "Pond");

        CognitoUserPoolPostConfirmationEvent returned = handler.handleRequest(event, null);

        assertSame(event, returned);
        Map<String, AttributeValue> row = db.item("sub-123");
        assertNotNull(row);
        assertEquals("sub-123", row.get("sub").s());
        assertEquals("amy@example.com", row.get("email").s());
        assertEquals("Amy", row.get("givenName").s());
        assertEquals("Pond", row.get("familyName").s());
        assertEquals("AP", row.get("initials").s());
    }

    @Test
    void passwordResetConfirmationLeavesEditedProfileUntouched() {
        db.seed(Map.of(
                "sub", AttributeValue.fromS("sub-123"),
                "email", AttributeValue.fromS("amy@example.com"),
                "givenName", AttributeValue.fromS("Amelia"),
                "familyName", AttributeValue.fromS("Williams")));
        CognitoUserPoolPostConfirmationEvent event = event(
                "PostConfirmation_ConfirmForgotPassword", "sub-123", "amy@example.com", "Amy", "Pond");

        CognitoUserPoolPostConfirmationEvent returned = handler.handleRequest(event, null);

        assertSame(event, returned);
        assertEquals("Amelia", db.item("sub-123").get("givenName").s());
        assertEquals("Williams", db.item("sub-123").get("familyName").s());
    }

    @Test
    void retriedSignUpConfirmationIsIdempotentAndDoesNotOverwriteExistingRow() {
        db.seed(Map.of(
                "sub", AttributeValue.fromS("sub-123"),
                "email", AttributeValue.fromS("amy@example.com"),
                "givenName", AttributeValue.fromS("Amelia"),
                "familyName", AttributeValue.fromS("Williams")));
        CognitoUserPoolPostConfirmationEvent event =
                event("PostConfirmation_ConfirmSignUp", "sub-123", "amy@example.com", "Amy", "Pond");

        CognitoUserPoolPostConfirmationEvent returned = handler.handleRequest(event, null);

        assertSame(event, returned);
        assertEquals("Amelia", db.item("sub-123").get("givenName").s());
    }

    static CognitoUserPoolPostConfirmationEvent event(
            String triggerSource, String sub, String email, String givenName, String familyName) {
        return CognitoUserPoolPostConfirmationEvent.builder()
                .withTriggerSource(triggerSource)
                .withRequest(CognitoUserPoolPostConfirmationEvent.Request.builder()
                        .withUserAttributes(Map.of(
                                "sub", sub,
                                "email", email,
                                "given_name", givenName,
                                "family_name", familyName))
                        .build())
                .build();
    }
}

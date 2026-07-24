package com.profile;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.CognitoUserPoolPostConfirmationEvent;
import com.profile.model.Profile;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.util.Map;

/**
 * Cognito post-confirmation trigger: creates the profile row for a newly
 * confirmed user. Invoked synchronously (5s timeout) — no heavy frameworks.
 */
public class PostConfirmationHandler
        implements RequestHandler<CognitoUserPoolPostConfirmationEvent, CognitoUserPoolPostConfirmationEvent> {

    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public PostConfirmationHandler() {
        this(DynamoDbClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build(), System.getenv("PROFILE_TABLE"));
    }

    PostConfirmationHandler(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = dynamoDb;
        this.tableName = tableName;
    }

    @Override
    public CognitoUserPoolPostConfirmationEvent handleRequest(
            CognitoUserPoolPostConfirmationEvent event, Context context) {
        // Also fires on password reset (ConfirmForgotPassword) — only sign-up creates a row
        if (!"PostConfirmation_ConfirmSignUp".equals(event.getTriggerSource())) {
            return event;
        }
        Map<String, String> attributes = event.getRequest().getUserAttributes();
        Profile profile = new Profile(
                attributes.get("sub"), attributes.get("email"),
                attributes.get("given_name"), attributes.get("family_name"));
        try {
            dynamoDb.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(profile.toItem())
                    .conditionExpression("attribute_not_exists(#sub)")
                    .expressionAttributeNames(Map.of("#sub", "sub"))
                    .build());
        } catch (ConditionalCheckFailedException alreadyExists) {
            // Retries make double-invocation normal — existing row wins
        }
        return event;
    }
}

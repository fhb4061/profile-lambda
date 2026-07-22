package com.profile;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * In-memory stand-in for the profile table (PK "sub", string). Implements just the
 * operations the handlers are allowed to use (Get/Put/Update/Scan) with narrow
 * DynamoDB semantics: attribute_not_exists / attribute_exists conditions and
 * simple "SET #a = :a, ..." update expressions.
 */
class InMemoryDynamoDb implements DynamoDbClient {

    private final NavigableMap<String, Map<String, AttributeValue>> items = new TreeMap<>();

    Map<String, AttributeValue> item(String sub) {
        return items.get(sub);
    }

    void seed(Map<String, AttributeValue> item) {
        items.put(item.get("sub").s(), new HashMap<>(item));
    }

    int size() {
        return items.size();
    }

    @Override
    public String serviceName() {
        return SERVICE_NAME;
    }

    @Override
    public void close() {
    }

    @Override
    public PutItemResponse putItem(PutItemRequest request) {
        String sub = request.item().get("sub").s();
        if (request.conditionExpression() != null
                && request.conditionExpression().contains("attribute_not_exists")
                && items.containsKey(sub)) {
            throw ConditionalCheckFailedException.builder().message("item already exists").build();
        }
        items.put(sub, new HashMap<>(request.item()));
        return PutItemResponse.builder().build();
    }

    @Override
    public GetItemResponse getItem(GetItemRequest request) {
        Map<String, AttributeValue> item = items.get(request.key().get("sub").s());
        GetItemResponse.Builder builder = GetItemResponse.builder();
        if (item != null) {
            builder.item(item);
        }
        return builder.build();
    }

    @Override
    public UpdateItemResponse updateItem(UpdateItemRequest request) {
        String sub = request.key().get("sub").s();
        Map<String, AttributeValue> item = items.get(sub);
        if (request.conditionExpression() != null
                && request.conditionExpression().contains("attribute_exists")
                && item == null) {
            throw ConditionalCheckFailedException.builder().message("item does not exist").build();
        }
        if (item == null) {
            item = new HashMap<>(request.key());
            items.put(sub, item);
        }
        String expression = request.updateExpression().replaceFirst("^\\s*SET\\s+", "");
        for (String clause : expression.split(",")) {
            String[] parts = clause.split("=");
            String name = parts[0].trim();
            if (name.startsWith("#")) {
                name = request.expressionAttributeNames().get(name);
            }
            item.put(name, request.expressionAttributeValues().get(parts[1].trim()));
        }
        return UpdateItemResponse.builder().build();
    }

    @Override
    public ScanResponse scan(ScanRequest request) {
        Collection<Map<String, AttributeValue>> remaining =
                request.hasExclusiveStartKey()
                        ? items.tailMap(request.exclusiveStartKey().get("sub").s(), false).values()
                        : items.values();
        int limit = request.limit() != null ? request.limit() : Integer.MAX_VALUE;
        List<Map<String, AttributeValue>> page = new ArrayList<>();
        for (Map<String, AttributeValue> item : remaining) {
            if (page.size() == limit) {
                break;
            }
            page.add(new HashMap<>(item));
        }
        ScanResponse.Builder builder = ScanResponse.builder().items(page).count(page.size());
        if (page.size() == limit && remaining.size() > limit) {
            builder.lastEvaluatedKey(Map.of("sub", page.get(page.size() - 1).get("sub")));
        }
        return builder.build();
    }
}

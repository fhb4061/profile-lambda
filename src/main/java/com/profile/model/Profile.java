package com.profile.model;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Map;

public record Profile(String sub, String email, String givenName, String familyName) {

    public String initials() {
        return firstChar(givenName) + firstChar(familyName);
    }

    private static String firstChar(String s) {
        return s == null || s.isEmpty() ? "" : s.substring(0, 1).toUpperCase();
    }

    public static Profile fromItem(Map<String, AttributeValue> item) {
        return new Profile(
                item.get("sub").s(),
                item.get("email").s(),
                item.get("givenName").s(),
                item.get("familyName").s());
    }

    public Map<String, AttributeValue> toItem() {
        return Map.of(
                "sub", AttributeValue.fromS(sub),
                "email", AttributeValue.fromS(email),
                "givenName", AttributeValue.fromS(givenName),
                "familyName", AttributeValue.fromS(familyName));
    }
}

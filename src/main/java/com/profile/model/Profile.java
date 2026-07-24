package com.profile.model;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.HashMap;
import java.util.Map;

public record Profile(String sub, String email, String givenName, String familyName, String photoKey) {

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
                item.get("familyName").s(),
                item.containsKey("photoKey") ? item.get("photoKey").s() : null);
    }

    public Map<String, AttributeValue> toItem() {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("sub", AttributeValue.fromS(sub));
        item.put("email", AttributeValue.fromS(email));
        item.put("givenName", AttributeValue.fromS(givenName));
        item.put("familyName", AttributeValue.fromS(familyName));
        if (photoKey != null) {
            item.put("photoKey", AttributeValue.fromS(photoKey));
        }
        return item;
    }
}

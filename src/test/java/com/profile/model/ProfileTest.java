package com.profile.model;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ProfileTest {

    @Test
    void initialsIsFirstLetterOfGivenAndFamilyNameUppercased() {
        Profile profile = new Profile("sub-123", "amy@example.com", "amy", "pond", null);

        assertEquals("AP", profile.initials());
    }

    @Test
    void fromItemReadsTheFourStoredAttributes() {
        Profile profile = Profile.fromItem(Map.of(
                "sub", AttributeValue.fromS("sub-123"),
                "email", AttributeValue.fromS("amy@example.com"),
                "givenName", AttributeValue.fromS("Amy"),
                "familyName", AttributeValue.fromS("Pond")));

        assertEquals("sub-123", profile.sub());
        assertEquals("amy@example.com", profile.email());
        assertEquals("Amy", profile.givenName());
        assertEquals("Pond", profile.familyName());
        assertEquals("AP", profile.initials());
    }

    @Test
    void toItemNeverIncludesInitials() {
        Profile profile = new Profile("sub-123", "amy@example.com", "Amy", "Pond", null);

        Map<String, AttributeValue> item = profile.toItem();

        assertEquals(4, item.size());
        assertFalse(item.containsKey("initials"), "initials is derived, never persisted");
    }

    @Test
    void fromItemReadsPhotoKeyWhenPresent() {
        Profile profile = Profile.fromItem(Map.of(
                "sub", AttributeValue.fromS("sub-123"),
                "email", AttributeValue.fromS("amy@example.com"),
                "givenName", AttributeValue.fromS("Amy"),
                "familyName", AttributeValue.fromS("Pond"),
                "photoKey", AttributeValue.fromS("photos/sub-123/abc")));

        assertEquals("photos/sub-123/abc", profile.photoKey());
    }

    @Test
    void toItemIncludesPhotoKeyWhenPresentButOmitsItWhenAbsent() {
        Profile withPhoto = new Profile("sub-123", "amy@example.com", "Amy", "Pond", "photos/sub-123/abc");
        Profile withoutPhoto = new Profile("sub-123", "amy@example.com", "Amy", "Pond", null);

        assertEquals("photos/sub-123/abc", withPhoto.toItem().get("photoKey").s());
        assertFalse(withoutPhoto.toItem().containsKey("photoKey"));
    }
}

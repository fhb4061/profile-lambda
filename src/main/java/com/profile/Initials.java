package com.profile;

final class Initials {
    private Initials() {}

    static String of(String givenName, String familyName) {
        return firstChar(givenName) + firstChar(familyName);
    }

    private static String firstChar(String s) {
        return s == null || s.isEmpty() ? "" : s.substring(0, 1).toUpperCase();
    }
}

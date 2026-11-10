package com.gathin.kit;

/** JSON string quoting for the few replies built by hand (the rest use org.json). */
final class Json {
    private Json() {
    }

    static String quote(String value) {
        return org.json.JSONObject.quote(value == null ? "" : value);
    }
}

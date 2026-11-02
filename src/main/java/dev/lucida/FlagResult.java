package dev.lucida;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Outcome of evaluating one flag. {@code value} is a {@code Boolean} or the variant key ({@code String}).
 * {@code reason} is the raw Lucida reason ({@code "fallback"} for an {@link Lucida.Options#fallbacks} value,
 * {@code "remote"} when the server sent none); {@code requestId} is set for server answers.
 */
public record FlagResult(Object value, String reason, int conditionIndex, String variant, JsonNode payload, String requestId, boolean evaluatedLocally) {
    public static final String FALLBACK = "fallback";

    public FlagResult(Object value, String reason, int conditionIndex, String variant, JsonNode payload) {
        this(value, reason, conditionIndex, variant, payload, null, true);
    }

    static FlagResult fallback(Object value) {
        return new FlagResult(value, FALLBACK, -1, value instanceof String s ? s : null, null, null, true);
    }

    public boolean isFallback() {
        return FALLBACK.equals(reason);
    }
}

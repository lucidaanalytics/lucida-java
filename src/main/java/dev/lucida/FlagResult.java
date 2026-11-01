package dev.lucida;

import com.fasterxml.jackson.databind.JsonNode;

/** Outcome of evaluating one flag. {@code value} is a {@code Boolean} or the variant key ({@code String}). */
public record FlagResult(Object value, String reason, int conditionIndex, String variant, JsonNode payload) {}

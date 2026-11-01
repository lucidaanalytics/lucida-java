package dev.lucida;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/**
 * Everything a flag may read; null fields are treated as absent.
 *
 * @param bucketingKey overrides {@code distinctId} for person aggregation
 * @param groups group type to group key
 * @param groupProperties group type to that group's properties
 * @param stickyAssignments flag key to the unit's stored value (true or a variant key) for sticky flags
 * @param now evaluation clock (RFC 3339) for relative date operators; null means the wall clock
 * @param appContexts app contexts this request declares; flags scoped to none of them are dropped (null: client default)
 * @param anonDistinctId pre-identify id of this person, sent on remote requests so the gateway links it (null: none)
 */
public record FlagContext(
        String distinctId,
        String bucketingKey,
        String deviceId,
        String appVersion,
        Map<String, String> groups,
        Map<String, JsonNode> personProperties,
        Map<String, Map<String, JsonNode>> groupProperties,
        Map<String, JsonNode> deviceProperties,
        Map<String, JsonNode> geoProperties,
        Map<String, JsonNode> stickyAssignments,
        String now,
        List<String> appContexts,
        String anonDistinctId) {

    public FlagContext(String distinctId, String bucketingKey, String deviceId, String appVersion, Map<String, String> groups,
            Map<String, JsonNode> personProperties, Map<String, Map<String, JsonNode>> groupProperties,
            Map<String, JsonNode> deviceProperties, Map<String, JsonNode> geoProperties, Map<String, JsonNode> stickyAssignments,
            String now, List<String> appContexts) {
        this(distinctId, bucketingKey, deviceId, appVersion, groups, personProperties, groupProperties, deviceProperties, geoProperties,
                stickyAssignments, now, appContexts, null);
    }

    public FlagContext(String distinctId, String bucketingKey, String deviceId, String appVersion, Map<String, String> groups,
            Map<String, JsonNode> personProperties, Map<String, Map<String, JsonNode>> groupProperties,
            Map<String, JsonNode> deviceProperties, Map<String, JsonNode> geoProperties, Map<String, JsonNode> stickyAssignments) {
        this(distinctId, bucketingKey, deviceId, appVersion, groups, personProperties, groupProperties, deviceProperties, geoProperties,
                stickyAssignments, null, null);
    }

    public FlagContext(String distinctId, String bucketingKey, String deviceId, String appVersion, Map<String, String> groups,
            Map<String, JsonNode> personProperties, Map<String, Map<String, JsonNode>> groupProperties,
            Map<String, JsonNode> deviceProperties, Map<String, JsonNode> geoProperties) {
        this(distinctId, bucketingKey, deviceId, appVersion, groups, personProperties, groupProperties, deviceProperties, geoProperties, null, null, null);
    }

    public static FlagContext of(String distinctId) {
        return new FlagContext(distinctId, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public FlagContext withAppContexts(List<String> contexts) {
        return new FlagContext(distinctId, bucketingKey, deviceId, appVersion, groups, personProperties, groupProperties,
                deviceProperties, geoProperties, stickyAssignments, now, contexts, anonDistinctId);
    }

    public FlagContext withAnonDistinctId(String anon) {
        return new FlagContext(distinctId, bucketingKey, deviceId, appVersion, groups, personProperties, groupProperties,
                deviceProperties, geoProperties, stickyAssignments, now, appContexts, anon);
    }
}

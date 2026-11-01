package dev.lucida;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Flags evaluated once for one distinct id. Reads never call the network or emit exposure events. */
public final class FlagSnapshot {
    private final Map<String, Object> values;
    private final Map<String, JsonNode> payloads;
    private final Set<String> accessed = ConcurrentHashMap.newKeySet();

    public FlagSnapshot(Map<String, Object> values, Map<String, JsonNode> payloads) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        this.payloads = Collections.unmodifiableMap(new LinkedHashMap<>(payloads));
    }

    /** {@code $feature/<key>} per flag plus sorted {@code $active_feature_flags}; entries already in {@code props} win. */
    public static Map<String, Object> eventProps(Map<String, ?> props, Map<String, Object> values) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!values.isEmpty()) {
            values.forEach((k, v) -> out.put("$feature/" + k, v));
            List<String> active = new ArrayList<>();
            values.forEach((k, v) -> {
                if (!Boolean.FALSE.equals(v)) active.add(k);
            });
            Collections.sort(active);
            out.put("$active_feature_flags", active);
        }
        if (props != null) out.putAll(props);
        return out;
    }

    /** The flag's value (Boolean or variant key), or null when it is not in the snapshot. */
    public Object getFlag(String key) {
        accessed.add(key);
        return values.get(key);
    }

    public boolean isEnabled(String key) {
        Object v = getFlag(key);
        return v != null && !Boolean.FALSE.equals(v);
    }

    /** Alias of {@link #getFlag} without recording the read as "accessed". */
    public Object get(String key) {
        return values.get(key);
    }

    /** Alias of {@link #getPayload}. */
    public JsonNode payload(String key) {
        return payloads.get(key);
    }

    public JsonNode getPayload(String key) {
        return payloads.get(key);
    }

    public List<String> keys() {
        List<String> k = new ArrayList<>(values.keySet());
        Collections.sort(k);
        return k;
    }

    public Map<String, Object> values() {
        return values;
    }

    public FlagSnapshot only(Collection<String> keys) {
        Map<String, Object> v = new LinkedHashMap<>();
        Map<String, JsonNode> p = new LinkedHashMap<>();
        for (String k : keys) {
            if (values.containsKey(k)) v.put(k, values.get(k));
            if (payloads.containsKey(k)) p.put(k, payloads.get(k));
        }
        return new FlagSnapshot(v, p);
    }

    public FlagSnapshot onlyAccessed() {
        return only(accessed);
    }

    public Map<String, Object> eventProps(Map<String, ?> props) {
        return eventProps(props, values);
    }
}

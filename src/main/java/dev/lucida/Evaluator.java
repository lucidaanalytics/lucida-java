package dev.lucida;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Local flag evaluator; behaviour pinned by testdata/flags vectors (see testdata/flags/README.md). */
public final class Evaluator {
    public static final String REQUIRES_SERVER = "requires_server";
    private static final double UNIT = 4503599627370496d; // 16^13
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern NUMBER = Pattern.compile("^[+-]?(\\d+\\.?\\d*|\\.\\d+)(e[+-]?\\d+)?$");

    private Evaluator() {}

    public static double hash01(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : d) hex.append(String.format("%02x", b));
            return Long.parseLong(hex.substring(0, 13), 16) / UNIT;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static FlagResult off(String reason) {
        return off(reason, -1);
    }

    private static FlagResult off(String reason, int idx) {
        return new FlagResult(Boolean.FALSE, reason, idx, null, null);
    }

    private static boolean absent(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull();
    }

    private static String str(JsonNode o, String name) {
        JsonNode v = o.get(name);
        return v != null && v.isTextual() ? v.asText() : "";
    }

    private static double num(JsonNode o, String name) {
        JsonNode v = o.get(name);
        return v != null && v.isNumber() ? v.asDouble() : 0;
    }

    static JsonNode textNode(String s) {
        return MAPPER.getNodeFactory().textNode(s);
    }

    static Double toFloat(JsonNode v) {
        if (v.isNumber()) return v.asDouble();
        if (!v.isTextual()) return null;
        String lower = v.asText().strip().toLowerCase();
        if (lower.contains("_")) return null;
        String bare = lower.replaceFirst("^[+-]", "");
        if (lower.length() - bare.length() <= 1) {
            double sign = lower.startsWith("-") ? -1 : 1;
            if (bare.equals("inf") || bare.equals("infinity")) return sign * Double.POSITIVE_INFINITY;
            if (bare.equals("nan")) return Double.NaN;
        }
        if (!NUMBER.matcher(lower).matches()) return null;
        try {
            return Double.parseDouble(lower);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean equal(JsonNode a, JsonNode b) {
        if (a.isNumber() || b.isNumber()) return a.isNumber() && b.isNumber() && a.asDouble() == b.asDouble();
        if (a.getNodeType() != b.getNodeType()) return false;
        if (a.isArray()) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!equal(a.get(i), b.get(i))) return false;
            return true;
        }
        if (a.isObject()) {
            if (a.size() != b.size()) return false;
            var it = a.fieldNames();
            while (it.hasNext()) {
                String k = it.next();
                if (!b.has(k) || !equal(a.get(k), b.get(k))) return false;
            }
            return true;
        }
        return a.equals(b);
    }

    static List<BigInteger> semver(JsonNode v) {
        if (!v.isTextual()) return null;
        String s = v.asText().strip();
        if (s.startsWith("v")) s = s.substring(1);
        int cut = -1;
        for (int i = 0; i < s.length() && cut < 0; i++) if (s.charAt(i) == '-' || s.charAt(i) == '+') cut = i;
        if (cut >= 0) s = s.substring(0, cut);
        List<BigInteger> out = new ArrayList<>();
        for (String p : s.split("\\.", -1)) {
            if (p.isEmpty() || !p.chars().allMatch(c -> c >= '0' && c <= '9')) return null;
            out.add(new BigInteger(p));
        }
        return out;
    }

    static Integer cmpSemver(JsonNode a, JsonNode b) {
        List<BigInteger> pa = semver(a), pb = semver(b);
        if (pa == null || pb == null) return null;
        for (int i = 0; i < Math.max(pa.size(), pb.size()); i++) {
            int c = (i < pa.size() ? pa.get(i) : BigInteger.ZERO).compareTo(i < pb.size() ? pb.get(i) : BigInteger.ZERO);
            if (c != 0) return c;
        }
        return 0;
    }

    private static Boolean regex(String pattern, String s) {
        try {
            return Pattern.compile(pattern).matcher(s).find();
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    private static boolean match(String op, JsonNode prop, boolean present, JsonNode want, boolean semver, Instant now) {
        boolean isSet = present && !absent(prop);
        if (op.equals("is_set")) return isSet;
        if (op.equals("is_not_set")) return !isSet;
        if (!isSet) return false;
        switch (op) {
            case "eq", "neq" -> {
                if (semver) {
                    Integer c = cmpSemver(prop, want);
                    return c != null && (c == 0) == op.equals("eq");
                }
                return equal(prop, want) == op.equals("eq");
            }
            case "contains", "ncontains", "regex", "nregex" -> {
                if (!prop.isTextual() || !want.isTextual()) return false;
                if (op.endsWith("contains")) return prop.asText().contains(want.asText()) == op.equals("contains");
                Boolean r = regex(want.asText(), prop.asText());
                return r != null && r == op.equals("regex");
            }
            case "gt", "gte", "lt", "lte" -> {
                Integer cmp;
                if (semver) cmp = cmpSemver(prop, want);
                else {
                    Double x = toFloat(prop), y = toFloat(want);
                    if (x == null || y == null) cmp = null;
                    else cmp = x > y ? 1 : x < y ? -1 : 0; // NaN compares equal, as the reference (Python) does
                }
                if (cmp == null) return false;
                return switch (op) {
                    case "gt" -> cmp > 0;
                    case "gte" -> cmp >= 0;
                    case "lt" -> cmp < 0;
                    default -> cmp <= 0;
                };
            }
            case "in", "not_in" -> {
                if (!want.isArray()) return false;
                boolean any = false;
                for (JsonNode w : want) any |= equal(prop, w);
                return any == op.equals("in");
            }
            default -> {
                return OpsV2.match(op, prop, want, now);
            }
        }
    }

    private static JsonNode valueNode(Object v) {
        return MAPPER.valueToTree(v);
    }

    private static boolean filterMatches(JsonNode fl, FlagContext c, Map<String, FlagResult> resolved) {
        JsonNode want = fl.has("value") ? fl.get("value") : MAPPER.nullNode();
        String op = str(fl, "op"), key = str(fl, "key"), type = str(fl, "type");
        Instant now = OpsV2.parseNow(c.now());
        if (type.equals("app_version")) {
            String v = c.appVersion() == null ? "" : c.appVersion();
            return match(op, valueNode(v), !v.isEmpty(), want, true, now);
        }
        if (type.equals("flag")) {
            FlagResult r = resolved.get(key);
            return r != null && match(op, valueNode(r.value()), true, want, false, now);
        }
        Map<String, JsonNode> props;
        switch (type) {
            case "person" -> props = c.personProperties();
            case "group" -> props = c.groupProperties() == null ? null : c.groupProperties().get(str(fl, "group_type"));
            case "device" -> props = c.deviceProperties();
            case "geo" -> props = c.geoProperties();
            default -> {
                return false;
            }
        }
        if (props == null) props = Map.of();
        return match(op, props.get(key), props.containsKey(key), want, false, now);
    }

    private static boolean valueMatches(Object got, JsonNode want) {
        if (want != null && want.isBoolean()) return got instanceof Boolean g ? g == want.asBoolean() : want.asBoolean();
        return want != null && want.isTextual() && got instanceof String s && s.equals(want.asText());
    }

    private static String bucketingKey(JsonNode f, FlagContext c) {
        return unitKey(f.get("aggregation"), c);
    }

    private static String unitKey(JsonNode agg, FlagContext c) {
        String kind = agg == null ? "" : str(agg, "kind");
        if (kind.equals("group")) {
            String v = c.groups() == null ? null : c.groups().get(str(agg, "group_type"));
            return v == null ? "" : v;
        }
        if (kind.equals("device")) return c.deviceId() == null ? "" : c.deviceId();
        if (c.bucketingKey() != null && !c.bucketingKey().isEmpty()) return c.bucketingKey();
        return c.distinctId() == null ? "" : c.distinctId();
    }

    private static JsonNode pickVariant(JsonNode f, JsonNode cond, String key) {
        JsonNode variants = f.get("variants");
        String over = str(cond, "variant_override");
        if (!over.isEmpty()) for (JsonNode v : variants) if (str(v, "key").equals(over)) return v;
        double h = hash01(str(f, "key") + "." + str(f, "salt") + ".variant." + key) * 100;
        double cum = 0;
        for (JsonNode v : variants) {
            cum += num(v, "rollout_pct");
            if (h < cum) return v;
        }
        return variants.get(variants.size() - 1); // percentages summed < 100: last variant takes the remainder
    }

    private static Iterable<JsonNode> items(JsonNode o, String name) {
        JsonNode a = o.get(name);
        return a != null && a.isArray() ? a : List.of();
    }

    private static boolean undecidable(JsonNode fl, Map<String, FlagResult> resolved) {
        if (str(fl, "type").equals("flag")) {
            FlagResult r = resolved.get(str(fl, "key"));
            return r != null && r.reason().equals(REQUIRES_SERVER);
        }
        return str(fl, "type").equals("cohort");
    }

    /** A stored value of a sticky flag decides once a condition's filters passed (LC-EXT-29); null otherwise. */
    private static FlagResult sticky(JsonNode f, JsonNode cond, int i, FlagContext c) {
        JsonNode stored = c.stickyAssignments() == null ? null : c.stickyAssignments().get(str(f, "key"));
        if (!f.path("sticky").asBoolean(false) || absent(stored)) return null;
        if (str(f, "type").equals("multivariate") && items(f, "variants").iterator().hasNext()) {
            String over = str(cond, "variant_override");
            for (JsonNode v : items(f, "variants")) if (str(v, "key").equals(over)) return null; // the override wins
            for (JsonNode v : items(f, "variants")) {
                if (stored.isTextual() && str(v, "key").equals(stored.asText())) {
                    return new FlagResult(stored.asText(), "sticky_bucket", i, stored.asText(), v.get("payload"));
                }
            }
            return null;
        }
        if (!stored.isBoolean() || !stored.asBoolean()) return null;
        return new FlagResult(Boolean.TRUE, "sticky_bucket", i, null, str(f, "type").equals("json") ? f.get("payload") : null);
    }

    private static boolean appContextAllowed(JsonNode f, FlagContext c) {
        JsonNode want = f.get("app_contexts");
        if (want == null || !want.isArray() || want.isEmpty() || c.appContexts() == null || c.appContexts().isEmpty()) return true;
        for (JsonNode w : want) if (c.appContexts().contains(w.asText())) return true;
        return false;
    }

    public static FlagResult evaluate(JsonNode f, FlagContext c, Map<String, FlagResult> resolved) {
        if (!appContextAllowed(f, c)) return off("app_context_excluded");
        if (!f.path("active").isBoolean() || !f.get("active").asBoolean()) return off("inactive");
        if (f.path("kill_switch").asBoolean(false)) return off("kill_switch");
        boolean server = false;
        for (JsonNode p : items(f, "prerequisites")) {
            FlagResult r = resolved.get(str(p, "flag"));
            if (r == null) return off("prerequisite_missing");
            if (r.reason().equals(REQUIRES_SERVER)) server = true; // a definite failure elsewhere still wins
            else if (r.reason().equals("prerequisite_cycle") || !valueMatches(r.value(), p.get("value"))) return off("prerequisite_failed");
        }
        if (server) return off(REQUIRES_SERVER);
        String key = bucketingKey(f, c);
        boolean perCondition = false;
        for (JsonNode cd : items(f, "conditions")) perCondition |= cd.hasNonNull("aggregation");
        JsonNode ho0 = f.get("holdout"), ns0 = f.get("namespace");
        if (key.isEmpty() && (!perCondition || (ho0 != null && ho0.isObject()) || (ns0 != null && ns0.isObject()))) return off("no_bucketing_key");
        JsonNode ho = f.get("holdout");
        if (ho != null && ho.isObject() && hash01("holdout." + str(ho, "salt") + "." + key) * 100 < num(ho, "percentage")) return off("holdout");
        JsonNode ns = f.get("namespace");
        if (ns != null && ns.isObject() && num(ns, "total") > 0) {
            long b = (long) (hash01("namespace." + str(ns, "salt") + "." + key) * num(ns, "total"));
            if (b < num(ns, "start") || b >= num(ns, "end")) return off("namespace_excluded");
        }
        int i = -1, skipped = 0, total = 0;
        for (JsonNode cond : items(f, "conditions")) {
            i++;
            total++;
            String ckey = cond.hasNonNull("aggregation") ? unitKey(cond.get("aggregation"), c) : key;
            if (ckey.isEmpty()) {
                skipped++;
                continue;
            }
            List<JsonNode> filters = new ArrayList<>();
            items(cond, "filters").forEach(filters::add);
            boolean all = true, anyUndecidable = false;
            for (JsonNode fl : filters) {
                if (undecidable(fl, resolved)) anyUndecidable = true;
                else all &= filterMatches(fl, c, resolved);
            }
            if (!all) continue;
            if (anyUndecidable) return off(REQUIRES_SERVER, i);
            FlagResult stuck = cond.hasNonNull("aggregation") ? null : sticky(f, cond, i, c); // the store keys on the flag-level unit
            if (stuck != null) return stuck;
            if (hash01(str(f, "key") + "." + str(f, "salt") + "." + ckey) * 100 >= num(cond, "rollout_pct")) {
                if (f.path("early_exit").asBoolean(false)) return off("out_of_rollout", i);
                continue;
            }
            if (str(f, "type").equals("multivariate") && items(f, "variants").iterator().hasNext()) {
                JsonNode v = pickVariant(f, cond, ckey);
                String vk = str(v, "key");
                return new FlagResult(vk, "condition_match", i, vk, v.get("payload"));
            }
            return new FlagResult(Boolean.TRUE, "condition_match", i, null, str(f, "type").equals("json") ? f.get("payload") : null);
        }
        return off(total > 0 && skipped == total ? "no_bucketing_key" : "no_condition_match");
    }

    private static List<String> deps(JsonNode f) {
        List<String> d = new ArrayList<>();
        for (JsonNode p : items(f, "prerequisites")) d.add(str(p, "flag"));
        for (JsonNode c : items(f, "conditions"))
            for (JsonNode fl : items(c, "filters")) if (str(fl, "type").equals("flag")) d.add(str(fl, "key"));
        return d;
    }

    /** Evaluates every flag, resolving prerequisites and {@code flag} filters depth-first. */
    public static Map<String, FlagResult> evaluateAll(List<JsonNode> flags, FlagContext c) {
        Map<String, JsonNode> byKey = new HashMap<>();
        for (JsonNode f : flags) byKey.put(str(f, "key"), f);
        Map<String, FlagResult> out = new HashMap<>();
        Map<String, Integer> state = new HashMap<>(); // 1 = visiting, 2 = done
        for (JsonNode f : flags) visit(str(f, "key"), byKey, out, state, c);
        return out;
    }

    private static void visit(String key, Map<String, JsonNode> byKey, Map<String, FlagResult> out, Map<String, Integer> state, FlagContext c) {
        JsonNode f = byKey.get(key);
        if (f == null || state.containsKey(key)) return;
        state.put(key, 1);
        boolean cyc = false;
        for (String dep : deps(f)) {
            visit(dep, byKey, out, state, c);
            FlagResult d = out.get(dep);
            if (state.getOrDefault(dep, 0) == 1 || (d != null && d.reason().equals("prerequisite_cycle"))) cyc = true;
        }
        out.put(key, cyc ? off("prerequisite_cycle") : evaluate(f, c, out));
        state.put(key, 2);
    }
}

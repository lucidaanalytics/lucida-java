package dev.lucida;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Spec v2 operators (testdata/flags/README.md, "Spec v2"); port of go/internal/flageval/ops_v2.go. */
final class OpsV2 {
    private static final Pattern REL = Pattern.compile("^-?(\\d{1,6})([hdwmy])$");
    private static final Pattern RFC3339 = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})$");
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private OpsV2() {}

    static Long relativeSeconds(String s) {
        Matcher m = REL.matcher(s.strip());
        if (!m.matches()) return null;
        long unit = switch (m.group(2)) {
            case "h" -> 3600L;
            case "d" -> 86_400L;
            case "w" -> 7 * 86_400L;
            case "m" -> 30 * 86_400L;
            default -> 365 * 86_400L;
        };
        return Long.parseLong(m.group(1)) * unit;
    }

    /** The evaluation clock: {@code Context.now} (RFC 3339) when it parses, else the wall clock. */
    static Instant parseNow(String s) {
        Instant t = s == null ? null : rfc(s);
        return t != null ? t : Instant.now();
    }

    private static Instant rfc(String s) {
        if (!RFC3339.matcher(s).matches()) return null;
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Instant instant(JsonNode v, Instant now, boolean rel) {
        if (v.isNumber()) {
            double d = v.asDouble();
            return Instant.ofEpochSecond((long) Math.floor(d), (long) ((d - Math.floor(d)) * 1e9));
        }
        if (!v.isTextual()) return null;
        String s = v.asText().strip();
        if (rel) {
            Long secs = relativeSeconds(s);
            if (secs != null) return now.minusSeconds(secs);
        }
        Instant t = rfc(s);
        if (t != null) return t;
        if (DATE.matcher(s).matches()) {
            try {
                return LocalDate.parse(s).atStartOfDay().toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        return null;
    }

    private static int cmpParts(List<BigInteger> a, List<BigInteger> b) {
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            int c = (i < a.size() ? a.get(i) : BigInteger.ZERO).compareTo(i < b.size() ? b.get(i) : BigInteger.ZERO);
            if (c != 0) return c;
        }
        return 0;
    }

    private static List<BigInteger> of(long... v) {
        List<BigInteger> out = new ArrayList<>();
        for (long x : v) out.add(BigInteger.valueOf(x));
        return out;
    }

    /** Half-open [lo, hi) of a tilde, caret or wildcard value; null when the value is unusable. */
    static List<List<BigInteger>> semverRange(String op, JsonNode want) {
        if (!want.isTextual()) return null;
        String s = want.asText().strip();
        if (s.startsWith("v")) s = s.substring(1);
        String[] parts = s.split("\\.", -1);
        if (op.equals("semver_wildcard")) {
            String last = parts[parts.length - 1];
            if (!last.equals("*") && !last.equals("x") && !last.equals("X")) return null;
            if (parts.length == 1) return List.of(of(0), of(1L << 30));
            List<BigInteger> lo = Evaluator.semver(Evaluator.textNode(String.join(".", java.util.Arrays.copyOf(parts, parts.length - 1))));
            if (lo == null) return null;
            List<BigInteger> hi = new ArrayList<>(lo);
            hi.set(hi.size() - 1, hi.get(hi.size() - 1).add(BigInteger.ONE));
            return List.of(lo, hi);
        }
        List<BigInteger> p = Evaluator.semver(want);
        if (p == null) return null;
        p = new ArrayList<>(p);
        while (p.size() < 3) p.add(BigInteger.ZERO);
        BigInteger a = p.get(0), b = p.get(1), c = p.get(2), one = BigInteger.ONE;
        List<BigInteger> hi;
        if (op.equals("semver_tilde") && parts.length == 1) hi = List.of(a.add(one), BigInteger.ZERO, BigInteger.ZERO);
        else if (op.equals("semver_tilde") || (a.signum() == 0 && b.signum() > 0)) hi = List.of(a, b.add(one), BigInteger.ZERO);
        else if (a.signum() > 0) hi = List.of(a.add(one), BigInteger.ZERO, BigInteger.ZERO);
        else hi = List.of(BigInteger.ZERO, BigInteger.ZERO, c.add(one));
        return List.of(p, hi);
    }

    /** Applies a v2 operator to a set property. Unknown operators never match. */
    static boolean match(String op, JsonNode prop, JsonNode want, Instant now) {
        switch (op) {
            case "icontains", "not_icontains", "starts_with", "not_starts_with", "ends_with", "not_ends_with" -> {
                if (!prop.isTextual() || !want.isTextual()) return false;
                String s = prop.asText(), w = want.asText();
                if (op.endsWith("icontains")) return s.toLowerCase(Locale.ROOT).contains(w.toLowerCase(Locale.ROOT)) == op.equals("icontains");
                if (op.endsWith("starts_with")) return s.startsWith(w) == op.equals("starts_with");
                return s.endsWith(w) == op.equals("ends_with");
            }
            case "between", "not_between" -> {
                Double x = Evaluator.toFloat(prop);
                if (!want.isArray() || want.size() != 2 || x == null || !want.get(0).isNumber() || !want.get(1).isNumber()) return false;
                return (x >= want.get(0).asDouble() && x <= want.get(1).asDouble()) == op.equals("between");
            }
            case "is_date_before", "is_date_after" -> {
                Instant p = instant(prop, now, false), w = instant(want, now, true);
                if (p == null || w == null) return false;
                return op.equals("is_date_before") ? p.isBefore(w) : p.isAfter(w);
            }
            case "semver_eq", "semver_neq", "semver_gt", "semver_gte", "semver_lt", "semver_lte" -> {
                Integer c = Evaluator.cmpSemver(prop, want);
                if (c == null) return false;
                return switch (op) {
                    case "semver_eq" -> c == 0;
                    case "semver_neq" -> c != 0;
                    case "semver_gt" -> c > 0;
                    case "semver_gte" -> c >= 0;
                    case "semver_lt" -> c < 0;
                    default -> c <= 0;
                };
            }
            case "semver_tilde", "semver_caret", "semver_wildcard" -> {
                List<BigInteger> v = Evaluator.semver(prop);
                List<List<BigInteger>> r = semverRange(op, want);
                return v != null && r != null && cmpParts(v, r.get(0)) >= 0 && cmpParts(v, r.get(1)) < 0;
            }
            default -> {
                return false;
            }
        }
    }
}

package dev.lucida;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A rendered AI Config variation (LC-FLG-25, P-M49): completion mode ({@code messages}) or agent mode
 * ({@code instructions} plus {@code tools}). {@code enabled} is false when the flag served nothing and this is
 * the fallback. The SDK only selects and renders it; evaluating model output is Watchtower's job. Mirrors
 * {@code packages/sdk-core/src/ai-config.ts}.
 */
public record AiConfig(
        boolean enabled,
        String variation,
        Integer version,
        String mode,
        String model,
        String provider,
        List<Map<String, String>> messages,
        String instructions,
        List<Map<String, Object>> tools,
        Map<String, Object> parameters) {

    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([\\w.-]+)\\s*\\}\\}");
    private static final ObjectMapper M = new ObjectMapper();

    /** A completion-mode fallback. */
    public static AiConfig completion(String model, List<Map<String, String>> messages) {
        return new AiConfig(false, null, null, "completion", model, null, messages, null, List.of(), Map.of());
    }

    /** An agent-mode fallback. */
    public static AiConfig agent(String model, String instructions, List<Map<String, Object>> tools) {
        return new AiConfig(false, null, null, "agent", model, null, List.of(), instructions, tools, Map.of());
    }

    static String fill(String s, Map<String, String> vars) {
        Matcher m = VAR.matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String v = vars.get(m.group(1));
            m.appendReplacement(b, Matcher.quoteReplacement(v != null ? v : m.group(0)));
        }
        m.appendTail(b);
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static <T> T convert(JsonNode n, Class<?> type) {
        return (T) M.convertValue(n, type);
    }

    /** The config served by {@code r} (or {@code fallback}) with {@code {{var}}} placeholders filled from {@code vars}. */
    public static AiConfig from(FlagResult r, AiConfig fallback, Map<String, String> vars) {
        Map<String, String> v = vars == null ? Map.of() : vars;
        JsonNode p = r == null || r.variant() == null ? null : r.payload();
        AiConfig base;
        if (p != null && p.path("model").isTextual()) {
            List<Map<String, String>> msgs = p.path("messages").isArray() ? convert(p.get("messages"), List.class) : List.of();
            List<Map<String, Object>> tools = p.path("tools").isArray() ? convert(p.get("tools"), List.class) : List.of();
            Map<String, Object> params = p.path("parameters").isObject() ? convert(p.get("parameters"), Map.class) : Map.of();
            base = new AiConfig(
                    true,
                    r.variant(),
                    p.path("version").isInt() ? p.get("version").intValue() : null,
                    "agent".equals(p.path("mode").asText()) ? "agent" : "completion",
                    p.get("model").asText(),
                    p.path("provider").isTextual() ? p.get("provider").asText() : null,
                    msgs,
                    p.path("instructions").isTextual() ? p.get("instructions").asText() : null,
                    tools,
                    params);
        } else {
            String mode = fallback.mode() != null ? fallback.mode() : (fallback.instructions() != null ? "agent" : "completion");
            base = new AiConfig(false, null, null, mode, fallback.model(), fallback.provider(),
                    fallback.messages() == null ? List.of() : fallback.messages(), fallback.instructions(),
                    fallback.tools() == null ? List.of() : fallback.tools(),
                    fallback.parameters() == null ? Map.of() : fallback.parameters());
        }
        List<Map<String, String>> filled = new ArrayList<>();
        for (Map<String, String> m : base.messages()) {
            Map<String, String> c = new LinkedHashMap<>();
            c.put("role", m.get("role"));
            c.put("content", fill(String.valueOf(m.get("content")), v));
            filled.add(c);
        }
        return new AiConfig(base.enabled(), base.variation(), base.version(), base.mode(), base.model(), base.provider(),
                filled, base.instructions() == null ? null : fill(base.instructions(), v), base.tools(), base.parameters());
    }
}

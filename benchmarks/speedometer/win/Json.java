/**
 * Minimal JSON reader/writer for the Windows Speedometer harness.
 *
 * Deliberately hand-rolled rather than pulled from a dependency: the harness is
 * run with single-file `java` source mode (no build, no classpath), so it cannot
 * take a library. Scope is exactly what CDP needs — parse a reply, escape a
 * string into a request. It is a parser, not a validator: malformed input throws
 * rather than being diagnosed.
 */
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Json {
    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
    }

    /** Parse a JSON document into Map / List / String / Double / Boolean / null. */
    static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object value = p.value();
        p.ws();
        if (p.pos != text.length()) {
            throw new IllegalArgumentException("Trailing JSON at offset " + p.pos);
        }
        return value;
    }

    /** Escape a Java string into a quoted JSON string literal. */
    static String quote(String s) {
        StringBuilder out = new StringBuilder(s.length() + 16).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    // Control characters must be escaped; everything else (including
                    // non-ASCII) is legal raw JSON and is sent as UTF-8.
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /** Serialize Map / List / String / Number / Boolean / null back to JSON. */
    static String write(Object value, String indent) {
        StringBuilder out = new StringBuilder();
        write(value, indent, "", out);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(Object value, String indent, String current, StringBuilder out) {
        String next = current + indent;
        String nl = indent.isEmpty() ? "" : "\n";
        String sep = indent.isEmpty() ? "," : ",\n" + next;
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append('{').append(nl).append(indent.isEmpty() ? "" : next);
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) map).entrySet()) {
                if (!first) {
                    out.append(sep);
                }
                first = false;
                out.append(quote(e.getKey())).append(indent.isEmpty() ? ":" : ": ");
                write(e.getValue(), indent, next, out);
            }
            out.append(nl).append(indent.isEmpty() ? "" : current).append('}');
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append('[').append(nl).append(indent.isEmpty() ? "" : next);
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    out.append(sep);
                }
                first = false;
                write(item, indent, next, out);
            }
            out.append(nl).append(indent.isEmpty() ? "" : current).append(']');
        } else if (value instanceof String s) {
            out.append(quote(s));
        } else if (value instanceof Double d) {
            // Emit whole doubles as integers so scores/counts read naturally.
            if (d == Math.floor(d) && !d.isInfinite()) {
                out.append((long) (double) d);
            } else {
                out.append(d);
            }
        } else if (value == null) {
            out.append("null");
        } else {
            out.append(value);
        }
    }

    // --- recursive descent ---

    private Object value() {
        char c = peek();
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        expect('{');
        Map<String, Object> map = new LinkedHashMap<>();
        ws();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            ws();
            String key = string();
            ws();
            expect(':');
            ws();
            map.put(key, value());
            ws();
            char c = next();
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw new IllegalArgumentException("Expected , or } at offset " + pos);
            }
        }
    }

    private List<Object> array() {
        expect('[');
        List<Object> list = new ArrayList<>();
        ws();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            ws();
            list.add(value());
            ws();
            char c = next();
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw new IllegalArgumentException("Expected , or ] at offset " + pos);
            }
        }
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char esc = next();
            switch (esc) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    out.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                }
                default -> throw new IllegalArgumentException("Bad escape \\" + esc);
            }
        }
    }

    private Double number() {
        int start = pos;
        while (pos < src.length() && "+-.eE0123456789".indexOf(src.charAt(pos)) >= 0) {
            pos++;
        }
        if (start == pos) {
            throw new IllegalArgumentException("Expected a value at offset " + pos);
        }
        return Double.valueOf(src.substring(start, pos));
    }

    private Object literal(String word, Object result) {
        if (!src.startsWith(word, pos)) {
            throw new IllegalArgumentException("Expected " + word + " at offset " + pos);
        }
        pos += word.length();
        return result;
    }

    private void ws() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private char peek() {
        if (pos >= src.length()) {
            throw new IllegalArgumentException("Unexpected end of JSON");
        }
        return src.charAt(pos);
    }

    private char next() {
        char c = peek();
        pos++;
        return c;
    }

    private void expect(char c) {
        if (next() != c) {
            throw new IllegalArgumentException("Expected '" + c + "' at offset " + (pos - 1));
        }
    }
}

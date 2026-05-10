package com.deployflow.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small JSON reader/writer used to keep the project dependency-free.
 * It supports the JSON value types needed by the DeployFlow API.
 */
public final class Json {
    private Json() {
    }

    public static Object parse(String input) {
        return new Parser(input).parseDocument();
    }

    public static String stringify(Object value) {
        StringBuilder builder = new StringBuilder();
        writeValue(builder, value);
        return builder.toString();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object value) {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        return List.of();
    }

    public static String stringValue(Map<String, Object> object, String key, String fallback) {
        Object value = object.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    public static int intValue(Map<String, Object> object, String key, int fallback) {
        Object value = object.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static boolean booleanValue(Map<String, Object> object, String key, boolean fallback) {
        Object value = object.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return fallback;
    }

    public static List<String> stringList(Map<String, Object> object, String key) {
        List<String> result = new ArrayList<>();
        for (Object item : asList(object.get(key))) {
            if (item != null && !String.valueOf(item).isBlank()) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    private static void writeValue(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof String text) {
            writeString(builder, text);
        } else if (value instanceof Number || value instanceof Boolean) {
            builder.append(value);
        } else if (value instanceof Map<?, ?> map) {
            builder.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    builder.append(',');
                }
                writeString(builder, String.valueOf(entry.getKey()));
                builder.append(':');
                writeValue(builder, entry.getValue());
                first = false;
            }
            builder.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            builder.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    builder.append(',');
                }
                writeValue(builder, item);
                first = false;
            }
            builder.append(']');
        } else if (value.getClass().isArray()) {
            builder.append('[');
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                if (i > 0) {
                    builder.append(',');
                }
                writeValue(builder, java.lang.reflect.Array.get(value, i));
            }
            builder.append(']');
        } else {
            writeString(builder, String.valueOf(value));
        }
    }

    private static void writeString(StringBuilder builder, String text) {
        builder.append('"');
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (ch < 32) {
                        builder.append(String.format("\\u%04x", (int) ch));
                    } else {
                        builder.append(ch);
                    }
                }
            }
        }
        builder.append('"');
    }

    private static final class Parser {
        private final String input;
        private int position;

        private Parser(String input) {
            this.input = input == null ? "" : input;
        }

        private Object parseDocument() {
            Object value = parseValue();
            skipWhitespace();
            if (position != input.length()) {
                throw new IllegalArgumentException("Unexpected token at position " + position);
            }
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (position >= input.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON input");
            }
            char ch = input.charAt(position);
            return switch (ch) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't', 'f' -> parseBoolean();
                case 'n' -> parseNull();
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> object = new LinkedHashMap<>();
            expect('{');
            skipWhitespace();
            if (peek('}')) {
                position++;
                return object;
            }
            while (position < input.length()) {
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                object.put(key, value);
                skipWhitespace();
                if (peek('}')) {
                    position++;
                    return object;
                }
                expect(',');
                skipWhitespace();
            }
            throw new IllegalArgumentException("Unexpected end of JSON object");
        }

        private List<Object> parseArray() {
            List<Object> array = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (peek(']')) {
                position++;
                return array;
            }
            while (position < input.length()) {
                array.add(parseValue());
                skipWhitespace();
                if (peek(']')) {
                    position++;
                    return array;
                }
                expect(',');
                skipWhitespace();
            }
            throw new IllegalArgumentException("Unexpected end of JSON array");
        }

        private String parseString() {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (position < input.length()) {
                char ch = input.charAt(position++);
                if (ch == '"') {
                    return builder.toString();
                }
                if (ch == '\\') {
                    if (position >= input.length()) {
                        throw new IllegalArgumentException("Invalid escape at end of string");
                    }
                    char escaped = input.charAt(position++);
                    switch (escaped) {
                        case '"' -> builder.append('"');
                        case '\\' -> builder.append('\\');
                        case '/' -> builder.append('/');
                        case 'b' -> builder.append('\b');
                        case 'f' -> builder.append('\f');
                        case 'n' -> builder.append('\n');
                        case 'r' -> builder.append('\r');
                        case 't' -> builder.append('\t');
                        case 'u' -> {
                            if (position + 4 > input.length()) {
                                throw new IllegalArgumentException("Invalid unicode escape");
                            }
                            String hex = input.substring(position, position + 4);
                            builder.append((char) Integer.parseInt(hex, 16));
                            position += 4;
                        }
                        default -> throw new IllegalArgumentException("Unsupported escape: \\" + escaped);
                    }
                } else {
                    builder.append(ch);
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }

        private Boolean parseBoolean() {
            if (input.startsWith("true", position)) {
                position += 4;
                return Boolean.TRUE;
            }
            if (input.startsWith("false", position)) {
                position += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("Invalid boolean value at position " + position);
        }

        private Object parseNull() {
            if (input.startsWith("null", position)) {
                position += 4;
                return null;
            }
            throw new IllegalArgumentException("Invalid null value at position " + position);
        }

        private Number parseNumber() {
            int start = position;
            if (peek('-')) {
                position++;
            }
            while (position < input.length() && Character.isDigit(input.charAt(position))) {
                position++;
            }
            if (peek('.')) {
                position++;
                while (position < input.length() && Character.isDigit(input.charAt(position))) {
                    position++;
                }
            }
            if (position < input.length() && (input.charAt(position) == 'e' || input.charAt(position) == 'E')) {
                position++;
                if (position < input.length() && (input.charAt(position) == '+' || input.charAt(position) == '-')) {
                    position++;
                }
                while (position < input.length() && Character.isDigit(input.charAt(position))) {
                    position++;
                }
            }
            String token = input.substring(start, position);
            if (token.isEmpty() || "-".equals(token)) {
                throw new IllegalArgumentException("Invalid number at position " + start);
            }
            if (token.contains(".") || token.contains("e") || token.contains("E")) {
                return Double.parseDouble(token);
            }
            try {
                return Integer.parseInt(token);
            } catch (NumberFormatException ignored) {
                return Long.parseLong(token);
            }
        }

        private void expect(char expected) {
            skipWhitespace();
            if (position >= input.length() || input.charAt(position) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' at position " + position);
            }
            position++;
        }

        private boolean peek(char expected) {
            return position < input.length() && input.charAt(position) == expected;
        }

        private void skipWhitespace() {
            while (position < input.length() && Character.isWhitespace(input.charAt(position))) {
                position++;
            }
        }
    }
}

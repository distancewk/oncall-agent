package org.example.config;

import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal W3C trace-context propagation without putting identifiers in metrics tags. */
public final class TraceContext {

    public static final String TRACEPARENT_HEADER = "traceparent";
    private static final Pattern TRACEPARENT = Pattern.compile(
            "^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");
    private static final HexFormat HEX = HexFormat.of();
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private TraceContext() {
    }

    public static Context from(String traceparent) {
        if (traceparent != null) {
            Matcher matcher = TRACEPARENT.matcher(traceparent.trim().toLowerCase(Locale.ROOT));
            if (matcher.matches() && !allZero(matcher.group(1)) && !allZero(matcher.group(2))) {
                return new Context(matcher.group(1), randomHex(8));
            }
        }
        return new Context(randomHex(16), randomHex(8));
    }

    public record Context(String traceId, String spanId) {
        public String traceparent() {
            return "00-" + traceId + "-" + spanId + "-01";
        }
    }

    private static String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        do {
            RANDOM.nextBytes(value);
        } while (allZero(value));
        return HEX.formatHex(value);
    }

    private static boolean allZero(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != '0') {
                return false;
            }
        }
        return true;
    }

    private static boolean allZero(byte[] value) {
        for (byte current : value) {
            if (current != 0) {
                return false;
            }
        }
        return true;
    }
}

package org.example.service;

import okhttp3.Request;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/** Tencent Cloud TC3-HMAC-SHA256 request signer for CLS-compatible endpoints. */
public final class ClsRequestSigner {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneOffset.UTC);
    private static final String TERMINATOR = "tc3_request";

    private ClsRequestSigner() {
    }

    public static void sign(Request.Builder builder, URI uri, String method, String body,
                            String secretId, String secretKey, String region, String service,
                            Instant now) {
        long timestamp = now.getEpochSecond();
        String date = DATE.format(now);
        String host = uri.getHost();
        String canonicalUri = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
        String query = canonicalQuery(uri);
        String payloadHash = sha256(body == null ? "" : body);
        String canonicalHeaders = "content-type:application/json\n" + "host:" + host + "\n";
        String signedHeaders = "content-type;host";
        String canonicalRequest = method.toUpperCase() + "\n" + canonicalUri + "\n"
                + query + "\n" + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;
        String credentialScope = date + "/" + region + "/" + service + "/" + TERMINATOR;
        String stringToSign = "TC3-HMAC-SHA256\n" + timestamp + "\n" + credentialScope + "\n"
                + sha256(canonicalRequest);
        byte[] secretDate = hmac(("TC3" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] secretService = hmac(secretDate, service);
        byte[] secretSigning = hmac(secretService, TERMINATOR);
        String signature = HexFormat.of().formatHex(hmac(secretSigning, stringToSign));
        String authorization = "TC3-HMAC-SHA256 Credential=" + secretId + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
        builder.header("Authorization", authorization)
                .header("X-TC-Timestamp", String.valueOf(timestamp))
                .header("X-TC-Region", region)
                .header("X-TC-Version", "2020-10-16")
                .header("X-TC-Action", "SearchLog")
                .header("Content-Type", "application/json");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String canonicalQuery(URI uri) {
        if (uri.getRawQuery() == null || uri.getRawQuery().isBlank()) {
            return "";
        }
        List<String> pairs = new ArrayList<>();
        for (String item : uri.getRawQuery().split("&", -1)) {
            String[] parts = item.split("=", 2);
            String key = encode(decode(parts[0]));
            String value = parts.length == 1 ? "" : encode(decode(parts[1]));
            pairs.add(key + "=" + value);
        }
        return pairs.stream().sorted(Comparator.naturalOrder()).collect(Collectors.joining("&"));
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("CLS 查询参数不是合法的 URL 编码", e);
        }
    }

    private static String encode(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder result = new StringBuilder(bytes.length);
        for (byte current : bytes) {
            int unsigned = current & 0xff;
            if ((unsigned >= 'a' && unsigned <= 'z')
                    || (unsigned >= 'A' && unsigned <= 'Z')
                    || (unsigned >= '0' && unsigned <= '9')
                    || unsigned == '-' || unsigned == '_' || unsigned == '.' || unsigned == '~') {
                result.append((char) unsigned);
            } else {
                result.append('%');
                result.append(Character.toUpperCase(Character.forDigit(unsigned >>> 4, 16)));
                result.append(Character.toUpperCase(Character.forDigit(unsigned & 0x0f, 16)));
            }
        }
        return result.toString();
    }

    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("CLS 请求签名失败", e);
        }
    }
}

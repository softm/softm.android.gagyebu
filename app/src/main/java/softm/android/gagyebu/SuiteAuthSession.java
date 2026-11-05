package softm.android.gagyebu;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/** Strict, independently testable boundary for browser-to-app authentication. */
final class SuiteAuthSession {
    static final long MAX_AGE_MS = 5 * 60 * 1000;
    static final String RETURN_URL = "https://softm.github.io/account/android/gagyebu/";
    static final String FALLBACK_URL = "softm.android.gagyebu://auth/callback";

    static boolean proof(String value) { return value != null && value.matches("[A-Za-z0-9_-]{43}"); }

    static Map<String, String> readReturn(String value, String expectedRequest, long createdAt, long now) {
        if (!proof(expectedRequest) || createdAt <= 0 || now < createdAt || now - createdAt > MAX_AGE_MS) return null;
        try {
            URI uri = new URI(value);
            String address = new URI(uri.getScheme(), uri.getRawAuthority(), uri.getPath(), null, null).toString();
            if ((!RETURN_URL.equals(address) && !FALLBACK_URL.equals(address)) || uri.getRawQuery() != null) return null;
            String fragment = uri.getRawFragment();
            if (fragment == null || fragment.length() > 256) return null;
            Map<String, String> fields = new HashMap<>();
            for (String item : fragment.split("&")) {
                String[] pair = item.split("=", 2);
                if (pair.length != 2) return null;
                String key = URLDecoder.decode(pair[0], "UTF-8"), data = URLDecoder.decode(pair[1], "UTF-8");
                if (!key.equals("request") && !key.equals("code") && !key.equals("error")) return null;
                if (fields.put(key, data) != null) return null;
            }
            if (!expectedRequest.equals(fields.get("request")) || fields.size() != 2) return null;
            if (!proof(fields.get("code")) && !"cancelled".equals(fields.get("error"))) return null;
            return fields;
        } catch (Exception ignored) { return null; }
    }

    static byte[] challenge(String verifier) {
        if (!proof(verifier)) throw new IllegalArgumentException("Invalid proof");
        try { return MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}

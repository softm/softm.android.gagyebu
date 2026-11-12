package softm.android.gagyebu;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.HttpsURLConnection;

/**
 * Cloudflare-only account session for Android gagyebu.
 * The browser authorizes with Google/Kakao/Naver; the native app receives
 * a short-lived PKCE-bound one-time code and stores the site-bound access
 * token encrypted with Android Keystore. Never exchanges Firebase tokens.
 *
 * Legacy user files and the previous Firebase SharedPreferences are left
 * untouched so no existing financial records are silently migrated/deleted.
 */
final class SuiteAuthManager {
    private static final String SITE = "gagyebu";
    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final String WORKER = "https://softm-hannun-account.softm.workers.dev";
    private final Activity activity;
    private final Runnable changed;
    private final SharedPreferences preferences;
    private final LocalCipher cipher = new LocalCipher();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private String status = "idle", error = "";
    private int generation;
    private boolean destroyed;

    SuiteAuthManager(Activity activity, Runnable changed) {
        this.activity = activity;
        this.changed = changed;
        preferences = activity.getSharedPreferences("hannun-cloudflare-auth", Activity.MODE_PRIVATE);
        if (pendingFresh()) status = "waiting";
        else clearPending();
    }

    JSONObject snapshot() throws Exception {
        if ("waiting".equals(status) && !pendingFresh()) fail("로그인 시간이 만료됐습니다. 다시 시도해 주세요.");
        String uid = activeUid();
        // JSONObject.NULL is deliberately used for the signed-out account.
        JSONObject out = new JSONObject().put("status", status).put("error", error).put("user", JSONObject.NULL);
        if (!uid.isEmpty()) {
            JSONObject profile = new JSONObject().put("uid", uid)
                .put("displayName", preferences.getString("displayName", "한눈 계정"))
                .put("photoURL", preferences.getString("photoURL", ""))
                .put("isAnonymous", false).put("providerData", new JSONArray());
            out.put("user", profile);
        }
        return out;
    }

    void login(String mode) {
        if ("preparing".equals(status) || "waiting".equals(status) || "exchanging".equals(status)) return;
        int attempt = ++generation;
        clearPending(); status = "preparing"; error = ""; notifyChanged();
        try {
            String verifier = secret(), requestId = secret();
            String challenge = encode(SuiteAuthSession.challenge(verifier));
            Uri uri = Uri.parse(WORKER + "/connect").buildUpon()
                .appendQueryParameter("site", SITE).appendQueryParameter("origin", ORIGIN)
                .appendQueryParameter("request", requestId).appendQueryParameter("challenge", challenge).build();
            preferences.edit().putString("request", requestId).putString("verifier", verifier)
                .putLong("created", System.currentTimeMillis()).apply();
            status = "waiting";
            activity.startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE));
            notifyChanged();
        } catch (Exception failure) {
            if (attempt == generation) fail("Cloudflare 로그인 브라우저를 열지 못했습니다.");
        }
    }

    void handleIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null) return;
        String link = intent.getData().toString();
        intent.setData(null);
        Map<String, String> callback = SuiteAuthSession.readReturn(link,
            preferences.getString("request", ""), preferences.getLong("created", 0), System.currentTimeMillis());
        if (callback == null || "exchanging".equals(status)) return;
        String verifier = preferences.getString("verifier", "");
        clearPending();
        if ("cancelled".equals(callback.get("error"))) { fail("로그인을 취소했습니다."); return; }
        if (!SuiteAuthSession.proof(verifier)) { fail("로그인 요청이 만료되었습니다."); return; }
        int attempt = ++generation;
        status = "exchanging"; error = ""; notifyChanged();
        worker.execute(() -> {
            try {
                JSONObject body = new JSONObject().put("site", SITE).put("origin", ORIGIN)
                    .put("code", callback.get("code")).put("verifier", verifier);
                JSONObject response = request("/api/connect/exchange", body, null);
                String token = response.getString("accessToken");
                String uid = response.getString("uid");
                int seconds = response.getInt("expiresIn");
                if (!SuiteAuthSession.proof(token) || uid.isEmpty() || seconds <= 0 || seconds > 3600)
                    throw new IllegalStateException("잘못된 로그인 세션");
                String encrypted = cipher.encrypt(token);
                long expiry = System.currentTimeMillis() + seconds * 1000L;
                onMain(() -> {
                    if (attempt != generation) return;
                    preferences.edit().putString("access", encrypted).putString("uid", uid)
                        .putLong("expiresAt", expiry)
                        .putString("displayName", response.optString("displayName", "한눈 계정"))
                        .putString("photoURL", response.optString("photoURL", ""))
                        .apply();
                    status = "idle"; error = ""; notifyChanged();
                });
            } catch (Exception failure) {
                onMain(() -> { if (attempt == generation) fail("Cloudflare 계정 연결에 실패했습니다. 다시 로그인해 주세요."); });
            }
        });
    }

    private String activeUid() {
        if (preferences.getLong("expiresAt", 0) <= System.currentTimeMillis()) return "";
        return preferences.getString("uid", "");
    }

    private String accessToken() {
        if (activeUid().isEmpty()) return "";
        try {
            String token = cipher.decrypt(preferences.getString("access", ""));
            return SuiteAuthSession.proof(token) ? token : "";
        } catch (Exception invalidKeystore) { return ""; }
    }

    void refresh() {
        if ("waiting".equals(status) && !pendingFresh()) {
            fail("로그인 시간이 만료됐습니다.");
            return;
        }
        notifyChanged();
    }

    interface LedgerReply { void finish(JSONObject result); }
    void syncLedger(JSONObject body, LedgerReply reply) {
        String uid = activeUid(), token = accessToken();
        if (uid.isEmpty() || token.isEmpty() || !uid.equals(body.optString("uid"))) {
            reply.finish(null); return;
        }
        worker.execute(() -> {
            JSONObject value = null;
            try { value = request("/api/apps/gagyebu/ledger", body, token); }
            catch (Exception ignored) { /* Retain the offline ledger and retry later. */ }
            JSONObject result = value;
            onMain(() -> reply.finish(uid.equals(activeUid()) ? result : null));
        });
    }

    void cancel() { ++generation; clearPending(); status = "idle"; error = ""; notifyChanged(); }
    void logout() {
        ++generation; clearPending();
        String token = accessToken();
        preferences.edit().remove("access").remove("uid").remove("expiresAt")
            .remove("displayName").remove("photoURL").apply();
        status = "idle"; error = ""; notifyChanged();
        if (!token.isEmpty()) worker.execute(() -> {
            try { request("/api/apps/gagyebu/logout", new JSONObject(), token); }
            catch (Exception ignored) { /* Native token is already invalidated locally. */ }
        });
    }
    void destroy() { destroyed = true; worker.shutdownNow(); }
    private void clearPending() { preferences.edit().remove("request").remove("verifier").remove("created").apply(); }
    private boolean pendingFresh() {
        long age = System.currentTimeMillis() - preferences.getLong("created", 0);
        return age >= 0 && age <= SuiteAuthSession.MAX_AGE_MS
            && SuiteAuthSession.proof(preferences.getString("request", ""));
    }
    private void fail(String message) { clearPending(); status = "idle"; error = message; notifyChanged(); }
    private void notifyChanged() { if (!destroyed) changed.run(); }
    private void onMain(Runnable action) { activity.runOnUiThread(() -> { if (!destroyed) action.run(); }); }
    private static String secret() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return encode(bytes);
    }
    private static String encode(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE);
    }
    private static JSONObject request(String path, JSONObject body, String token) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(WORKER + path).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(18000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Origin", ORIGIN);
        connection.setRequestProperty("Cache-Control", "no-store");
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        try {
            if (body != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                try (java.io.OutputStream stream = connection.getOutputStream()) {
                    stream.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = connection.getResponseCode();
            if (status != 200) throw new IllegalStateException("Cloudflare 응답 " + status);
            try (InputStream stream = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    if (output.size() > 4 * 1024 * 1024) throw new IllegalStateException("응답 크기 초과");
                }
                return new JSONObject(output.toString("UTF-8"));
            }
        } finally { connection.disconnect(); }
    }
}

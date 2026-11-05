package softm.android.gagyebu;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidUserException;
import com.google.firebase.auth.FirebaseUser;
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

/** Common identity only. No ledger, capture or Firestore access. Tokens stay native. */
final class SuiteAuthManager {
    private static final String SITE = "gagyebu", ORIGIN = "https://appassets.androidplatform.net";
    private static final String HUB = "https://softm.github.io/account/";
    private static final String REGISTRY = "https://softm.github.io/shared/platform/v1/registry.json";
    private static final String EXCHANGE = "https://asia-northeast3-softm-hannun-api.cloudfunctions.net/suiteAuth/session/exchange";
    private final Activity activity;
    private final Runnable changed;
    private final SharedPreferences preferences;
    private final FirebaseAuth auth;
    private final FirebaseAuth.AuthStateListener listener;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private String status = "idle", error = "";
    private int generation;
    private boolean destroyed, signingIn;

    SuiteAuthManager(Activity activity, Runnable changed) {
        this.activity = activity; this.changed = changed;
        preferences = activity.getSharedPreferences("hannun-auth", Activity.MODE_PRIVATE);
        FirebaseApp app;
        try { app = FirebaseApp.getInstance("gagyebu-hannun"); }
        catch (IllegalStateException absent) {
            app = FirebaseApp.initializeApp(activity.getApplicationContext(), new FirebaseOptions.Builder()
                .setProjectId(activity.getString(R.string.suite_firebase_project_id))
                .setApplicationId(activity.getString(R.string.suite_firebase_app_id))
                .setApiKey(activity.getString(R.string.suite_firebase_api_key)).build(), "gagyebu-hannun");
        }
        auth = FirebaseAuth.getInstance(app);
        if (pendingFresh()) status = "waiting"; else clearPending();
        listener = value -> {
            if (signingIn) return;
            FirebaseUser user = value.getCurrentUser();
            if (user == null) { preferences.edit().remove("verifiedUid").apply(); notifyChanged(); }
            else if (user.getUid().equals(preferences.getString("verifiedUid", ""))) notifyChanged();
            else verifyUser(user, generation);
        };
        auth.addAuthStateListener(listener);
    }

    JSONObject snapshot() throws Exception {
        if (status.equals("waiting") && !pendingFresh()) fail("로그인 시간이 만료됐습니다. 다시 시도해 주세요.");
        JSONObject out = new JSONObject().put("status", status).put("error", error).put("user", JSONObject.NULL);
        FirebaseUser user = auth.getCurrentUser();
        if (user != null && user.getUid().equals(preferences.getString("verifiedUid", ""))) {
            out.put("user", new JSONObject().put("uid", user.getUid()).put("displayName", user.getDisplayName())
                .put("email", user.getEmail()).put("photoURL", user.getPhotoUrl() == null ? "" : user.getPhotoUrl().toString())
                .put("isAnonymous", false).put("providerData", new JSONArray()));
        }
        return out;
    }

    void login(String mode) {
        if (signingIn || status.equals("preparing") || status.equals("exchanging")) return;
        int attempt = ++generation;
        clearPending(); status = "preparing"; error = ""; notifyChanged();
        worker.execute(() -> {
            try {
                JSONObject site = request(REGISTRY, null).getJSONObject("sites").getJSONObject(SITE);
                JSONObject android = site.getJSONObject("android");
                if (!activity.getPackageName().equals(android.getString("packageName"))
                    || !ORIGIN.equals(android.getString("origin"))
                    || !SuiteAuthSession.RETURN_URL.equals(android.getString("returnUrl"))) throw new IllegalStateException();
                String verifier = secret(), requestId = secret();
                String challenge = encode(SuiteAuthSession.challenge(verifier));
                Uri uri = Uri.parse(HUB).buildUpon().appendQueryParameter("site", SITE).appendQueryParameter("origin", ORIGIN)
                    .appendQueryParameter("transport", "android").appendQueryParameter("mode", "signup".equals(mode) ? "signup" : "login")
                    .appendQueryParameter("request", requestId).appendQueryParameter("challenge", challenge).build();
                onMain(() -> {
                    if (attempt != generation) return;
                    preferences.edit().putString("request", requestId).putString("verifier", verifier)
                        .putLong("created", System.currentTimeMillis()).apply();
                    try { status = "waiting"; activity.startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)); notifyChanged(); }
                    catch (Exception unavailable) { fail("로그인할 브라우저를 열 수 없습니다."); }
                });
            } catch (Exception failure) {
                onMain(() -> { if (attempt == generation) fail("공통 로그인 연결을 확인하지 못했습니다. 인터넷 연결을 확인한 뒤 다시 시도해 주세요."); });
            }
        });
    }

    void handleIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null) return;
        String value = intent.getData().toString(); intent.setData(null);
        Map<String, String> result = SuiteAuthSession.readReturn(value, preferences.getString("request", ""),
            preferences.getLong("created", 0), System.currentTimeMillis());
        if (result == null || status.equals("exchanging")) return;
        String verifier = preferences.getString("verifier", ""); clearPending();
        if ("cancelled".equals(result.get("error"))) { fail("로그인을 취소했습니다."); return; }
        if (!SuiteAuthSession.proof(verifier)) { fail("로그인 요청이 만료됐습니다. 다시 시도해 주세요."); return; }
        int attempt = ++generation; status = "exchanging"; error = ""; notifyChanged();
        worker.execute(() -> {
            try {
                JSONObject body = new JSONObject().put("siteId", SITE).put("origin", ORIGIN)
                    .put("code", result.get("code")).put("verifier", verifier);
                String token = request(EXCHANGE, body).getString("token");
                onMain(() -> {
                    if (attempt != generation) return;
                    signingIn = true;
                    auth.signInWithCustomToken(token).addOnCompleteListener(task -> {
                        signingIn = false;
                        if (attempt != generation) { auth.signOut(); return; }
                        if (destroyed) return;
                        if (task.isSuccessful() && auth.getCurrentUser() != null) verifyUser(auth.getCurrentUser(), attempt);
                        else fail("로그인을 완료하지 못했습니다. 다시 시도해 주세요.");
                    });
                });
            } catch (Exception failure) {
                onMain(() -> { if (attempt == generation) fail("로그인 응답이 만료됐거나 연결이 끊겼습니다. 다시 시도해 주세요."); });
            }
        });
    }

    private void verifyUser(FirebaseUser user, int attempt) {
        user.getIdToken(false).addOnCompleteListener(task -> {
            if (destroyed || attempt != generation || auth.getCurrentUser() == null || !auth.getCurrentUser().getUid().equals(user.getUid())) return;
            if (task.isSuccessful() && SITE.equals(task.getResult().getClaims().get("suite_site"))) {
                preferences.edit().putString("verifiedUid", user.getUid()).apply(); status = "idle"; error = ""; notifyChanged();
            } else if (task.isSuccessful() || task.getException() instanceof FirebaseAuthInvalidUserException) {
                auth.signOut(); fail("가계부 로그인 권한을 다시 확인해 주세요.");
            } else fail("로그인 상태를 확인하지 못했습니다. 연결 후 다시 시도해 주세요.");
        });
    }

    void refresh() {
        FirebaseUser user = auth.getCurrentUser();
        if (user != null) user.reload().addOnFailureListener(failure -> {
            if (!destroyed && failure instanceof FirebaseAuthInvalidUserException) logout();
        });
        notifyChanged();
    }

    interface LedgerReply { void finish(JSONObject result); }
    void syncLedger(JSONObject body, LedgerReply reply) {
        FirebaseUser user=auth.getCurrentUser();
        if(user==null || !user.getUid().equals(body.optString("uid")) || !user.getUid().equals(preferences.getString("verifiedUid",""))) { reply.finish(null); return; }
        user.getIdToken(false).addOnCompleteListener(task -> {
            if(destroyed || !task.isSuccessful() || !SITE.equals(task.getResult().getClaims().get("suite_site"))) { reply.finish(null); return; }
            String token=task.getResult().getToken();
            worker.execute(()->{
                JSONObject result=null;try {result=request(EXCHANGE.replace("/session/exchange","/gagyebu/ledger"),body,token);}catch(Exception ignored){}
                JSONObject value=result;onMain(()->reply.finish(auth.getCurrentUser()!=null && user.getUid().equals(auth.getCurrentUser().getUid())?value:null));
            });
        });
    }

    void cancel() { ++generation; clearPending(); status = "idle"; error = ""; notifyChanged(); }
    void logout() { cancel(); preferences.edit().remove("verifiedUid").apply(); auth.signOut(); }
    void destroy() { destroyed = true; auth.removeAuthStateListener(listener); worker.shutdownNow(); }
    private void clearPending() { preferences.edit().remove("request").remove("verifier").remove("created").apply(); }
    private boolean pendingFresh() { long age = System.currentTimeMillis() - preferences.getLong("created", 0); return age >= 0 && age <= SuiteAuthSession.MAX_AGE_MS && SuiteAuthSession.proof(preferences.getString("request", "")); }
    private void fail(String message) { clearPending(); status = "idle"; error = message; notifyChanged(); }
    private void notifyChanged() { if (!destroyed) changed.run(); }
    private void onMain(Runnable action) { activity.runOnUiThread(() -> { if (!destroyed) action.run(); }); }
    private static String secret() { byte[] value = new byte[32]; new SecureRandom().nextBytes(value); return encode(value); }
    private static String encode(byte[] value) { return Base64.encodeToString(value, Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE); }
    private static JSONObject request(String address, JSONObject body) throws Exception {
        return request(address,body,null);
    }
    private static JSONObject request(String address, JSONObject body,String token) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(address).openConnection();
        if(token!=null)connection.setRequestProperty("Authorization","Bearer "+token);
        connection.setConnectTimeout(12000); connection.setReadTimeout(15000); connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Cache-Control", "no-cache");
        try {
            if (body != null) {
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Origin", ORIGIN); connection.setRequestProperty("Content-Type", "application/json");
                try (java.io.OutputStream out = connection.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            }
            if (connection.getResponseCode() != 200) throw new IllegalStateException("Authentication request failed");
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int size;
                while ((size = in.read(buffer)) != -1) { out.write(buffer, 0, size); if (out.size() > (token==null ? 131072 : 4194304)) throw new IllegalStateException(); }
                return new JSONObject(out.toString("UTF-8"));
            }
        } finally { connection.disconnect(); }
    }
}

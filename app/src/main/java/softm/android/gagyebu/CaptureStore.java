package softm.android.gagyebu;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.Locale;
import java.util.Optional;

public final class CaptureStore {
    private static final String STATUS_NEW = "new";
    private static final String STATUS_IMPORTED = "imported";
    private static final String STATUS_DISMISSED = "dismissed";

    private static volatile CaptureStore instance;

    private final CaptureDatabase database;
    private final LocalCipher cipher = new LocalCipher();

    private CaptureStore(Context context) {
        database = new CaptureDatabase(context.getApplicationContext());
    }

    public static CaptureStore get(Context context) {
        CaptureStore local = instance;
        if (local == null) {
            synchronized (CaptureStore.class) {
                local = instance;
                if (local == null) {
                    local = new CaptureStore(context);
                    instance = local;
                }
            }
        }
        return local;
    }

    public synchronized CaptureResult capture(
        String sourceType,
        String sourcePackage,
        String eventId,
        String sender,
        String title,
        String body,
        long receivedAt
    ) {
        String safeType = "sms".equals(sourceType) ? "sms" : "notification";
        String safePackage = limit(sourcePackage, 200);
        String safeSender = limit(sender, 500);
        String safeTitle = limit(title, 1_000);
        String safeBody = limit(body, 4_000);
        Optional<FinancialMessageParser.Result> parsed =
            FinancialMessageParser.parse(safeSender, safeTitle, safeBody, receivedAt);
        if (parsed.isEmpty()) return CaptureResult.ignored();

        String captureId = fingerprint(
            safeType + "|" + safePackage + "|" + limit(eventId, 1_000) + "|" +
                normalize(safeSender) + "|" + normalize(safeTitle) + "|" + normalize(safeBody) + "|" +
                (receivedAt / 86_400_000L)
        );
        FinancialMessageParser.Result result = parsed.get();
        CapturedTransaction transaction = new CapturedTransaction(
            captureId,
            safeType,
            safePackage,
            safeSender,
            safeTitle,
            safeBody,
            receivedAt,
            result.kind,
            result.amount,
            result.date,
            result.merchant,
            result.accountHint,
            result.accountType
        );

        ContentValues values = new ContentValues();
        values.put("capture_id", captureId);
        values.put("source_type", safeType);
        values.put("source_package", safePackage);
        values.put("sender_enc", cipher.encrypt(safeSender));
        values.put("title_enc", cipher.encrypt(safeTitle));
        values.put("body_enc", cipher.encrypt(safeBody));
        values.put("received_at", receivedAt);
        values.put("kind_enc", cipher.encrypt(result.kind));
        values.put("amount_enc", cipher.encrypt(Long.toString(result.amount)));
        values.put("transaction_date_enc", cipher.encrypt(result.date));
        values.put("merchant_enc", cipher.encrypt(result.merchant));
        values.put("account_hint_enc", cipher.encrypt(result.accountHint));
        values.put("account_type_enc", cipher.encrypt(result.accountType));
        values.put("status", STATUS_NEW);
        values.put("created_at", System.currentTimeMillis());

        long inserted = database.getWritableDatabase().insertWithOnConflict(
            "captures",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        );
        return inserted == -1 ? CaptureResult.duplicate(transaction) : CaptureResult.inserted(transaction);
    }

    public synchronized JSONArray pendingJson(int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 200));
        JSONArray output = new JSONArray();
        try (Cursor cursor = database.getReadableDatabase().query(
            "captures",
            null,
            "status=?",
            new String[]{STATUS_NEW},
            null,
            null,
            "received_at DESC",
            Integer.toString(limit)
        )) {
            while (cursor.moveToNext()) {
                try {
                    output.put(toJson(cursor));
                } catch (JSONException failure) {
                    throw new IllegalStateException("감지 내용을 변환하지 못했습니다.", failure);
                }
            }
        }
        return output;
    }

    public synchronized void markImported(JSONArray captureIds) {
        SQLiteDatabase writable = database.getWritableDatabase();
        writable.beginTransaction();
        try {
            for (int index = 0; index < captureIds.length(); index++) {
                String captureId = captureIds.optString(index, "");
                if (!validId(captureId)) continue;
                ContentValues values = new ContentValues();
                values.put("status", STATUS_IMPORTED);
                values.put("imported_at", System.currentTimeMillis());
                writable.update(
                    "captures",
                    values,
                    "capture_id=? AND status=?",
                    new String[]{captureId, STATUS_NEW}
                );
            }
            writable.setTransactionSuccessful();
        } finally {
            writable.endTransaction();
        }
    }

    public synchronized void dismiss(String captureId) {
        if (!validId(captureId)) return;
        ContentValues values = new ContentValues();
        values.put("status", STATUS_DISMISSED);
        database.getWritableDatabase().update(
            "captures",
            values,
            "capture_id=? AND status=?",
            new String[]{captureId, STATUS_NEW}
        );
    }

    public synchronized int clearHistory() {
        return database.getWritableDatabase().delete("captures", null, null);
    }

    public synchronized JSONObject counts() {
        SQLiteDatabase readable = database.getReadableDatabase();
        long total = DatabaseUtils.longForQuery(readable, "SELECT COUNT(*) FROM captures", null);
        long pending = DatabaseUtils.longForQuery(
            readable,
            "SELECT COUNT(*) FROM captures WHERE status=?",
            new String[]{STATUS_NEW}
        );
        try {
            return new JSONObject().put("total", total).put("pending", pending);
        } catch (JSONException failure) {
            throw new IllegalStateException("감지 건수를 변환하지 못했습니다.", failure);
        }
    }

    private JSONObject toJson(Cursor cursor) throws JSONException {
        String title = decrypt(cursor, "title_enc");
        String body = decrypt(cursor, "body_enc");
        String sender = decrypt(cursor, "sender_enc");
        return new JSONObject()
            .put("captureId", value(cursor, "capture_id"))
            .put("sourceType", value(cursor, "source_type"))
            .put("sourcePackage", value(cursor, "source_package"))
            .put("sender", sender)
            .put("title", title)
            .put("body", body)
            .put("receivedAt", number(cursor, "received_at"))
            .put("kind", decrypt(cursor, "kind_enc"))
            .put("amount", decryptedNumber(cursor, "amount_enc"))
            .put("date", decrypt(cursor, "transaction_date_enc"))
            .put("merchant", decrypt(cursor, "merchant_enc"))
            .put("accountHint", decrypt(cursor, "account_hint_enc"))
            .put("accountType", decrypt(cursor, "account_type_enc"));
    }

    private String decrypt(Cursor cursor, String column) {
        try {
            return cipher.decrypt(value(cursor, column));
        } catch (IllegalStateException ignored) {
            return "";
        }
    }

    private static String value(Cursor cursor, String column) {
        int index = cursor.getColumnIndexOrThrow(column);
        return cursor.isNull(index) ? "" : cursor.getString(index);
    }

    private static long number(Cursor cursor, String column) {
        return cursor.getLong(cursor.getColumnIndexOrThrow(column));
    }

    private long decryptedNumber(Cursor cursor, String column) {
        try {
            return Long.parseLong(decrypt(cursor, column));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(digest.length * 2);
            for (byte item : digest) output.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            return output.toString();
        } catch (Exception failure) {
            throw new IllegalStateException("감지 식별자를 만들지 못했습니다.", failure);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static String limit(String value, int max) {
        String safe = value == null ? "" : value.replace('\u0000', ' ').trim();
        return safe.substring(0, Math.min(max, safe.length()));
    }

    private static boolean validId(String value) {
        return value != null && value.matches("[a-f0-9]{64}");
    }

    public static final class CaptureResult {
        public final boolean matched;
        public final boolean inserted;
        public final CapturedTransaction transaction;

        private CaptureResult(boolean matched, boolean inserted, CapturedTransaction transaction) {
            this.matched = matched;
            this.inserted = inserted;
            this.transaction = transaction;
        }

        static CaptureResult ignored() {
            return new CaptureResult(false, false, null);
        }

        static CaptureResult duplicate(CapturedTransaction transaction) {
            return new CaptureResult(true, false, transaction);
        }

        static CaptureResult inserted(CapturedTransaction transaction) {
            return new CaptureResult(true, true, transaction);
        }
    }
}

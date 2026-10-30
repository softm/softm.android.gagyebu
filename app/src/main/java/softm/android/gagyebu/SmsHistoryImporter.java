package softm.android.gagyebu;

import android.content.Context;
import android.database.Cursor;
import android.provider.Telephony;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Calendar;

final class SmsHistoryImporter {
    private static final String[] PROJECTION = {
        Telephony.Sms._ID,
        Telephony.Sms.ADDRESS,
        Telephony.Sms.BODY,
        Telephony.Sms.DATE
    };

    private SmsHistoryImporter() {}

    static Result importCurrentMonth(Context context) {
        Calendar start = Calendar.getInstance();
        start.set(Calendar.DAY_OF_MONTH, 1);
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);

        int scanned = 0;
        int matched = 0;
        int imported = 0;
        try (Cursor cursor = context.getContentResolver().query(
            Telephony.Sms.Inbox.CONTENT_URI,
            PROJECTION,
            Telephony.Sms.DATE + ">=?",
            new String[]{Long.toString(start.getTimeInMillis())},
            Telephony.Sms.DATE + " ASC"
        )) {
            if (cursor == null) return new Result(0, 0, 0);
            int idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID);
            int addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS);
            int bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY);
            int dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE);
            CaptureStore store = CaptureStore.get(context);
            while (cursor.moveToNext()) {
                scanned++;
                String id = cursor.getString(idIndex);
                String sender = cursor.isNull(addressIndex) ? "" : cursor.getString(addressIndex);
                String body = cursor.isNull(bodyIndex) ? "" : cursor.getString(bodyIndex);
                long receivedAt = cursor.getLong(dateIndex);
                CaptureStore.CaptureResult result = store.capture(
                    "sms",
                    "android.telephony.history",
                    "history:" + id,
                    sender,
                    sender,
                    body,
                    receivedAt
                );
                if (result.matched) matched++;
                if (result.inserted) imported++;
            }
        }
        return new Result(scanned, matched, imported);
    }

    static final class Result {
        final int scanned;
        final int matched;
        final int imported;

        Result(int scanned, int matched, int imported) {
            this.scanned = scanned;
            this.matched = matched;
            this.imported = imported;
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                .put("scanned", scanned)
                .put("matched", matched)
                .put("imported", imported);
        }
    }
}

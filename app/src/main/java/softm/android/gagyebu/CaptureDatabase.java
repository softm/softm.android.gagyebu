package softm.android.gagyebu;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

final class CaptureDatabase extends SQLiteOpenHelper {
    private static final String NAME = "financial_captures.db";
    private static final int VERSION = 1;

    CaptureDatabase(Context context) {
        super(context, NAME, null, VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase database) {
        database.execSQL(
            "CREATE TABLE captures (" +
                "capture_id TEXT PRIMARY KEY NOT NULL," +
                "source_type TEXT NOT NULL," +
                "source_package TEXT NOT NULL," +
                "sender_enc TEXT NOT NULL," +
                "title_enc TEXT NOT NULL," +
                "body_enc TEXT NOT NULL," +
                "received_at INTEGER NOT NULL," +
                "kind_enc TEXT NOT NULL," +
                "amount_enc TEXT NOT NULL," +
                "transaction_date_enc TEXT NOT NULL," +
                "merchant_enc TEXT NOT NULL," +
                "account_hint_enc TEXT NOT NULL," +
                "account_type_enc TEXT NOT NULL," +
                "status TEXT NOT NULL," +
                "created_at INTEGER NOT NULL," +
                "imported_at INTEGER" +
            ")"
        );
        database.execSQL("CREATE INDEX captures_status_received ON captures(status, received_at DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        throw new IllegalStateException("지원하지 않는 캡처 DB 업그레이드입니다: " + oldVersion + " -> " + newVersion);
    }
}

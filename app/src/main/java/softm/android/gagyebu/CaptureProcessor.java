package softm.android.gagyebu;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

final class CaptureProcessor {
    static final String ACTION_CAPTURED = "softm.android.gagyebu.action.CAPTURED";
    static final String EXTRA_CAPTURE_ID = "capture_id";
    private static final String TAG = "GagyebuCapture";

    private CaptureProcessor() {}

    static void process(
        Context context,
        String sourceType,
        String sourcePackage,
        String eventId,
        String sender,
        String title,
        String body,
        long receivedAt
    ) {
        try {
            CaptureStore.CaptureResult result = CaptureStore.get(context).capture(
                sourceType,
                sourcePackage,
                eventId,
                sender,
                title,
                body,
                receivedAt
            );
            if (!result.inserted || result.transaction == null) return;
            CaptureNotification.show(context, result.transaction);
            Intent changed = new Intent(ACTION_CAPTURED)
                .setPackage(context.getPackageName())
                .putExtra(EXTRA_CAPTURE_ID, result.transaction.captureId);
            context.sendBroadcast(changed);
        } catch (RuntimeException failure) {
            Log.e(TAG, "금융 알림을 로컬 보관하지 못했습니다.", failure);
        }
    }
}


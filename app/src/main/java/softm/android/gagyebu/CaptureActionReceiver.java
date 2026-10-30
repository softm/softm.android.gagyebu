package softm.android.gagyebu;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class CaptureActionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!CaptureNotification.ACTION_IGNORE.equals(intent.getAction())) return;
        String captureId = intent.getStringExtra(CaptureProcessor.EXTRA_CAPTURE_ID);
        PendingResult pendingResult = goAsync();
        new Thread(() -> {
            try {
                CaptureStore.get(context).dismiss(captureId);
                CaptureNotification.cancel(context, captureId);
                context.sendBroadcast(new Intent(CaptureProcessor.ACTION_CAPTURED)
                    .setPackage(context.getPackageName()));
            } finally {
                pendingResult.finish();
            }
        }, "gagyebu-ignore-capture").start();
    }
}


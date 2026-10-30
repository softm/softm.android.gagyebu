package softm.android.gagyebu;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

public final class SmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;
        SmsMessage[] messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (messages == null || messages.length == 0) return;

        String sender = messages[0].getDisplayOriginatingAddress();
        StringBuilder body = new StringBuilder();
        long receivedAt = Long.MAX_VALUE;
        for (SmsMessage message : messages) {
            if (message == null) continue;
            body.append(message.getDisplayMessageBody());
            receivedAt = Math.min(receivedAt, message.getTimestampMillis());
        }
        if (receivedAt == Long.MAX_VALUE) receivedAt = System.currentTimeMillis();
        final long timestamp = receivedAt;
        final String content = body.toString();
        PendingResult pendingResult = goAsync();
        new Thread(() -> {
            try {
                CaptureProcessor.process(
                    context,
                    "sms",
                    "android.telephony",
                    sender + "|" + timestamp,
                    sender,
                    sender,
                    content,
                    timestamp
                );
            } finally {
                pendingResult.finish();
            }
        }, "gagyebu-sms-capture").start();
    }
}

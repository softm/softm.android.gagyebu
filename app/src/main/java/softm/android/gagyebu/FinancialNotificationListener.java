package softm.android.gagyebu;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public final class FinancialNotificationListener extends NotificationListenerService {
    @Override
    public void onNotificationPosted(StatusBarNotification statusBarNotification) {
        if (statusBarNotification == null ||
            getPackageName().equals(statusBarNotification.getPackageName())) {
            return;
        }
        Notification notification = statusBarNotification.getNotification();
        if (notification == null || (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;

        Bundle extras = notification.extras;
        String title = text(extras.get(Notification.EXTRA_TITLE));
        String shortText = text(extras.get(Notification.EXTRA_TEXT));
        String bigText = text(extras.get(Notification.EXTRA_BIG_TEXT));
        String subText = text(extras.get(Notification.EXTRA_SUB_TEXT));
        String body = !bigText.isBlank() ? bigText : shortText;
        if (!subText.isBlank() && !body.contains(subText)) body = body + "\n" + subText;
        if (title.isBlank() && body.isBlank()) return;

        CaptureProcessor.process(
            this,
            "notification",
            statusBarNotification.getPackageName(),
            statusBarNotification.getKey(),
            title,
            title,
            body,
            statusBarNotification.getPostTime()
        );
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}


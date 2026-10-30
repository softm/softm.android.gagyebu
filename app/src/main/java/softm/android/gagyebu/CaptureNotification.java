package softm.android.gagyebu;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;

import java.text.NumberFormat;
import java.util.Locale;

final class CaptureNotification {
    static final String ACTION_IGNORE = "softm.android.gagyebu.action.IGNORE_CAPTURE";
    private static final String CHANNEL_ID = "financial-capture-v1";

    private CaptureNotification() {}

    static void show(Context context, CapturedTransaction transaction) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        ensureChannel(context, manager);

        int requestCode = transaction.captureId.hashCode() & 0x7fffffff;
        Intent reviewIntent = new Intent(context, MainActivity.class)
            .setAction(MainActivity.ACTION_REVIEW_CAPTURE)
            .putExtra(CaptureProcessor.EXTRA_CAPTURE_ID, transaction.captureId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent review = PendingIntent.getActivity(
            context,
            requestCode,
            reviewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent ignoreIntent = new Intent(context, CaptureActionReceiver.class)
            .setAction(ACTION_IGNORE)
            .putExtra(CaptureProcessor.EXTRA_CAPTURE_ID, transaction.captureId);
        PendingIntent ignore = PendingIntent.getBroadcast(
            context,
            requestCode + 1,
            ignoreIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String amount = NumberFormat.getNumberInstance(Locale.KOREA).format(transaction.amount) + "원";
        String kind = switch (transaction.kind) {
            case "income" -> "입금";
            case "refund" -> "취소·환불";
            default -> transaction.accountType.equals("card") ? "카드 결제" : "출금·지출";
        };
        String merchant = transaction.merchant.isBlank() ? "사용처 확인 필요" : transaction.merchant;
        String detail = kind + " · " + amount + "\n" + merchant;

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(context, CHANNEL_ID)
            : new Notification.Builder(context);
        builder
            .setSmallIcon(R.drawable.ic_stat_wallet)
            .setColor(Color.rgb(33, 125, 113))
            .setContentTitle("가계부에 기록할까요?")
            .setContentText(kind + " " + amount + " · " + merchant)
            .setStyle(new Notification.BigTextStyle().bigText(detail))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(review)
            .addAction(new Notification.Action.Builder(
                R.drawable.ic_stat_wallet,
                "확인 후 기록",
                review
            ).build())
            .addAction(new Notification.Action.Builder(
                R.drawable.ic_stat_wallet,
                "무시",
                ignore
            ).build());
        manager.notify(requestCode, builder.build());
    }

    static void cancel(Context context, String captureId) {
        if (captureId == null) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.cancel(captureId.hashCode() & 0x7fffffff);
    }

    private static void ensureChannel(Context context, NotificationManager manager) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.capture_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.setDescription(context.getString(R.string.capture_channel_description));
        manager.createNotificationChannel(channel);
    }
}


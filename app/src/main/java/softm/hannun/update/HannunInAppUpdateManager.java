package softm.hannun.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.google.android.play.core.appupdate.AppUpdateInfo;
import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.appupdate.AppUpdateOptions;
import com.google.android.play.core.install.InstallStateUpdatedListener;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.install.model.InstallStatus;
import com.google.android.play.core.install.model.UpdateAvailability;

import org.json.JSONObject;

import java.util.function.Consumer;

/**
 * Google Play-only update flow for a Hannun Android WebView shell.
 * No APK sideloading and no web-delivered executable replacement.
 *
 * Copy this package to other Hannun Android apps without app-specific changes.
 * The hosting Activity forwards onResume/onPause/onActivityResult/onDestroy.
 */
public final class HannunInAppUpdateManager {
    public static final int REQUEST_CODE = 7816;

    private static final String TAG = "HannunInAppUpdate";
    private static final String PREFS = "hannun_in_app_update";
    private static final String KEY_LAST_PROMPT = "last_auto_prompt_at";
    private static final String KEY_LAST_DOWNLOADED = "last_downloaded_prompt_at";

    private final Activity activity;
    private final SharedPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable stateChanged;
    private final AppUpdateManager manager;
    private final InstallStateUpdatedListener installListener = state ->
        main.post(() -> onInstallState(state.installStatus(), state.bytesDownloaded(), state.totalBytesToDownload()));

    private String status = "not_checked";
    private long lastCheckedAt;
    private int availableVersionCode = -1;
    private int priority = 0;
    private int progressPercent = -1;
    private boolean resumed;
    private boolean queryInFlight;
    private boolean flowInProgress;
    private AlertDialog completionDialog;

    public HannunInAppUpdateManager(Activity activity, Runnable stateChanged) {
        this.activity = activity;
        this.stateChanged = stateChanged;
        this.preferences = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        AppUpdateManager created = null;
        try {
            created = AppUpdateManagerFactory.create(activity);
            created.registerListener(installListener);
        } catch (RuntimeException error) {
            Log.w(TAG, "Play update initialization failed", error);
            status = "unavailable";
        }
        manager = created;
    }

    public JSONObject snapshot() {
        JSONObject result = new JSONObject();
        try {
            PackageInfo packageInfo = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            long versionCode = Build.VERSION.SDK_INT >= 28
                ? packageInfo.getLongVersionCode() : packageInfo.versionCode;

            result.put("versionName", packageInfo.versionName);
            result.put("versionCode", versionCode);
            result.put("status", status);
            result.put("playInstalled", isPlayInstalled());
            result.put("lastCheckedAt", lastCheckedAt > 0 ? lastCheckedAt : JSONObject.NULL);
            result.put("availableVersionCode", availableVersionCode > 0 ? availableVersionCode : JSONObject.NULL);
            result.put("priority", priority);
            result.put("progressPercent", progressPercent >= 0 ? progressPercent : JSONObject.NULL);
        } catch (Exception error) {
            Log.w(TAG, "Cannot read update status", error);
        }
        return result;
    }

    private boolean isPlayInstalled() {
        try {
            PackageManager packages = activity.getPackageManager();
            String installer = Build.VERSION.SDK_INT >= 30
                ? packages.getInstallSourceInfo(activity.getPackageName()).getInstallingPackageName()
                : packages.getInstallerPackageName(activity.getPackageName());
            return "com.android.vending".equals(installer);
        } catch (Exception error) {
            return false;
        }
    }

    private void setStatus(String value) {
        status = value;
        if (stateChanged != null) stateChanged.run();
    }

    private void onInstallState(int installStatus, long downloadedBytes, long totalBytes) {
        if (installStatus == InstallStatus.DOWNLOADING) {
            progressPercent = totalBytes > 0
                ? (int) Math.min(100, 100.0d * downloadedBytes / totalBytes) : -1;
            setStatus("downloading");
        } else if (installStatus == InstallStatus.DOWNLOADED) {
            progressPercent = 100;
            flowInProgress = false;
            setStatus("downloaded");
            showCompletionDialog(false);
        } else if (installStatus == InstallStatus.FAILED) {
            flowInProgress = false;
            setStatus("failed");
        } else if (installStatus == InstallStatus.CANCELED) {
            flowInProgress = false;
            setStatus("canceled");
        } else if (installStatus == InstallStatus.INSTALLED) {
            flowInProgress = false;
            setStatus("up_to_date");
        }
    }

    public void onResume() {
        resumed = true;
        // Check on every foreground return to recover a downloaded or interrupted update.
        // The auto-prompt itself is throttled separately.
        query(false, null);
    }

    public void onPause() {
        resumed = false;
    }

    public void checkManually(Consumer<JSONObject> callback) {
        query(true, callback);
    }

    private void query(boolean manual, Consumer<JSONObject> callback) {
        if (manager == null) {
            setStatus("unavailable");
            deliver(callback);
            return;
        }
        if ((activity.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0 || !isPlayInstalled()) {
            setStatus("not_play_install");
            deliver(callback);
            return;
        }
        if (flowInProgress) {
            deliver(callback);
            return;
        }
        if (queryInFlight) {
            deliver(callback);
            return;
        }

        queryInFlight = true;
        if (manual) setStatus("checking");
        manager.getAppUpdateInfo()
            .addOnSuccessListener(info -> main.post(() -> {
                queryInFlight = false;
                lastCheckedAt = System.currentTimeMillis();
                try {
                    handleInfo(info, manual, callback);
                } catch (RuntimeException error) {
                    Log.w(TAG, "Unable to handle Play update", error);
                    setStatus("unavailable");
                    deliver(callback);
                }
            }))
            .addOnFailureListener(error -> main.post(() -> {
                queryInFlight = false;
                lastCheckedAt = System.currentTimeMillis();
                Log.w(TAG, "Play update availability failed", error);
                setStatus("unavailable");
                deliver(callback);
            }));
    }

    private void handleInfo(AppUpdateInfo info, boolean manual, Consumer<JSONObject> callback) {
        if (info.installStatus() == InstallStatus.DOWNLOADED) {
            progressPercent = 100;
            setStatus("downloaded");
            deliver(callback);
            showCompletionDialog(manual);
            return;
        }

        if (info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
            setStatus("in_progress");
            deliver(callback);
            if (!flowInProgress && resumed && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                main.post(() -> startFlow(info, HannunUpdatePolicy.Mode.IMMEDIATE, false));
            }
            return;
        }

        if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) {
            availableVersionCode = -1;
            progressPercent = -1;
            setStatus("up_to_date");
            deliver(callback);
            return;
        }

        availableVersionCode = info.availableVersionCode();
        priority = info.updatePriority();
        HannunUpdatePolicy.Mode mode = HannunUpdatePolicy.choose(
            priority,
            info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE),
            info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
        );
        setStatus(mode == HannunUpdatePolicy.Mode.NONE ? "available_play_only" : "available");
        deliver(callback);

        long now = System.currentTimeMillis();
        boolean allowAuto = HannunUpdatePolicy.intervalElapsed(
            preferences.getLong(KEY_LAST_PROMPT, 0L),
            now,
            HannunUpdatePolicy.AUTO_PROMPT_INTERVAL_MS
        );
        if (mode != HannunUpdatePolicy.Mode.NONE && (manual || allowAuto) && resumed) {
            // Return the WebView bridge response before presenting Play's full-screen UI.
            main.post(() -> startFlow(info, mode, !manual));
        }
    }

    private void startFlow(AppUpdateInfo info, HannunUpdatePolicy.Mode mode, boolean automatic) {
        if (flowInProgress || !resumed || activity.isFinishing() || mode == HannunUpdatePolicy.Mode.NONE) return;
        try {
            int type = mode == HannunUpdatePolicy.Mode.IMMEDIATE
                ? AppUpdateType.IMMEDIATE : AppUpdateType.FLEXIBLE;
            boolean started = manager.startUpdateFlowForResult(
                info,
                activity,
                AppUpdateOptions.newBuilder(type).build(),
                REQUEST_CODE
            );
            if (started) {
                flowInProgress = true;
                if (automatic) preferences.edit().putLong(KEY_LAST_PROMPT, System.currentTimeMillis()).apply();
                setStatus(mode == HannunUpdatePolicy.Mode.IMMEDIATE ? "in_progress" : "prompting");
            } else {
                setStatus("available_play_only");
            }
        } catch (Exception error) {
            Log.w(TAG, "Play update flow failed", error);
            setStatus("failed");
        }
    }

    public boolean onActivityResult(int requestCode, int resultCode) {
        if (requestCode != REQUEST_CODE) return false;
        flowInProgress = false;
        if (resultCode == Activity.RESULT_CANCELED) setStatus("canceled");
        else if (resultCode == Activity.RESULT_OK) setStatus("downloading");
        else setStatus("failed");
        return true;
    }

    private void showCompletionDialog(boolean manual) {
        if (!resumed || activity.isFinishing() || activity.isDestroyed() || manager == null) return;
        if (completionDialog != null && completionDialog.isShowing()) return;
        long now = System.currentTimeMillis();
        if (!manual && !HannunUpdatePolicy.intervalElapsed(
            preferences.getLong(KEY_LAST_DOWNLOADED, 0),
            now,
            HannunUpdatePolicy.DOWNLOADED_REMINDER_INTERVAL_MS
        )) return;

        preferences.edit().putLong(KEY_LAST_DOWNLOADED, now).apply();
        completionDialog = new AlertDialog.Builder(activity)
            .setTitle("앱 업데이트 준비 완료")
            .setMessage("새 버전을 설치하려면 앱을 재시작해야 합니다. 기록은 삭제되지 않습니다.")
            .setPositiveButton("업데이트 후 재시작", (dialog, which) ->
                manager.completeUpdate().addOnFailureListener(error ->
                    main.post(() -> {
                        Log.w(TAG, "Unable to install downloaded update", error);
                        Toast.makeText(activity, "업데이트를 완료하지 못했습니다. 다시 시도해 주세요.", Toast.LENGTH_LONG).show();
                        setStatus("downloaded");
                    })))
            .setNegativeButton("나중에", (dialog, which) -> dialog.dismiss())
            .create();
        completionDialog.show();
    }

    private void deliver(Consumer<JSONObject> callback) {
        if (callback != null) callback.accept(snapshot());
    }

    public void destroy() {
        resumed = false;
        if (completionDialog != null) completionDialog.dismiss();
        if (manager != null) manager.unregisterListener(installListener);
    }
}

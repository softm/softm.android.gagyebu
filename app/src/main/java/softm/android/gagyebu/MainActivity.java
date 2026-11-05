package softm.android.gagyebu;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.ServiceWorkerClientCompat;
import androidx.webkit.ServiceWorkerControllerCompat;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdLoader;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.gms.ads.nativead.NativeAd;
import com.google.android.gms.ads.nativead.NativeAdOptions;
import com.google.android.gms.ads.nativead.NativeAdView;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.UserMessagingPlatform;
import com.kakao.adfit.ads.ba.BannerAdView;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;

public final class MainActivity extends Activity {
    public static final String ACTION_REVIEW_CAPTURE = "softm.android.gagyebu.action.REVIEW_CAPTURE";
    private static final String AD_LOG_TAG = "GagyebuAds";

    private static final String HOME =
        "https://appassets.androidplatform.net/assets/web/index.html";
    private static final String TRUSTED_ORIGIN = "https://appassets.androidplatform.net";
    private static final String TRUSTED_PATH = "/assets/web/";
    private static final int REQUEST_SMS = 7101;
    private static final int REQUEST_NOTIFICATIONS = 7102;
    private static final int REQUEST_FILE = 7103;
    private static final long FIRST_INTERSTITIAL_DELAY_MS = 75_000L;
    private static final long INTERSTITIAL_INTERVAL_MS = 180_000L;
    private static final long EXIT_AD_WAIT_TIMEOUT_MS = 5_000L;
    private static final int MAX_INTERSTITIALS_PER_SESSION = 3;

    private WebView web;
    private ProgressBar progress;
    private LinearLayout error;
    private WebViewAssetLoader assetLoader;
    private boolean webReady;
    private boolean bridgeEnabled;
    private String launchCaptureId;
    private ValueCallback<Uri[]> fileChooser;
    private AlertDialog exitDialog;
    private BroadcastReceiver captureReceiver;
    private FrameLayout adContainer;
    private NativeAd nativeAd;
    private AdView adaptiveBannerAd;
    private BannerAdView adFitBanner;
    private ConsentInformation consentInformation;
    private boolean adsStarted;
    private boolean adFitLoadRequested;
    private final Handler adHandler = new Handler(Looper.getMainLooper());
    private InterstitialAd interstitialAd;
    private boolean interstitialLoading;
    private int interstitialsShown;
    private boolean activityResumed;
    private SuiteAuthManager suiteAuth;
    private final Runnable interstitialOpportunity = this::showInterstitialIfReady;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xff17675d);
        getWindow().setNavigationBarColor(0xfff5f7f5);
        readLaunchIntent(getIntent());
        buildLayout();
        configureWebView();
        suiteAuth = new SuiteAuthManager(this, () -> dispatchNativeEvent("gagyebu:account-changed"));
        suiteAuth.handleIntent(getIntent());
        registerCaptureReceiver();
        initializeAdvertising();

        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            web.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
            web.loadUrl(HOME, java.util.Collections.singletonMap("Cache-Control", "no-cache"));
        }
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                this::handleBack
            );
        }
    }

    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xfff5f7f5);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(
                insets.getSystemWindowInsetLeft(),
                insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(),
                insets.getSystemWindowInsetBottom()
            );
            return insets;
        });

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.getProgressDrawable().setTint(0xff217d71);
        root.addView(progress, new LinearLayout.LayoutParams(-1, 6));

        error = new LinearLayout(this);
        error.setOrientation(LinearLayout.VERTICAL);
        error.setPadding(40, 72, 40, 40);
        TextView message = new TextView(this);
        message.setText("가계부를 불러오지 못했습니다.\n시스템 WebView를 업데이트한 뒤 다시 시도해 주세요.");
        message.setTextSize(18);
        message.setTextColor(Color.DKGRAY);
        error.addView(message);
        Button retry = new Button(this);
        retry.setText("다시 시도");
        retry.setOnClickListener(view -> retry());
        error.addView(retry);
        root.addView(error);
        error.setVisibility(View.GONE);

        web = new WebView(this);
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));

        adContainer = new FrameLayout(this);
        adContainer.setBackgroundColor(Color.WHITE);
        adContainer.setVisibility(View.GONE);
        root.addView(adContainer, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        assetLoader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .build();
        installServiceWorkerAssetLoader();

        WebView.setWebContentsDebuggingEnabled(
            (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
        );
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setUserAgentString(
            settings.getUserAgentString() + " SoftmGagyebuAndroid/1 AdMobNative/1"
        );
        if (Build.VERSION.SDK_INT >= 26) settings.setSafeBrowsingEnabled(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);

        installBridge();
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                WebResourceResponse response = assetLoader.shouldInterceptRequest(request.getUrl());
                return response != null ? response : super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return route(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return route(Uri.parse(url));
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                webReady = false;
                error.setVisibility(View.GONE);
                web.setVisibility(View.VISIBLE);
                progress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
                progress.setVisibility(View.GONE);
                webReady = trusted(Uri.parse(url));
                if (webReady) {
                    dispatchNativeEvent("gagyebu:native-status");
                    maybeExplainCapture();
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError failure) {
                if (request.isForMainFrame()) showError();
            }

            @Override
            public void onReceivedHttpError(
                WebView view,
                WebResourceRequest request,
                WebResourceResponse response
            ) {
                if (request.isForMainFrame() && response.getStatusCode() >= 400) showError();
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                webReady = false;
                if (view.getParent() instanceof ViewGroup) {
                    ((ViewGroup) view.getParent()).removeView(view);
                }
                view.destroy();
                web = null;
                Toast.makeText(
                    MainActivity.this,
                    "웹 화면이 종료되어 안전하게 다시 시작합니다.",
                    Toast.LENGTH_LONG
                ).show();
                runOnUiThread(MainActivity.this::recreate);
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int value) {
                progress.setProgress(value);
                progress.setVisibility(value >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public boolean onShowFileChooser(
                WebView view,
                ValueCallback<Uri[]> callback,
                FileChooserParams params
            ) {
                if (fileChooser != null) fileChooser.onReceiveValue(null);
                fileChooser = callback;
                try {
                    Intent chooser = params.createIntent();
                    chooser.setType("image/*");
                    startActivityForResult(chooser, REQUEST_FILE);
                    return true;
                } catch (ActivityNotFoundException failure) {
                    fileChooser = null;
                    Toast.makeText(MainActivity.this, "사진을 선택할 앱이 없습니다.", Toast.LENGTH_LONG).show();
                    return false;
                }
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.deny();
            }
        });
    }

    @SuppressLint("RequiresFeature")
    private void installServiceWorkerAssetLoader() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) return;
        ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(
            new ServiceWorkerClientCompat() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                    return assetLoader.shouldInterceptRequest(request.getUrl());
                }
            }
        );
    }

    @SuppressLint("RequiresFeature")
    private void installBridge() {
        bridgeEnabled = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER);
        if (bridgeEnabled) {
            WebViewCompat.addWebMessageListener(
                web,
                "AndroidLedger",
                Collections.singleton(TRUSTED_ORIGIN),
                (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                    if (!isMainFrame || !trustedOrigin(sourceOrigin)) return;
                    handleBridgeMessage(message, replyProxy);
                }
            );
        }
    }

    private void handleBridgeMessage(WebMessageCompat message, JavaScriptReplyProxy replyProxy) {
        String requestId = "";
        try {
            JSONObject request = new JSONObject(message.getData());
            requestId = request.optString("id", "");
            String action = request.optString("action", "");
            JSONObject payload = request.optJSONObject("payload");
            if (payload == null) payload = new JSONObject();
            if ("scanSmsHistory".equals(action)) {
                scanCurrentMonthSmsHistory(replyProxy, requestId, false);
                return;
            }
            if("ledgerSync".equals(action)){
                final String ledgerId=requestId;
                suiteAuth.syncLedger(payload,value->reply(replyProxy,ledgerId,value!=null,value==null?JSONObject.NULL:value,value==null?"장부 연결 실패":null));return;
            }
            Object result;
            switch (action) {
                case "accountStatus" -> result = suiteAuth.snapshot();
                case "accountLogin" -> { suiteAuth.login(payload.optString("mode", "login")); result = true; }
                case "accountCancel" -> { suiteAuth.cancel(); result = true; }
                case "accountLogout" -> { suiteAuth.logout(); result = true; }
                case "pending" -> result = CaptureStore.get(this).pendingJson(payload.optInt("limit", 100));
                case "captureSource" -> {
                    JSONObject capture = CaptureStore.get(this).findJson(payload.optString("captureId", ""));
                    if (capture != null && "notification".equals(capture.optString("sourceType"))) {
                        try {
                            capture.put("sourceAppName", getPackageManager()
                                .getApplicationInfo(capture.optString("sourcePackage"), 0)
                                .loadLabel(getPackageManager()).toString());
                        } catch (PackageManager.NameNotFoundException ignored) {
                            // The original package name is still available if the app was removed.
                        }
                    }
                    result = capture == null ? JSONObject.NULL : capture;
                }
                case "status" -> result = permissionState();
                case "markImported" -> {
                    JSONArray ids = payload.optJSONArray("captureIds");
                    CaptureStore.get(this).markImported(ids == null ? new JSONArray() : ids);
                    result = true;
                }
                case "openNotificationSettings" -> {
                    runOnUiThread(this::openNotificationAccess);
                    result = true;
                }
                case "requestSms" -> {
                    runOnUiThread(this::requestSmsAccess);
                    result = true;
                }
                case "requestNotifications" -> {
                    runOnUiThread(this::requestPostingNotifications);
                    result = true;
                }
                case "showAdPrivacyOptions" -> {
                    runOnUiThread(this::showAdPrivacyOptions);
                    result = true;
                }
                case "clearHistory" -> result = CaptureStore.get(this).clearHistory();
                case "consumeLaunch" -> {
                    result = launchCaptureId == null ? JSONObject.NULL : launchCaptureId;
                    launchCaptureId = null;
                }
                default -> throw new IllegalArgumentException("지원하지 않는 네이티브 작업입니다.");
            }
            reply(replyProxy, requestId, true, result, null);
        } catch (Exception failure) {
            reply(replyProxy, requestId, false, JSONObject.NULL, "네이티브 작업을 완료하지 못했습니다.");
        }
    }

    @SuppressLint("RequiresFeature")
    private static void reply(
        JavaScriptReplyProxy replyProxy,
        String requestId,
        boolean ok,
        Object result,
        String errorMessage
    ) {
        try {
            JSONObject response = new JSONObject()
                .put("id", requestId)
                .put("ok", ok)
                .put("result", result == null ? JSONObject.NULL : result);
            if (errorMessage != null) response.put("error", errorMessage);
            replyProxy.postMessage(response.toString());
        } catch (JSONException ignored) {
            replyProxy.postMessage("{\"ok\":false,\"error\":\"응답을 만들지 못했습니다.\"}");
        }
    }

    private JSONObject permissionState() throws JSONException {
        boolean smsAvailable = supportsSms();
        boolean smsReceiveGranted = checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
        boolean smsHistoryGranted = checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;
        boolean postingGranted = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        JSONObject counts = CaptureStore.get(this).counts();
        return new JSONObject()
            .put("bridge", bridgeEnabled)
            .put("notificationAccess", hasNotificationAccess())
            .put("smsAvailable", smsAvailable)
            .put("smsGranted", smsReceiveGranted && smsHistoryGranted)
            .put("smsReceiveGranted", smsReceiveGranted)
            .put("smsHistoryGranted", smsHistoryGranted)
            .put("postingGranted", postingGranted)
            .put("adPrivacyOptionsRequired", adPrivacyOptionsRequired())
            .put("pendingCount", counts.optLong("pending"))
            .put("storedCount", counts.optLong("total"));
    }

    private void initializeAdvertising() {
        consentInformation = UserMessagingPlatform.getConsentInformation(this);
        ConsentRequestParameters parameters = new ConsentRequestParameters.Builder().build();
        consentInformation.requestConsentInfoUpdate(
            this,
            parameters,
            () -> UserMessagingPlatform.loadAndShowConsentFormIfRequired(
                this,
                formError -> {
                    maybeStartAds();
                    dispatchNativeEvent("gagyebu:native-status");
                }
            ),
            requestError -> {
                maybeStartAds();
                dispatchNativeEvent("gagyebu:native-status");
            }
        );

        if (consentInformation.canRequestAds()) maybeStartAds();
    }

    private void maybeStartAds() {
        if (adsStarted || consentInformation == null || !consentInformation.canRequestAds()) return;
        adsStarted = true;
        MobileAds.initialize(this, initializationStatus -> {
            loadNativeAd();
            loadInterstitialAd();
            adHandler.postDelayed(interstitialOpportunity, FIRST_INTERSTITIAL_DELAY_MS);
        });
    }

    private void loadNativeAd() {
        if (isDestroyed() || isFinishing()) return;
        String unitId = getString(R.string.admob_native_ad_unit_id).trim();
        if (!AdConfiguration.isValidAdMobUnitId(unitId)) {
            Log.e(AD_LOG_TAG, "AdMob native unit is not configured correctly");
            loadAdaptiveBannerAd();
            return;
        }
        AdLoader loader = new AdLoader.Builder(this, unitId)
            .forNativeAd(ad -> {
                if (isDestroyed() || isFinishing()) {
                    ad.destroy();
                    return;
                }
                if (nativeAd != null) nativeAd.destroy();
                nativeAd = ad;
                if (adaptiveBannerAd != null) {
                    adaptiveBannerAd.destroy();
                    adaptiveBannerAd = null;
                }
                if (adFitBanner != null) {
                    adFitBanner.destroy();
                    adFitBanner = null;
                }
                adContainer.removeAllViews();
                adContainer.addView(createNativeAdView(ad));
                adContainer.setVisibility(View.VISIBLE);
                Log.i(AD_LOG_TAG, "AdMob native ad loaded");
            })
            .withAdListener(new AdListener() {
                @Override
                public void onAdFailedToLoad(LoadAdError error) {
                    Log.w(AD_LOG_TAG, "AdMob native load failed; trying adaptive banner: " + error.getCode());
                    loadAdaptiveBannerAd();
                }
            })
            .withNativeAdOptions(new NativeAdOptions.Builder().build())
            .build();
        loader.loadAd(new AdRequest.Builder().build());
    }

    private void loadAdaptiveBannerAd() {
        if (adaptiveBannerAd != null || isDestroyed() || isFinishing()) return;
        String unitId = getString(R.string.admob_banner_ad_unit_id).trim();
        if (!AdConfiguration.isValidAdMobUnitId(unitId)) {
            Log.e(AD_LOG_TAG, "AdMob banner unit is not configured correctly");
            loadAdFitBanner();
            return;
        }

        int widthPixels = getResources().getDisplayMetrics().widthPixels;
        float density = getResources().getDisplayMetrics().density;
        int widthDp = Math.max(1, Math.round(widthPixels / density));
        AdView banner = new AdView(this);
        banner.setAdUnitId(unitId);
        banner.setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(this, widthDp));
        banner.setAdListener(new AdListener() {
            @Override
            public void onAdLoaded() {
                if (banner != adaptiveBannerAd || isDestroyed() || isFinishing()) {
                    banner.destroy();
                    return;
                }
                adContainer.removeAllViews();
                adContainer.addView(banner);
                adContainer.setVisibility(View.VISIBLE);
                Log.i(AD_LOG_TAG, "AdMob adaptive banner loaded");
            }

            @Override
            public void onAdFailedToLoad(LoadAdError error) {
                if (banner == adaptiveBannerAd) adaptiveBannerAd = null;
                banner.destroy();
                Log.w(AD_LOG_TAG, "AdMob banner load failed; trying AdFit: " + error.getCode());
                loadAdFitBanner();
            }
        });
        adaptiveBannerAd = banner;
        banner.loadAd(new AdRequest.Builder().build());
    }

    private NativeAdView createNativeAdView(NativeAd ad) {
        NativeAdView adView = (NativeAdView) getLayoutInflater().inflate(
            R.layout.native_ad_compact,
            adContainer,
            false
        );

        TextView headline = adView.findViewById(R.id.ad_headline);
        headline.setText(ad.getHeadline());
        adView.setHeadlineView(headline);

        TextView advertiser = adView.findViewById(R.id.ad_advertiser);
        advertiser.setText(ad.getAdvertiser());
        advertiser.setVisibility(ad.getAdvertiser() == null ? View.GONE : View.VISIBLE);
        adView.setAdvertiserView(advertiser);

        TextView body = adView.findViewById(R.id.ad_body);
        body.setText(ad.getBody());
        body.setVisibility(ad.getBody() == null ? View.GONE : View.VISIBLE);
        adView.setBodyView(body);

        ImageView icon = adView.findViewById(R.id.ad_app_icon);
        if (ad.getIcon() == null) {
            icon.setVisibility(View.GONE);
        } else {
            icon.setImageDrawable(ad.getIcon().getDrawable());
            icon.setVisibility(View.VISIBLE);
        }
        adView.setIconView(icon);

        Button callToAction = adView.findViewById(R.id.ad_call_to_action);
        callToAction.setText(ad.getCallToAction());
        callToAction.setVisibility(ad.getCallToAction() == null ? View.GONE : View.VISIBLE);
        adView.setCallToActionView(callToAction);

        adView.setNativeAd(ad);
        return adView;
    }

    private void loadInterstitialAd() {
        if (interstitialLoading || interstitialAd != null || isFinishing() || isDestroyed()) return;
        String unitId = getString(R.string.admob_interstitial_ad_unit_id).trim();
        if (!AdConfiguration.isValidAdMobUnitId(unitId)) {
            Log.e(AD_LOG_TAG, "AdMob interstitial unit is not configured correctly");
            return;
        }
        interstitialLoading = true;
        InterstitialAd.load(
            this,
            unitId,
            new AdRequest.Builder().build(),
            new InterstitialAdLoadCallback() {
                @Override
                public void onAdLoaded(InterstitialAd ad) {
                    interstitialLoading = false;
                    if (isFinishing() || isDestroyed()) return;
                    interstitialAd = ad;
                    Log.i(AD_LOG_TAG, "AdMob interstitial loaded");
                }

                @Override
                public void onAdFailedToLoad(LoadAdError error) {
                    interstitialLoading = false;
                    interstitialAd = null;
                    Log.w(AD_LOG_TAG, "AdMob interstitial load failed: " + error.getCode());
                }
            }
        );
    }

    private void showInterstitialIfReady() {
        if (isFinishing() || isDestroyed() || interstitialsShown >= MAX_INTERSTITIALS_PER_SESSION) {
            return;
        }
        adHandler.postDelayed(interstitialOpportunity, INTERSTITIAL_INTERVAL_MS);
        if (!activityResumed || !webReady || (exitDialog != null && exitDialog.isShowing())) {
            return;
        }
        if (interstitialAd == null) {
            loadInterstitialAd();
            return;
        }

        InterstitialAd ad = interstitialAd;
        interstitialAd = null;
        ad.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override
            public void onAdShowedFullScreenContent() {
                interstitialsShown++;
            }

            @Override
            public void onAdDismissedFullScreenContent() {
                loadInterstitialAd();
            }

            @Override
            public void onAdFailedToShowFullScreenContent(
                com.google.android.gms.ads.AdError error
            ) {
                Log.w(AD_LOG_TAG, "AdMob interstitial show failed: " + error.getCode());
                loadInterstitialAd();
            }
        });
        ad.show(this);
    }

    private void showExitConfirmation() {
        int spacing = Math.round(16 * getResources().getDisplayMetrics().density);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(spacing, 0, spacing, 0);

        TextView message = new TextView(this);
        message.setText("가계부를 종료하고 홈 화면으로 돌아갑니다.");
        message.setTextColor(Color.DKGRAY);
        message.setTextSize(16);
        content.addView(message, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout adSlot = new FrameLayout(this);
        LinearLayout loading = new LinearLayout(this);
        loading.setOrientation(LinearLayout.VERTICAL);
        loading.setGravity(Gravity.CENTER);
        ProgressBar loadingProgress = new ProgressBar(this);
        loading.addView(loadingProgress, new LinearLayout.LayoutParams(spacing * 3, spacing * 3));
        TextView loadingText = new TextView(this);
        loadingText.setText("광고를 불러오고 있습니다.");
        loadingText.setTextColor(Color.GRAY);
        loadingText.setGravity(Gravity.CENTER);
        loadingText.setPadding(0, spacing / 2, 0, 0);
        loading.addView(loadingText, new LinearLayout.LayoutParams(-2, -2));
        adSlot.addView(loading, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams slotParams = new LinearLayout.LayoutParams(
            -1,
            Math.round(250 * getResources().getDisplayMetrics().density)
        );
        slotParams.topMargin = spacing;
        content.addView(adSlot, slotParams);

        String unitId = getString(R.string.admob_exit_mrec_ad_unit_id).trim();
        int availableWidthDp = Math.round(
            getResources().getDisplayMetrics().widthPixels /
                getResources().getDisplayMetrics().density
        );
        boolean requestAd = adsStarted && availableWidthDp >= 332 &&
            AdConfiguration.isValidAdMobUnitId(unitId);
        if (!requestAd) adSlot.setVisibility(View.GONE);

        exitDialog = new AlertDialog.Builder(this)
            .setTitle("앱을 종료하시겠습니까?")
            .setView(content)
            .setNegativeButton("계속 이용", null)
            .setPositiveButton("종료", (dialog, which) -> finish())
            .create();

        final AdView[] exitAd = new AdView[1];
        final Runnable[] timeout = new Runnable[1];
        exitDialog.setOnShowListener(ignored -> {
            if (!requestAd) return;
            Button exit = exitDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            exit.setEnabled(false);
            exit.setAlpha(0.45f);

            Runnable enableExit = () -> {
                if (exitDialog == null || !exitDialog.isShowing()) return;
                exit.setEnabled(true);
                exit.setAlpha(1f);
            };
            timeout[0] = enableExit;
            adHandler.postDelayed(enableExit, EXIT_AD_WAIT_TIMEOUT_MS);

            AdView ad = new AdView(this);
            exitAd[0] = ad;
            ad.setAdSize(AdSize.MEDIUM_RECTANGLE);
            ad.setAdUnitId(unitId);
            ad.setVisibility(View.INVISIBLE);
            FrameLayout.LayoutParams adParams = new FrameLayout.LayoutParams(
                Math.round(300 * getResources().getDisplayMetrics().density),
                Math.round(250 * getResources().getDisplayMetrics().density),
                Gravity.CENTER
            );
            adSlot.addView(ad, adParams);
            ad.setAdListener(new AdListener() {
                @Override
                public void onAdLoaded() {
                    if (exitDialog == null || !exitDialog.isShowing() || exitAd[0] != ad) {
                        ad.destroy();
                        return;
                    }
                    adHandler.removeCallbacks(enableExit);
                    adSlot.removeView(loading);
                    ad.setVisibility(View.VISIBLE);
                    enableExit.run();
                    Log.i(AD_LOG_TAG, "AdMob exit MREC loaded");
                }

                @Override
                public void onAdFailedToLoad(LoadAdError error) {
                    adHandler.removeCallbacks(enableExit);
                    adSlot.setVisibility(View.GONE);
                    adSlot.removeAllViews();
                    if (exitAd[0] == ad) exitAd[0] = null;
                    ad.destroy();
                    enableExit.run();
                    Log.w(AD_LOG_TAG, "AdMob exit MREC load failed: " + error.getCode());
                }
            });
            ad.loadAd(new AdRequest.Builder().build());
        });
        exitDialog.setOnDismissListener(dialog -> {
            if (timeout[0] != null) adHandler.removeCallbacks(timeout[0]);
            if (exitAd[0] != null) exitAd[0].destroy();
            exitAd[0] = null;
            exitDialog = null;
        });
        exitDialog.show();
    }

    private void loadAdFitBanner() {
        String unitId = getString(R.string.adfit_banner_ad_unit_id).trim();
        if (!AdConfiguration.isValidAdFitBannerId(unitId) || isDestroyed() || isFinishing()) {
            Log.i(AD_LOG_TAG, "AdFit fallback is not configured");
            adContainer.setVisibility(View.GONE);
            return;
        }
        if (adFitLoadRequested) return;
        adFitLoadRequested = true;

        if (adFitBanner != null) adFitBanner.destroy();
        BannerAdView banner = new BannerAdView(this);
        banner.setAdUnitId(unitId);
        banner.setTestMode((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        banner.setAdListener(new com.kakao.adfit.ads.AdListener() {
            @Override
            public void onAdLoaded() {
                if (banner != adFitBanner || isDestroyed() || isFinishing()) return;
                if (nativeAd != null) {
                    nativeAd.destroy();
                    nativeAd = null;
                }
                adContainer.setVisibility(View.VISIBLE);
                Log.i(AD_LOG_TAG, "AdFit fallback banner loaded");
            }

            @Override
            public void onAdFailed(int errorCode) {
                if (banner != adFitBanner) return;
                adFitLoadRequested = false;
                adContainer.setVisibility(View.GONE);
                Log.w(AD_LOG_TAG, "AdFit load failed: " + errorCode);
            }

            @Override
            public void onAdImpression() {
            }

            @Override
            public void onAdClicked() {
            }
        });
        adFitBanner = banner;
        adContainer.removeAllViews();
        adContainer.setVisibility(View.GONE);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        );
        adContainer.addView(banner, params);
        banner.loadAd();
    }

    private boolean adPrivacyOptionsRequired() {
        return consentInformation != null &&
            consentInformation.getPrivacyOptionsRequirementStatus() ==
                ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED;
    }

    private void showAdPrivacyOptions() {
        if (!adPrivacyOptionsRequired()) {
            Toast.makeText(this, "현재 변경할 광고 개인정보 선택사항이 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        UserMessagingPlatform.showPrivacyOptionsForm(this, formError -> {
            if (formError != null) {
                Toast.makeText(this, "광고 개인정보 설정을 열지 못했습니다.", Toast.LENGTH_LONG).show();
            }
            dispatchNativeEvent("gagyebu:native-status");
        });
    }

    private boolean hasNotificationAccess() {
        String enabled = Settings.Secure.getString(
            getContentResolver(),
            "enabled_notification_listeners"
        );
        if (enabled == null || enabled.isBlank()) return false;
        for (String flattened : enabled.split(":")) {
            ComponentName component = ComponentName.unflattenFromString(flattened);
            if (component != null && getPackageName().equals(component.getPackageName())) return true;
        }
        return false;
    }

    private void openNotificationAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (ActivityNotFoundException failure) {
            startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
        }
    }

    private void requestSmsAccess() {
        if (!supportsSms()) {
            Toast.makeText(this, "이 기기는 SMS 수신을 지원하지 않습니다.", Toast.LENGTH_LONG).show();
            return;
        }
        boolean receiveGranted = checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
        boolean historyGranted = checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;
        if (receiveGranted && historyGranted) {
            scanCurrentMonthSmsHistory(null, null, true);
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("이번 달 금융 문자 분석")
            .setMessage(
                "이번 달 1일부터 현재까지의 기존 문자와 앞으로 도착하는 문자 중 카드 승인·취소, " +
                "입금·출금처럼 금액과 금융 키워드가 함께 있는 내용만 선별합니다. " +
                "원문은 이 기기의 Android 키스토어로 암호화해 보관하며 광고나 인증번호는 제외합니다.\n\n" +
                "가져온 거래는 자동 확정하지 않고 사용자가 검토할 '확인 필요' 항목으로 추가합니다. " +
                "Google Play 배포 시에는 SMS 기반 자금관리 권한 심사가 별도로 필요합니다."
            )
            .setNegativeButton("취소", null)
            .setPositiveButton("이번 달 분석 허용", (dialog, which) ->
                requestPermissions(
                    new String[]{Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS},
                    REQUEST_SMS
                )
            )
            .show();
    }

    private void scanCurrentMonthSmsHistory(
        JavaScriptReplyProxy replyProxy,
        String requestId,
        boolean notify
    ) {
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            if (replyProxy != null) {
                reply(replyProxy, requestId, false, JSONObject.NULL, "기존 문자 읽기 권한이 필요합니다.");
            }
            return;
        }
        new Thread(() -> {
            try {
                SmsHistoryImporter.Result result = SmsHistoryImporter.importCurrentMonth(this);
                JSONObject json = result.toJson();
                runOnUiThread(() -> {
                    if (notify) {
                        Toast.makeText(
                            this,
                            result.imported > 0
                                ? "이번 달 금융 문자 " + result.imported + "건을 확인 필요함에 추가했습니다."
                                : "이번 달에 새로 가져올 금융 문자가 없습니다.",
                            Toast.LENGTH_LONG
                        ).show();
                    }
                    if (replyProxy != null) reply(replyProxy, requestId, true, json, null);
                    dispatchNativeEvent("gagyebu:native-capture");
                    dispatchNativeEvent("gagyebu:native-status");
                });
            } catch (Exception failure) {
                Log.e("GagyebuSms", "이번 달 문자 분석 실패", failure);
                runOnUiThread(() -> {
                    if (replyProxy != null) {
                        reply(replyProxy, requestId, false, JSONObject.NULL, "이번 달 문자를 분석하지 못했습니다.");
                    }
                    if (notify) {
                        Toast.makeText(this, "이번 달 문자를 분석하지 못했습니다.", Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "gagyebu-sms-history").start();
    }

    private void requestPostingNotifications() {
        if (Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            dispatchNativeEvent("gagyebu:native-status");
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("기록 확인 알림")
            .setMessage("감지된 거래를 바로 확인하고 가계부에 기록하려면 알림 표시 권한이 필요합니다.")
            .setNegativeButton("나중에", null)
            .setPositiveButton("알림 허용", (dialog, which) ->
                requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS
                )
            )
            .show();
    }

    private void maybeExplainCapture() {
        if (!bridgeEnabled) {
            Toast.makeText(this, "시스템 WebView를 업데이트하면 자동 기록 기능을 사용할 수 있습니다.", Toast.LENGTH_LONG).show();
            return;
        }
        if (getPreferences(MODE_PRIVATE).getBoolean("capture_explained", false)) return;
        getPreferences(MODE_PRIVATE).edit().putBoolean("capture_explained", true).apply();
        web.postDelayed(() -> new AlertDialog.Builder(this)
            .setTitle("이번 달 거래를 간편하게 시작")
            .setMessage(
                "이번 달 기존 금융 문자와 앞으로 도착하는 은행·카드 알림에서 " +
                "금액과 거래 키워드가 함께 있는 내용만 단말에 암호화 보관합니다. " +
                "감지된 거래는 자동 확정하지 않고 ‘확인 필요’ 상태로 가져옵니다.\n\n" +
                "문자 분석과 알림 접근은 각각 선택할 수 있으며, 허용하지 않아도 직접 입력할 수 있습니다."
            )
            .setNegativeButton("나중에", null)
            .setNeutralButton("알림 접근 설정", (dialog, which) -> openNotificationAccess())
            .setPositiveButton("이번 달 문자 분석", (dialog, which) -> requestSmsAccess())
            .show(), 500);
    }

    private void recognizeReceipt(Uri uri) {
        if (uri == null) return;
        final InputImage image;
        try {
            image = InputImage.fromFilePath(this, uri);
        } catch (Exception failure) {
            Toast.makeText(this, "영수증 사진을 읽지 못했습니다.", Toast.LENGTH_LONG).show();
            return;
        }
        TextRecognizer recognizer = TextRecognition.getClient(
            new KoreanTextRecognizerOptions.Builder().build()
        );
        recognizer.process(image)
            .addOnSuccessListener(text -> {
                ReceiptTextParser.Result parsed = ReceiptTextParser.parse(
                    text.getText(),
                    System.currentTimeMillis()
                );
                try {
                    JSONObject result = new JSONObject()
                        .put("amount", parsed.amount)
                        .put("date", parsed.date)
                        .put("merchant", parsed.merchant);
                    sendReceiptOcrResult(result);
                } catch (JSONException failure) {
                    Toast.makeText(this, "영수증 분석 결과를 만들지 못했습니다.", Toast.LENGTH_LONG).show();
                }
            })
            .addOnFailureListener(failure ->
                Toast.makeText(this, "영수증 글자를 인식하지 못했습니다.", Toast.LENGTH_LONG).show()
            )
            .addOnCompleteListener(task -> recognizer.close());
    }

    private void sendReceiptOcrResult(JSONObject result) {
        if (!webReady || web == null) return;
        String serialized = JSONObject.quote(result.toString());
        web.evaluateJavascript(
            "window.gagyebuReceiptOcr&&window.gagyebuReceiptOcr(JSON.parse(" + serialized + "));",
            null
        );
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerCaptureReceiver() {
        captureReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                dispatchNativeEvent("gagyebu:native-capture");
            }
        };
        IntentFilter filter = new IntentFilter(CaptureProcessor.ACTION_CAPTURED);
        String permission = getPackageName() + ".permission.INTERNAL";
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                captureReceiver,
                filter,
                permission,
                null,
                Context.RECEIVER_NOT_EXPORTED
            );
        } else {
            registerReceiver(captureReceiver, filter, permission, null);
        }
    }

    private boolean supportsSms() {
        if (Build.VERSION.SDK_INT >= 33) {
            return getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING) ||
                getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
        }
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
    }

    private void dispatchNativeEvent(String eventName) {
        if (!webReady || !trusted(Uri.parse(web.getUrl() == null ? "" : web.getUrl()))) return;
        web.evaluateJavascript(
            "window.dispatchEvent(new Event(" + JSONObject.quote(eventName) + "));",
            null
        );
    }

    private void openCaptureInWeb(String captureId) {
        if (!validCaptureId(captureId)) return;
        launchCaptureId = captureId;
        if (!webReady) return;
        web.evaluateJavascript(
            "window.gagyebuNativeOpenCapture&&window.gagyebuNativeOpenCapture(" +
                JSONObject.quote(captureId) + ");",
            null
        );
    }

    private void readLaunchIntent(Intent intent) {
        if (intent == null || !ACTION_REVIEW_CAPTURE.equals(intent.getAction())) return;
        String captureId = intent.getStringExtra(CaptureProcessor.EXTRA_CAPTURE_ID);
        if (validCaptureId(captureId)) launchCaptureId = captureId;
    }

    private static boolean validCaptureId(String captureId) {
        return captureId != null && captureId.matches("[a-f0-9]{64}");
    }

    private boolean trustedOrigin(Uri uri) {
        return uri != null &&
            "https".equals(uri.getScheme()) &&
            "appassets.androidplatform.net".equals(uri.getHost()) &&
            (uri.getPort() == -1 || uri.getPort() == 443);
    }

    private boolean trusted(Uri uri) {
        String path = uri == null ? null : uri.getPath();
        return trustedOrigin(uri) && path != null && path.startsWith(TRUSTED_PATH);
    }

    private boolean route(Uri uri) {
        String path = uri == null ? null : uri.getPath();
        if (trustedOrigin(uri) &&
            (TRUSTED_PATH.equals(path) || (TRUSTED_PATH.substring(0, TRUSTED_PATH.length() - 1)).equals(path))) {
            web.loadUrl(HOME);
            return true;
        }
        if (trusted(uri)) return false;
        String scheme = uri == null ? null : uri.getScheme();
        if (!"https".equals(scheme) && !"http".equals(scheme) &&
            !"mailto".equals(scheme) && !"tel".equals(scheme)) {
            Toast.makeText(this, "지원하지 않는 링크입니다.", Toast.LENGTH_SHORT).show();
            return true;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException failure) {
            Toast.makeText(this, "연결할 앱이 설치되어 있지 않습니다.", Toast.LENGTH_LONG).show();
        }
        return true;
    }

    private void showError() {
        error.setVisibility(View.VISIBLE);
        web.setVisibility(View.GONE);
        progress.setVisibility(View.GONE);
    }

    private void retry() {
        error.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
        web.loadUrl(HOME);
    }

    private void handleBack() {
        if (exitDialog != null && exitDialog.isShowing()) return;
        web.evaluateJavascript(
            "(() => {const account = document.querySelector('softm-site-account');" +
                "const profile = account?.shadowRoot?.getElementById('profile');" +
                "if (profile?.getAttribute('aria-expanded') === 'true') {profile.click(); return true;}" +
                "const source = document.getElementById('transactionSourceBody');" +
                "if (!source) return false;" +
                "source.closest('.dialog').querySelector('[data-action=\"dismiss-modal\"]').click();" +
                "return true;})()",
            closed -> {
                if ("true".equals(closed)) return;
                if (web.canGoBack()) web.goBack();
                else showExitConfirmation();
            }
        );
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (suiteAuth != null) suiteAuth.handleIntent(intent);
        readLaunchIntent(intent);
        if (launchCaptureId != null) openCaptureInWeb(launchCaptureId);
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        if (web != null) web.onResume();
        if (adaptiveBannerAd != null) adaptiveBannerAd.resume();
        if (adFitBanner != null) adFitBanner.resume();
        dispatchNativeEvent("gagyebu:native-status");
        if (suiteAuth != null) suiteAuth.refresh();
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        if (adaptiveBannerAd != null) adaptiveBannerAd.pause();
        if (adFitBanner != null) adFitBanner.pause();
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        if (web != null) web.saveState(state);
        super.onSaveInstanceState(state);
    }

    @Override
    @SuppressLint("GestureBackNavigation")
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        handleBack();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_FILE || fileChooser == null) return;
        Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
        fileChooser.onReceiveValue(result);
        fileChooser = null;
        if (result != null && result.length > 0) recognizeReceipt(result[0]);
    }

    @Override
    public void onRequestPermissionsResult(
        int requestCode,
        String[] permissions,
        int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_SMS) {
            boolean receiveGranted = checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
            boolean historyGranted = checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;
            boolean granted = receiveGranted && historyGranted;
            Toast.makeText(
                this,
                granted ? "문자 권한을 허용했습니다. 이번 달 내역을 분석합니다." : "문자 권한을 모두 허용하지 않았습니다.",
                Toast.LENGTH_SHORT
            ).show();
            dispatchNativeEvent("gagyebu:native-status");
            if (granted) scanCurrentMonthSmsHistory(null, null, true);
            return;
        }
        if (requestCode == REQUEST_NOTIFICATIONS) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Toast.makeText(
                this,
                granted ? "확인 알림 권한을 허용했습니다." : "알림 권한을 허용하지 않았습니다.",
                Toast.LENGTH_SHORT
            ).show();
            dispatchNativeEvent("gagyebu:native-status");
        }
    }

    @Override
    protected void onDestroy() {
        if (suiteAuth != null) suiteAuth.destroy();
        adHandler.removeCallbacks(interstitialOpportunity);
        if (captureReceiver != null) unregisterReceiver(captureReceiver);
        if (fileChooser != null) fileChooser.onReceiveValue(null);
        if (nativeAd != null) nativeAd.destroy();
        if (adaptiveBannerAd != null) adaptiveBannerAd.destroy();
        interstitialAd = null;
        if (adFitBanner != null) adFitBanner.destroy();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}

package com.saji.whatsapptv;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import java.util.ArrayList;

public class MainActivity extends Activity {
    private static final String WHATSAPP_URL = "https://web.whatsapp.com/";
    private static final String PREFS = "yellow_whatsapp_tv";
    private static final String PREF_WEBSITE_MIC = "website_mic_enabled";
    private static final int REQUEST_MIC = 410;
    private static final int REQUEST_FILE = 411;
    private static final int ACTION_NONE = 0;
    private static final int ACTION_SPEECH_TO_TEXT = 1;
    private static final int ACTION_WEB_RECORD = 2;

    private FrameLayout root;
    private WebView webView;
    private LinearLayout sidebar;
    private SpeechRecognizer speechRecognizer;
    private PermissionRequest pendingWebPermission;
    private ValueCallback<Uri[]> filePathCallback;
    private int pendingMicAction = ACTION_NONE;
    private boolean isListening = false;
    private SharedPreferences preferences;

    private static final String JS_INSERT_TEXT =
            "(function(){var t=TEXT_PLACEHOLDER;var f=document.querySelector('#main footer')||document.querySelector('footer');" +
            "var a=f?Array.from(f.querySelectorAll('[contenteditable=true][role=textbox],[contenteditable=true]')):[];" +
            "if(!a.length)a=Array.from(document.querySelectorAll('[contenteditable=true][role=textbox],[contenteditable=true]'));" +
            "a=a.filter(function(e){return !!(e.offsetWidth||e.offsetHeight||e.getClientRects().length)});" +
            "if(!a.length)return 'NO_COMPOSER';var e=a[a.length-1];e.focus();" +
            "var ok=false;try{ok=document.execCommand('insertText',false,t)}catch(x){}" +
            "if(!ok){var s=window.getSelection();var r=document.createRange();r.selectNodeContents(e);r.collapse(false);s.removeAllRanges();s.addRange(r);r.insertNode(document.createTextNode(t));}" +
            "e.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:t}));return 'OK';})()";

    private static final String JS_START_RECORD =
            "(function(){var f=document.querySelector('#main footer')||document.querySelector('footer');if(!f)return false;" +
            "var ns=Array.from(f.querySelectorAll('button,[role=button],[data-icon]'));" +
            "function lb(n){return ((n.getAttribute('aria-label')||'')+' '+(n.getAttribute('title')||'')+' '+(n.innerText||'')).toLowerCase()}" +
            "var h=ns.find(function(n){return /voice message|record voice|microphone|mic|audio message/.test(lb(n))});" +
            "if(!h){var i=f.querySelector('[data-icon*="ptt"],[data-icon*="mic"],[data-icon*="voice"]');if(i)h=i.closest('button,[role=button]')||i;}" +
            "if(!h)return false;h.click();return true;})()";

    private static final String JS_STOP_RECORD =
            "(function(){var f=document.querySelector('#main footer')||document.querySelector('footer');if(!f)return false;" +
            "var ns=Array.from(f.querySelectorAll('button,[role=button],[data-icon]'));" +
            "function lb(n){return ((n.getAttribute('aria-label')||'')+' '+(n.getAttribute('title')||'')+' '+(n.innerText||'')).toLowerCase()}" +
            "var h=ns.find(function(n){return /stop recording|stop voice|finish recording|pause recording/.test(lb(n))});" +
            "if(!h){var i=f.querySelector('[data-icon*="stop"],[data-icon*="pause"],[data-icon*="recording"]');if(i)h=i.closest('button,[role=button]')||i;}" +
            "if(!h)return false;h.click();return true;})()";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        enterImmersiveMode();
        buildInterface();
        buildWebView();
        loadWhatsApp();
    }

    private void enterImmersiveMode() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                getWindow().setDecorFitsSystemWindows(false);
                WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) {
                    c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }
        } catch (Throwable ignored) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }
    }

    private void buildInterface() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(7, 13, 10));
        setContentView(root);

        sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setPadding(dp(7), dp(10), dp(7), dp(10));
        GradientDrawable panel = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xF21A2B21, 0xF20D1712});
        panel.setCornerRadius(dp(22));
        panel.setStroke(dp(1), 0x805F8769);
        sidebar.setBackground(panel);
        sidebar.setElevation(dp(16));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.MATCH_PARENT);
        rp.setMargins(dp(10), dp(10), 0, dp(10));
        root.addView(scroll, rp);
        scroll.addView(sidebar, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView brand = new TextView(this);
        brand.setText("YELLOW\nWA TV");
        brand.setTextColor(0xFFFFDF70);
        brand.setTextSize(13);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        brand.setGravity(Gravity.CENTER);
        brand.setPadding(0, dp(7), 0, dp(10));
        sidebar.addView(brand, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        addRailButton("‹", "BACK", "Go back in WhatsApp", this::goBack);
        addRailButton("🎙", "MIC", "Speak to type a WhatsApp message", this::startSpeechToText);
        addRailButton("●", "WA REC", "Start WhatsApp voice-message recording", this::startWhatsAppRecording);
        addRailButton("■", "STOP", "Stop voice recording without sending", this::stopWhatsAppRecording);
        addRailButton("↑", "UP", "Scroll conversation upward", () -> scrollConversation(-1));
        addRailButton("↓", "DOWN", "Scroll conversation downward", () -> scrollConversation(1));

        View spacer = new View(this);
        sidebar.addView(spacer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        addRailButton("⚙", "SETTINGS", "Microphone and website permissions", this::showSettings);
        addRailButton("⏻", "EXIT", "Close Yellow WhatsApp TV", this::confirmExit);
    }

    private void addRailButton(String symbol, String label, String description, Runnable action) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(symbol + "\n" + label);
        b.setTextColor(0xFFF3F8F1);
        b.setTextSize(11);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(2), dp(3), dp(2), dp(3));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setFocusable(true);
        b.setFocusableInTouchMode(false);
        b.setContentDescription(description);
        b.setBackground(makeButtonBackground(false));
        b.setOnFocusChangeListener((v, focused) -> {
            v.setBackground(makeButtonBackground(focused));
            v.animate().scaleX(focused ? 1.045f : 1f).scaleY(focused ? 1.045f : 1f).setDuration(110).start();
        });
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        p.setMargins(dp(1), dp(3), dp(1), dp(3));
        sidebar.addView(b, p);
    }

    private GradientDrawable makeButtonBackground(boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(13));
        d.setColor(focused ? 0xFF314B36 : 0x442A3B30);
        d.setStroke(dp(focused ? 2 : 1), focused ? 0xFFFFD54F : 0x553E5746);
        return d;
    }

    private void buildWebView() {
        webView = new WebView(this);
        webView.setId(View.generateViewId());
        webView.setBackgroundColor(Color.rgb(10, 16, 13));
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setLoadsImagesAutomatically(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(makeDesktopUserAgent(s.getUserAgentString()));

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) cm.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return false; }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) Toast.makeText(MainActivity.this,
                        "WhatsApp Web load nahi hua. Internet check karke reload karo.", Toast.LENGTH_LONG).show();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> handleWebsitePermissionRequest(request));
            }
            @Override public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pendingWebPermission == request) pendingWebPermission = null;
            }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQUEST_FILE);
                    return true;
                } catch (ActivityNotFoundException ex) {
                    filePathCallback = null;
                    Toast.makeText(MainActivity.this, "TV par file picker available nahi hai.", Toast.LENGTH_LONG).show();
                    return false;
                }
            }
        });

        webView.setDownloadListener((url, ua, disposition, mime, length) -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
            catch (Exception ex) { Toast.makeText(this, "Download link open nahi ho saka.", Toast.LENGTH_SHORT).show(); }
        });

        FrameLayout.LayoutParams wp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        wp.leftMargin = dp(116);
        root.addView(webView, 0, wp);
        for (int i = 0; i < sidebar.getChildCount(); i++) {
            View child = sidebar.getChildAt(i);
            if (child instanceof Button) child.setNextFocusRightId(webView.getId());
        }
    }

    private String makeDesktopUserAgent(String original) {
        String chrome = "Chrome/120.0.0.0";
        if (original != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Chrome/[0-9.]+").matcher(original);
            if (m.find()) chrome = m.group();
        }
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " + chrome + " Safari/537.36";
    }

    private void loadWhatsApp() {
        if (webView != null) webView.loadUrl(WHATSAPP_URL);
    }

    private void goBack() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else loadWhatsApp();
    }

    private void scrollConversation(int direction) {
        if (webView == null) return;
        String script = direction < 0
                ? "(function(){var e=document.querySelector('#main .copyable-area')||document.scrollingElement;e.scrollBy({top:-Math.max(350,window.innerHeight*.65),behavior:'smooth'});return true;})()"
                : "(function(){var e=document.querySelector('#main .copyable-area')||document.scrollingElement;e.scrollBy({top:Math.max(350,window.innerHeight*.65),behavior:'smooth'});return true;})()";
        webView.evaluateJavascript(script, null);
    }

    private void startSpeechToText() { requestMicrophonePermission(ACTION_SPEECH_TO_TEXT); }

    private void startSpeechRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Is TV Box par Speech-to-Text service available nahi hai.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            destroySpeechRecognizer();
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {
                    isListening = true;
                    Toast.makeText(MainActivity.this, "Bolo, main text likh raha hoon…", Toast.LENGTH_SHORT).show();
                }
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() { isListening = false; }
                @Override public void onError(int error) {
                    isListening = false;
                    String msg = (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                            ? "Awaaz clear nahi mili. Dobara MIC dabao."
                            : "Speech recognition error (" + error + "). TV ki voice typing service check karo.";
                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                    destroySpeechRecognizer();
                }
                @Override public void onResults(Bundle results) {
                    isListening = false;
                    ArrayList<String> matches = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) insertTextIntoWhatsApp(matches.get(0));
                    destroySpeechRecognizer();
                }
                @Override public void onPartialResults(Bundle partialResults) {}
                @Override public void onEvent(int eventType, Bundle params) {}
            });
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "WhatsApp message boliye");
            speechRecognizer.startListening(intent);
        } catch (Exception e) {
            isListening = false;
            Toast.makeText(this, "Speech-to-Text start nahi ho saka.", Toast.LENGTH_LONG).show();
            destroySpeechRecognizer();
        }
    }

    private void insertTextIntoWhatsApp(String text) {
        if (webView == null || text == null || text.trim().isEmpty()) return;
        String script = JS_INSERT_TEXT.replace("TEXT_PLACEHOLDER", JSONObject.quote(text.trim()));
        webView.evaluateJavascript(script, value -> {
            if (value != null && value.contains("NO_COMPOSER"))
                Toast.makeText(this, "WhatsApp ka message box nahi mila. Pehle chat open karo.", Toast.LENGTH_LONG).show();
            else Toast.makeText(this, "Text message box mein add ho gaya. Send tum khud karo.", Toast.LENGTH_SHORT).show();
        });
    }

    private void destroySpeechRecognizer() {
        if (speechRecognizer != null) {
            try { speechRecognizer.cancel(); } catch (Exception ignored) {}
            try { speechRecognizer.destroy(); } catch (Exception ignored) {}
            speechRecognizer = null;
        }
    }

    private void startWhatsAppRecording() { requestMicrophonePermission(ACTION_WEB_RECORD); }

    private void stopWhatsAppRecording() {
        if (webView == null) return;
        webView.evaluateJavascript(JS_STOP_RECORD, value -> {
            if ("true".equals(value))
                Toast.makeText(this, "Recording stop hui. Preview check karke WhatsApp ka Send control khud dabao.", Toast.LENGTH_LONG).show();
            else Toast.makeText(this, "WhatsApp ka Stop Recording control detect nahi hua. Recording UI open hai ya nahi check karo.", Toast.LENGTH_LONG).show();
        });
    }

    private void clickWhatsAppRecordControl() {
        if (!isWebsiteMicEnabled()) {
            Toast.makeText(this, "Settings mein Website Microphone ON karo.", Toast.LENGTH_LONG).show();
            return;
        }
        if (webView == null) return;
        webView.evaluateJavascript(JS_START_RECORD, value -> {
            if ("true".equals(value))
                Toast.makeText(this, "WhatsApp voice-record control press kiya.", Toast.LENGTH_LONG).show();
            else Toast.makeText(this, "WhatsApp ka voice-record button detect nahi hua. Chat open karke WA REC dabao.", Toast.LENGTH_LONG).show();
        });
    }

    private void requestMicrophonePermission(int action) {
        pendingMicAction = action;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            continuePendingMicAction();
        } else requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
    }

    private void continuePendingMicAction() {
        int action = pendingMicAction;
        pendingMicAction = ACTION_NONE;
        if (action == ACTION_SPEECH_TO_TEXT) startSpeechRecognition();
        else if (action == ACTION_WEB_RECORD) clickWhatsAppRecordControl();
        if (pendingWebPermission != null) grantPendingWebsiteAudio();
    }

    private void handleWebsitePermissionRequest(PermissionRequest request) {
        if (!isWhatsAppWebOrigin(request.getOrigin())) { request.deny(); return; }
        boolean audio = false;
        for (String r : request.getResources()) if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) audio = true;
        if (!audio || !isWebsiteMicEnabled()) { request.deny(); return; }
        pendingWebPermission = request;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestMicrophonePermission(ACTION_NONE);
            return;
        }
        grantPendingWebsiteAudio();
    }

    private boolean isWhatsAppWebOrigin(Uri origin) {
        return origin != null && "web.whatsapp.com".equalsIgnoreCase(origin.getHost());
    }

    private void grantPendingWebsiteAudio() {
        PermissionRequest request = pendingWebPermission;
        if (request == null) return;
        pendingWebPermission = null;
        if (!isWebsiteMicEnabled() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)) {
            request.deny();
            return;
        }
        ArrayList<String> allowed = new ArrayList<>();
        for (String r : request.getResources()) if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) allowed.add(r);
        if (allowed.isEmpty()) request.deny();
        else request.grant(allowed.toArray(new String[0]));
    }

    private boolean isWebsiteMicEnabled() { return preferences.getBoolean(PREF_WEBSITE_MIC, true); }

    private void showSettings() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(12), dp(20), dp(8));
        content.setBackgroundColor(0xFF17221A);

        TextView status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(14);
        status.setPadding(0, 0, 0, dp(10));
        content.addView(status);

        Button androidMic = makeDialogButton("ALLOW ANDROID MICROPHONE");
        content.addView(androidMic);
        androidMic.setOnClickListener(v -> requestMicrophonePermission(ACTION_NONE));

        android.widget.Switch websiteMic = new android.widget.Switch(this);
        websiteMic.setText("WHATSAPP WEBSITE MIC ACCESS");
        websiteMic.setTextColor(Color.WHITE);
        websiteMic.setTextSize(14);
        websiteMic.setPadding(0, dp(12), 0, dp(12));
        websiteMic.setChecked(isWebsiteMicEnabled());
        content.addView(websiteMic);

        Button appSettings = makeDialogButton("OPEN ANDROID APP SETTINGS");
        content.addView(appSettings);
        appSettings.setOnClickListener(v -> openAndroidAppSettings());

        Button reload = makeDialogButton("RELOAD WHATSAPP WEB");
        content.addView(reload);
        reload.setOnClickListener(v -> loadWhatsApp());

        Button micTest = makeDialogButton("TEST MIC / SPEECH TO TEXT");
        content.addView(micTest);
        micTest.setOnClickListener(v -> startSpeechToText());

        status.setText(buildPermissionStatus());
        websiteMic.setOnCheckedChangeListener((v, checked) -> {
            preferences.edit().putBoolean(PREF_WEBSITE_MIC, checked).apply();
            status.setText(buildPermissionStatus());
            if (!checked && pendingWebPermission != null) {
                try { pendingWebPermission.deny(); } catch (Exception ignored) {}
                pendingWebPermission = null;
            }
            Toast.makeText(this, checked ? "WhatsApp website mic access ON" : "WhatsApp website mic access OFF", Toast.LENGTH_SHORT).show();
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("YELLOW WHATSAPP TV SETTINGS")
                .setView(content)
                .setNegativeButton("CLOSE", null)
                .create();
        dialog.setOnShowListener(d -> androidMic.requestFocus());
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
    }

    private String buildPermissionStatus() {
        boolean androidMic = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        return "Android microphone: " + (androidMic ? "ALLOWED" : "NOT ALLOWED") +
                "\nWhatsApp website microphone: " + (isWebsiteMicEnabled() ? "ON (WhatsApp Web only)" : "OFF") +
                "\n\nWebsite audio is approved only when web.whatsapp.com requests it.";
    }

    private Button makeDialogButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13);
        b.setFocusable(true);
        b.setMinHeight(dp(48));
        b.setBackground(makeButtonBackground(false));
        b.setOnFocusChangeListener((v, focused) -> v.setBackground(makeButtonBackground(focused)));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        p.setMargins(0, dp(4), 0, dp(4));
        b.setLayoutParams(p);
        return b;
    }

    private void openAndroidAppSettings() {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) { Toast.makeText(this, "Android app settings open nahi ho saki.", Toast.LENGTH_SHORT).show(); }
    }

    private void confirmExit() {
        new AlertDialog.Builder(this).setTitle("Exit Yellow WhatsApp TV?")
                .setMessage("App close karni hai?")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("EXIT", (dialog, which) -> finish()).show();
    }

    private int dp(float v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override public void onRequestPermissionsResult(int requestCode, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(requestCode, perms, grants);
        if (requestCode == REQUEST_MIC) {
            boolean ok = grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED;
            if (ok) continuePendingMicAction();
            else {
                pendingMicAction = ACTION_NONE;
                if (pendingWebPermission != null) {
                    try { pendingWebPermission.deny(); } catch (Exception ignored) {}
                    pendingWebPermission = null;
                }
                Toast.makeText(this, "Microphone permission allow karna zaroori hai.", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_FILE && filePathCallback != null) {
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            filePathCallback.onReceiveValue(result);
            filePathCallback = null;
        }
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) { goBack(); return true; }
        return super.onKeyDown(keyCode, event);
    }

    @Override public void onBackPressed() { goBack(); }

    @Override protected void onResume() {
        super.onResume();
        enterImmersiveMode();
        if (webView != null) webView.onResume();
    }

    @Override protected void onPause() {
        if (webView != null) webView.onPause();
        if (isListening && speechRecognizer != null) {
            try { speechRecognizer.stopListening(); } catch (Exception ignored) {}
        }
        super.onPause();
    }

    @Override protected void onDestroy() {
        destroySpeechRecognizer();
        if (pendingWebPermission != null) {
            try { pendingWebPermission.deny(); } catch (Exception ignored) {}
            pendingWebPermission = null;
        }
        if (filePathCallback != null) { filePathCallback.onReceiveValue(null); filePathCallback = null; }
        if (webView != null) {
            try {
                webView.stopLoading();
                webView.loadUrl("about:blank");
                webView.setWebChromeClient(null);
                webView.setWebViewClient(null);
                webView.destroy();
            } catch (Exception ignored) {}
            webView = null;
        }
        super.onDestroy();
    }
}

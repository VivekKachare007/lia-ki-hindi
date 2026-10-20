package com.kachare.liahindi;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lia ki Hindi: a small Android shell around the Lia ki Hindi page on claude.ai.
 * First run: sign in to Claude (cookies are kept, so this is one time).
 * After that the app opens straight to the lesson page, where Lia or Vivek enter their PIN.
 * The page gets the phone's Hindi voice and speech recognition through window.LiaNative.
 */
public class MainActivity extends Activity {

    static final String APP_URL = "https://claude.ai/artifact/G3RH8APiS7w4oAXMb7i2dB";
    static final String LOGIN_URL = "https://claude.ai/login";
    static final int REQ_FILE = 11, REQ_MIC = 12;

    private WebView web;
    private LinearLayout signInBar;
    private TextView signInText;
    private ProgressBar progress;

    private ValueCallback<Uri[]> fileCallback;
    private Uri cameraUri;
    private PermissionRequest pendingWebPermission;

    private TextToSpeech tts;
    private volatile boolean hindiVoiceReady = false;
    private SpeechRecognizer recognizer;
    private final Map<String, String> results = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();

    // Back button: the lesson page polls takeBack(); if it doesn't take it in time, we go back in history or ask to close.
    private volatile boolean backPending = false;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildLayout();
        setupWebView();
        setupTts();
        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(APP_URL);
        }
    }

    // ---------------------------------------------------------------- layout
    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F4F3FB"));
        root.setFitsSystemWindows(true);

        signInBar = new LinearLayout(this);
        signInBar.setOrientation(LinearLayout.VERTICAL);
        signInBar.setBackgroundColor(Color.parseColor("#221F55"));
        signInBar.setPadding(dp(16), dp(12), dp(16), dp(12));
        signInBar.setVisibility(View.GONE);

        signInText = new TextView(this);
        signInText.setTextColor(Color.WHITE);
        signInText.setTextSize(15);
        signInText.setText("One-time setup: sign in to Claude with your email. "
                + "Google sign-in does not work inside apps, so use “Continue with email”. "
                + "If Claude emails you a link, long-press it, copy it, and tap “Paste sign-in link”.");
        signInBar.addView(signInText);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, dp(8), 0, 0);
        Button paste = barButton("Paste sign-in link");
        paste.setOnClickListener(v -> pasteSignInLink());
        Button open = barButton("Open lessons");
        open.setOnClickListener(v -> web.loadUrl(APP_URL));
        buttons.addView(paste);
        buttons.addView(open);
        signInBar.addView(buttons);
        root.addView(signInBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));

        web = new WebView(this);
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private Button barButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(Color.parseColor("#3D2600"));
        b.setBackgroundColor(Color.parseColor("#F4A83A"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(dp(8));
        b.setLayoutParams(lp);
        return b;
    }

    private void pasteSignInLink() {
        String clip = "";
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip()) {
            ClipData d = cm.getPrimaryClip();
            if (d != null && d.getItemCount() > 0 && d.getItemAt(0).getText() != null) clip = d.getItemAt(0).getText().toString().trim();
        }
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://claude.ai/…");
        if (clip.startsWith("https://claude.ai/")) input.setText(clip);
        new AlertDialog.Builder(this)
                .setTitle("Paste the sign-in link from your email")
                .setView(input)
                .setPositiveButton("Open", (dlg, w) -> {
                    String url = input.getText().toString().trim();
                    if (url.startsWith("https://claude.ai/")) web.loadUrl(url);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------------------------------------------------------------- web view
    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new Bridge(), "LiaNative");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri u = request.getUrl();
                String host = u.getHost() == null ? "" : u.getHost();
                if (isClaudeHost(host)) return false;
                // Everything else (help pages, Google sign-in, mail apps) opens outside the app.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException ignored) { }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                CookieManager.getInstance().flush();
                updateSignInBar(url);
                Uri u = Uri.parse(url);
                String path = u.getPath() == null ? "/" : u.getPath();
                // After signing in, Claude lands on its chat home: go straight to the lessons.
                if ("claude.ai".equals(u.getHost()) && (path.equals("/") || path.startsWith("/new") || path.startsWith("/recents") || path.startsWith("/chats"))) {
                    view.loadUrl(APP_URL);
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                progress.setProgress(p);
                progress.setVisibility(p >= 100 ? View.INVISIBLE : View.VISIBLE);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                openPhotoPicker();
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean wantsMic = false;
                    for (String r : request.getResources()) if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) wantsMic = true;
                    if (!wantsMic) { request.deny(); return; }
                    if (hasMic()) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    } else {
                        pendingWebPermission = request;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                    }
                });
            }
        });
    }

    static boolean isClaudeHost(String host) {
        return host.equals("claude.ai") || host.endsWith(".claude.ai")
                || host.endsWith(".claudeusercontent.com") || host.equals("claudeusercontent.com")
                || host.endsWith(".anthropic.com") || host.equals("anthropic.com");
    }

    private void updateSignInBar(String url) {
        Uri u = Uri.parse(url);
        String path = u.getPath() == null ? "" : u.getPath();
        boolean signingIn = "claude.ai".equals(u.getHost())
                && (path.startsWith("/login") || path.startsWith("/magic-link") || path.startsWith("/verify") || path.startsWith("/onboarding") || path.startsWith("/signup"));
        signInBar.setVisibility(signingIn ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------- photos
    private void openPhotoPicker() {
        Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        cameraUri = null;
        try {
            File dir = new File(getCacheDir(), "photos");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = new File(dir, "notebook_" + System.currentTimeMillis() + ".jpg");
            cameraUri = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
            camera.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
            camera.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception e) {
            camera = null;
        }
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("image/*");
        Intent chooser = Intent.createChooser(pick, "Photo of the notebook");
        if (camera != null) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{camera});
        try {
            startActivityForResult(chooser, REQ_FILE);
        } catch (ActivityNotFoundException e) {
            fileCallback.onReceiveValue(null);
            fileCallback = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE || fileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK) {
            if (data != null && data.getData() != null) {
                result = new Uri[]{data.getData()};
            } else if (cameraUri != null) {
                File f = new File(new File(getCacheDir(), "photos"), cameraUri.getLastPathSegment());
                if (f.exists() && f.length() > 0) result = new Uri[]{cameraUri};
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    // ---------------------------------------------------------------- voice
    private void setupTts() {
        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) return;
            int r = tts.setLanguage(new Locale("hi", "IN"));
            hindiVoiceReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
        });
    }

    private boolean hasMic() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void putResult(String id, String status, String error, ArrayList<String> alts) {
        try {
            JSONObject o = new JSONObject();
            o.put("status", status);
            if (error != null) o.put("error", error);
            JSONArray a = new JSONArray();
            if (alts != null) for (String s : alts) a.put(s);
            o.put("alts", a);
            results.put(id, o.toString());
        } catch (Exception e) {
            results.put(id, "{\"status\":\"error\",\"error\":\"error\"}");
        }
    }

    private void startListening(String id, String lang) {
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onResults(Bundle b) {
                ArrayList<String> alts = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                putResult(id, "done", null, alts);
            }
            @Override public void onError(int error) {
                String e;
                switch (error) {
                    case SpeechRecognizer.ERROR_NO_MATCH:
                    case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: e = "no-speech"; break;
                    case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: e = "permission"; break;
                    case SpeechRecognizer.ERROR_NETWORK:
                    case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: e = "network"; break;
                    default: e = "error";
                }
                putResult(id, "error", e, null);
            }
            @Override public void onReadyForSpeech(Bundle b) { }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float v) { }
            @Override public void onBufferReceived(byte[] bytes) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onPartialResults(Bundle b) { }
            @Override public void onEvent(int i, Bundle b) { }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        recognizer.startListening(i);
    }

    /** What the lesson page can call as window.LiaNative. Only voice features; nothing else is exposed. */
    public class Bridge {
        @JavascriptInterface public boolean ready() { return true; }

        /** The page calls this often; true means "the Back button was pressed and the page will handle it". */
        @JavascriptInterface public boolean takeBack() {
            if (!backPending) return false;
            backPending = false;
            return true;
        }

        @JavascriptInterface public void exit() {
            runOnUiThread(MainActivity.this::confirmExit);
        }

        @JavascriptInterface public boolean canSpeak() { return hindiVoiceReady; }

        @JavascriptInterface public void speak(String text, float rate) {
            runOnUiThread(() -> {
                if (!hindiVoiceReady || text == null) return;
                tts.setSpeechRate(rate > 0.3f && rate < 2f ? rate : 0.85f);
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lia" + ids.incrementAndGet());
            });
        }

        @JavascriptInterface public void stop() {
            runOnUiThread(() -> { if (tts != null) tts.stop(); });
        }

        @JavascriptInterface public boolean canListen() {
            return SpeechRecognizer.isRecognitionAvailable(MainActivity.this);
        }

        @JavascriptInterface public String listen(String lang) {
            final String id = "r" + ids.incrementAndGet();
            final String l = (lang == null || lang.isEmpty()) ? "hi-IN" : lang;
            if (!hasMic()) {
                putResult(id, "error", "permission", null);
                runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC));
                return id;
            }
            putResult(id, "pending", null, null);
            runOnUiThread(() -> startListening(id, l));
            return id;
        }

        @JavascriptInterface public String result(String id) {
            String r = results.get(id);
            if (r != null && !r.contains("\"pending\"")) results.remove(id);
            return r != null ? r : "{\"status\":\"error\",\"error\":\"error\"}";
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC && pendingWebPermission != null) {
            if (hasMic()) pendingWebPermission.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            else pendingWebPermission.deny();
            pendingWebPermission = null;
        }
    }

    // ---------------------------------------------------------------- lifecycle
    @Override
    public void onBackPressed() {
        if (backPending) return;
        backPending = true;
        main.postDelayed(() -> {
            if (!backPending) return;          // the lesson page handled it
            backPending = false;
            String url = web.getUrl() == null ? "" : web.getUrl();
            if (web.canGoBack() && !url.startsWith(APP_URL)) web.goBack();
            else confirmExit();
        }, 600);
    }

    private AlertDialog exitDialog;

    private void confirmExit() {
        if (exitDialog != null && exitDialog.isShowing()) return;
        exitDialog = new AlertDialog.Builder(this)
                .setTitle("Close Lia ki Hindi?")
                .setMessage("Progress is saved. You will only need your PIN next time.")
                .setPositiveButton("Close", (d, w) -> finish())
                .setNegativeButton("Stay", null)
                .show();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
        if (tts != null) tts.stop();
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) recognizer.destroy();
        if (tts != null) tts.shutdown();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}

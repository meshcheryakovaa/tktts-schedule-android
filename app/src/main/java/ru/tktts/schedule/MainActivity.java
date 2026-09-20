package ru.tktts.schedule;

import android.app.Activity;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int FILE_CHOOSER_REQUEST = 101;
    private WebView webView;
    private ValueCallback<Uri[]> fileChooserCallback;
    private final ExecutorService httpExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        webView.addJavascriptInterface(new AndroidScheduleBridge(), "AndroidSchedule");
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
                fileChooserCallback = filePathCallback;
                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                } catch (Exception error) {
                    fileChooserCallback = null;
                    return false;
                }
                return true;
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && fileChooserCallback != null) {
            Uri[] results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            fileChooserCallback.onReceiveValue(results);
            fileChooserCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        httpExecutor.shutdownNow();
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidSchedule");
            webView.destroy();
        }
        super.onDestroy();
    }

    public class AndroidScheduleBridge {
        @JavascriptInterface
        public void fetchTextAsync(String url, String requestId) {
            httpExecutor.execute(() -> {
                JSONObject result = fetchUrl(url);
                final String payload = result.toString();
                final String safeRequestId = JSONObject.quote(requestId == null ? "" : requestId);
                final String safePayload = JSONObject.quote(payload);
                runOnUiThread(() -> {
                    if (webView != null) {
                        webView.evaluateJavascript(
                                "window.__androidFetchComplete(" + safeRequestId + "," + safePayload + ");",
                                null
                        );
                    }
                });
            });
        }

        @JavascriptInterface
        public boolean isOnline() {
            return hasInternetConnection();
        }
    }

    private JSONObject fetchUrl(String value) {
        long started = System.currentTimeMillis();
        HttpURLConnection connection = null;
        JSONObject out = new JSONObject();
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(scheme) || host == null || !host.equalsIgnoreCase("rasp.tktts.ru")) {
                throw new SecurityException("Разрешены запросы только к https://rasp.tktts.ru");
            }

            URL url = uri.toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(18000);
            connection.setReadTimeout(22000);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8");
            connection.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.7");
            connection.setRequestProperty("Cache-Control", "no-cache");
            connection.setRequestProperty("Pragma", "no-cache");
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/150.0 Mobile Safari/537.36 TKTTS-Schedule/1.0");

            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 400 ? connection.getInputStream() : connection.getErrorStream();
            byte[] bytes = stream == null ? new byte[0] : readAll(stream);
            Charset charset = charsetFromContentType(connection.getContentType());
            String text = new String(bytes, charset);

            out.put("ok", status >= 200 && status < 300);
            out.put("status", status);
            out.put("text", text);
            out.put("elapsedMs", System.currentTimeMillis() - started);
            if (status < 200 || status >= 300) out.put("error", "HTTP " + status);
        } catch (Exception error) {
            try {
                out.put("ok", false);
                out.put("status", 0);
                out.put("text", "");
                out.put("elapsedMs", System.currentTimeMillis() - started);
                out.put("error", error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()));
            } catch (Exception ignored) { }
        } finally {
            if (connection != null) connection.disconnect();
        }
        return out;
    }

    private static byte[] readAll(InputStream input) throws Exception {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }

    private static Charset charsetFromContentType(String contentType) {
        if (contentType != null) {
            String lower = contentType.toLowerCase(Locale.ROOT);
            int idx = lower.indexOf("charset=");
            if (idx >= 0) {
                String name = contentType.substring(idx + 8).trim();
                int semicolon = name.indexOf(';');
                if (semicolon >= 0) name = name.substring(0, semicolon).trim();
                name = name.replace("\"", "");
                try { return Charset.forName(name); } catch (Exception ignored) { }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private boolean hasInternetConnection() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception ignored) {
            return true;
        }
    }
}

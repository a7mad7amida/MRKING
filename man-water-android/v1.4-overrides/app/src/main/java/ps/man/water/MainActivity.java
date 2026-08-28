package ps.man.water;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.RingtoneManager;
import android.net.*;
import android.os.*;
import android.webkit.*;
import java.io.*;
import java.net.*;
import java.util.List;
import java.util.Map;
import org.json.*;

public class MainActivity extends Activity {
    private WebView web;
    private ConnectivityManager connectivity;
    private final Handler syncHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoSync = () -> new Thread(() -> { syncNow(); refresh(); }).start();
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) {
            syncHandler.removeCallbacks(autoSync);
            syncHandler.post(autoSync);
            syncHandler.postDelayed(autoSync, 5000);
            syncHandler.postDelayed(autoSync, 15000);
        }
        @Override public void onLost(Network network) { syncHandler.removeCallbacks(autoSync); }
    };
    private final BroadcastReceiver updates = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refresh(); }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 30);
        web = new WebView(this);
        setContentView(web);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
        settings.setAllowFileAccess(true);
        web.addJavascriptInterface(new Bridge(), "MAN");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() { @Override public void onPageFinished(WebView view, String url) { refresh(); } });
        if (state == null) web.loadUrl("file:///android_asset/offline/index.html");
        registerReceiver(updates, new IntentFilter(TimerService.UPDATE), Build.VERSION.SDK_INT >= 33 ? Context.RECEIVER_NOT_EXPORTED : 0);
        connectivity = (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        connectivity.registerDefaultNetworkCallback(networkCallback);
    }

    private void refresh() { if (web != null) web.post(() -> web.evaluateJavascript("window.refreshNative&&window.refreshNative()", null)); }
    private SharedPreferences prefs() { return getSharedPreferences("man_water", MODE_PRIVATE); }

    @Override protected void onDestroy() {
        try { unregisterReceiver(updates); } catch (Exception ignored) {}
        try { connectivity.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
        syncHandler.removeCallbacks(autoSync);
        super.onDestroy();
    }

    public class Bridge {
        @JavascriptInterface public String state() { return TimerService.snapshot(MainActivity.this).toString(); }
        @JavascriptInterface public String archive() { return prefs().getString("archive", "[]"); }
        @JavascriptInterface public String queue() { return prefs().getString("queue", "[]"); }
        @JavascriptInterface public String accountStatus() {
            try { return new JSONObject().put("connected", prefs().getBoolean("account_connected", false)).put("username", prefs().getString("account_username", "")).put("last_sync", prefs().getLong("last_sync", 0)).toString(); }
            catch (Exception error) { return "{}"; }
        }
        @JavascriptInterface public String login(String username, String password) { return loginNow(username, password); }
        @JavascriptInterface public void enqueue(String name, int seconds) { startForegroundService(new Intent(MainActivity.this, TimerService.class).setAction(TimerService.ENQUEUE).putExtra("name", name).putExtra("seconds", seconds)); }
        @JavascriptInterface public void pause() { send(TimerService.PAUSE); }
        @JavascriptInterface public void resume() { send(TimerService.RESUME); }
        @JavascriptInterface public void finish() { send(TimerService.FINISH); }
        @JavascriptInterface public void removeQueued(String id) { startService(new Intent(MainActivity.this, TimerService.class).setAction(TimerService.REMOVE).putExtra("uuid", id)); }
        @JavascriptInterface public void chooseSound() { runOnUiThread(MainActivity.this::pickSound); }
        @JavascriptInterface public void testAlert() { startForegroundService(new Intent(MainActivity.this, TimerService.class).setAction(TimerService.TEST)); }
        @JavascriptInterface public String sync() { return syncNow(); }
        private void send(String action) { startService(new Intent(MainActivity.this, TimerService.class).setAction(action)); }
    }

    private String loginNow(String username, String password) {
        try {
            JSONObject body = new JSONObject().put("username", username.trim()).put("password", password);
            HttpURLConnection connection = post("https://man.ps/water?api=login", body, null);
            String response = read(connection.getResponseCode() < 400 ? connection.getInputStream() : connection.getErrorStream());
            JSONObject result = new JSONObject(response);
            if (!result.optBoolean("ok")) return response;
            String cookie = extractCookie(connection.getHeaderFields());
            if (cookie.isEmpty()) return new JSONObject().put("ok", false).put("error", "لم تصل جلسة الحساب من الموقع").toString();
            prefs().edit().putString("session_cookie", cookie).putBoolean("account_connected", true).putString("account_username", username.trim()).apply();
            android.webkit.CookieManager.getInstance().setCookie("https://man.ps/water", cookie);
            android.webkit.CookieManager.getInstance().flush();
            String syncResult = syncNow();
            refresh();
            return new JSONObject(syncResult).put("login", true).toString();
        } catch (Exception error) { return errorJson(error); }
    }

    private String syncNow() {
        try {
            String cookie = prefs().getString("session_cookie", "");
            if (cookie.isEmpty()) return new JSONObject().put("ok", false).put("error", "الحساب غير متصل").toString();
            JSONArray all = new JSONArray(prefs().getString("archive", "[]"));
            JSONArray items = new JSONArray();
            for (int i = 0; i < all.length(); i++) if (!all.getJSONObject(i).optBoolean("synced", false)) items.put(all.getJSONObject(i));
            if (items.length() == 0) { prefs().edit().putLong("last_sync", System.currentTimeMillis()).apply(); return new JSONObject().put("ok", true).put("count", 0).toString(); }
            HttpURLConnection connection = post("https://man.ps/water?api=sync_offline", new JSONObject().put("items", items), cookie);
            String response = read(connection.getResponseCode() < 400 ? connection.getInputStream() : connection.getErrorStream());
            JSONObject result = new JSONObject(response);
            if (connection.getResponseCode() == 401) prefs().edit().putBoolean("account_connected", false).remove("session_cookie").apply();
            if (!result.optBoolean("ok")) return response;
            JSONArray ids = result.optJSONArray("synced");
            for (int i = 0; i < all.length(); i++) for (int j = 0; ids != null && j < ids.length(); j++) if (all.getJSONObject(i).optString("uuid").equals(ids.optString(j))) all.getJSONObject(i).put("synced", true);
            prefs().edit().putString("archive", all.toString()).putLong("last_sync", System.currentTimeMillis()).putBoolean("account_connected", true).apply();
            return new JSONObject().put("ok", true).put("count", ids == null ? 0 : ids.length()).toString();
        } catch (Exception error) { return errorJson(error); }
    }

    private HttpURLConnection post(String url, JSONObject body, String cookie) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)new URL(url).openConnection();
        connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setConnectTimeout(12000); connection.setReadTimeout(12000);
        connection.setRequestProperty("Content-Type", "application/json"); if (cookie != null && !cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
        try (OutputStream output = connection.getOutputStream()) { output.write(body.toString().getBytes("UTF-8")); }
        return connection;
    }
    private String extractCookie(Map<String, List<String>> headers) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) if (entry.getKey() != null && entry.getKey().equalsIgnoreCase("Set-Cookie")) for (String value : entry.getValue()) if (value.startsWith("PHPSESSID=")) return value.split(";", 2)[0];
        return "";
    }
    private String errorJson(Exception error) { try { return new JSONObject().put("ok", false).put("error", error.getMessage()).toString(); } catch (Exception ignored) { return "{\"ok\":false}"; } }
    private String read(InputStream input) throws IOException { if (input == null) return "{}"; BufferedReader reader = new BufferedReader(new InputStreamReader(input)); StringBuilder text = new StringBuilder(); String line; while ((line = reader.readLine()) != null) text.append(line); return text.toString(); }
    private void pickSound() { Intent picker = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER); picker.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION); picker.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false); startActivityForResult(picker, 91); }
    @Override protected void onActivityResult(int request, int result, Intent data) { super.onActivityResult(request, result, data); if (request == 91 && result == RESULT_OK && data != null) { Uri selected = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI); if (selected != null) prefs().edit().putString("alert_uri", selected.toString()).apply(); } }
}

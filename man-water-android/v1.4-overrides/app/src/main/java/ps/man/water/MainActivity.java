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
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.*;

public class MainActivity extends Activity {
    private WebView web;
    private ConnectivityManager connectivity;
    private final Handler syncHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoSync = new Runnable() {
        @Override public void run() {
            new Thread(() -> { syncNow(); refresh(); }).start();
            syncHandler.postDelayed(this, 30000);
        }
    };
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) {
            syncHandler.removeCallbacks(autoSync);
            syncHandler.post(autoSync);
        }
        @Override public void onLost(Network network) { refresh(); }
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
        syncHandler.removeCallbacks(autoSync);
        syncHandler.post(autoSync);
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
            String response = createSession(username.trim(), password);
            JSONObject result = new JSONObject(response);
            if (!result.optBoolean("ok")) return response;
            saveCredentials(username.trim(), password);
            String syncResult = syncNow();
            refresh();
            return new JSONObject(syncResult).put("login", true).toString();
        } catch (Exception error) { return errorJson(error); }
    }

    private String syncNow() {
        try {
            String cookie = prefs().getString("session_cookie", "");
            if (cookie.isEmpty()) {
                String renewed = renewSession();
                if (!new JSONObject(renewed).optBoolean("ok")) return renewed;
                cookie = prefs().getString("session_cookie", "");
            }
            JSONArray all = new JSONArray(prefs().getString("archive", "[]"));
            JSONArray items = new JSONArray();
            for (int i = 0; i < all.length(); i++) if (!all.getJSONObject(i).optBoolean("synced", false)) items.put(all.getJSONObject(i));
            if (items.length() == 0) { prefs().edit().putLong("last_sync", System.currentTimeMillis()).apply(); return new JSONObject().put("ok", true).put("count", 0).toString(); }
            HttpURLConnection connection = post("https://man.ps/water/?api=sync_offline", new JSONObject().put("items", items), cookie);
            int code = connection.getResponseCode();
            String response = read(code < 400 ? connection.getInputStream() : connection.getErrorStream());
            if (code == 401 || code == 403) {
                String renewed = renewSession();
                if (!new JSONObject(renewed).optBoolean("ok")) return renewed;
                cookie = prefs().getString("session_cookie", "");
                connection = post("https://man.ps/water/?api=sync_offline", new JSONObject().put("items", items), cookie);
                code = connection.getResponseCode();
                response = read(code < 400 ? connection.getInputStream() : connection.getErrorStream());
            }
            JSONObject result = new JSONObject(response);
            if (!result.optBoolean("ok")) return response;
            JSONArray ids = result.optJSONArray("synced");
            for (int i = 0; i < all.length(); i++) for (int j = 0; ids != null && j < ids.length(); j++) if (all.getJSONObject(i).optString("uuid").equals(ids.optString(j))) all.getJSONObject(i).put("synced", true);
            prefs().edit().putString("archive", all.toString()).putLong("last_sync", System.currentTimeMillis()).putBoolean("account_connected", true).apply();
            return new JSONObject().put("ok", true).put("count", ids == null ? 0 : ids.length()).toString();
        } catch (Exception error) { return errorJson(error); }
    }

    private String createSession(String username, String password) throws Exception {
        JSONObject body = new JSONObject().put("username", username).put("password", password);
        HttpURLConnection connection = post("https://man.ps/water/?api=login", body, null);
        String response = read(connection.getResponseCode() < 400 ? connection.getInputStream() : connection.getErrorStream());
        JSONObject result = new JSONObject(response);
        if (!result.optBoolean("ok")) return response;
        String cookie = extractCookie(connection.getHeaderFields());
        if (cookie.isEmpty()) return new JSONObject().put("ok", false).put("error", "لم تصل جلسة الحساب من الموقع").toString();
        prefs().edit().putString("session_cookie", cookie).putBoolean("account_connected", true).putString("account_username", username).apply();
        android.webkit.CookieManager.getInstance().setCookie("https://man.ps/water", cookie);
        android.webkit.CookieManager.getInstance().flush();
        return new JSONObject().put("ok", true).toString();
    }

    private String renewSession() {
        try {
            String username = prefs().getString("account_username", "");
            String password = decrypt(prefs().getString("account_password", ""));
            if (username.isEmpty() || password.isEmpty()) return new JSONObject().put("ok", false).put("error", "يلزم اتصال الحساب مرة واحدة").toString();
            return createSession(username, password);
        } catch (Exception error) { return errorJson(error); }
    }

    private void saveCredentials(String username, String password) throws Exception {
        prefs().edit().putString("account_username", username).putString("account_password", encrypt(password)).putBoolean("account_connected", true).apply();
    }

    private SecretKey credentialKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias("man_water_account")) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder("man_water_account", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return ((KeyStore.SecretKeyEntry)store.getEntry("man_water_account", null)).getSecretKey();
    }

    private String encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, credentialKey());
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "." + Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    private String decrypt(String value) throws Exception {
        if (value == null || !value.contains(".")) return "";
        String[] parts = value.split("\\.", 2);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, credentialKey(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
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

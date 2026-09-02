package ps.man.water;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.RingtoneManager;
import android.net.*;
import android.os.*;
import android.provider.Settings;
import android.webkit.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.*;

public class MainActivity extends Activity {
    private WebView web;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private static final int RINGTONE_REQUEST = 44;
    private final BroadcastReceiver timerReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String payload = intent.getStringExtra("payload");
            if (payload != null) callJs("window.NativeApp&&NativeApp.onTimer(" + payload + ")");
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        setContentView(web);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMediaPlaybackRequiresUserGesture(false);
        web.setBackgroundColor(0xFFF4F8FA);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                callJs("window.NativeApp&&NativeApp.onTimer(" + TimerService.stateJson(MainActivity.this) + ")");
                callJs("window.NativeApp&&NativeApp.onConnection(" + (isOnline() ? "true" : "false") + ")");
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 55);
    }

    @Override protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(timerReceiver, new IntentFilter(TimerService.BROADCAST), RECEIVER_NOT_EXPORTED);
        else registerReceiver(timerReceiver, new IntentFilter(TimerService.BROADCAST));
        callJs("window.NativeApp&&NativeApp.onConnection(" + (isOnline() ? "true" : "false") + ")");
    }

    @Override protected void onPause() {
        try { unregisterReceiver(timerReceiver); } catch (Exception ignored) {}
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else moveTaskToBack(true);
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        Network n = cm.getActiveNetwork();
        NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
        return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private void callJs(String js) { runOnUiThread(() -> web.evaluateJavascript(js, null)); }

    private String api(String action, String body) throws Exception {
        URL url = new URL("https://man.ps/water/?api=" + action);
        HttpURLConnection c = (HttpURLConnection)url.openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(12000); c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        String cookie = getPreferences(MODE_PRIVATE).getString("cookie", "");
        if (!cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
        try(OutputStream os = c.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
        String setCookie = c.getHeaderField("Set-Cookie");
        if (setCookie != null && !setCookie.isEmpty()) getPreferences(MODE_PRIVATE).edit().putString("cookie", setCookie.split(";",2)[0]).apply();
        InputStream input = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        if (input == null) return "{\"ok\":false,\"error\":\"لا توجد استجابة من الخادم\"}";
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[4096]; int len;
        while ((len = input.read(buf)) > 0) out.write(buf,0,len);
        return out.toString("UTF-8");
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RINGTONE_REQUEST && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
            if (uri != null) {
                getSharedPreferences(TimerService.PREFS,MODE_PRIVATE).edit().putString("ringtone",uri.toString()).apply();
                callJs("window.NativeApp&&NativeApp.onRingtoneSelected(" + JSONObject.quote(uri.toString()) + ")");
            }
        }
    }

    public final class Bridge {
        @JavascriptInterface public String state() { return TimerService.stateJson(MainActivity.this); }
        @JavascriptInterface public boolean online() { return isOnline(); }
        @JavascriptInterface public void startTimer(String name, int seconds, String pump, String uuid) {
            Intent i = new Intent(MainActivity.this, TimerService.class).setAction(TimerService.START);
            i.putExtra("name",name); i.putExtra("seconds",seconds); i.putExtra("pump",pump); i.putExtra("uuid",uuid);
            startForegroundService(i);
        }
        @JavascriptInterface public void pauseTimer() { startForegroundService(new Intent(MainActivity.this,TimerService.class).setAction(TimerService.PAUSE)); }
        @JavascriptInterface public void resumeTimer() { startForegroundService(new Intent(MainActivity.this,TimerService.class).setAction(TimerService.RESUME)); }
        @JavascriptInterface public void stopTimer() { startForegroundService(new Intent(MainActivity.this,TimerService.class).setAction(TimerService.STOP)); }
        @JavascriptInterface public void acknowledgeFinished() { getSharedPreferences(TimerService.PREFS,MODE_PRIVATE).edit().putString("status","idle").apply(); }
        @JavascriptInterface public void saveAlertSettings(String json) { getSharedPreferences(TimerService.PREFS,MODE_PRIVATE).edit().putString("alerts",json).apply(); }
        @JavascriptInterface public void chooseRingtone() {
            runOnUiThread(() -> {
                Intent i = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
                i.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE,RingtoneManager.TYPE_NOTIFICATION|RingtoneManager.TYPE_ALARM);
                i.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT,false);
                startActivityForResult(i,RINGTONE_REQUEST);
            });
        }
        @JavascriptInterface public void openNotificationSettings() {
            runOnUiThread(() -> startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName())));
        }
        @JavascriptInterface public void login(String username, String password) {
            network.execute(() -> { try { callJs("window.NativeApp.onLogin(" + api("login",new JSONObject().put("username",username).put("password",password).toString()) + ")"); }
                catch(Exception e) { callJs("window.NativeApp.onLogin({ok:false,error:"+JSONObject.quote(e.getMessage())+"})"); } });
        }
        @JavascriptInterface public void sync(String itemsJson) {
            network.execute(() -> { try { callJs("window.NativeApp.onSync(" + api("sync_offline",new JSONObject().put("items",new JSONArray(itemsJson)).toString()) + ")"); }
                catch(Exception e) { callJs("window.NativeApp.onSync({ok:false,error:"+JSONObject.quote(e.getMessage())+"})"); } });
        }
    }
}

package ps.man.water;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.media.*;
import android.net.Uri;
import android.os.*;
import java.util.*;
import org.json.*;

public class TimerService extends Service {
    public static final String PREFS="man_timer", BROADCAST="ps.man.water.TIMER";
    public static final String START="START", PAUSE="PAUSE", RESUME="RESUME", STOP="STOP", RESTORE="RESTORE";
    private static final String CHANNEL_TIMER="man_water_timer", CHANNEL_ALERT="man_water_alerts";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<Integer> fired = new HashSet<>();
    private Runnable tick;

    @Override public void onCreate() { super.onCreate(); createChannels(); }
    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) intent = new Intent().setAction(RESTORE);
        String action = intent.getAction();
        if (START.equals(action)) begin(intent);
        else if (PAUSE.equals(action)) pause();
        else if (RESUME.equals(action)) resume();
        else if (STOP.equals(action)) finish(false);
        else restore();
        return START_STICKY;
    }

    private void begin(Intent i) {
        long now=System.currentTimeMillis(); int seconds=Math.max(1,i.getIntExtra("seconds",60));
        prefs().edit().putString("status","running").putString("name",i.getStringExtra("name"))
            .putString("pump",i.getStringExtra("pump")).putString("uuid",i.getStringExtra("uuid"))
            .putLong("startedAt",now).putLong("endAt",now+seconds*1000L).putInt("duration",seconds)
            .putInt("pausedRemaining",0).putLong("pausedAt",0).putLong("pausedTotal",0).apply();
        fired.clear(); startLoop();
    }

    private void beginNextFromQueue() {
        try {
            JSONArray queue=new JSONArray(prefs().getString("queue","[]"));
            if(queue.length()==0){goIdle();return;}
            JSONObject item=queue.getJSONObject(0);long now=System.currentTimeMillis();int seconds=Math.max(1,item.optInt("seconds",60));
            item.put("startedAt",now).put("start_at",new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX",Locale.US).format(new Date(now)));
            prefs().edit().putString("queue",queue.toString()).putString("status","running")
                .putString("name",item.optString("name","المشترك")).putString("pump",item.optString("pump","الغاطس"))
                .putString("uuid",item.optString("uuid",item.optString("id",""))).putLong("startedAt",now)
                .putLong("endAt",now+seconds*1000L).putInt("duration",seconds).putInt("pausedRemaining",0)
                .putLong("pausedAt",0).putLong("pausedTotal",0).putLong("transitionEndAt",0).apply();
            fired.clear();broadcast();
        } catch(Exception e){goIdle();}
    }

    private void pause() {
        if(!"running".equals(prefs().getString("status","idle")))return;
        int left=remaining(); prefs().edit().putString("status","paused").putInt("pausedRemaining",left).putLong("pausedAt",System.currentTimeMillis()).apply();
        updateNotification(left,"متوقف مؤقتًا"); broadcast();
    }

    private void resume() {
        if(!"paused".equals(prefs().getString("status","idle")))return;
        int left=Math.max(1,prefs().getInt("pausedRemaining",1));
        long pausedAt=prefs().getLong("pausedAt",System.currentTimeMillis());long added=Math.max(0,(System.currentTimeMillis()-pausedAt)/1000);
        prefs().edit().putString("status","running").putLong("endAt",System.currentTimeMillis()+left*1000L).putInt("pausedRemaining",0).putLong("pausedTotal",prefs().getLong("pausedTotal",0)+added).apply();
        startLoop();
    }

    private void restore() {
        String status=prefs().getString("status","idle");
        if("running".equals(status)||"paused".equals(status)||"transition".equals(status))startLoop(); else stopSelf();
    }

    private void startLoop() {
        if(tick!=null)handler.removeCallbacks(tick);
        tick=new Runnable(){@Override public void run(){String status=prefs().getString("status","idle");int left=remaining();
            if("running".equals(status)){checkAlerts(left);if(left<=0){finish(true);return;}}
            else if("transition".equals(status)&&left<=0){beginNextFromQueue();status=prefs().getString("status","idle");left=remaining();}
            if("idle".equals(status)){goIdle();return;}
            updateNotification(left,"paused".equals(status)?"متوقف مؤقتًا":"transition".equals(status)?"فاصل انتقال 10 ثوانٍ":"العداد يعمل");broadcast();handler.postDelayed(this,250);}};
        handler.post(tick);
    }

    private int remaining() {
        String status=prefs().getString("status","idle");
        if("paused".equals(status))return Math.max(0,prefs().getInt("pausedRemaining",0));
        long target="transition".equals(status)?prefs().getLong("transitionEndAt",0):prefs().getLong("endAt",0);
        return (int)Math.max(0,(target-System.currentTimeMillis()+999)/1000);
    }

    private void checkAlerts(int left) {
        try {
            String raw=prefs().getString("alerts","[{\"before\":90,\"duration\":5,\"enabled\":true},{\"before\":60,\"duration\":5,\"enabled\":true},{\"before\":0,\"duration\":5,\"enabled\":true}]");
            JSONArray rules=new JSONArray(raw);
            for(int x=0;x<rules.length();x++){JSONObject r=rules.getJSONObject(x);int before=r.optInt("before",0);if(before>0&&r.optBoolean("enabled",true)&&left<=before&&!fired.contains(before)){fired.add(before);alert(r.optString("label","تنبيه العداد"),r.optInt("duration",5));}}
        } catch(Exception ignored) {}
    }

    private void finish(boolean natural) {
        if(tick!=null)handler.removeCallbacks(tick);
        long finishedAt=System.currentTimeMillis();
        archiveCompletion(finishedAt,natural);
        alert(natural?"انتهى وقت المشترك":"تم إنهاء العداد",natural?finalDuration():3);
        try {
            JSONArray queue=new JSONArray(prefs().getString("queue","[]"));String uuid=prefs().getString("uuid","");
            for(int i=0;i<queue.length();i++)if(uuid.equals(queue.getJSONObject(i).optString("uuid"))){queue.remove(i);break;}
            SharedPreferences.Editor edit=prefs().edit().putString("queue",queue.toString()).putLong("finishedAt",finishedAt).putBoolean("natural",natural);
            if(queue.length()>0){long transitionEnd=finishedAt+10000L;edit.putString("status","transition").putLong("transitionEndAt",transitionEnd).apply();startLoop();}
            else {edit.putString("status","idle").putLong("transitionEndAt",0).apply();broadcast();goIdle();}
        } catch(Exception e){goIdle();}
    }

    private void archiveCompletion(long finishedAt,boolean natural){
        try{
            JSONArray completed=new JSONArray(prefs().getString("completed","[]"));String uuid=prefs().getString("uuid","");
            for(int i=0;i<completed.length();i++)if(uuid.equals(completed.getJSONObject(i).optString("uuid")))return;
            int duration=prefs().getInt("duration",0),left=remaining();
            completed.put(new JSONObject().put("uuid",uuid).put("name",prefs().getString("name","المشترك"))
                .put("pump",prefs().getString("pump","الغاطس")).put("startedAt",prefs().getLong("startedAt",finishedAt-duration*1000L))
                .put("finishedAt",finishedAt).put("duration",duration).put("remaining",left)
                .put("pausedTotal",prefs().getLong("pausedTotal",0)).put("natural",natural));
            prefs().edit().putString("completed",completed.toString()).apply();
            BackgroundSync.schedule(this);
        }catch(Exception ignored){}
    }

    private void goIdle(){
        if(tick!=null)handler.removeCallbacks(tick);
        NotificationManager nm=getSystemService(NotificationManager.class);nm.cancel(1001);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
    }

    private void createChannels() {
        NotificationManager nm=getSystemService(NotificationManager.class);
        NotificationChannel timer=new NotificationChannel(CHANNEL_TIMER,"العداد الجاري",NotificationManager.IMPORTANCE_LOW);timer.setSound(null,null);timer.setShowBadge(true);timer.setDescription("إظهار العداد والوقت المتبقي بجانب الساعة");nm.createNotificationChannel(timer);
        NotificationChannel alerts=new NotificationChannel(CHANNEL_ALERT,"تنبيهات انتهاء الوقت",NotificationManager.IMPORTANCE_HIGH);alerts.enableVibration(true);alerts.setVibrationPattern(new long[]{0,300,180,300});alerts.setSound(null,null);alerts.setLightColor(Color.CYAN);alerts.enableLights(true);nm.createNotificationChannel(alerts);
    }

    private void updateNotification(int left,String state) {
        Intent open=new Intent(this,MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        String status=prefs().getString("status","idle");String name="transition".equals(status)?"المشترك التالي":prefs().getString("name","المشترك");String pump=prefs().getString("pump","الغاطس");
        Notification.Builder b=new Notification.Builder(this,CHANNEL_TIMER).setSmallIcon(ps.man.water.R.drawable.ic_water).setContentTitle(name+" • "+format(left)).setContentText(pump+" — "+state).setContentIntent(content).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_STOPWATCH).setVisibility(Notification.VISIBILITY_PUBLIC).setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        if(!"transition".equals(status)){if("paused".equals(status))b.addAction(new Notification.Action.Builder(null,"استئناف",serviceAction(RESUME,2)).build());else b.addAction(new Notification.Action.Builder(null,"إيقاف مؤقت",serviceAction(PAUSE,1)).build());b.addAction(new Notification.Action.Builder(null,"إنهاء",serviceAction(STOP,3)).build());}
        startForeground(1001,b.build());
    }

    private PendingIntent serviceAction(String action,int code){return PendingIntent.getService(this,code,new Intent(this,TimerService.class).setAction(action),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}

    private void alert(String title,int duration) {
        String name=prefs().getString("name","المشترك");
        Notification n=new Notification.Builder(this,CHANNEL_ALERT).setSmallIcon(ps.man.water.R.drawable.ic_water).setContentTitle(title).setContentText(name+" — "+prefs().getString("pump","الغاطس")).setAutoCancel(true).setPriority(Notification.PRIORITY_MAX).setCategory(Notification.CATEGORY_ALARM).setVisibility(Notification.VISIBILITY_PUBLIC).build();
        getSystemService(NotificationManager.class).notify((int)(System.currentTimeMillis()%100000),n);
        try { String saved=prefs().getString("ringtone","");Uri uri=saved.isEmpty()?RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM):Uri.parse(saved);Ringtone ring=RingtoneManager.getRingtone(this,uri);if(Build.VERSION.SDK_INT>=28)ring.setLooping(true);ring.play();handler.postDelayed(ring::stop,Math.max(1,Math.min(30,duration))*1000L); } catch(Exception ignored) {}
        Vibrator v=(Vibrator)getSystemService(VIBRATOR_SERVICE);if(v!=null)v.vibrate(VibrationEffect.createWaveform(new long[]{0,400,180,400,180,400},-1));
    }

    private int finalDuration(){try{JSONArray a=new JSONArray(prefs().getString("alerts","[]"));for(int i=0;i<a.length();i++){JSONObject r=a.getJSONObject(i);if(r.optInt("before",-1)==0&&r.optBoolean("enabled",true))return Math.max(1,Math.min(30,r.optInt("duration",5)));}}catch(Exception ignored){}return 5;}

    private void broadcast() { sendBroadcast(new Intent(BROADCAST).setPackage(getPackageName()).putExtra("payload",stateJson(this))); }
    private SharedPreferences prefs(){return getSharedPreferences(PREFS,MODE_PRIVATE);}
    private static String format(int s){return String.format(Locale.US,"%02d:%02d:%02d",s/3600,(s%3600)/60,s%60);}

    public static String stateJson(Context c) {
        SharedPreferences p=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);String status=p.getString("status","idle");long now=System.currentTimeMillis();long target="transition".equals(status)?p.getLong("transitionEndAt",0):p.getLong("endAt",0);int left="paused".equals(status)?p.getInt("pausedRemaining",0):(int)Math.max(0,(target-now+999)/1000);
        try{return new JSONObject().put("status",status).put("name",p.getString("name","")).put("pump",p.getString("pump","")).put("uuid",p.getString("uuid","")).put("startedAt",p.getLong("startedAt",0)).put("endAt",p.getLong("endAt",0)).put("transitionEndAt",p.getLong("transitionEndAt",0)).put("finishedAt",p.getLong("finishedAt",0)).put("duration",p.getInt("duration",0)).put("remaining",left).put("pausedTotal",p.getLong("pausedTotal",0)).put("natural",p.getBoolean("natural",false)).put("queue",new JSONArray(p.getString("queue","[]"))).put("completed",new JSONArray(p.getString("completed","[]"))).put("synced",new JSONArray(p.getString("synced_native","[]"))).toString();}catch(Exception e){return "{\"status\":\"idle\"}";}
    }
}

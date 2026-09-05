package ps.man.water;

import android.content.*;
import androidx.annotation.NonNull;
import androidx.work.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.json.*;

public class BackgroundSync extends Worker {
    private static final String UNIQUE_NOW="man-water-sync-now";

    public BackgroundSync(@NonNull Context context,@NonNull WorkerParameters params){super(context,params);}

    public static void schedule(Context context){
        SharedPreferences timer=context.getSharedPreferences(TimerService.PREFS,Context.MODE_PRIVATE);
        SharedPreferences account=context.getSharedPreferences(MainActivity.ACCOUNT_PREFS,Context.MODE_PRIVATE);
        if(!account.getBoolean("locked",false))return;
        if("[]".equals(timer.getString("pending","[]"))&&"[]".equals(timer.getString("completed","[]")))return;
        Constraints constraints=new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        OneTimeWorkRequest request=new OneTimeWorkRequest.Builder(BackgroundSync.class).setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,10,TimeUnit.SECONDS).build();
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniqueWork(UNIQUE_NOW,ExistingWorkPolicy.REPLACE,request);
    }

    @NonNull @Override public Result doWork(){
        Context context=getApplicationContext();SharedPreferences timer=context.getSharedPreferences(TimerService.PREFS,Context.MODE_PRIVATE);
        SharedPreferences account=context.getSharedPreferences(MainActivity.ACCOUNT_PREFS,Context.MODE_PRIVATE);
        String cookie=account.getString("cookie","");if(cookie.isEmpty()||!account.getBoolean("locked",false))return Result.retry();
        try{
            JSONArray items=new JSONArray(timer.getString("pending","[]"));JSONArray completed=new JSONArray(timer.getString("completed","[]"));
            JSONObject accountJson=new JSONObject(account.getString("account","{}"));Set<String> ids=new HashSet<>();
            for(int i=0;i<items.length();i++)ids.add(items.getJSONObject(i).optString("uuid"));
            for(int i=0;i<completed.length();i++){
                JSONObject c=completed.getJSONObject(i);String uuid=c.optString("uuid");if(uuid.isEmpty()||ids.contains(uuid))continue;
                int duration=c.optInt("duration",0),remaining=c.optInt("remaining",0),seconds=Math.max(1,duration-remaining);
                items.put(new JSONObject().put("uuid",uuid).put("name",c.optString("name")).put("subscriber_name",c.optString("name"))
                    .put("pump",c.optString("pump")).put("pump_id",accountJson.optInt("pump_id",0))
                    .put("start_at",iso(c.optLong("startedAt",c.optLong("finishedAt")-seconds*1000L)))
                    .put("end_at",iso(c.optLong("finishedAt",System.currentTimeMillis()))).put("seconds",seconds)
                    .put("planned_seconds",duration).put("paused_seconds",c.optLong("pausedTotal",0)));
                ids.add(uuid);
            }
            if(items.length()==0)return Result.success();
            JSONObject request=new JSONObject().put("items",items);JSONObject response=post("sync_offline",cookie,request);
            if(!response.optBoolean("ok",false)&&AuthStore.ready(context)){
                JSONObject login=post("login",cookie,new JSONObject().put("username",AuthStore.username(context)).put("password",AuthStore.password(context)));
                if(login.optBoolean("ok",false)){cookie=account.getString("cookie","");response=post("sync_offline",cookie,request);}
            }
            if(!response.optBoolean("ok",false)){account.edit().putBoolean("reauth_required",!AuthStore.ready(context)).apply();return Result.retry();}
            JSONArray done=response.optJSONArray("synced");if(done==null)done=new JSONArray();Set<String> doneIds=new HashSet<>();
            JSONArray ack=new JSONArray(timer.getString("synced_native","[]"));for(int i=0;i<ack.length();i++)doneIds.add(ack.optString(i));
            for(int i=0;i<done.length();i++){String id=done.optString(i);if(!id.isEmpty()&&doneIds.add(id))ack.put(id);}
            JSONArray left=new JSONArray();for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);if(!doneIds.contains(item.optString("uuid")))left.put(item);}
            timer.edit().putString("pending",left.toString()).putString("synced_native",ack.toString()).apply();return Result.success();
        }catch(Exception e){return Result.retry();}
    }

    private JSONObject post(String action,String cookie,JSONObject body)throws Exception{
        URL url=new URL("https://man.ps/water/?api="+action);HttpURLConnection c=(HttpURLConnection)url.openConnection();
        c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setRequestMethod("POST");c.setDoOutput(true);
        c.setRequestProperty("Content-Type","application/json; charset=utf-8");c.setRequestProperty("Cookie",cookie);
        try(OutputStream os=c.getOutputStream()){os.write(body.toString().getBytes(StandardCharsets.UTF_8));}
        String setCookie=c.getHeaderField("Set-Cookie");if(setCookie!=null&&!setCookie.isEmpty())getApplicationContext().getSharedPreferences(MainActivity.ACCOUNT_PREFS,Context.MODE_PRIVATE).edit().putString("cookie",setCookie.split(";",2)[0]).apply();
        InputStream in=c.getResponseCode()>=400?c.getErrorStream():c.getInputStream();if(in==null)throw new IOException("empty response");
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))>0)out.write(buffer,0,n);
        return new JSONObject(out.toString("UTF-8"));
    }

    private static String iso(long time){return new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX",Locale.US).format(new Date(time));}
}

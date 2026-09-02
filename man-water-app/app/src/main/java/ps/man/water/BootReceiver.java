package ps.man.water;

import android.content.*;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String status = context.getSharedPreferences(TimerService.PREFS,Context.MODE_PRIVATE).getString("status","idle");
        if (!"idle".equals(status) && !"finished".equals(status)) {
            Intent service = new Intent(context,TimerService.class).setAction(TimerService.RESTORE);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service); else context.startService(service);
        }
    }
}

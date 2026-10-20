package com.kachare.liahindi;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Fires when Lia's iPad time is over and starts the 30-second spoken alarm on the phone that started the timer. */
public class TimerReceiver extends BroadcastReceiver {
    static final int NOTIFY_ID = 4242;

    static PendingIntent pending(Context c, String title, String text) {
        Intent i = new Intent(c, TimerReceiver.class);
        if (title != null) i.putExtra("title", title);
        if (text != null) i.putExtra("text", text);
        return PendingIntent.getBroadcast(c, 1, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void schedule(Context c, long atMs, String title, String text) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = pending(c, title, text);
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi);
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi);
        }
    }

    static void cancel(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pending(c, null, null));
        AlarmService.timerStopped = true;
        c.stopService(new Intent(c, AlarmService.class));
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIFY_ID);
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        Intent svc = new Intent(c, AlarmService.class);
        svc.putExtra("title", intent.getStringExtra("title"));
        svc.putExtra("text", intent.getStringExtra("text"));
        c.startForegroundService(svc);
    }
}

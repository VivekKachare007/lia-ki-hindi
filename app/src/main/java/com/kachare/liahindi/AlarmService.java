package com.kachare.liahindi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;

/**
 * Says "Hey Lia, your iPad time is over" out loud for 30 seconds, on the alarm volume,
 * even when the app is closed. Started by TimerReceiver when the alarm time comes.
 */
public class AlarmService extends Service {
    static final String ACTION_STOP = "com.kachare.liahindi.STOP_ALARM";
    static final String CHANNEL = "ipad_time_voice";
    static final int NOTIFY_ID = 4243;
    static final long DURATION_MS = 30_000;

    private TextToSpeech tts;
    private ToneGenerator tone;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long until;
    private String words = "Hey Lia, your iPad time is over";
    private boolean stopping = false;
    /** Set when Papa presses Stop timer: end silently, with no reminder. */
    static volatile boolean timerStopped = false;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        timerStopped = false;
        if (intent != null && intent.getStringExtra("text") != null) words = intent.getStringExtra("text");
        startInForeground(intent != null ? intent.getStringExtra("title") : null);

        until = System.currentTimeMillis() + DURATION_MS;
        handler.postDelayed(this::stopSelf, DURATION_MS + 1500);
        vibrate();
        try { tone = new ToneGenerator(AudioManager.STREAM_ALARM, 90); } catch (Exception ignored) { }
        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) { beepLoop(); return; }
            int r = tts.setLanguage(new Locale("en", "IN"));
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.ENGLISH);
            tts.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            tts.setSpeechRate(0.95f);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) { handler.postDelayed(AlarmService.this::sayOnce, 900); }
                @Override public void onError(String id) { handler.postDelayed(AlarmService.this::sayOnce, 900); }
            });
            sayOnce();
        });
        return START_NOT_STICKY;
    }

    private void sayOnce() {
        if (stopping || System.currentTimeMillis() > until) { stopSelf(); return; }
        if (tone != null) try { tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 400); } catch (Exception ignored) { }
        handler.postDelayed(() -> {
            if (stopping || tts == null) return;
            Bundle p = new Bundle();
            p.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM);
            tts.speak(words, TextToSpeech.QUEUE_FLUSH, p, "lia-alarm-" + System.currentTimeMillis());
        }, 500);
    }

    private void beepLoop() {
        if (stopping || System.currentTimeMillis() > until) { stopSelf(); return; }
        if (tone != null) try { tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 800); } catch (Exception ignored) { }
        handler.postDelayed(this::beepLoop, 1500);
    }

    private void vibrate() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(VibrationEffect.createWaveform(new long[]{0, 700, 400, 700, 400, 700, 400, 700}, -1));
        } catch (Exception ignored) { }
    }

    private void startInForeground(String title) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "iPad time alarm", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Says when Lia's iPad time is over");
        ch.setSound(null, null); // the voice is the sound
        if (nm != null) nm.createNotificationChannel(ch);

        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopPi = PendingIntent.getService(this, 3, new Intent(this, AlarmService.class).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_timer)
                .setContentTitle(title != null ? title : "Lia’s iPad time is over")
                .setContentText("Open the app and press Stop timer when the iPad is put away.")
                .setCategory(Notification.CATEGORY_ALARM)
                .setContentIntent(openPi)
                .addAction(new Notification.Action.Builder(null, "Silence", stopPi).build())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NOTIFY_ID, n);
    }

    @Override
    public void onDestroy() {
        stopping = true;
        handler.removeCallbacksAndMessages(null);
        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; }
        if (tone != null) { tone.release(); tone = null; }
        try { ((Vibrator) getSystemService(Context.VIBRATOR_SERVICE)).cancel(); } catch (Exception ignored) { }
        stopForeground(STOP_FOREGROUND_REMOVE);
        // Leave a quiet reminder that the timer is still counting.
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && !timerStopped) {
            Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent openPi = PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            nm.notify(TimerReceiver.NOTIFY_ID, new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_timer)
                    .setContentTitle("Lia is over her iPad time")
                    .setContentText("The timer keeps counting. Open the app and press Stop timer.")
                    .setContentIntent(openPi)
                    .setAutoCancel(true)
                    .build());
        }
        super.onDestroy();
    }
}

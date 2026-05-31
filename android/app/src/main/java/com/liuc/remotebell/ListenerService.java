package com.liuc.remotebell;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class ListenerService extends Service implements TextToSpeech.OnInitListener {
    private static final String CHANNEL_LISTEN = "remote_bell_listening";
    private static final String CHANNEL_ALARM = "remote_bell_alarm";
    private static final int NOTIFICATION_ID = 41;

    private final Object listenLock = new Object();
    private Handler mainHandler;
    private Thread listenerThread;
    private volatile boolean listening;
    private volatile HttpURLConnection currentConnection;
    private MediaPlayer mediaPlayer;
    private TextToSpeech textToSpeech;
    private Runnable speakRunnable;
    private String currentMessage;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;
    private String server;
    private String topic;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        createChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? Constants.ACTION_START : intent.getAction();
        if (Constants.ACTION_STOP_LISTENER.equals(action)) {
            stopAlarm();
            stopListening();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (Constants.ACTION_STOP_ALARM.equals(action)) {
            stopAlarm();
            if (listening) {
                startAsForeground(buildListeningNotification("正在监听频道：" + topic), false);
            } else {
                stopForeground(true);
                stopSelf();
            }
            return START_STICKY;
        }
        if (Constants.ACTION_LOCAL_ALARM.equals(action)) {
            startAsForeground(buildListeningNotification("本机测试响铃中"), false);
            String message = intent.getStringExtra(Constants.EXTRA_MESSAGE);
            triggerAlarm(message == null ? "这是一条本机测试提醒" : message);
            return START_STICKY;
        }

        SharedPreferences prefs = getSharedPreferences(Constants.PREFS, MODE_PRIVATE);
        server = valueOrDefault(
            intent == null ? null : intent.getStringExtra(Constants.EXTRA_SERVER),
            prefs.getString(Constants.PREF_SERVER, Constants.DEFAULT_SERVER)
        ).replaceAll("/+$", "");
        topic = valueOrDefault(
            intent == null ? null : intent.getStringExtra(Constants.EXTRA_TOPIC),
            prefs.getString(Constants.PREF_TOPIC, Constants.DEFAULT_TOPIC)
        );
        prefs.edit()
            .putString(Constants.PREF_SERVER, server)
            .putString(Constants.PREF_TOPIC, topic)
            .apply();

        startAsForeground(buildListeningNotification("正在监听频道：" + topic), false);
        startListening();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopListening();
        stopAlarm();
        if (textToSpeech != null) {
            textToSpeech.shutdown();
            textToSpeech = null;
        }
        super.onDestroy();
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS && textToSpeech != null) {
            textToSpeech.setLanguage(Locale.getDefault());
            startSpeakingLoop();
        }
    }

    private void startListening() {
        synchronized (listenLock) {
            stopListening();
            listening = true;
            listenerThread = new Thread(this::listenLoop, "RemoteBellListener");
            listenerThread.start();
        }
    }

    private void stopListening() {
        listening = false;
        HttpURLConnection connection = currentConnection;
        if (connection != null) {
            connection.disconnect();
        }
        Thread thread = listenerThread;
        if (thread != null) {
            thread.interrupt();
        }
        listenerThread = null;
    }

    private void listenLoop() {
        int backoffMs = 1500;
        while (listening) {
            try {
                String endpoint = server + "/" + Uri.encode(topic) + "/json";
                HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
                currentConnection = connection;
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(0);
                connection.setRequestProperty("User-Agent", "RemoteBellAndroid/1.0");

                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    backoffMs = 1500;
                    String line;
                    while (listening && (line = reader.readLine()) != null) {
                        handleNtfyLine(line);
                    }
                }
            } catch (Exception ignored) {
                sleep(backoffMs);
                backoffMs = Math.min(backoffMs * 2, 30000);
            } finally {
                HttpURLConnection connection = currentConnection;
                if (connection != null) {
                    connection.disconnect();
                }
                currentConnection = null;
            }
        }
    }

    private void handleNtfyLine(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        try {
            JSONObject object = new JSONObject(trimmed);
            String event = object.optString("event", "message");
            if (!"message".equals(event)) {
                return;
            }
            String message = object.optString("message", "收到远程提醒").trim();
            if (message.isEmpty()) {
                message = "收到远程提醒";
            }
            triggerAlarm(message);
        } catch (Exception ignored) {
        }
    }

    private void triggerAlarm(String message) {
        mainHandler.post(() -> startAlarm(message));
    }

    private void startAlarm(String message) {
        currentMessage = message;
        acquireWakeLock();
        startRingtone();
        startVibration();
        startTextToSpeech();

        Intent alarmIntent = new Intent(this, AlarmActivity.class);
        alarmIntent.putExtra(Constants.EXTRA_MESSAGE, message);
        alarmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        PendingIntent contentIntent = PendingIntent.getActivity(
            this,
            10,
            alarmIntent,
            pendingFlags(PendingIntent.FLAG_UPDATE_CURRENT)
        );

        Notification notification = buildAlarmNotification(message, contentIntent);
        startAsForeground(notification, true);

        try {
            startActivity(alarmIntent);
        } catch (Exception ignored) {
        }
    }

    private void stopAlarm() {
        currentMessage = null;
        if (speakRunnable != null) {
            mainHandler.removeCallbacks(speakRunnable);
            speakRunnable = null;
        }
        if (textToSpeech != null) {
            textToSpeech.stop();
        }
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } catch (Exception ignored) {
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }
        if (vibrator != null) {
            vibrator.cancel();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private void startRingtone() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            return;
        }
        try {
            Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (soundUri == null) {
                soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            }
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(this, soundUri);
            mediaPlayer.setLooping(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            }
            mediaPlayer.prepare();
            mediaPlayer.start();
        } catch (Exception ignored) {
            if (mediaPlayer != null) {
                mediaPlayer.release();
                mediaPlayer = null;
            }
        }
    }

    private void startVibration() {
        if (vibrator == null) {
            return;
        }
        long[] pattern = new long[]{0, 500, 300, 500, 800};
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
        } else {
            vibrator.vibrate(pattern, 0);
        }
    }

    private void startTextToSpeech() {
        if (textToSpeech == null) {
            textToSpeech = new TextToSpeech(this, this);
        } else {
            startSpeakingLoop();
        }
    }

    private void startSpeakingLoop() {
        if (currentMessage == null || textToSpeech == null) {
            return;
        }
        if (speakRunnable != null) {
            mainHandler.removeCallbacks(speakRunnable);
        }
        speakRunnable = new Runnable() {
            @Override
            public void run() {
                if (currentMessage == null || textToSpeech == null) {
                    return;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    textToSpeech.speak(currentMessage, TextToSpeech.QUEUE_FLUSH, null, "remote-bell-message");
                } else {
                    textToSpeech.speak(currentMessage, TextToSpeech.QUEUE_FLUSH, null);
                }
                long delay = Math.max(6000, Math.min(18000, currentMessage.length() * 450L));
                mainHandler.postDelayed(this, delay);
            }
        };
        mainHandler.post(speakRunnable);
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            return;
        }
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RemoteBell:Alarm");
            wakeLock.acquire(30 * 60 * 1000L);
        }
    }

    private Notification buildListeningNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(this, 1, openIntent, pendingFlags(PendingIntent.FLAG_UPDATE_CURRENT));

        Intent stopIntent = new Intent(this, ListenerService.class);
        stopIntent.setAction(Constants.ACTION_STOP_LISTENER);
        PendingIntent stopPending = PendingIntent.getService(this, 2, stopIntent, pendingFlags(PendingIntent.FLAG_UPDATE_CURRENT));

        Notification.Builder builder = builder(CHANNEL_LISTEN)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Remote Bell")
            .setContentText(text)
            .setContentIntent(openPending)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "停止监听", stopPending);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setPriority(Notification.PRIORITY_LOW);
        }
        return builder.build();
    }

    private void startAsForeground(Notification notification, boolean alarm) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int type = alarm
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                : ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            startForeground(NOTIFICATION_ID, notification, type);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildAlarmNotification(String message, PendingIntent contentIntent) {
        Intent stopIntent = new Intent(this, ListenerService.class);
        stopIntent.setAction(Constants.ACTION_STOP_ALARM);
        PendingIntent stopPending = PendingIntent.getService(this, 3, stopIntent, pendingFlags(PendingIntent.FLAG_UPDATE_CURRENT));

        Notification.Builder builder = builder(CHANNEL_ALARM)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("远程提醒")
            .setContentText(message)
            .setStyle(new Notification.BigTextStyle().bigText(message))
            .setContentIntent(contentIntent)
            .setFullScreenIntent(contentIntent, true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "停止响铃", stopPending);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setPriority(Notification.PRIORITY_MAX);
        }
        return builder.build();
    }

    private Notification.Builder builder(String channelId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, channelId);
        }
        return new Notification.Builder(this);
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel listen = new NotificationChannel(
            CHANNEL_LISTEN,
            "Remote Bell Listening",
            NotificationManager.IMPORTANCE_LOW
        );
        NotificationChannel alarm = new NotificationChannel(
            CHANNEL_ALARM,
            "Remote Bell Alarm",
            NotificationManager.IMPORTANCE_HIGH
        );
        alarm.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        manager.createNotificationChannel(listen);
        manager.createNotificationChannel(alarm);
    }

    private int pendingFlags(int flags) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return flags | PendingIntent.FLAG_IMMUTABLE;
        }
        return flags;
    }

    private String valueOrDefault(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value.trim();
    }

    private void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}

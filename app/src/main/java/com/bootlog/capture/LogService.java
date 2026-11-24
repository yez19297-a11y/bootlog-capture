package com.bootlog.capture;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import java.io.File;

/**
 * 常驻前台服务：负责启动/维持 LogSession，并在服务被系统回收后自动续记录。
 */
public class LogService extends Service {

    public static final String ACTION_START = "com.bootlog.capture.action.START";
    public static final String ACTION_STOP = "com.bootlog.capture.action.STOP";

    private static final String CHANNEL_ID = "bootlog_capture";
    private static final int NOTIF_ID = 1001;

    private LogSession session;
    private Handler handler;
    private boolean wantRunning;
    private int restartCount;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = (intent == null) ? ACTION_START : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            wantRunning = false;
            stopSession("用户手动停止");
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }

        wantRunning = true;
        startForegroundCompat();

        bgStart();
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 3000);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        wantRunning = false;
        stopSession("服务已停止");
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------ 会话

    /** 启动会话可能涉及 su 探测 / 创建进程，放到后台线程，避免阻塞服务主线程。 */
    private void bgStart() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                synchronized (LogService.this) {
                    if (!wantRunning) return;
                    if (session != null && session.isRunning()) return;
                    startSession();
                }
            }
        }, "bootlog-start");
        t.start();
    }

    private void startSession() {
        Prefs prefs = new Prefs(this);
        File dir;
        try {
            dir = FileUtil.pickWritableDir(this);
        } catch (Throwable t) {
            dir = new File(getFilesDir(), "logs");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        final File logDir = dir;

        final boolean rootMode = prefs.rootMode();
        if (rootMode) {
            // 关键：用 root 一次性把 READ_LOGS 授予自己，之后 logcat 以普通子进程运行，
            // 既能看到全量日志，又不会在停止时留下孤儿进程。
            RootHelper.grantLogPermission(this);
        }
        final boolean granted = MainActivity.hasReadLogs(this);
        final boolean useSu = rootMode && !granted;

        // 内核日志已经有独立快照文件时，主流水里不必再追加一遍 dmesg（否则每条内核日志写两份）
        boolean kernelSnap = prefs.kernelLog();
        boolean dumpDmesgInline = rootMode && !kernelSnap;

        LogSession s = new LogSession(dir, useSu, dumpDmesgInline, prefs.level(),
                prefs.maxMb(), prefs.keepFiles(), prefs.resumeSession());
        s.setListener(new LogSession.Listener() {
            @Override
            public void onSessionEnded(final String reason, final boolean abnormal) {
                App.STATE.running = false;
                App.STATE.lastReason = reason;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        updateNotificationIdle(reason);
                        if (abnormal && wantRunning) {
                            handler.postDelayed(resurrect, nextRetryDelay());
                        }
                    }
                });
            }
        });

        session = s;
        try {
            s.start();
            App.STATE.running = true;
            App.STATE.startWall = s.getStartWall();
            App.STATE.dirPath = dir.getAbsolutePath();
            App.STATE.root = prefs.rootMode();
            App.STATE.fileName = s.getCurrentFileName();
            App.STATE.bytes = 0;
            App.STATE.lines = 0;
            App.STATE.lastReason = "";

            // 内核日志快照：dmesg + 上一次关机/panic 的 pstore
            if (prefs.kernelLog()) {
                final LogSession fs = s;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            File kf = KernelLog.snapshot(logDir, fs.getBootId(), fs.getStartWall(), rootMode);
                            if (kf != null) {
                                Log.i("BootLog", "内核日志快照: " + kf.getName());
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }, "bootlog-kernel").start();
            }

            // 每次成功启动，先修复上次异常掉电留下的全 0 文件，再清理超量历史
            final int keep = prefs.keepFiles();
            final File cur = s.getCurrentFile();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        int fixed = 0;
                        for (File d : FileUtil.logDirCandidates(LogService.this)) {
                            fixed += FileUtil.repairCorrupt(d, cur);
                        }
                        if (fixed > 0) {
                            Log.i("BootLog", "已清理/修复 " + fixed + " 个损坏的日志文件");
                        }
                    } catch (Throwable ignored) {
                    }
                    try {
                        FileUtil.prune(LogService.this, keep);
                    } catch (Throwable ignored) {
                    }
                }
            }, "bootlog-prune").start();
        } catch (Throwable t) {
            session = null;
            App.STATE.running = false;
            App.STATE.lastReason = "启动失败：" + t.getMessage();
            final String msg = "采集启动失败：" + t.getMessage();
            handler.post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(LogService.this, msg, Toast.LENGTH_LONG).show();
                }
            });
            if (restartCount < 3) {
                restartCount++;
                handler.postDelayed(resurrect, nextRetryDelay());
            } else {
                wantRunning = false;
                stopSelf();
            }
        }
    }

    /** 连续失败时逐步退避，避免「崩溃 → 重启 → 再崩溃」把日志刷成一片空白。 */
    private long nextRetryDelay() {
        int n = Math.min(restartCount, 5);
        return Math.min(3000L * (1L << n), 60000L);
    }

    private final Runnable resurrect = new Runnable() {
        @Override
        public void run() {
            if (!wantRunning) return;
            if (session != null && session.isRunning()) return;
            if (restartCount >= 20) {
                wantRunning = false;
                updateNotificationIdle("多次重启失败，已停止采集");
                stopSelf();
                return;
            }
            restartCount++;
            bgStart();
            updateNotification("采集已自动恢复");
        }
    };

    private void stopSession(String reason) {
        LogSession s = session;
        if (s != null) {
            s.stop(reason);
            App.STATE.running = false;
            App.STATE.lastReason = reason;
        }
        session = null;
    }

    // ------------------------------------------------------------------ 通知

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "日志采集",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("持续采集系统日志（常驻通知）");
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        }
    }

    private int pendingFlags() {
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) f |= PendingIntent.FLAG_IMMUTABLE;
        return f;
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(this, 1, open, pendingFlags());

        Intent stop = new Intent(this, LogService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 2, stop, pendingFlags());

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("BootLog 正在记录系统日志")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(contentPi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPi);
        if (Build.VERSION.SDK_INT >= 21) {
            b.setCategory(Notification.CATEGORY_SERVICE);
        }
        return b.build();
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
        } catch (Throwable ignored) {
        }
    }

    private void updateNotificationIdle(String reason) {
        try {
            Notification n = buildNotification("已暂停：" + reason);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, n);
        } catch (Throwable ignored) {
        }
    }

    private void startForegroundCompat() {
        Notification n = buildNotification("正在启动采集…");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    @SuppressWarnings("deprecation")
    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            LogSession s = session;
            if (s != null && s.isRunning()) {
                App.STATE.running = true;
                App.STATE.bytes = s.getBytes();
                App.STATE.lines = s.getLines();
                String name = s.getCurrentFileName();
                if (name != null && name.length() > 0) App.STATE.fileName = name;
                // 稳定运行 10 分钟以上，重置重启计数
                if (App.STATE.startWall > 0
                        && System.currentTimeMillis() - App.STATE.startWall > 10 * 60 * 1000L) {
                    restartCount = 0;
                }
                String text = "已开机 " + FileUtil.uptimeText(SystemClock.elapsedRealtime())
                        + " · 已记录 " + FileUtil.formatSize(s.getBytes())
                        + " · " + s.getLines() + " 行";
                updateNotification(text);
                if (wantRunning) handler.postDelayed(this, 5000);
            }
        }
    };
}

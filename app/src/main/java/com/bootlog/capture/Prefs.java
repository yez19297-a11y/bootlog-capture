package com.bootlog.capture;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

/**
 * 配置存储。必须落在「设备加密存储」里，这样在 LOCKED_BOOT_COMPLETED（用户还没解锁）时也能读到配置。
 */
public final class Prefs {

    private static final String FILE = "bootlog_prefs";

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        Context c = ctx;
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                c = ctx.createDeviceProtectedStorageContext();
            } catch (Throwable ignored) {
                c = ctx;
            }
        }
        sp = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public boolean autoStart() {
        return sp.getBoolean("auto_start", true);
    }

    public void setAutoStart(boolean v) {
        sp.edit().putBoolean("auto_start", v).apply();
    }

    public boolean rootMode() {
        return sp.getBoolean("root_mode", false);
    }

    public void setRootMode(boolean v) {
        sp.edit().putBoolean("root_mode", v).apply();
    }

    /** 是否在每次开始记录时抓一份内核日志快照（dmesg + pstore） */
    public boolean kernelLog() {
        return sp.getBoolean("kernel_log", true);
    }

    public void setKernelLog(boolean v) {
        sp.edit().putBoolean("kernel_log", v).apply();
    }

    /** 同一次开机内再次开会话时，是否从上次写完的位置续读（避免重复回放历史日志） */
    public boolean resumeSession() {
        return sp.getBoolean("resume_session", true);
    }

    public void setResumeSession(boolean v) {
        sp.edit().putBoolean("resume_session", v).apply();
    }

    /** V / D / I / W / E */
    public String level() {
        return sp.getString("level", "V");
    }

    public void setLevel(String v) {
        sp.edit().putString("level", v).apply();
    }

    public int maxMb() {
        int v = sp.getInt("max_mb", 32);
        // 1.2 起默认值由 8 MB 提到 32 MB：一次全量缓冲区回放就能到几十 MB，
        // 8 MB 会被切成六七个文件。只在值仍等于旧默认值时迁移一次，用户自选的不动。
        if (!sp.getBoolean("max_mb_migrated", false)) {
            if (v == 8 && !sp.contains("max_mb_user_set")) {
                v = 32;
                sp.edit().putInt("max_mb", v)
                        .putBoolean("max_mb_migrated", true)
                        .apply();
            } else {
                sp.edit().putBoolean("max_mb_migrated", true).apply();
            }
        }
        return v;
    }

    public void setMaxMb(int v) {
        sp.edit().putInt("max_mb", v).putBoolean("max_mb_user_set", true).apply();
    }

    public int keepFiles() {
        return sp.getInt("keep_files", 20);
    }

    public void setKeepFiles(int v) {
        sp.edit().putInt("keep_files", v).apply();
    }
}

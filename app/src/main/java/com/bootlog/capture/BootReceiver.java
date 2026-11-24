package com.bootlog.capture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * 开机广播接收器。
 *
 * LOCKED_BOOT_COMPLETED 比 BOOT_COMPLETED 更早（用户还没输入密码/图案解锁时就能收到），
 * 是普通应用能拿到的「最早时机」。因此这里是「从开机开始记录」的起点。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootLog";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        Log.i(TAG, "收到广播: " + action);

        Prefs prefs = new Prefs(context);
        if (!prefs.autoStart()) {
            Log.i(TAG, "未开启开机自启，忽略");
            return;
        }

        Intent svc = new Intent(context, LogService.class);
        svc.setAction(LogService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Throwable t) {
            Log.e(TAG, "启动采集服务失败", t);
        }
    }
}

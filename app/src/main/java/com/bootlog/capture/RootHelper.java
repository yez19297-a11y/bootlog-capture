package com.bootlog.capture;

import android.content.Context;

import java.io.InputStream;

/**
 * Root 相关的小工具。
 *
 * 这里采用的做法不是「su -c logcat」常驻子进程（那样在停止时容易留下孤儿 logcat），
 * 而是用 root 一次性执行 `pm grant ... READ_LOGS`，把签名级权限真正授予本应用。
 * 之后 logcat 作为普通子进程运行，既能读到全量日志，也能被干净地 kill 掉。
 */
public final class RootHelper {

    private RootHelper() {
    }

    /** 用 su 给本应用授予 READ_LOGS。阻塞调用，请在子线程执行。 */
    public static boolean grantLogPermission(Context ctx) {
        if (MainActivity.hasReadLogs(ctx)) return true;
        String pkg = ctx.getPackageName();
        String[] cmds = new String[]{
                "pm grant " + pkg + " android.permission.READ_LOGS",
                "appops set " + pkg + " READ_LOGS allow"
        };
        for (String c : cmds) {
            execSu(c, 8000);
        }
        return MainActivity.hasReadLogs(ctx);
    }

    /** 判断 su 是否可用（会触发一次授权弹窗）。阻塞调用。 */
    public static boolean hasSu() {
        String out = execSu("id", 8000);
        return out != null && out.contains("uid=0");
    }

    /** 执行 su -c，返回合并后的输出（失败返回 null）。 */
    public static String execSu(String command, long timeoutMs) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            final Process fp = p;
            Thread killer = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(timeoutMs);
                    } catch (InterruptedException ignored) {
                    }
                    try {
                        fp.destroy();
                    } catch (Throwable ignored) {
                    }
                }
            });
            killer.setDaemon(true);
            killer.start();

            InputStream in = p.getInputStream();
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[512];
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n));
                if (sb.length() > 4096) break;
            }
            p.waitFor();
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (p != null) p.destroy();
            } catch (Throwable ignored) {
            }
        }
    }
}

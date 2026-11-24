package com.bootlog.capture;

import android.os.Build;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 内核日志快照。
 *
 * 普通应用能拿到的内核日志分三个来源，各自覆盖不同的时间段：
 *
 *  1. logcat -b kernel —— 实时，随主日志一起采集（logd 从 /dev/kmsg 抄过来的内核 printk）。
 *     不需要 Root，但不少机型这个 buffer 是空的（SELinux 限制 logd 读 kmsg）。
 *  2. dmesg —— 本次开机至今的内核环形缓冲区全量。需要 Root（CAP_SYSLOG）。
 *  3. /sys/fs/pstore/* 、/proc/last_kmsg —— 上一次开机、关机或内核 panic 瞬间的控制台输出，
 *     由内核在重启后保留。需要 Root。这是「关机那一刻内核发生了什么」唯一可靠的来源。
 *
 * 本类负责在第 2、3 项上补一刀，生成独立的 kernel_*.log 快照文件。
 */
public final class KernelLog {

    private static final long MAX_SECTION = 2 * 1024 * 1024; // 单段最多 2 MB

    private KernelLog() {
    }

    public static File snapshot(File dir, String bootId, long startWall, boolean root) {
        if (dir == null) return null;
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(startWall));
        File f = new File(dir, "kernel_" + bootId + "_" + ts + ".log");

        OutputStream os = null;
        long payload = 0;
        try {
            os = new BufferedOutputStream(new FileOutputStream(f, false), 32 * 1024);
            emit(os, banner());

            // 1. 内核启动参数
            emit(os, "\n===== 1. 内核启动参数 (/proc/cmdline) =====\n");
            String cmdline = FileUtil.readSmallFile(new File("/proc/cmdline")).trim();
            emit(os, cmdline.length() == 0 ? "（读取失败或为空）\n" : cmdline + "\n");

            // 2. 上一次开机的内核日志（pstore）
            emit(os, "\n===== 2. 上一次关机 / 内核 panic 时的控制台日志 =====\n");
            emit(os, "# 来源：/sys/fs/pstore/console-ramoops*、dmesg-ramoops*、/proc/last_kmsg\n");
            emit(os, "# 内核在重启后仍保留这一段，是「关机那一刻」唯一可靠的记录。\n");
            if (root) {
                long n2 = runTo(os, su(PSTORE_CMD), MAX_SECTION, 20000);
                payload += n2;
                if (n2 < 32) {
                    emit(os, "（pstore 为空：上次是正常关机，或设备未启用 ramoops）\n");
                }
            } else {
                emit(os, "（跳过：读取 pstore / last_kmsg 需要 Root）\n");
            }

            // 3. 本次开机至今的 dmesg
            emit(os, "\n===== 3. 本次开机至今的内核环形缓冲区 =====\n");
            long n3;
            if (root) {
                emit(os, "# 来源：dmesg（Root）\n");
                n3 = runTo(os, su("dmesg"), MAX_SECTION, 20000);
            } else {
                emit(os, "# 来源：logcat -b kernel -d（未 Root，可能为空）\n");
                n3 = runTo(os, Arrays.asList("logcat", "-b", "kernel", "-d", "-v", "threadtime"),
                        1024 * 1024, 15000);
            }
            payload += n3;
            if (n3 < 32) {
                emit(os, "（空：本机 kernel buffer 不可用，开启 Root 模式后会改用 dmesg）\n");
            }

            String tail = "\n===== 快照结束 =====\n"
                    + "# 内核缓冲区 " + (n3 < 32 ? "无" : "有") + "内容，pstore "
                    + (root ? "已尝试读取" : "未读取（需 Root）") + "\n";
            emit(os, tail);
            os.flush();
        } catch (Throwable t) {
            try {
                if (os != null) os.close();
            } catch (Throwable ignored) {
            }
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return null;
        } finally {
            FileUtil.closeQuietly(os);
        }

        // 只有表头没有实际内容就没必要留文件
        if (payload < 128) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return null;
        }
        return f;
    }

    private static String banner() {
        StringBuilder sb = new StringBuilder();
        sb.append("############################################################\n");
        sb.append("# BootLog 内核日志快照\n");
        sb.append("# 设备      : ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append(")\n");
        sb.append("# 系统      : Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("# 内核      : ").append(FileUtil.kernelVersion()).append('\n');
        sb.append("# 启动ID    : ").append(FileUtil.readBootId()).append('\n');
        sb.append("# 快照时间  : ").append(FileUtil.formatTime(System.currentTimeMillis())).append('\n');
        sb.append("# 本次开机  : ").append(FileUtil.formatTime(FileUtil.bootWallClock()))
                .append("（快照时已开机 ").append(FileUtil.uptimeText(android.os.SystemClock.elapsedRealtime()))
                .append("）\n");
        sb.append("# 说明      : dmesg 只保留环形缓冲区里最近的一段；更早的内核输出看 pstore 段。\n");
        sb.append("############################################################\n");
        return sb.toString();
    }

    private static final String PSTORE_CMD =
            "for f in /sys/fs/pstore/console-ramoops* /sys/fs/pstore/dmesg-ramoops* /proc/last_kmsg; "
                    + "do if [ -f \"$f\" ]; then echo \"\"; echo \"----- $f -----\"; cat \"$f\"; echo \"\"; fi; done";

    private static List<String> su(String cmd) {
        List<String> l = new ArrayList<String>(3);
        l.add("su");
        l.add("-c");
        l.add(cmd);
        return l;
    }

    private static void emit(OutputStream os, String s) {
        try {
            os.write(s.getBytes(FileUtil.UTF8));
        } catch (Throwable ignored) {
        }
    }

    /** 把命令输出写进文件，返回写入字节数。带超时，避免 su 卡住。 */
    private static long runTo(OutputStream os, List<String> cmd, final long maxBytes, long timeoutMs) {
        Process p = null;
        long total = 0;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            final Process fp = p;
            Thread killer = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(timeoutMs == 0 ? 15000 : timeoutMs);
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
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (total + n > maxBytes) n = (int) (maxBytes - total);
                if (n <= 0) break;
                os.write(buf, 0, n);
                total += n;
                if (total >= maxBytes) break;
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                if (p != null) p.destroy();
            } catch (Throwable ignored) {
            }
        }
        return total;
    }
}

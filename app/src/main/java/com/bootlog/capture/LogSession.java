package com.bootlog.capture;

import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 一次「开机 → 关机」的采集会话。
 *
 * 工作方式：
 * 1. 启动 logcat 子进程（不带 -d），logcat 会先把环形缓冲区里的历史日志完整回放，
 *    然后继续跟随输出 —— 因此开机早期（应用还没启动时）的日志同样会被收进第一个文件。
 * 2. 子进程 stdout 每读到一块就落盘并 flush，保证掉电/关机时最多丢最后一个数据块。
 * 3. 后台守护线程轮询 sys.powerctl，检测到 shutdown 时保留约 1.5 秒把管道读干净，
 *    再写入结束标记并关闭文件。
 * 4. 单文件超过上限自动续写到新文件，旧文件按保留个数清理。
 * 5. 同一次开机内再次开会话时，从上次写完的时间戳处续读（logcat -T），不再重复回放历史。
 */
public class LogSession {

    public interface Listener {
        /** 会话结束回调（在读线程里触发，调用方需自行切线程）。 */
        void onSessionEnded(String reason, boolean abnormal);
    }

    private static final String TAG = "BootLog";

    private final File dir;
    /** 是否用 su -c logcat 的方式读全量日志（仅在无法直接授予 READ_LOGS 时使用） */
    private final boolean root;
    /** 是否把 dmesg 内核日志一并落盘（需要 root） */
    private final boolean dumpKernel;
    private final String level;
    private final long maxBytes;
    private final int keepFiles;
    /** 是否在本次会话里尝试从上次的断点续读 */
    private final boolean resumeEnabled;

    /** 断点时间戳（形如 2026-11-20 13:28:13.803）；null 表示从头完整回放 */
    private String resumeTs;

    private final long startWall;
    private final long bootWall;
    private final String bootId;

    private volatile Process process;
    private Thread pump;
    private Thread guard;

    private volatile File currentFile;
    private OutputStream out;

    private long fileBytes;
    private long totalBytes;
    private long totalLines;
    private int part = 1;

    private volatile boolean running;
    private volatile boolean stopRequested;
    private volatile boolean shutdownDetected;
    private volatile String stopReason;
    private boolean footerWritten;

    private Listener listener;

    /** 会话实际使用的输出格式：0 threadtime，1 threadtime+year+zone，2 threadtime+year+UTC */
    private int fmtUsed = -1;

    /** 断点是否已经尝试解析过，避免重试时反复扫描目录 */
    private boolean resumeResolved;

    public LogSession(File dir, boolean useSuStream, boolean dumpKernel,
                      String level, int maxMb, int keepFiles, boolean resumeEnabled) {
        this.dir = dir;
        this.root = useSuStream;
        this.dumpKernel = dumpKernel;
        this.level = (level == null || level.length() == 0) ? "V" : level;
        this.maxBytes = Math.max(1, maxMb) * 1024L * 1024L;
        this.keepFiles = Math.max(2, keepFiles);
        this.resumeEnabled = resumeEnabled;
        this.startWall = System.currentTimeMillis();
        this.bootWall = this.startWall - SystemClock.elapsedRealtime();
        String id = FileUtil.readBootId();
        this.bootId = (id == null || id.length() < 6) ? "noboot" : id.substring(0, 8);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public long getStartWall() {
        return startWall;
    }

    public long getBytes() {
        return totalBytes;
    }

    public long getLines() {
        return totalLines;
    }

    public String getBootId() {
        return bootId;
    }

    public boolean isRunning() {
        return running;
    }

    public File getCurrentFile() {
        return currentFile;
    }

    public String getCurrentFileName() {
        File f = currentFile;
        return f == null ? "" : f.getName();
    }

    // ------------------------------------------------------------------ 生命周期

    public synchronized void start() throws IOException {
        if (running) return;
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建日志目录：" + dir.getAbsolutePath());
        }
        // 先确定输出格式，header 里要如实记录下来
        fmtUsed = probeFormat();
        if (!resumeResolved) {
            resumeResolved = true;
            resumeTs = resumeEnabled ? findResumeTimestamp() : null;
        }
        openNewFile();
        writeHeader();

        running = true;
        process = createProcess();

        pump = new Thread(new Runnable() {
            @Override
            public void run() {
                pumpLoop();
            }
        }, "bootlog-pump");
        pump.start();

        guard = new Thread(new Runnable() {
            @Override
            public void run() {
                guardLoop();
            }
        }, "bootlog-guard");
        guard.start();
    }

    /**
     * 请求停止。不会立刻杀进程：先给 0.4 秒（关机时 1.6 秒）把管道里剩下的日志读完，
     * 然后再 destroy，保证结尾几行不丢。
     */
    public void stop(String reason) {
        if (stopRequested) return;
        stopRequested = true;
        stopReason = reason;
        final Process p = process;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(shutdownDetected ? 1600 : 400);
                } catch (InterruptedException ignored) {
                }
                try {
                    if (p != null) p.destroy();
                } catch (Throwable ignored) {
                }
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ignored) {
                }
                // 关闭读端，保证读线程一定能退出（su 场景下子进程可能不会随父进程结束）
                try {
                    if (p != null) p.getInputStream().close();
                } catch (Throwable ignored) {
                }
            }
        }, "bootlog-stopper");
        t.start();
    }

    // ------------------------------------------------------------------ 子进程

    private List<String> baseArgs(int fmt) {
        List<String> a = new ArrayList<String>();
        a.add("logcat");
        a.add("-b");
        a.add("all");
        a.add("-v");
        a.add("threadtime");
        if (fmt > 0) {
            a.add("-v");
            a.add("year");
            a.add("-v");
            // zone 让正文时间与文件名同区（本地时间），UTC 只在老设备上作为兜底
            a.add(fmt == 1 ? "zone" : "UTC");
        }
        if (!"V".equals(level)) {
            // 只输出该级别及以上的日志
            a.add("*:" + level);
        }
        return a;
    }

    private static String shellQuote(String s) {
        if (s.matches("[A-Za-z0-9_\\-:/.,=+]+")) return s;
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String s : parts) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(shellQuote(s));
        }
        return sb.toString();
    }

    private Process createProcess() throws IOException {
        int fmt = probeFormat();
        List<String> args = baseArgs(fmt);
        if (resumeTs != null) {
            args.add("-T");
            args.add(resumeTs);
        }
        List<String> cmd = new ArrayList<String>();
        if (root) {
            // su -c 会经过 sh，需要给 *:V 这类参数加引号
            cmd.add("su");
            cmd.add("-c");
            cmd.add("exec " + join(args));
        } else {
            cmd.addAll(args);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        return pb.start();
    }

    /** 探测结果缓存：-1 未知，0 仅 threadtime，1 year+zone，2 year+UTC */
    private static volatile int fmtCache = -1;

    private int probeFormat() {
        int c = fmtCache;
        if (c >= 0) return c;
        if (probeFormatOnce(1)) {
            fmtCache = 1;
            return 1;
        }
        if (probeFormatOnce(2)) {
            fmtCache = 2;
            return 2;
        }
        fmtCache = 0;
        return 0;
    }

    /** 老设备可能不支持 -v year / -v zone，先探测一次，失败就往下降级。 */
    private boolean probeFormatOnce(final int fmt) {
        Process p = null;
        try {
            final List<String> args = baseArgs(fmt);
            args.add("-t");
            args.add("1");
            List<String> cmd = new ArrayList<String>();
            if (root) {
                cmd.add("su");
                cmd.add("-c");
                cmd.add(join(args));
            } else {
                cmd.addAll(args);
            }
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            final Process fp = p;
            Thread killer = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(6000);
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
            byte[] buf = new byte[512];
            int n = in.read(buf);
            int code = p.waitFor();
            return n > 0 && code == 0;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                if (p != null) p.destroy();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 验证 logcat 是否能接受 -T 参数。老版本 toybox/logcat 不认识 -T 会直接报用法错误，
     * 这时必须放弃续读，否则整个会话一行日志都收不到。
     */
    private boolean probeResume(String ts) {
        Process p = null;
        try {
            List<String> args = baseArgs(probeFormat());
            args.add("-T");
            args.add(ts);
            args.add("-d");
            args.add("-t");
            args.add("1");
            List<String> cmd = new ArrayList<String>();
            if (root) {
                cmd.add("su");
                cmd.add("-c");
                cmd.add(join(args));
            } else {
                cmd.addAll(args);
            }
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            final Process fp = p;
            Thread killer = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(5000);
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
            return p.waitFor() == 0;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                if (p != null) p.destroy();
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ 断点续读

    private static final java.util.regex.Pattern TS_LINE =
            java.util.regex.Pattern.compile("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})");

    /**
     * 找同一次开机里最近写完的日志，取它最后一条时间戳。
     * 只有 boot_id 相同才续读 —— 换了开机就得从头回放，否则会漏掉开机早期的日志。
     */
    private String findResumeTimestamp() {
        try {
            File[] all = dir.listFiles();
            if (all == null || all.length == 0) return null;
            final String prefix = bootId + "_";
            List<File> cand = new ArrayList<File>();
            for (File f : all) {
                String n = f.getName();
                if (!n.endsWith(".log")) continue;
                if (n.startsWith("kernel_")) continue;
                if (!n.startsWith(prefix)) continue;
                if (f.isFile() && f.length() > 0) cand.add(f);
            }
            if (cand.isEmpty()) return null;
            Collections.sort(cand, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return Long.compare(b.lastModified(), a.lastModified());
                }
            });
            int n = Math.min(2, cand.size());
            for (int i = 0; i < n; i++) {
                File f = cand.get(i);
                // 1.2 之前正文写的是 UTC（文件名却是本地时间），拿它当断点会错 8 小时，
                // 宁可完整回放一次也不能错位。
                if (!isLocalZoneFile(f)) continue;
                String ts = lastTimestamp(f);
                if (ts == null) continue;
                // 新版本 logcat 认 YYYY-MM-DD，Android 5/6 只认 MM-DD，失败就退一格
                if (probeResume(ts)) return ts;
                if (ts.length() > 18) {
                    String shortTs = ts.substring(5);
                    if (probeResume(shortTs)) return shortTs;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "解析断点失败: " + t);
        }
        return null;
    }

    /** 判断这份日志的正文时间是否为本地时区（只有 1.2 起的 header 会带 ",zone"）。 */
    private boolean isLocalZoneFile(File f) {
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[2048];
            int n = in.read(buf);
            if (n <= 0) return false;
            String head = new String(buf, 0, n, FileUtil.UTF8);
            int p = head.indexOf("# 采集方式");
            if (p < 0) return false;
            int e = head.indexOf('\n', p);
            if (e < 0) e = head.length();
            return head.substring(p, e).contains(",zone");
        } catch (Throwable t) {
            return false;
        } finally {
            FileUtil.closeQuietly(in);
        }
    }

    /** 从文件尾部往回找最后一条带时间戳的日志行。 */
    private String lastTimestamp(File f) {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            long len = raf.length();
            int window = (int) Math.min(len, 256 * 1024);
            if (window <= 0) return null;
            raf.seek(len - window);
            byte[] buf = new byte[window];
            raf.readFully(buf);

            // 掉电会在尾部留下 NUL 填充，先削掉
            int end = window;
            while (end > 0 && buf[end - 1] == 0) end--;
            if (end <= 0) return null;

            String s = new String(buf, 0, end, FileUtil.UTF8);
            String[] lines = s.split("\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                String ln = lines[i];
                if (ln.length() < 20) continue;
                java.util.regex.Matcher m = TS_LINE.matcher(ln);
                if (m.find()) {
                    String ts = m.group(1);
                    if (isSaneTs(ts)) return ts;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            FileUtil.closeQuietly(raf);
        }
        return null;
    }

    /** 时间戳若荒谬地落在未来，logcat -T 会一直空等，必须挡掉。 */
    private boolean isSaneTs(String ts) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
            f.setLenient(false);
            Date d = f.parse(ts);
            if (d == null) return false;
            long t = d.getTime();
            long now = System.currentTimeMillis();
            return t > now - 30L * 24 * 3600 * 1000 && t < now + 60L * 1000;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 写盘

    private void openNewFile() throws IOException {
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(startWall));
        String name = bootId + "_" + ts + (part > 1 ? "_p" + part : "") + ".log";
        currentFile = new File(dir, name);
        out = new BufferedOutputStream(new FileOutputStream(currentFile, true), 32 * 1024);
        fileBytes = 0;
    }

    private void writeRaw(String s) {
        try {
            byte[] b = s.getBytes(FileUtil.UTF8);
            out.write(b);
            fileBytes += b.length;
            totalBytes += b.length;
            for (int i = 0; i < b.length; i++) if (b[i] == '\n') totalLines++;
        } catch (Throwable ignored) {
        }
    }

    private void writeChunk(byte[] b, int n) throws IOException {
        if (fileBytes + n > maxBytes && fileBytes > 0) {
            writeFooter("单个文件达到 " + (maxBytes / 1024 / 1024) + " MB 上限，续写到下一个文件", false);
            footerWritten = false; // 新文件还需要自己的结束标记
            closeOut();
            part++;
            openNewFile();
            writeRaw("# ---- 续写：第 " + part + " 个文件，接上文 ----\n");
        }
        out.write(b, 0, n);
        fileBytes += n;
        totalBytes += n;
        for (int i = 0; i < n; i++) if (b[i] == '\n') totalLines++;
        out.flush();
    }

    private void writeHeader() {
        StringBuilder sb = new StringBuilder();
        sb.append("############################################################\n");
        sb.append("# BootLog 采集会话开始\n");
        sb.append("# 设备      : ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append(")\n");
        sb.append("# 系统      : Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("# 版本号    : ").append(Build.DISPLAY).append('\n');
        sb.append("# 内核      : ").append(FileUtil.kernelVersion()).append('\n');
        sb.append("# 启动ID    : ").append(bootId).append('\n');
        sb.append("# 本次开机  : ").append(FileUtil.formatTime(bootWall))
                .append("（已开机 ").append(FileUtil.uptimeText(SystemClock.elapsedRealtime())).append("）\n");
        sb.append("# 会话开始  : ").append(FileUtil.formatTime(startWall)).append('\n');
        int fmt = fmtUsed;
        sb.append("# 采集方式  : logcat -b all ")
                .append(fmt == 0 ? "-v threadtime" : (fmt == 1 ? "-v threadtime,year,zone"
                        : "-v threadtime,year,UTC"))
                .append("  级别=").append(level)
                .append("  dmesg 内联=").append(dumpKernel ? "是" : "否")
                .append(root ? "（经 su 直读）" : "（直读）").append('\n');
        if (resumeTs != null) {
            sb.append("# 续写自    : ").append(resumeTs).append('\n');
            sb.append("# 说明      : 同一次开机内的接续会话，从上次结束处继续读，不重复回放。\n");
            sb.append("#             上次停止到本次开始之间的日志仍在缓冲区中，会一并补上。\n");
        } else {
            sb.append("# 说明      : logcat 先完整回放环形缓冲区，故本文件包含应用启动之前（含开机早期）的日志。\n");
        }
        sb.append("############################################################\n\n");
        writeRaw(sb.toString());
        try {
            out.flush();
        } catch (Throwable ignored) {
        }
    }

    private void writeFooter(String reason, boolean abnormal) {
        if (footerWritten || out == null) return;
        footerWritten = true;
        StringBuilder sb = new StringBuilder();
        sb.append("\n############################################################\n");
        sb.append("# BootLog 会话结束\n");
        sb.append("# 结束时间  : ").append(FileUtil.formatTime(System.currentTimeMillis())).append('\n');
        sb.append("# 结束原因  : ").append(reason == null ? "未知" : reason).append('\n');
        sb.append("# 是否异常  : ").append(abnormal ? "是（可能被强杀或掉电）" : "否").append('\n');
        sb.append("# 累计写入  : ").append(totalLines).append(" 行 / ")
                .append(FileUtil.formatSize(totalBytes)).append('\n');
        sb.append("############################################################\n");
        writeRaw(sb.toString());
        try {
            out.flush();
        } catch (Throwable ignored) {
        }
    }

    private void closeOut() {
        try {
            if (out != null) out.flush();
        } catch (Throwable ignored) {
        }
        try {
            if (out != null) out.close();
        } catch (Throwable ignored) {
        }
        out = null;
    }

    // ------------------------------------------------------------------ 读线程

    private void pumpLoop() {
        String reason = "logcat 进程结束";
        boolean abnormal = true;
        try {
            if (dumpKernel) dumpDmesg();

            InputStream in = process.getInputStream();
            byte[] buf = new byte[16 * 1024];
            while (true) {
                int n = in.read(buf);
                if (n <= 0) break;
                writeChunk(buf, n);
            }
        } catch (Throwable t) {
            reason = "读取异常：" + t.getMessage();
            abnormal = true;
        }

        if (shutdownDetected) {
            reason = "系统关机";
            abnormal = false;
        } else if (stopRequested) {
            if (stopReason != null) reason = stopReason;
            abnormal = false;
        }

        running = false;
        writeFooter(reason, abnormal);
        closeOut();
        try {
            if (process != null) process.destroy();
        } catch (Throwable ignored) {
        }

        if (listener != null) {
            try {
                listener.onSessionEnded(reason, abnormal);
            } catch (Throwable ignored) {
            }
        }
        Log.i(TAG, "会话结束: " + reason);
    }

    /** Root 模式下把内核环形缓冲区（dmesg）一并落盘，覆盖 logcat 之外的早期内核日志。 */
    private void dumpDmesg() {
        final Process[] holder = new Process[1];
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    holder[0] = new ProcessBuilder("su", "-c", "dmesg").redirectErrorStream(true).start();
                    InputStream in = holder[0].getInputStream();
                    byte[] buf = new byte[8192];
                    long total = 0;
                    int n;
                    writeRaw("\n# ======== 以下为 dmesg 内核日志（Root）========\n");
                    while ((n = in.read(buf)) > 0) {
                        if (total > 768 * 1024) break;
                        writeChunk(buf, n);
                        total += n;
                    }
                    writeRaw("\n# ======== dmesg 结束 ========\n\n");
                } catch (Throwable ignored) {
                }
            }
        }, "bootlog-dmesg");
        t.setDaemon(true);
        t.start();
        try {
            t.join(8000);
        } catch (InterruptedException ignored) {
        }
        if (t.isAlive()) {
            try {
                if (holder[0] != null) holder[0].destroy();
            } catch (Throwable ignored) {
            }
            t.interrupt();
        }
    }

    // ------------------------------------------------------------------ 关机守护

    private static Method sysPropGet;

    private static synchronized String sysProp(String key) {
        try {
            if (sysPropGet == null) {
                Class<?> c = Class.forName("android.os.SystemProperties");
                sysPropGet = c.getMethod("get", String.class);
            }
            Object v = sysPropGet.invoke(null, key);
            return v instanceof String ? (String) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private void guardLoop() {
        while (!stopRequested) {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                return;
            }
            String v = sysProp("sys.powerctl");
            if (v != null && v.startsWith("shutdown")) {
                shutdownDetected = true;
                Log.i(TAG, "检测到关机: " + v);
                stop("系统关机（sys.powerctl=" + v + "）");
                return;
            }
        }
    }
}

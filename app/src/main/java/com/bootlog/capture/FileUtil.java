package com.bootlog.capture;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 文件、格式化、打包相关的小工具集合。
 */
public final class FileUtil {

    public static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String DIR_NAME = "logs";

    private FileUtil() {
    }

    // ------------------------------------------------------------------ 目录

    /**
     * 所有可能存放日志的目录，按优先级排列。
     * 外部私有目录（方便电脑/文件管理器直接看）→ 设备加密内部目录（锁定启动阶段可用）→ 内部目录。
     */
    public static List<File> logDirCandidates(Context ctx) {
        List<File> list = new ArrayList<File>(3);
        try {
            File ext = ctx.getExternalFilesDir(DIR_NAME);
            if (ext != null) list.add(ext);
        } catch (Throwable ignored) {
        }
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                list.add(new File(ctx.createDeviceProtectedStorageContext().getFilesDir(), DIR_NAME));
            } catch (Throwable ignored) {
            }
        }
        try {
            list.add(new File(ctx.getFilesDir(), DIR_NAME));
        } catch (Throwable ignored) {
        }
        return list;
    }

    public static File pickWritableDir(Context ctx) {
        for (File d : logDirCandidates(ctx)) {
            try {
                if (!d.exists() && !d.mkdirs()) continue;
                if (d.isDirectory() && d.canWrite()) return d;
            } catch (Throwable ignored) {
            }
        }
        File fallback = new File(ctx.getFilesDir(), DIR_NAME);
        //noinspection ResultOfMethodCallIgnored
        fallback.mkdirs();
        return fallback;
    }

    public static List<File> listLogFiles(Context ctx) {
        List<File> out = new ArrayList<File>();
        Set<String> seen = new HashSet<String>();
        for (File d : logDirCandidates(ctx)) {
            File[] fs = d.listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                if (f.isFile() && f.getName().endsWith(".log") && seen.add(f.getName())) {
                    out.add(f);
                }
            }
        }
        Collections.sort(out, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        return out;
    }

    public static File findByName(Context ctx, String name) {
        if (name == null || name.length() == 0) return null;
        for (File d : logDirCandidates(ctx)) {
            File f = new File(d, name);
            if (f.isFile()) return f;
        }
        return null;
    }

    /** 只保留最新的 keep 个文件，其余删除。 */
    public static int prune(Context ctx, int keep) {
        List<File> all = listLogFiles(ctx);
        int removed = 0;
        for (int i = keep; i < all.size(); i++) {
            if (all.get(i).delete()) removed++;
        }
        return removed;
    }

    /**
     * 修复上次异常掉电留下的损坏文件。
     *
     * 掉电时文件系统可能只落了 inode 的 size，数据块还留在 page cache 里，
     * 重启后这些块读出来是全 0。表现就是「8 MB 的文件，一个字节的日志都没有」。
     *
     * 处理方式：整文件全 0 的直接删；只有尾部一段 0 的截断到最后一个有效字节。
     *
     * @return 被删除或截断的文件数
     */
    public static int repairCorrupt(File dir, File exclude) {
        File[] fs = (dir == null) ? null : dir.listFiles();
        if (fs == null) return 0;
        int fixed = 0;
        String skip = (exclude == null) ? null : exclude.getAbsolutePath();
        for (File f : fs) {
            if (!f.isFile() || !f.getName().endsWith(".log")) continue;
            if (skip != null && skip.equals(f.getAbsolutePath())) continue;
            try {
                long len = f.length();
                if (len <= 0) continue;
                long keep = lastNonZeroOffset(f);
                if (keep <= 0) {
                    if (f.delete()) fixed++;
                } else if (keep < len) {
                    RandomAccessFile raf = new RandomAccessFile(f, "rw");
                    try {
                        raf.setLength(keep);
                        fixed++;
                    } finally {
                        closeQuietly(raf);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return fixed;
    }

    /** 返回最后一个非 0 字节之后的位置；整文件都是 0 时返回 0。最多回溯 2 MB。 */
    private static long lastNonZeroOffset(File f) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        try {
            long len = raf.length();
            long limit = Math.max(0, len - 2L * 1024 * 1024);
            byte[] buf = new byte[64 * 1024];
            long pos = len;
            while (pos > limit) {
                int n = (int) Math.min(buf.length, pos - limit);
                pos -= n;
                raf.seek(pos);
                raf.readFully(buf, 0, n);
                for (int i = n - 1; i >= 0; i--) {
                    if (buf[i] != 0) return pos + i + 1;
                }
            }
            return 0;
        } finally {
            closeQuietly(raf);
        }
    }

    /** 删除一组文件，返回成功删除的个数。 */
    public static int deleteAll(List<File> files) {
        int n = 0;
        if (files == null) return 0;
        for (File f : files) {
            try {
                if (f != null && f.isFile() && f.delete()) n++;
            } catch (Throwable ignored) {
            }
        }
        return n;
    }

    // ------------------------------------------------------------- 设备/开机信息

    /** 内核随机 boot_id，用来区分「同一次开机」。不可读时退化为按开机时刻推算的伪 ID。 */
    public static String readBootId() {
        try {
            String s = readSmallFile(new File("/proc/sys/kernel/random/boot_id")).trim();
            s = s.replace("-", "");
            if (s.length() >= 8) return s;
        } catch (Throwable ignored) {
        }
        long bootWall = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        return "x" + Long.toHexString(bootWall / 60000L);
    }

    public static String readSmallFile(File f) {
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 64 * 1024) break;
            }
            return new String(bos.toByteArray(), UTF8);
        } catch (Throwable t) {
            return "";
        } finally {
            closeQuietly(in);
        }
    }

    public static String kernelVersion() {
        String v = readSmallFile(new File("/proc/version")).trim();
        if (v.length() == 0) {
            try {
                v = System.getProperty("os.version", "unknown");
            } catch (Throwable ignored) {
                v = "unknown";
            }
        }
        return v;
    }

    public static long bootWallClock() {
        return System.currentTimeMillis() - SystemClock.elapsedRealtime();
    }

    // ------------------------------------------------------------------ 格式化

    public static String formatSize(long b) {
        if (b < 1024) return b + " B";
        double k = b / 1024.0;
        if (k < 1024) return String.format(Locale.US, "%.1f KB", k);
        double m = k / 1024.0;
        if (m < 1024) return String.format(Locale.US, "%.1f MB", m);
        return String.format(Locale.US, "%.2f GB", m / 1024.0);
    }

    public static String formatTime(long ms) {
        if (ms <= 0) return "—";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(ms));
    }

    public static String stamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
    }

    /** 把毫秒时长写成「3 天 2 小时 11 分」这种中文可读形式。 */
    public static String uptimeText(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        long d = s / 86400;
        long h = (s % 86400) / 3600;
        long m = (s % 3600) / 60;
        if (d > 0) return d + " 天 " + h + " 小时 " + m + " 分";
        if (h > 0) return h + " 小时 " + m + " 分";
        if (m > 0) return m + " 分 " + (s % 60) + " 秒";
        return s + " 秒";
    }

    // ------------------------------------------------------------------ 读写

    /** 读文件尾部若干行（最多读 maxBytes 字节），用于实时预览。 */
    public static String tail(File f, int maxLines, int maxBytes) {
        if (f == null || !f.isFile()) return "（暂无内容）";
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            long len = raf.length();
            long start = Math.max(0, len - maxBytes);
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            String s = new String(buf, UTF8);
            if (start > 0) {
                int i = s.indexOf('\n');
                if (i >= 0) s = s.substring(i + 1);
            }
            String[] arr = s.split("\n", -1);
            int from = Math.max(0, arr.length - maxLines);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < arr.length; i++) {
                sb.append(arr[i]).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            return "（读取失败：" + t.getMessage() + "）";
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    public static void closeQuietly(Object c) {
        try {
            if (c instanceof java.io.Closeable) ((java.io.Closeable) c).close();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 打包导出

    public static long zip(List<File> files, File dest) throws IOException {
        ZipOutputStream zos = null;
        try {
            zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(dest)));
            byte[] buf = new byte[64 * 1024];
            for (File f : files) {
                if (f == null || !f.isFile()) continue;
                ZipEntry e = new ZipEntry(f.getName());
                e.setTime(f.lastModified());
                zos.putNextEntry(e);
                InputStream in = new BufferedInputStream(new FileInputStream(f));
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
                } finally {
                    closeQuietly(in);
                }
                zos.closeEntry();
            }
        } finally {
            closeQuietly(zos);
        }
        return dest.length();
    }

    /**
     * 把 zip 复制到「下载/BootLog/」，返回 MediaStore Uri（失败返回 null）。
     * 只在 API 29+ 可用；低版本直接走 FileProvider 分享缓存文件。
     */
    public static Uri publishToDownloads(Context ctx, File src) {
        if (Build.VERSION.SDK_INT < 29) return null;
        Uri uri = null;
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, src.getName());
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/BootLog");
            uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) return null;
            OutputStream os = ctx.getContentResolver().openOutputStream(uri);
            InputStream in = new BufferedInputStream(new FileInputStream(src));
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                os.flush();
            } finally {
                closeQuietly(in);
                closeQuietly(os);
            }
            return uri;
        } catch (Throwable t) {
            return null;
        }
    }
}

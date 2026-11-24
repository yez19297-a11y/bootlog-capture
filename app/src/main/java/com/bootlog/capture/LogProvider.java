package com.bootlog.capture;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 极简的文件共享 Provider：把日志目录 / 缓存目录里的文件以 content:// 形式临时授权给其他应用，
 * 用于「分享日志」而无需申请存储权限。
 */
public class LogProvider extends ContentProvider {

    public static final String AUTHORITY = "com.bootlog.capture.files";

    public static Uri uriFor(String name) {
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(name)
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.length() == 0 || name.contains("..") || name.contains("/")) {
            throw new FileNotFoundException("非法文件名");
        }
        // 日志目录
        for (File d : FileUtil.logDirCandidates(getContext())) {
            File f = new File(d, name);
            if (f.isFile()) {
                return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
            }
        }
        // 导出用的缓存文件
        File cached = new File(getContext().getCacheDir(), name);
        if (cached.isFile()) {
            return ParcelFileDescriptor.open(cached, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        throw new FileNotFoundException(name);
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name != null && name.endsWith(".zip")) return "application/zip";
        return "text/plain";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}

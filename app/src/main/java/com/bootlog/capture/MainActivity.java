package com.bootlog.capture;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {

    private View dot;
    private TextView tvState, tvUptime, tvStart, tvFile, tvSize, tvDir, tvFileCount, tvEmpty, tvManage;
    private Button btnStart, btnStop, btnLive, btnExport, btnBattery, btnAdb;
    private View banner;
    private Switch swAuto, swRoot, swKernel, swResume;
    private Spinner spLevel, spMax, spKeep;
    private LinearLayout containerFiles;

    private Prefs prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean binding = true;
    private long lastListRefresh;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            if (System.currentTimeMillis() - lastListRefresh > 4000) {
                refreshFiles();
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);

        bindViews();
        setupSpinners();
        setupSwitches();
        setupButtons();

        binding = false;
        updatePermissionBanner();
        refreshStatus();
        refreshFiles();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(ticker);
        handler.post(ticker);
        updatePermissionBanner();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
    }

    private void bindViews() {
        dot = findViewById(R.id.dot);
        tvState = (TextView) findViewById(R.id.tvState);
        tvUptime = (TextView) findViewById(R.id.tvUptime);
        tvStart = (TextView) findViewById(R.id.tvStart);
        tvFile = (TextView) findViewById(R.id.tvFile);
        tvSize = (TextView) findViewById(R.id.tvSize);
        tvDir = (TextView) findViewById(R.id.tvDir);

        btnStart = (Button) findViewById(R.id.btnStart);
        btnStop = (Button) findViewById(R.id.btnStop);
        btnLive = (Button) findViewById(R.id.btnLive);
        btnExport = (Button) findViewById(R.id.btnExport);
        btnBattery = (Button) findViewById(R.id.btnBattery);
        btnAdb = (Button) findViewById(R.id.btnAdb);

        banner = findViewById(R.id.banner);
        tvFileCount = (TextView) findViewById(R.id.tvFileCount);
        tvManage = (TextView) findViewById(R.id.tvManage);
        tvEmpty = (TextView) findViewById(R.id.tvEmpty);
        containerFiles = (LinearLayout) findViewById(R.id.containerFiles);

        swAuto = (Switch) findViewById(R.id.swAuto);
        swRoot = (Switch) findViewById(R.id.swRoot);
        swKernel = (Switch) findViewById(R.id.swKernel);
        swResume = (Switch) findViewById(R.id.swResume);
        spLevel = (Spinner) findViewById(R.id.spLevel);
        spMax = (Spinner) findViewById(R.id.spMax);
        spKeep = (Spinner) findViewById(R.id.spKeep);
    }

    // ------------------------------------------------------------------ 设置

    private void setupSpinners() {
        final String[] levelValues = getResources().getStringArray(R.array.level_values);
        ArrayAdapter<CharSequence> la = ArrayAdapter.createFromResource(this,
                R.array.levels, R.layout.spinner_item);
        la.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spLevel.setAdapter(la);
        spLevel.setSelection(indexOf(levelValues, prefs.level(), 0));
        spLevel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (binding) return;
                String v = levelValues[position];
                if (v.equals(prefs.level())) return; // 忽略初始化时的回调
                prefs.setLevel(v);
                hintNextStart();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        final String[] maxValues = getResources().getStringArray(R.array.max_size_values);
        ArrayAdapter<CharSequence> ma = ArrayAdapter.createFromResource(this,
                R.array.max_sizes, R.layout.spinner_item);
        ma.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spMax.setAdapter(ma);
        spMax.setSelection(indexOf(maxValues, String.valueOf(prefs.maxMb()), 1));
        spMax.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (binding) return;
                int v = Integer.parseInt(maxValues[position]);
                if (v == prefs.maxMb()) return;
                prefs.setMaxMb(v);
                hintNextStart();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        final String[] keepValues = getResources().getStringArray(R.array.keep_count_values);
        ArrayAdapter<CharSequence> ka = ArrayAdapter.createFromResource(this,
                R.array.keep_counts, R.layout.spinner_item);
        ka.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spKeep.setAdapter(ka);
        spKeep.setSelection(indexOf(keepValues, String.valueOf(prefs.keepFiles()), 1));
        spKeep.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (binding) return;
                int v = Integer.parseInt(keepValues[position]);
                if (v == prefs.keepFiles()) return;
                prefs.setKeepFiles(v);
                FileUtil.prune(MainActivity.this, prefs.keepFiles());
                refreshFiles();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private int indexOf(String[] arr, String value, int def) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(value)) return i;
        }
        return def;
    }

    private void setupSwitches() {
        swAuto.setChecked(prefs.autoStart());
        swAuto.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (binding) return;
            prefs.setAutoStart(isChecked);
            toast(isChecked ? "已开启开机自动记录" : "已关闭开机自动记录");
        });

        swRoot.setChecked(prefs.rootMode());
        swRoot.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (binding) return;
            prefs.setRootMode(isChecked);
            updatePermissionBanner();
            if (isChecked) {
                checkRootAsync();
            } else {
                toast("已关闭 Root 模式");
            }
        });

        swKernel.setChecked(prefs.kernelLog());
        swKernel.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (binding) return;
            prefs.setKernelLog(isChecked);
            toast(isChecked ? "已开启：下次开始记录时抓内核快照" : "已关闭内核日志快照");
        });

        swResume.setChecked(prefs.resumeSession());
        swResume.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (binding) return;
            prefs.setResumeSession(isChecked);
            toast(isChecked
                    ? "已开启断点续写：同一次开机内不再重复回放"
                    : "已关闭：每次开始都会完整回放缓冲区（日志会重复）");
        });
    }

    private void hintNextStart() {
        if (App.STATE.running) {
            toast("已保存，停止后重新开始记录即生效");
        } else {
            toast("已保存");
        }
    }

    private void checkRootAsync() {
        new Thread(() -> {
            String out = RootHelper.execSu("id", 8000);
            boolean su = out != null && out.contains("uid=0");
            if (!su) {
                runOnUiThread(() -> {
                    updatePermissionBanner();
                    toast("未检测到 Root（su 不可用），只能记录本应用日志");
                });
                return;
            }
            boolean ok = RootHelper.grantLogPermission(MainActivity.this);
            final boolean granted = ok;
            runOnUiThread(() -> {
                updatePermissionBanner();
                toast(granted ? "已通过 Root 授予日志读取权限" : "Root 可用，但授权失败，退回普通模式");
            });
        }, "bootlog-root-check").start();
    }

    // ------------------------------------------------------------------ 操作

    private void setupButtons() {
        btnStart.setOnClickListener(v -> startCapture());
        btnStop.setOnClickListener(v -> stopCapture());
        btnLive.setOnClickListener(v -> {
            Intent i = new Intent(this, LiveLogActivity.class);
            if (!App.STATE.fileName.isEmpty()) {
                i.putExtra(LiveLogActivity.EXTRA_FILE, App.STATE.fileName);
            }
            startActivity(i);
        });
        btnExport.setOnClickListener(v -> exportAll());
        btnBattery.setOnClickListener(v -> requestBatteryWhitelist());
        btnAdb.setOnClickListener(v -> showAdbDialog());
        tvManage.setOnClickListener(v -> showDeleteDialog());
    }

    private void startCapture() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 101);
            }
        }
        Intent i = new Intent(this, LogService.class);
        i.setAction(LogService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Throwable t) {
            toast("启动失败：" + t.getMessage());
        }
        handler.postDelayed(() -> {
            refreshStatus();
            refreshFiles();
        }, 900);
    }

    private void stopCapture() {
        Intent i = new Intent(this, LogService.class);
        i.setAction(LogService.ACTION_STOP);
        try {
            startService(i);
        } catch (Throwable t) {
            toast("停止失败：" + t.getMessage());
        }
        handler.postDelayed(() -> {
            refreshStatus();
            refreshFiles();
        }, 900);
    }

    // ------------------------------------------------------------------ 刷新

    private void refreshStatus() {
        LogState st = App.STATE;
        boolean running = st.running;

        dot.setBackgroundResource(running ? R.drawable.bg_dot_on : R.drawable.bg_dot_off);
        if (running) {
            tvState.setText("正在记录");
            tvState.setTextColor(0xFF0F172A);
        } else if (st.lastReason != null && st.lastReason.length() > 0) {
            tvState.setText("已停止");
            tvState.setTextColor(0xFF334155);
        } else {
            tvState.setText("未开始记录");
            tvState.setTextColor(0xFF334155);
        }

        tvUptime.setText("已开机 " + FileUtil.uptimeText(SystemClock.elapsedRealtime()));
        tvStart.setText(st.startWall > 0 ? FileUtil.formatTime(st.startWall) : "—");
        tvFile.setText(st.fileName == null || st.fileName.isEmpty() ? "—" : st.fileName);
        tvSize.setText(FileUtil.formatSize(st.bytes) + " · " + st.lines + " 行");
        tvDir.setText(st.dirPath == null || st.dirPath.isEmpty() ? "—" : st.dirPath);

        btnStart.setEnabled(!running);
        btnStop.setEnabled(running);
        btnStop.setTextColor(running ? 0xFF334155 : 0xFFA9B4C2);
    }

    private void refreshFiles() {
        lastListRefresh = System.currentTimeMillis();
        List<File> files = FileUtil.listLogFiles(this);
        containerFiles.removeAllViews();
        tvFileCount.setText(files.size() + " 个");
        tvEmpty.setVisibility(files.isEmpty() ? View.VISIBLE : View.GONE);

        int limit = Math.min(files.size(), 8);
        for (int i = 0; i < limit; i++) {
            final File f = files.get(i);
            if (i > 0) {
                View div = new View(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1);
                div.setLayoutParams(lp);
                div.setBackgroundColor(0xFFF1F5F9);
                containerFiles.addView(div);
            }
            View row = getLayoutInflater().inflate(R.layout.item_file, containerFiles, false);
            TextView name = (TextView) row.findViewById(R.id.tvName);
            TextView meta = (TextView) row.findViewById(R.id.tvMeta);
            name.setText(f.getName());
            String m = FileUtil.formatSize(f.length()) + " · " + FileUtil.formatTime(f.lastModified());
            if (f.getName().startsWith("kernel_")) m = "内核日志 · " + m;
            if (i == 0 && App.STATE.running) m += " · 写入中";
            meta.setText(m);
            row.setOnClickListener(v -> showFileDialog(f));
            containerFiles.addView(row);
        }

        if (files.size() > limit) {
            TextView more = new TextView(this);
            more.setText("还有 " + (files.size() - limit) + " 个文件，点右上角「管理」查看全部");
            more.setTextSize(10);
            more.setTextColor(0xFF9AA7B8);
            more.setPadding(0, dp(10), 0, dp(2));
            containerFiles.addView(more);
        }
    }

    // ------------------------------------------------------------------ 文件操作

    private void showFileDialog(final File f) {
        String[] items = {"查看内容", "分享文件", "删除文件"};
        new AlertDialog.Builder(this)
                .setTitle(f.getName())
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        Intent i = new Intent(this, LiveLogActivity.class);
                        i.putExtra(LiveLogActivity.EXTRA_FILE, f.getName());
                        startActivity(i);
                    } else if (which == 1) {
                        shareFile(f);
                    } else {
                        confirmDelete(f);
                    }
                })
                .show();
    }

    private void shareFile(File f) {
        try {
            Uri uri = LogProvider.uriFor(f.getName());
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, f.getName());
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "分享日志文件"));
        } catch (Throwable t) {
            toast("分享失败：" + t.getMessage());
        }
    }

    private void confirmDelete(final File f) {
        new AlertDialog.Builder(this)
                .setTitle("删除日志")
                .setMessage("确定删除 " + f.getName() + " 吗？此操作不可恢复。")
                .setPositiveButton("删除", (dialog, which) -> {
                    if (f.delete()) {
                        toast("已删除");
                    } else {
                        toast("删除失败");
                    }
                    refreshFiles();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 多选删除：一次挑几个文件，带「全选 / 取消选择」和体积统计。 */
    private void showDeleteDialog() {
        final List<File> files = FileUtil.listLogFiles(this);
        if (files.isEmpty()) {
            toast("还没有日志文件");
            return;
        }

        View root = getLayoutInflater().inflate(R.layout.dialog_select_files, null);
        final ListView lv = (ListView) root.findViewById(R.id.lvFiles);
        final TextView hint = (TextView) root.findViewById(R.id.tvSelectHint);
        final TextView btnAll = (TextView) root.findViewById(R.id.btnSelectAll);
        final TextView btnNone = (TextView) root.findViewById(R.id.btnSelectNone);
        final TextView btnDelete = (TextView) root.findViewById(R.id.btnDelete);
        final TextView btnCancel = (TextView) root.findViewById(R.id.btnCancel);

        final Set<Integer> picked = new HashSet<Integer>();
        final SelectAdapter adapter = new SelectAdapter(files, picked);
        lv.setAdapter(adapter);
        updatePickHint(hint, files, picked);
        syncDeleteButton(btnDelete, picked);

        final AlertDialog dlg = new AlertDialog.Builder(this).setView(root).create();

        lv.setOnItemClickListener((parent, view, position, id) -> {
            if (picked.contains(position)) {
                picked.remove(position);
            } else {
                picked.add(position);
            }
            adapter.notifyDataSetChanged();
            updatePickHint(hint, files, picked);
            syncDeleteButton(btnDelete, picked);
        });

        btnAll.setOnClickListener(v -> {
            picked.clear();
            for (int i = 0; i < files.size(); i++) picked.add(i);
            adapter.notifyDataSetChanged();
            updatePickHint(hint, files, picked);
            syncDeleteButton(btnDelete, picked);
        });

        btnNone.setOnClickListener(v -> {
            picked.clear();
            adapter.notifyDataSetChanged();
            updatePickHint(hint, files, picked);
            syncDeleteButton(btnDelete, picked);
        });

        btnCancel.setOnClickListener(v -> dlg.dismiss());

        btnDelete.setOnClickListener(v -> {
            if (picked.isEmpty()) {
                toast("还没有勾选文件");
                return;
            }
            final List<File> targets = new ArrayList<File>();
            long bytes = 0;
            int skipped = 0;
            String running = App.STATE.fileName;
            for (int i : picked) {
                File f = files.get(i);
                if (App.STATE.running && running != null && f.getName().equals(running)) {
                    skipped++;
                    continue;
                }
                targets.add(f);
                bytes += f.length();
            }
            if (targets.isEmpty()) {
                toast("正在写入的文件不能删除，请先停止记录");
                return;
            }
            final long total = bytes;
            final int skip = skipped;
            new AlertDialog.Builder(this)
                    .setTitle("删除 " + targets.size() + " 个文件")
                    .setMessage("共 " + FileUtil.formatSize(total) + "，删除后不可恢复。"
                            + (skip > 0 ? "\n\n有 " + skip + " 个正在写入的文件已跳过。" : ""))
                    .setPositiveButton("删除", (d, w) -> {
                        int n = FileUtil.deleteAll(targets);
                        toast("已删除 " + n + " 个文件");
                        dlg.dismiss();
                        refreshFiles();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });

        btnDelete.setEnabled(false);
        dlg.show();
    }

    private void syncDeleteButton(TextView btn, Set<Integer> picked) {
        boolean has = !picked.isEmpty();
        btn.setEnabled(has);
        btn.setAlpha(has ? 1f : 0.45f);
    }

    private void updatePickHint(TextView hint, List<File> files, Set<Integer> picked) {
        long bytes = 0;
        for (int i : picked) {
            if (i >= 0 && i < files.size()) bytes += files.get(i).length();
        }
        if (picked.isEmpty()) {
            hint.setText("共 " + files.size() + " 个文件，勾选要删除的");
        } else {
            hint.setText("已选 " + picked.size() + " / " + files.size()
                    + " 个 · " + FileUtil.formatSize(bytes));
        }
    }

    /** 文件多选列表的适配器（不依赖 RecyclerView，保持零第三方库）。 */
    private class SelectAdapter extends BaseAdapter {

        private final List<File> data;
        private final Set<Integer> picked;

        SelectAdapter(List<File> data, Set<Integer> picked) {
            this.data = data;
            this.picked = picked;
        }

        @Override
        public int getCount() {
            return data.size();
        }

        @Override
        public Object getItem(int position) {
            return data.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.item_select_file, parent, false);
            }
            File f = data.get(position);
            CheckBox cb = (CheckBox) v.findViewById(R.id.cbPick);
            TextView name = (TextView) v.findViewById(R.id.tvName);
            TextView meta = (TextView) v.findViewById(R.id.tvMeta);

            boolean on = picked.contains(position);
            cb.setChecked(on);
            name.setText(f.getName());

            String m = FileUtil.formatSize(f.length()) + " · " + FileUtil.formatTime(f.lastModified());
            if (f.getName().startsWith("kernel_")) m = "内核日志 · " + m;
            if (App.STATE.running && f.getName().equals(App.STATE.fileName)) m += " · 写入中";
            meta.setText(m);

            v.setBackgroundColor(on ? 0xFFEFF6FF : 0xFFFFFFFF);
            return v;
        }
    }

    private void exportAll() {
        final List<File> files = FileUtil.listLogFiles(this);
        if (files.isEmpty()) {
            toast("还没有日志文件");
            return;
        }
        toast("正在打包 " + files.size() + " 个文件…");
        new Thread(() -> {
            try {
                File zip = new File(getCacheDir(), "BootLog-" + FileUtil.stamp() + ".zip");
                long size = FileUtil.zip(files, zip);
                Uri downloadUri = FileUtil.publishToDownloads(MainActivity.this, zip);
                final Uri shareUri = (downloadUri != null) ? downloadUri : LogProvider.uriFor(zip.getName());
                final long finalSize = size;
                runOnUiThread(() -> showExportResult(zip, downloadUri, shareUri, files.size(), finalSize));
            } catch (final Throwable t) {
                runOnUiThread(() -> toast("导出失败：" + t.getMessage()));
            }
        }, "bootlog-export").start();
    }

    private void showExportResult(File zip, Uri downloadUri, final Uri shareUri,
                                  int count, long size) {
        final String where = (downloadUri != null)
                ? "下载/BootLog/" + zip.getName()
                : "应用缓存（请用“分享”导出）";
        new AlertDialog.Builder(this)
                .setTitle("打包完成")
                .setMessage("已打包 " + count + " 个日志文件，共 " + FileUtil.formatSize(size)
                        + "\n\n保存位置：" + where)
                .setPositiveButton("分享", (d, w) -> {
                    try {
                        Intent send = new Intent(Intent.ACTION_SEND);
                        send.setType("application/zip");
                        send.putExtra(Intent.EXTRA_STREAM, shareUri);
                        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(send, "分享日志压缩包"));
                    } catch (Throwable t) {
                        toast("分享失败：" + t.getMessage());
                    }
                })
                .setNegativeButton("好", null)
                .show();
    }

    // ------------------------------------------------------------------ 权限

    private void updatePermissionBanner() {
        boolean granted = hasReadLogs(this) || prefs.rootMode();
        banner.setVisibility(granted ? View.GONE : View.VISIBLE);
    }

    public static boolean hasReadLogs(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                return ctx.checkSelfPermission("android.permission.READ_LOGS")
                        == PackageManager.PERMISSION_GRANTED;
            }
            return ctx.getPackageManager().checkPermission(
                    "android.permission.READ_LOGS", ctx.getPackageName())
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private void showAdbDialog() {
        String cmd = "adb shell pm grant " + getPackageName() + " android.permission.READ_LOGS\n"
                + "adb shell appops set " + getPackageName() + " READ_LOGS allow";
        final String finalCmd = cmd;

        TextView tv = new TextView(this);
        tv.setText(cmd);
        tv.setTextSize(11);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(0xFFB9E4FF);
        tv.setBackgroundResource(R.drawable.bg_code);
        tv.setTextIsSelectable(true);
        int p = dp(14);
        tv.setPadding(p, p, p, p);

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(20), 0, dp(20), 0);
        wrap.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("用 adb 授予日志权限")
                .setMessage("手机连电脑开启 USB 调试后执行（每次重装应用需重新执行）：")
                .setView(wrap)
                .setPositiveButton("复制命令", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("adb", finalCmd));
                    toast("已复制到剪贴板");
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void requestBatteryWhitelist() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                toast("已在电池优化白名单中");
                return;
            }
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable ignored) {
                toast("当前系统无法打开电池优化设置");
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshStatus();
    }

    // ------------------------------------------------------------------ 工具

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}

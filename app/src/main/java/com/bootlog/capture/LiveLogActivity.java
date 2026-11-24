package com.bootlog.capture;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.util.List;

/**
 * 实时日志预览：每 1.5 秒读一次文件尾部，自动滚到底。
 */
public class LiveLogActivity extends Activity {

    public static final String EXTRA_FILE = "file";

    private TextView tvLog, tvTitle, tvSub;
    private ScrollView scroll;
    private Switch swFollow;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String fileName;
    private boolean follow = true;
    private long lastLength = -1;

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            load();
            handler.postDelayed(this, 1500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live);

        tvLog = (TextView) findViewById(R.id.tvLog);
        tvTitle = (TextView) findViewById(R.id.tvTitle);
        tvSub = (TextView) findViewById(R.id.tvSub);
        scroll = (ScrollView) findViewById(R.id.scroll);
        swFollow = (Switch) findViewById(R.id.swFollow);

        fileName = getIntent().getStringExtra(EXTRA_FILE);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        swFollow.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                follow = isChecked;
                if (follow) scrollToBottom();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refresher);
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresher);
    }

    private File resolveFile() {
        File f = null;
        if (fileName != null && fileName.length() > 0) {
            f = FileUtil.findByName(this, fileName);
        }
        if (f == null && App.STATE.running) {
            f = FileUtil.findByName(this, App.STATE.fileName);
        }
        if (f == null) {
            List<File> all = FileUtil.listLogFiles(this);
            if (!all.isEmpty()) f = all.get(0);
        }
        return f;
    }

    private void load() {
        File f = resolveFile();
        if (f == null || !f.isFile()) {
            tvTitle.setText("实时日志");
            tvSub.setText("暂无日志，请先在主界面开始记录");
            tvLog.setText("（暂无内容）");
            return;
        }

        tvTitle.setText(f.getName());
        String state = App.STATE.running ? "写入中" : "已结束";
        tvSub.setText(state + " · " + FileUtil.formatSize(f.length())
                + " · 每 1.5 秒刷新");

        long len = f.length();
        if (len == lastLength) return;
        lastLength = len;

        tvLog.setText(FileUtil.tail(f, 260, 72 * 1024));
        if (follow) scrollToBottom();
    }

    private void scrollToBottom() {
        scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }
}

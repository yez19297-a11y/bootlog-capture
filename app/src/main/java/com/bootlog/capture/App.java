package com.bootlog.capture;

import android.app.Application;

/**
 * 应用入口。全局状态放在这里，供 Activity 与 Service 之间共享（同一进程，无需 IPC）。
 */
public class App extends Application {

    /** 采集运行时状态，Service 负责写入，UI 只读。 */
    public static final LogState STATE = new LogState();

    @Override
    public void onCreate() {
        super.onCreate();
    }
}

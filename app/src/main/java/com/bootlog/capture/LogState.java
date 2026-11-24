package com.bootlog.capture;

/**
 * 采集状态的快照容器。字段都是 volatile，UI 线程直接读取即可。
 */
public class LogState {

    public volatile boolean running = false;

    /** 当前正在写入的文件名。 */
    public volatile String fileName = "";

    /** 日志目录绝对路径。 */
    public volatile String dirPath = "";

    /** 本次会话累计写入字节数。 */
    public volatile long bytes = 0L;

    /** 本次会话累计已写入行数。 */
    public volatile long lines = 0L;

    /** 本次会话开始时间（wall clock，毫秒）。 */
    public volatile long startWall = 0L;

    /** 是否以 Root 模式采集。 */
    public volatile boolean root = false;

    /** 上一次会话结束的原因。 */
    public volatile String lastReason = "";
}

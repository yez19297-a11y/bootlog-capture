# BootLog · 开机日志记录器

一个不依赖任何第三方库的 Android 应用：**开机自动开始抓 logcat，一直记到关机**，同时能把**内核日志**（dmesg / pstore）一并留档。日志按「启动批次」落盘，可实时查看、多选删除、单文件分享、整包导出。

- 安装包：`BootLog-v1.2.apk`（91 KB，minSdk 21 / targetSdk 34，已签名，可直接安装）
- SHA-256：`25d66286c8ab44b494971cf43b854d000266e2bda341103dc7a7a5a6445d5554`
- 源码：本仓库（Gradle 工程 + 纯命令行构建脚本 `build.sh` + GitHub Actions workflow）

---

## 一、安装

```bash
adb install -r BootLog-v1.2.apk
```

或把 APK 拷进手机点击安装（需允许「安装未知来源应用」）。

## 二、首次使用（关键，约 30 秒）

| 步骤 | 操作 |
|---|---|
| 1 | 打开应用，允许通知（前台服务需要常驻通知） |
| 2 | **授予日志读取权限**，二选一 ↓ |
| 3 | 点「开始记录」，然后重启手机验证开机自启 |

**方式 A：无 Root（推荐）**

```bash
adb shell pm grant com.bootlog.capture android.permission.READ_LOGS
adb shell appops set com.bootlog.capture READ_LOGS allow
```

应用内点「查看 adb 授权命令」可一键复制。**卸载重装后需要重新执行。**

**方式 B：有 Root**

打开应用内「Root 模式」开关，应用会自己执行 `su -c pm grant ... READ_LOGS`（一次性，不会常驻 su 进程），之后 logcat 以普通子进程运行，停止时能被干净回收。

> 不授权也能跑，但 Android 只允许应用读到自己进程的日志 —— 界面上会有黄色提示条。

## 三、日志在哪

| 位置 | 说明 |
|---|---|
| `/sdcard/Android/data/com.bootlog.capture/files/logs/` | 实时写入的位置，可用 adb 或文件管理器直接看 |
| `下载/BootLog/BootLog-时间.zip` | 点「打包导出」后生成，微信/邮件可直接发 |

```bash
adb pull /sdcard/Android/data/com.bootlog.capture/files/logs ./logs
```

文件名格式：

```
3f2a9c1e_20261120-170532.log          ← logcat 主日志（同一次开机）
3f2a9c1e_20261120-170532_p2.log       ← 单个文件到上限后的续写
kernel_3f2a9c1e_20261120-170535.log   ← 内核日志快照
a71b02dd_20261121-083010.log          ← 换了一次开机
```

同一个 `启动ID`（取自 `/proc/sys/kernel/random/boot_id`）= 同一次开机，不用再猜日志属于哪次重启。

## 四、它能记多早、记到多晚

**开始：不是从点按钮开始，而是从开机开始。**

1. 应用监听 `LOCKED_BOOT_COMPLETED` 并声明 `directBootAware` —— 这是普通应用能拿到的最早时机（用户还没输入锁屏密码时就会触发），配置和日志目录都放在设备加密存储里，锁定阶段就能写。
2. `logcat` 启动时**先完整回放环形缓冲区**再跟随输出，所以应用启动之前的日志（含开机早期系统服务启动过程）也会进入第一个文件。
3. 内核启动阶段由 pstore / dmesg 补齐（见第五节）。

> 诚实的边界：logcat 缓冲是**环形**的，容量通常 256 KB～4 MB/缓冲区。如果开机后过了很久应用才被拉起（例如被系统限制自启），最早那段会被新日志覆盖 —— 内核侧的 dmesg / pstore 不受此限制。

**结束：关机前最后一行也在。**

- 每读到一块（16 KB）就 `flush` 落盘，突然关机/掉电最多丢最后一块。
- 后台守护线程轮询 `sys.powerctl`，检测到 `shutdown` 时**多等 1.6 秒**把管道读干净，再写入「会话结束」标记和累计行数。
- 下次开机看到新文件；若上一个文件**没有**结束标记，说明那次是强制断电、长按电源或进程被强杀。

## 五、内核日志（v1.1 起）

设置里的「**内核日志快照**」默认开启。每次开始记录（含开机自启）会在同一目录额外生成 `kernel_<启动ID>_<时间>.log`，里面分三段：

| 段 | 来源 | 权限 | 覆盖的时间段 |
|---|---|---|---|
| 1 | `/proc/cmdline` | 无 | 本次开机的内核启动参数（bootargs、分区、debug 开关） |
| 2 | `/sys/fs/pstore/console-ramoops*`、`dmesg-ramoops*`、`/proc/last_kmsg` | **Root** | **上一次关机 / 内核 panic 的那几秒**，内核重启后仍保留 |
| 3 | `dmesg`（Root）/ `logcat -b kernel -d`（无 Root） | Root 更完整 | 本次开机至今，内核环形缓冲区最近一段 |

为什么必须三段拼起来才算「内核日志」：

- `logcat -b kernel` 是 logd 从 `/dev/kmsg` 抄来的副本，很多机型被 SELinux 关成空，且同样会回绕。
- `dmesg` 直接读内核环形缓冲，比 logcat 副本更新、更全，但需要 CAP_SYSLOG（uid 0）。
- **关机/panic 那一刻，内核已经来不及写 logcat 了** —— 唯一留下来的是 pstore（ramoops），下次开机能读到。这是排查「为什么突然死机重启」唯一的现场证据。

> pstore 为空是正常的：正常关机不写 ramoops，只有 kernel panic、部分强制重启或 watchdog 复位才会留下。

## 六、v1.2 改了什么

这一版全部针对真实使用中暴露的问题（来自一份 74 MB 的实机日志分析）。

### 1. 断点续写，不再重复回放

以前每次点「开始记录」都会把环形缓冲区从头回放一遍。同一小时内点两次，第二份文件里有一半是重复的；实测出现过 **17 秒写出 48 MB、切成 6 个文件**。

现在会记录「上一次写到哪一条」，新会话用 `logcat -T '<时间戳>'` 从那里接着读：

- 只对**同一次开机**（同 boot_id）生效 —— 换了开机仍然完整回放，否则会漏掉开机早期日志。
- 启动前会先探测 `-T` 是否被支持，不支持就自动放弃续读（老设备不会因此一行都收不到）。
- 时间戳会做合理性校验，落在未来的一律不用（否则 logcat 会一直空等）。
- 设置里可关闭（「断点续写」开关），关掉就恢复完整回放。

> 升级到 1.2 后的**第一次**会话仍会完整回放一次：旧版本正文写的是 UTC，而新版本写本地时间，拿旧时间戳当断点会错 8 小时。宁可重复一次，也不错位。

### 2. 自动清理异常掉电留下的空文件

之前的日志里出现过 3 个文件、共 **11.6 MB 全是 `0x00`** —— 内核 panic 时存储栈已冻结，写入进了 page cache 却没落盘，重启后回填成全 0。

现在每次成功启动后会在后台扫描一遍：

| 情况 | 处理 |
|---|---|
| 整个文件都是 0 | 直接删除（这种文件一个字节的日志都没有） |
| 只有尾部一段是 0 | 截断到最后一个有效字节（保住前半段可用日志） |
| 正常文件 | 不做任何改动（末尾是 `\n`，非 0，不会被误截） |

只扫描最近 2 MB 的尾部，32 MB 的文件也不会拖慢启动。

### 3. 正文时间改成与文件名同一时区

以前文件名是 `20261121-101515`（本地时间），正文却是 `2026-11-21 02:15:15 +0000`（UTC），对时间要手工加 8 小时。

现在改用 `-v year -v zone`，正文直接是 `+0800`。老设备不支持 `zone` 会依次降级到 `UTC`、再到 `threadtime`。

### 4. 单文件上限默认 32 MB（原 8 MB）

8 MB 太小，一次缓冲区回放就切成六七个文件，看一次开机要拼六个。新增 64 MB 选项，默认值提到 32 MB。

升级时会**一次性**把「还是旧默认值 8 MB」的配置迁到 32 MB；自己手动选过的不动。

### 5. 多选删除

日志列表右上角新增「**管理**」：

- 列出**全部**文件（主界面只显示最近 8 个）
- 每行可勾选，实时显示「已选 N / 总数 · 合计体积」
- 一键「全选 / 取消选择」
- 删除前二次确认，正在写入的文件自动跳过
- 单个文件的「查看 / 分享 / 删除」入口保持不变

### 6. 顺带修掉的两处

- **内核日志不再写两份**：v1.1 里 dmesg 既进了独立快照文件，又被追加进主日志（一次开机多出 8000 多行重复内容）。现在两者互斥。
- **失败重启加了退避**：连续异常时按 3s → 6s → 12s … 最长 60s 退避，避免「崩溃-重启」循环把磁盘刷满。

## 七、界面

- **状态卡**：运行状态、本次会话开始时间、当前文件名、已写入大小/行数、保存路径
- **开始 / 停止 / 实时查看 / 打包导出**
- **采集设置**：开机自启、Root 模式、内核日志快照、断点续写、日志级别（V/D/I/W/E）、单文件上限（4/8/16/32/64 MB）、保留文件数（10/20/50/100）、电池优化白名单
- **日志文件列表**：右上角「管理」进入多选删除；点单个文件可「查看内容 / 分享 / 删除」
- **实时查看页**：深色终端风格，1.5 秒刷新、自动滚到底，可关闭跟随

## 八、手机厂商的额外设置（重要）

国产 ROM 会主动限制后台，不设置的话可能出现「开机没自动记录」或「记几小时就断了」：

| 品牌 | 需要做的事 |
|---|---|
| 小米 / Redmi | 应用设置 → 省电策略 → **无限制**；自启动打开；最近任务卡片下拉**锁定** |
| 华为 / 荣耀 | 应用启动管理 → 关闭「自动管理」，手动勾选三项；电池优化 → 不允许 |
| OPPO / 一加 / realme | 电池 → 应用速冻 → 关闭；允许自启动、允许后台运行 |
| vivo / iQOO | 后台高耗电 → 允许；自启动 → 打开 |
| 三星 | 电池 → 后台使用限制 → 从「休眠应用」中移除 |

应用内「电池优化白名单 → 去设置」可直接跳转系统授权。

## 九、常见问题

**Q：日志里只有我自己应用的几条？**
READ_LOGS 没授予。执行第二节的 adb 命令或开 Root 模式，然后**停止再开始**记录。

**Q：内核日志文件里只有表头就没了？**
说明三段都没拿到内容（未 Root、kernel buffer 为空、pstore 也为空），应用会自动删掉这种空文件。开 Root 模式再试。

**Q：日志里出现了一段重复内容？**
① 检查「断点续写」是否被关掉；② 断点那一行本身会重复一次（`-T` 是含起始点的），属正常；③ 升级到 1.2 后的第一次会话会完整回放，之后不再重复。

**Q：日志文件变成 0 字节 / 全是乱码空白？**
那是异常掉电时写了一半的产物，1.2 起会在下次启动时自动清理。

**Q：为什么有的行时间是 `01-01 08:00:00.000`？**
早期内核日志没有 RTC 时间。现代设备会用 `-v year -v zone` 输出完整本地时间；老设备自动降级为 `threadtime`。

**Q：日志会不会很大？**
默认 32 MB/文件、保留 20 个 ≈ 640 MB 上限，超出自动删最旧的。如果觉得占空间，把上限调回 8 MB、保留数调到 10 即可。内核快照只有几百 KB。

**Q：删除时提示「正在写入的文件不能删除」？**
当前会话正在写的那个不能删，先点「停止」再删。

**Q：隐私？**
logcat 与 dmesg 里可能出现包名、账号片段、Wi-Fi 名称、甚至内核地址。导出分享前请自行检查。

## 十、从源码构建

**方式一：纯命令行（无需 Gradle / Android Studio，本机已验证）**

```bash
# 需要 JDK 17+ 与 Android SDK（platform 34 + build-tools 34.0.0）
export ANDROID_SDK_ROOT=/path/to/android-sdk
./build.sh          # 输出 build/BootLog-v1.2.apk
```

`build.sh` 走的是 `aapt2 compile → aapt2 link → javac → d8 → zipalign → apksigner`，全程无网络依赖，SDK 路径与 build-tools 版本自动探测。可用环境变量：

| 变量 | 作用 |
|---|---|
| `ANDROID_SDK_ROOT` | SDK 根目录（找不到时依次尝试 `/opt/android-sdk`、`~/Android/Sdk`） |
| `VERSION_NAME` / `VERSION_CODE` | 覆盖清单里的版本号 |
| `KEYSTORE_FILE` | 签名密钥；不存在时自动生成一个临时密钥（仅测试用） |
| `KS_PASS` / `KEY_ALIAS` / `KEY_PASS` | 签名口令 |

**方式二：Android Studio**

直接打开本目录（Gradle 8 + AGP 8.1.4），Run 即可。

## 十一、GitHub Actions

仓库自带 `.github/workflows/build.yml`，构建产物与本地 `build.sh` 完全同源（同一条 aapt2 → javac → d8 → apksigner 链路），不依赖 AGP 版本，因此不会出现「本地能编、CI 编不过」。

| 触发 | 行为 |
|---|---|
| push 到 `main` / 任意 PR | 构建 APK，上传为 artifact（保留 30 天） |
| 打 tag `v1.3` | 构建 + 自动创建 Release，附 APK 与 SHA-256 |
| 手动 Run workflow | 可填版本号，仅上传 artifact |

发版只需两步：

```bash
git tag v1.3 && git push origin v1.3
```

`versionCode` 由 tag 自动推导（`major*10000 + minor*100 + patch`，如 `1.3` → `10300`），保证单调递增，可直接覆盖安装。

**配置正式签名**（不配置也能构建，但产物是临时签名，仅供测试）——在仓库 `Settings → Secrets and variables → Actions` 添加：

```bash
# 本地生成并导出
base64 -w0 bootlog.keystore      # → 填入 ANDROID_KEYSTORE_BASE64
```

| Secret | 说明 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | keystore 文件的 base64 |
| `ANDROID_KEYSTORE_PASS` | storePassword |
| `ANDROID_KEY_ALIAS` | keyAlias |
| `ANDROID_KEY_PASS` | keyPassword |

> 换了签名密钥的构建无法覆盖安装旧版本，必须卸载重装。正式发版前先把这四个 secret 配好。

### 代码结构

```
app/src/main/java/com/bootlog/capture/
├── MainActivity.java     主界面：状态、设置、文件列表、多选删除、导出
├── LiveLogActivity.java  实时日志预览（深色终端风格）
├── LogService.java       前台服务：维持会话、常驻通知、被杀后自动恢复
├── LogSession.java       核心：起 logcat 子进程、断点续读、分块落盘、轮转、关机检测
├── KernelLog.java        内核日志快照：cmdline / dmesg / pstore / last_kmsg
├── BootReceiver.java     开机广播（LOCKED_BOOT_COMPLETED / BOOT_COMPLETED）
├── LogProvider.java      极简 ContentProvider，用于分享日志文件
├── RootHelper.java       su 一次性授权 READ_LOGS / 执行命令
├── Prefs.java            配置（设备加密存储，锁定阶段可读）
├── FileUtil.java         目录、格式化、tail、打包 zip、损坏文件修复
└── LogState.java         跨界面共享的运行状态
```

### 关键实现点

- **回放而非 tail**：`logcat` 不带 `-d` 时先打印缓冲区历史再跟随，正好覆盖「应用启动前」这段。
- **断点续读**：同 boot_id 内取上份文件最后一条时间戳，`-T` 接续；探测失败自动降级为全量回放。
- **分段轮转**：按字节上限切文件，每个文件都有独立的结束标记，续写文件头写「接上文」。
- **抗掉电**：每 16 KB 一次 flush，而不是靠缓冲区。
- **抗脏文件**：启动时扫描尾部 2 MB，全 0 删除、尾部半截 0 截断。
- **干净停止**：停止时先等 0.4 s 排空管道再 `destroy()`，随后关闭读端兜底，避免读线程卡死。
- **内核三段**：`dmesg` 直读环形缓冲、pstore 补上一次关机、`logcat -b kernel` 管实时，互不重叠。
- **权限自愈**：`probe` 失败自动降级日志格式；进程异常退出按指数退避重启（最多 20 次，稳定 10 分钟后重置计数）。

## 十二、推送到 GitHub

仓库已在本地初始化好（分支 `main`，含一次完整提交），三条命令即可发布到 `github.com/<你>/bootlog-capture`：

```bash
git remote add origin https://github.com/<你的用户名>/bootlog-capture.git
git branch -M main
git push -u origin main
```

推完立刻打 tag，触发第一次 Release 构建：

```bash
git tag v1.2 && git push origin v1.2
```

仓库设成 Private 也能用，但私有仓库的 Actions 有免费额度（每月 2000 分钟，公开仓库不限量）。

如果手上是 `BootLog-git.bundle`，用它克隆可保留提交历史：

```bash
git clone BootLog-git.bundle bootlog-capture
cd bootlog-capture
git remote set-url origin https://github.com/<你的用户名>/bootlog-capture.git
git push -u origin main --tags
```

## 十三、已验证 / 未验证

- 已验证：资源编译、清单合并、dex 生成（79 个类 / 659 个方法）、APK 对齐与签名（v1+v2+v3）、布局与代码中 ID 全量交叉校验、逐行 API level 审查（21→34 的版本分支）、断点时间戳解析与空文件修复算法（用 17 个真实日志文件回归验证）。
- 未验证：真机运行（构建环境无 Android 设备 / KVM）。首次装到手机后，建议先点「开始记录」看 10 秒确认文件在增长，停止后再点一次，确认第二份文件**没有**重复回放。

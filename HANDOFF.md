# DSH Harness 本地 Android — 工作区交接说明

> 换新对话直接粘贴本文件路径即可：`/workspace/HANDOFF.md`
> 环境全部放在持久层 `/workspace`，沙箱重置不会丢。

## 1. 环境（已解压，可直接用）

| 组件 | 路径 |
|---|---|
| 源码工程 | `/workspace/src/pkg1/Deepseek-Harness-Local-Android-1.4.36` （**当前缺失，待用户上传**） |
| JDK 17 | `/workspace/env/jdk-17/jdk-17.0.21+9`（Temurin 17.0.21） |
| Gradle | `/workspace/env/gradle-8.9`（独立安装，wrapper 不可用时兜底）；分发包备份 `env/gradle-dists/gradle-8.9-bin.zip` |
| Gradle 依赖缓存 | `/workspace/env/gradle-home/.gradle`（导出为 `GRADLE_USER_HOME`，首构建时自动填充） |
| Android SDK | `/workspace/env/android-sdk`（platform-tools 37.0.1 / build-tools 35.0.0+36.0.0 / platforms android-35+36 / **NDK r27c `27.2.12479018` 必需**：`app/src/main/cpp/dsh_pty.c` 提供真 PTY） |

一键环境变量：`. /workspace/env/env.sh`（source，不要用 sh 执行）。

无 `/opt`、`/root` 依赖 —— 不要再用旧的 `JAVA_HOME=/root/.local/...` 那条命令。

## 2. 构建（唯一命令）

```sh
sh /workspace/build-dsh.sh
```

脚本内部等价于：

```sh
JAVA_HOME=/workspace/env/jdk-17/jdk-17.0.21+9 \
GRADLE_USER_HOME=/workspace/env/gradle-home/.gradle \
ANDROID_HOME=/workspace/env/android-sdk \
./gradlew -Ppackage=noruntime -Pabi=arm64-v8a assembleDebug --no-daemon \
  -Dorg.gradle.jvmargs="-Xmx3g -XX:MaxMetaspaceSize=768m"
```

- 产物：`app/build/outputs/apk/debug/app-debug.apk`，约 24M
- `-Ppackage=noruntime`：APK 不打包 runtime，装后需在启动页导入 `runtime-arm64-v8a.zip`
- `-Pabi=arm64-v8a`：真机架构
- 全量首次约 2 分钟；带缓存增量约 30–60 秒

## 3. 版本与签名

- `versionCode = 286` / `versionName = "1.4.36"`（内部号未跟随文件名，**靠 APK 文件名区分版本**）
- debug 签名已固定到 `keystore/dsh-debug.keystore`（口令 `dshdebug`，别名 `dsh-debug`）。
  早期签名随环境重置而变、导致必须卸载重装；固定后同签名可覆盖升级。

## 4. 已实现的关键点（1.7.x → 1.8.2）

| 版本 | 内容 |
|---|---|
| 1.7.5 | `onDestroy` 精确销毁序列（removeView → stopLoading → clearHistory → destroy）+ 防重建守卫 |
| 1.7.6 | **轻量模式**（LiteMode）：CSS 削减 backdrop-filter/模糊/阴影/合成层，CSP 被拦时降级 CSSOM |
| 1.7.7 / 1.7.9 | 应用图标全量替换为毛玻璃版（去掉 adaptive icon 裁切，保留圆角与光晕） |
| 1.7.8 | 引擎端口修复：引擎**不读 `PORT` 环境变量**，必须追加 `--port <n>`（位置在 `web --no-open` 之后、`--patch` 之后）；改端口后自动重启引擎 |
| 1.8.1 | 按用户要求**移除**在线升级引擎功能（拿不到签名索引） |
| 1.8.2 | 设置页重构：移除网格模式、分区标题吸顶、标题样式统一（12sp + 0.08 字距） |

### 设置页吸顶的实现要点

- 顶层 `FrameLayout(ScrollView + 悬浮 TextView@id/stickySection)`
- 每个分区标题带 `android:tag="sec"`
- `setupStickySectionHeader()` 在 `applyLayoutMode` 之后 `post` 执行，预计算各标题相对
  `settingsContentWidth` 的累计 top；滚动时取「已滚过且最靠下」的一个 —— **双栏
  （columnPrimary/columnSecondary）也正确**
- 点吸顶条回跳该分区顶部
- 网格模式（`SettingsLayoutMode.GRID`）的枚举、分支、`compactRowForGrid`、文案已全删；
  存量偏好 `"grid"` 由 `fromId` 自动回落 LIST

## 5. 改代码前必读的约定

1. **Kotlin 源码禁止中文（CJK）字面量**：`scripts/check-i18n.py` 会拦。所有文案进
   `app/src/main/res/values/strings.xml`，Kotlin 里只用 `R.string.xxx`。
2. **WebView 内缩（键盘/系统栏）必须加在父容器 `webHost`**，Chromium 忽略 WebView 自身 padding。
3. **边到边**：`webHost` 顶部 padding 归零（否则露出窗口底色形成空白带），只有底部跟随 IME。
4. **引擎端口**：`EngineProcess` 里 `--port` 必须排在 `web --no-open` 之后（Launcher 层先解析
   `--patch`，web app 层才解析 `--port`）。
5. **设置页分区识别**：`sectionCards()` 按 `columnPrimary/columnSecondary` 下的卡片收集，
   新增分区请沿用 `@drawable/bg_card` 与同结构。
6. **`StateFlow` 去重坑**：data class `State` 内容相同不重发；需要「一写入就触发」的信号
   （如乐观首屏 URL）必须用独立 Flow（`EngineSupervisor.loadUrl`）。

## 6. 关键文件索引

| 文件 | 职责 |
|---|---|
| `app/src/main/java/app/dsh/mobile/MainActivity.kt` | WebView 容器、边到边、键盘、启动遮罩动画、LiteMode 注入 |
| `app/src/main/java/app/dsh/mobile/SettingsActivity.kt` | 设置页全部逻辑（含吸顶、运行时导入、端口设置） |
| `app/src/main/java/app/dsh/mobile/engine/EngineSupervisor.kt` | 引擎生命周期状态机、健康探测、乐观首屏 |
| `app/src/main/java/app/dsh/mobile/engine/EngineProcess.kt` | 进程拉起的命令行拼装（`--port` 就在这里） |
| `app/src/main/java/app/dsh/mobile/engine/RuntimeInstaller.kt` | runtime.zip 原子换装 + 失败自动回滚（SwapLog） |
| `app/src/main/java/app/dsh/mobile/engine/ProfileGuardian.kt` | 配置快照/回滚/安全模式（last-good 快照，`profiles.crash-archive-*` 保留最近 N 份） |
| `app/src/main/res/layout/activity_settings.xml` | 设置页布局（2200 行，分区标题带 `tag="sec"`） |
| `app/src/main/res/drawable/bg_sticky_header.xml` | 吸顶条背景（bg + 底部 1dp 分隔线） |

## 7. 已知问题 / 边界

- **WebView 内存 350–450MB 是 Chromium 物理下限**（多进程 + V8 + Blink 空转 150–200MB 起）。
  唯一可行的继续压降手段是轻量模式（省 40–80MB）；`android:process=":webview"`
  会让 App 总内存更高（两个进程 PSS 都算），不要做。
- 在线升级引擎已移除（上游索引签名与本地 debug 签名不符，且拿不到发布产物）。
  升级方式只剩「设置 → 导入本地 runtime zip」。
- 关于页/设置页文案为中文单语言（其他语言已按要求删除）。

## 8. 工作区现状

```
/workspace
├── HANDOFF.md                              本文件（重建时已更新路径）
├── build-dsh.sh                            构建脚本（自动定位 src/ 下的 gradlew）
├── env/                                    3.2G
│   ├── jdk-17/jdk-17.0.21+9                Temurin JDK 17（319M）
│   ├── android-sdk/                        2.7G：platform-tools / build-tools 35+36 /
│   │                                       platforms 35+36 / ndk 27.2.12479018 (r27c)
│   ├── gradle-8.9/                         独立 Gradle 8.9（145M）
│   ├── gradle-dists/gradle-8.9-bin.zip     分发包离线备份（130M）
│   ├── gradle-home/.gradle                 GRADLE_USER_HOME（首构建时填充）
│   └── env.sh                              环境变量一键导出
└── src/pkg1/                               源码工程待放（当前为空）
```

**⚠️ 重建说明**：沙箱重置后原 `src/`、APK 产物、`runtime-arm64-v8a.zip`、文档备份均已丢失。
工具链（JDK / SDK / NDK / Gradle）已按本文档规格重新装好并通过自检；
源码工程需重新放入 `/workspace/src/pkg1/Deepseek-Harness-Local-Android-1.4.36/`，
首次构建会重新下载 AGP 依赖（约 1.1G，仅一次）。

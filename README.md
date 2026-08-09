
# BPC Time Sync

Android APP for transmitting China BPC (68.5 kHz, Shangqiu) radio-controlled time signal to synchronize radio watches such as Casio GWM5610.

利用手机音频输出非对称脉冲波（17.125 kHz 基频，第 4 次谐波 = 68.5 kHz），配合外接线圈，模拟商丘授时台信号，让电波手表自动对时。

---

## 工作原理

### 信号链路

```
NTP 服务器 ──→ SNTP 同步 ──→ BPC 编码 ──→ 非对称脉冲波 ──→ 手机音频口 ──→ 线圈 ──→ 手表
              (精确时间)      (20秒帧×3)    (17.125 kHz)                 (68.5 kHz 磁场)
```

### 谐波方案

手机音频 DAC 最高采样率 192 kHz，无法直接输出 68.5 kHz 正弦波。本 APP 采用以下方案：

| 参数 | 值 |
|------|-----|
| 目标载波 | 68.5 kHz (BPC 商丘授时台) |
| 输出基频 | 17.125 kHz (= 68.5 / 4) |
| 波形 | 非对称脉冲波 (占空比 40%) |
| 谐波次数 | 第 4 次 (17.125 x 4 = 68.5 kHz) |
| 采样率 | 192 kHz |

> **为什么用非对称脉冲波而不是方波？** 方波只含奇次谐波（1、3、5...），无法产生 68.5 kHz。非对称脉冲波（40% 占空比）同时含奇次和偶次谐波，第 4 次谐波恰好落在 68.5 kHz。

### BPC 协议

每分钟包含 3 个 20 秒帧，起始于 0s、20s、40s。每秒编码 2 位二进制数据（四进制脉冲宽度调制）：

| 低电平时长 | 四进制值 | 含义 |
|-----------|---------|------|
| 0 ms | - | P0 帧起始（全秒高电平） |
| 100 ms | 00 | 四进制 0 |
| 200 ms | 01 | 四进制 1 |
| 300 ms | 10 | 四进制 2 |
| 400 ms | 11 | 四进制 3 |

帧数据布局：

| 秒序号 | 字段 | 说明 |
|--------|------|------|
| S0 | P0 | 帧起始标记 |
| S1 | 帧号 | 00/01/10 = 第 1/2/3 帧 |
| S2 | 保留 | 固定 00 |
| S3-S4 | 时 | 12 小时制 (0-11) |
| S5-S7 | 分 | 0-59 |
| S8-S9 | 星期 | 1=周一, 7=周日 |
| S10 | AM/PM + 校验 | 上/下午 + S1-S9 偶校验 |
| S11-S13 | 日 | 1-31 |
| S14-S15 | 月 | 1-12 |
| S16-S18 | 年 | 0-99 |
| S19 | 年 MSB + 校验 | 年最高位 + S11-S18 偶校验 |

### 发射时序

1. 点击"开始发射"后，APP 等待下一个整分 `:00` 边界
2. 最后 50 ms 采用 busy-wait（自旋等待），误差 < 1 ms
3. 到达 `:00` 后，编码当前分钟数据，开始连续发射
4. P0 帧标记与真实时钟秒 0 精确对齐
5. 多分钟发射时 AudioTrack 只创建一次，分钟间零间隙无缝衔接

---

## 功能

- **NTP 网络授时** — 支持 time1.aliyun.com / ntp.tencent.com / pool.ntp.org，自动故障切换
- **BPC 协议编码** — 完整实现 20 秒帧格式，含时/分/日/月/年/星期/校验位
- **音频信号输出** — 192 kHz 采样率，非对称脉冲波 4 次谐波方案
- **整分对齐发射** — 精确等到 `:00` 边界启动，确保 P0 与真实秒 0 对齐
- **多分钟连续发射** — 1/3/5 次可选，AudioTrack 无缝衔接不断流
- **实时状态显示** — 倒计时、发射进度、信号参数一目了然

---

## 使用方法

### 准备工作

1. 准备一个线圈（可用漆包线绕 50-100 匝，直径 5-10 cm）
2. 将线圈通过 3.5mm 耳机插头或 Type-C 转接头连接到手机

### 操作步骤

1. 打开 APP，首页点击 **"同步时间"** 从 NTP 服务器获取精确时间
2. 确认时间显示正确，NTP 状态为"已同步"
3. 将手机音量调至最大
4. 点击 **"开始发射 BPC 信号"**
5. APP 会等待到下一个整分 `:00` 开始发射（倒计时显示剩余秒数）
6. 将电波手表放置在线圈侧面 2-5 cm 处
7. 手表接收信号后自动对时（通常需要 1-3 分钟）

### 设置选项

- **NTP 服务器** — 可切换时间源服务器
- **采样率** — 48 / 96 / 192 kHz（越高谐波能量越强）
- **重复发射次数** — 1 / 3 / 5 次
- **正分整点发射** — 开启后只在 `:00` 边界启动发射

---

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material 3 |
| 架构 | MVVM (ViewModel + StateFlow) |
| 音频 | AudioTrack (PCM 16-bit, 192 kHz) |
| 网络 | SNTP (UDP, RFC 5905) |
| 异步 | Kotlin Coroutines |
| 持久化 | DataStore Preferences |
| 最低版本 | Android 8.0 (API 26) |
| 目标版本 | Android 14 (API 34) |
| 构建工具 | Gradle 8.4 + AGP |

---

## 项目结构

```
BpcTimeSync/
├── app/src/main/java/com/bpctimesync/
│   ├── BpcApp.kt                      # Application 入口
│   ├── MainActivity.kt                # Compose UI (首页/发射页/设置页)
│   ├── ntp/
│   │   └── NtpClient.kt               # SNTP 客户端
│   ├── bpc/
│   │   └── BpcEncoder.kt              # BPC 协议编码器
│   ├── audio/
│   │   └── BpcAudioOutput.kt          # 音频输出引擎
│   └── ui/
│       └── MainViewModel.kt           # 状态管理 + 发射时序控制
├── app/src/main/res/
│   ├── values/                        # 主题、字符串
│   └── drawable/                      # 图标
├── app/build.gradle.kts
├── settings.gradle.kts
└── gradle/wrapper/gradle-wrapper.properties
```

### 核心模块

#### `BpcEncoder.kt`
BPC 协议编码器。`encodeMinute(cal)` 将一个 `Calendar` 时刻编码为 3 个 20 秒帧，`toLowDurations(frame)` 转换为 60 个静默时长（ms），供音频引擎使用。

#### `BpcAudioOutput.kt`
音频输出引擎。使用 AudioTrack 以 192 kHz 采样率输出 17.125 kHz 非对称脉冲波（40% 占空比）。`transmitAllMinutesAsync()` 实现多分钟连续发射，AudioTrack 只创建一次，相位连续推进，分钟间零间隙。

#### `MainViewModel.kt`
状态管理与发射时序控制。`startTransmit()` 包含完整的发射流程：等待 `:00` 整分边界（粗等待 + busy-wait） → 编码所有分钟数据 → 连续发射。

#### `NtpClient.kt`
SNTP 客户端。实现 RFC 5905 协议，通过 UDP 向 NTP 服务器发送 48 字节请求报文，解析返回的时间戳并计算本地时钟偏差。

---

## 构建

### 环境要求

- JDK 17
- Android SDK 34
- Gradle 8.4

### 步骤

```bash
# 使用 Gradle Wrapper 构建
./gradlew assembleDebug

# 输出 APK
# app/build/outputs/apk/debug/app-debug.apk
```

或在 Android Studio 中直接打开项目，点击 Run。

> **国内网络注意：** `settings.gradle.kts` 已配置阿里云 Maven 镜像，`gradle-wrapper.properties` 已配置腾讯云 Gradle 下载源。Gradle JVM 需设置为 jbr-17（Android Studio 自带）。

---

## 已验证设备

- Casio G-Wolf GWM5610 — 已成功对时

> 理论上支持所有能接收中国 BPC 68.5 kHz 信号的电波手表。

---

## 参考资源

- [BPC time signal - Wikipedia](https://en.wikipedia.org/wiki/BPC_(time_signal))
- [JJY Simulator - starstonesoft.com](http://www.starstonesoft.com/jjy_simulator.htm) — 谐波方案参考
- 专利 CN1667528A — BPC 编码格式

---



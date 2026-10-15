# Sync+ / DiPlay CarPlay 仪表桥接

包名：`com.local.diplay.clusterbridge`，版本：0.1.7。已在原测试车机安装；最终版本的完整首次冷开机流程尚未再次实测。界面使用深蓝灰背景、浅色文字和绿色开关，左右两栏分别显示设置及运行状态。已删除型号和 Android 8.1 白名单限制，不再根据车型或系统是否为 API 27 拒绝转发；最低系统要求仍为 Android 8.0。仪表应用及车机助手的签名检查保留，签名变化或助手缺失时拒绝转发。其他车型的实际仪表协议兼容性尚未验证。

## 工作方式

DiPlay 的标准 `NavigationWidget` → Android `AppWidgetHost` → 本应用读取小组件中实际呈现的箭头、距离、道路、歌曲 → 定向广播到现有仪表程序。

本应用自行实现桥接代码，没有修改或重新打包 DiPlay，没有复制其私有认证材料，也没有替换仪表系统应用。没有网络、无障碍、通知读取或 root 权限。声明前台服务、接收开机广播、日志读取和使用情况访问权限；小组件绑定、日志读取需单独通过 ADB 授权，使用情况访问只供默认关闭的自动进入功能使用。

0.1.4 增加路名回退：本次百度地图的 iAP2 数据未提供专用的“当前路名”和“转向后的路名”字段，但提供了 `0x5202` 参数 2 的完整导航说明，其中包含道路文字。DiPlay 0.2.19 没有把这个说明填入小组件，所以仪表收到空路名，显示为 `null`。

小组件有正常路名时，优先使用小组件。路名为空时，桥接读取 `xcertplay-usb` 标签的日志，并检查来源 UID 是否为当前 DiPlay，按进程、会话、转向编号缓存说明。只有当前导航状态、方向和取整距离均与小组件匹配，且导航状态不超过 15 秒，才把这段真实说明作为仪表道路文字。它保留导航说明原文，不凭文字猜道路名称。没有可用数据时显示“道路名称未提供”，避免把空值显示为 `null`。

日志读取权限可以访问系统日志；本应用的读取命令只选择 DiPlay 的传输标签，解析器只接受 DiPlay UID 的导航记录，其他记录会丢弃。日志进程随桥接服务停止，应用不把原始日志写入文件、不上传。

0.1.5 在本应用私有偏好中缓存解析后的转向说明，覆盖更新后可以恢复。恢复只加载缓存，不恢复“导航有效”的判断；必须重新收到匹配进程、转向编号、方向和距离的新导航状态后才能使用。恢复时拒绝超过 24 小时的说明，导航结束或断开时清除相应缓存。

箭头通过当前安装 DiPlay 的同名 drawable 渲染结果匹配，资源 ID 在运行时读取。DiPlay 后续更新保留这些小组件名称和显示规则时，可以继续使用；接口变化时可能需要更新桥接应用。未知箭头或距离解析失败不会被当成直行指令。导航小组件超过 5 秒不更新时，停止本应用开启的导航。

## 0.1.7 首次连接与歌曲推送

音乐转发开启后，第一次从 DiPlay 小组件读到有效歌曲就推送一次；之后只在歌名或播放/暂停状态实际变化时更新。读取同一首歌不会反复推送；没有周期重发，也没有启动阶段的清空/回填。断开或关闭转发时仍清除本应用开启的音乐显示；重新连接后可推送新的首次歌曲快照。

“连接后停留在 DiPlay 时自动进入 CarPlay”默认关闭。启用并授予使用情况访问后，以 CarPlay 真正连接成功为起点观察 30 秒，连接前的等待不计入这个窗口。等待至少 3 秒，确认 DiPlay 入口 Activity 连续停留约 2 秒后，调用 DiPlay 小组件原有的点击动作，最多请求一次。

首次开机可能先显示 CarPlay、随后又回到 DiPlay 入口。因此首次看到 CarPlay 不会提前结束这 30 秒的检查，仍保留一次回退纠正机会。切到其他应用后，本次连接立即结束检查；窗口结束或已补跳转后，不持续拉回。锁屏、灭屏、权限缺失或无法确认前台时不跳转。

DiPlay 主页和设置页共用 `DiPlayActivity`，外部应用只能识别 Activity，无法精确区分内部页面。若希望新连接后留在 DiPlay 设置页，请关闭自动进入。此功能辅助处理入口停留，不修改 DiPlay 内部的启动逻辑。Android 10 及更新版本还可能限制后台启动界面，当前未实测。

在桥接页面点“授权自动进入 CarPlay”，在系统的“使用情况访问”中允许本应用；定制车机没有授权页面时，可在停稳后用 ADB：

```sh
adb shell appops set com.local.diplay.clusterbridge GET_USAGE_STATS allow
```

该授权只用于自动进入，音乐推送不需要它。建议授权并开启后重新连接一次 CarPlay。应用仅在连接窗口内读取本次开机事件，后续增量读取，并在内存保留最新前台状态，避免主页停留超过一分钟后被遗漏；不保存或上传应用使用历史。

本地构建、签名和三组回归测试已通过。现车验证了主页长时间停留后的识别和小组件跳转；实测首次开机中“连接后约 4 秒退回入口”的时序已加入回归。最终版本的完整首次冷开机流程尚未再次实测。导航原有的 5 秒超时行为保留。

## 使用

1. 在车辆停稳时安装 APK，授权小组件。
2. 在 DiPlay 中连接 CarPlay，播放音乐并设置导航。
3. 打开“CarPlay仪表桥接”，确认状态中出现真实歌曲、道路、距离和动作。
4. 勾选导航和/或音乐转发，并在仪表上核对显示。
5. 使用原车百度/高德/QQ 音乐前，关闭本应用对应转发，避免两个来源互相覆盖。

首次安装的转发开关默认关闭；覆盖更新保留已经选择的导航和音乐开关。打开应用会启动前台读取服务，退回 DiPlay 后仍可读取。

0.1.2 默认开启开机自启动：系统完成启动后，在后台启动桥接前台服务，不弹出界面；覆盖更新本应用也会恢复读取服务。开机早期小组件暂不可用时，每 10 秒重试绑定。CarPlay 连接后才会转发有效内容。界面中的“车机开机后自动启动桥接”可关闭此行为。

停止按钮会关闭转发并停止当前服务，正常停止时会清除本应用开启的导航及音乐文字；它不改变开机自启动开关，下次开机可能启动读取，但不会自动重新勾选已关闭的导航或音乐转发。系统强杀、断电时无法保证执行清理；导航源本身不更新时由 5 秒超时处理。只有已启动的桥接进程能执行该超时。

开机自启动依赖系统发送 `BOOT_COMPLETED`，车机仅休眠再唤醒时不一定发送该事件。系统设置里的“强行停止”会阻止系统广播拉起应用，需手动打开一次恢复。车型定制的进程清理策略及休眠恢复需要另行实测；本应用没有改动车机电源策略。

ADB 安装和绑定：

```sh
adb connect <车机IP>:5555
adb install -r DiPlayClusterBridge-0.1.7.apk
adb shell appwidget grantbind --package com.local.diplay.clusterbridge --user 0
adb shell pm grant com.local.diplay.clusterbridge android.permission.READ_LOGS
adb shell am force-stop com.local.diplay.clusterbridge
adb shell am start -n com.local.diplay.clusterbridge/.MainActivity
```

Android 8.1 上首次授予日志权限后，需要按以上步骤重新启动桥接进程，使它获得 `log` 权限组。只重启读取服务可能不够。后续覆盖更新会保留授权；该流程不会重启或修改 DiPlay。

退出和卸载：先打开应用，点击“停止并关闭转发”，再卸载。

```sh
adb shell appwidget revokebind --package com.local.diplay.clusterbridge --user 0
adb uninstall com.local.diplay.clusterbridge
```

## 显示限制

- 距离是小组件已经取整的显示值，不能恢复原始精确距离。
- 环岛显示通用环岛箭头，不能恢复第几出口或车道信息。
- 小组件歌曲是已合并的文字，作为标题转发；艺术家不单独拆分。仪表程序可能截短到 19 个字符。
- 不转发缺失的经纬度、剩余总路程、到达时间、歌曲进度/时长；仪表接口对缺失字段的默认处理由原程序决定。
- 音乐显示不等于接管声音输出；本应用不请求音频焦点，也不改变音量、音源或按键控制。
- 音乐更新依赖 DiPlay 的歌曲/连接状态回调，暂无独立的进程死亡检测。异常退出而没有断开回调时，歌曲可能暂时保留。
- 两个应用同时向仪表发送信息可能互相覆盖。仪表没有提供可验证的来源仲裁接口，需关闭不使用的转发来源。
- 本次测试依据当前安装的 DiPlay 0.2.19。源码主分支仅用于理解公开小组件接口，实际解析取自设备当前资源。
- 日志回退依赖 DiPlay 现有诊断标签和格式，目前验证的是有线 CarPlay。未来 DiPlay 若关闭或改变这些日志，正常小组件路名仍可用，日志回退则可能需要适配。
- 如果启动桥接时，系统日志已清掉该转向的说明，必须等待新的转向说明，或在停稳时重新开始导航；程序不会复用没有匹配依据的旧路名。
- 日志回退输出的是完整导航说明，仪表自身仍会截短较长的文字；这不是恢复百度地图的全部道路/车道信息。

## 源码构建

需要 JDK 17 或更新版本、Google Android SDK 平台 35 和构建工具 36.0.0。应用仍编译为 Java 8 字节码，最低 Android 8.0、目标 Android 9。

```sh
# 不依赖 Android SDK 的协议和导航日志测试
./test.sh

# 先安装对应的 Android SDK 包，再设置本机路径及自己的签名配置
export ANDROID_SDK_ROOT=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk
export BRIDGE_SIGNING_KEY=/private/path/to/signing.jks
export BRIDGE_KEY_ALIAS=bridge
export BRIDGE_STORE_PASSWORD='your-store-password'
export BRIDGE_KEY_PASSWORD='your-key-password'
./build.sh
```

编译输出为 `build/DiPlayClusterBridge.apk`。构建不会自动创建签名密钥；签名私钥不在仓库中。使用其他密钥构建的 APK 无法直接覆盖本仓库发布的 APK。维护者后续发布会继续使用原签名，保留覆盖更新能力。

## 下载与发布验证

[v0.1.7 发布页](https://github.com/zwzhen-whu/sync-diplay-bridge/releases/tag/v0.1.7)提供 [APK 安装包](https://github.com/zwzhen-whu/sync-diplay-bridge/releases/download/v0.1.7/DiPlayClusterBridge-0.1.7.apk)和 `SHA256SUMS`。发布文件与原测试车机安装的 0.1.7 APK 完全一致，沿用 0.1.5 的签名，可覆盖更新。历史版本：[v0.1.5](https://github.com/zwzhen-whu/sync-diplay-bridge/releases/tag/v0.1.5)。

```sh
# 在下载 APK 和 SHA256SUMS 的目录中核对文件
shasum -a 256 -c SHA256SUMS
```

仓库中的 `release/<版本>/` 保存对应已签名 APK、校验文件和发布说明。推送与该标签下 manifest 版本一致的 `v*` 标签后，GitHub Actions 运行三组纯 Java 测试，校验对应 APK 的 SHA-256，再发布到 GitHub Releases。发布流程不在服务器上重新签名，签名私钥保留在维护者本地。源代码压缩包由 GitHub 自动提供。

0.1.7 已通过构建、协议/导航/启动行为回归和签名检查。约 94 秒实车观察中，两首不同歌曲各推送一次，没有重复歌曲状态或空标题回填；后台更新后约 5.2 秒自动进入 CarPlay。首次冷开机退回入口的真实时序已纳入回归，最终版本完整首次冷开机及其他车型、系统版本、休眠恢复仍需实测。

## 本次确认的接收协议

目标包：`com.baidu.xiaoduos.instrumentcluster`。

- `com.baidu.car.navi.STATE`：字符串 `state=TRUE/FALSE`。
- `com.baidu.car.navi.INFO`：字符串 `name` / `nextRoadName`，整型 `distance`，单位米。
- `com.baidu.car.radio.notify.PLAY_INFO`：`title` / `artist` / `album_name` / `source_name`，整型 `source_type=1`，布尔 `is_playing`。

导航转向令牌采用该车已安装程序实际支持的白名单。仪表程序对 `org.savior.sync` 已安装包进行签名检查；本应用没有伪装该包、绕过签名或修改接收程序。

公开接口参考：[DiPlay 小组件](https://github.com/shihabal3amri/DiPlay/blob/main/common/src/main/java/com/shilapi/xcertplay/NavigationWidget.kt)、[DiPlay Launcher Integration](https://github.com/shihabal3amri/DiPlay/blob/main/docs/LAUNCHER_INTEGRATION.md)、[Android AppWidgetHost](https://developer.android.com/develop/ui/views/appwidgets/host)。

开机广播和前台服务参考：[Android 广播机制](https://developer.android.com/develop/background-work/background-tasks/broadcasts)、[Android 8.0 后台执行限制](https://developer.android.com/about/versions/oreo/background)。

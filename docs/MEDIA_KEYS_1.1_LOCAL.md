# 1.1 本地按键适配

2026-10-22。本版本仅本地开发和交付；未安装到车机，未提交或推送 GitHub。包名保持 `com.local.diplay.clusterbridge`，versionName 1.1，versionCode 19；继续使用原签名，可覆盖升级。

## 按键设置

1. 桥接中开启“接收 Key Mapper 的上一曲／下一曲（测试）”，保持桥接服务运行及日志读取授权。
2. Key Mapper 中将两个实体键的动作设为“控制指定应用媒体 → CarPlay仪表桥接 → 上一曲／下一曲”，替换原来直接控制 DiPlay 的动作。不要同时保留两组动作。
3. DiPlay 0.2.19 需要开启 `CarPlay call keys` / CarPlay 通话按键（实验性）；原项目设置位于 BYD 导航分类。SYNC+ 上该设置是否可见、是否已开启尚未核对；找不到时，先核对 DiPlay 设置，不视为桥接安装失败。桥接只读取该开关，不替其他应用修改设置。

| iPhone 通话状态 | 上一曲 | 下一曲 |
| --- | --- | --- |
| 无通话 | 上一曲 | 下一曲 |
| 来电响铃 | 拒接 | 接听 |
| 拨号、连接中、通话、保持 | 挂断 | 忽略 |
| 状态或连接无法确认 | 不执行 | 不执行 |

不需要把 Key Mapper 配成 Android 自带的接听／挂断动作；这些动作针对 Android 电话，不等于控制 CarPlay 中的 iPhone。

接听可能先打开 CarPlay 界面。蓝牙通话连接仍在或原车电话正在占用时，桥接暂不投递接听键；可结合已有“插入 iPhone 时同时关闭蓝牙通话连接”选项。该限制不影响仅向 DiPlay 投递的挂断广播。

## 已核对的 DiPlay 实现

对应源码 tag `v0.2.19` 与本地 `DiPlay-installed.apk`（com.shihab.diplay，0.2.19/code38）包含以下逻辑：

- `CarPlayCallKeys.onKey()` 在开关打开、存在会话和来电状态时，接收 CALL 键的释放事件，调用 `controller.answerCall()`。
- 挂断动态广播 `com.byd.btcall.action.CLOSE_BLUETOOTHSETTING`，整数 extra `keycode=314`，在开关打开且有会话和通话时调用 `controller.endCall()`；接收器要求发送方拥有 DUMP 权限。桥接通过本车已有 root 辅助组件定向发送给 DiPlay。
- `CarPlayController.answerCall()` → `sendTelephony(1)`；`endCall()` → `sendTelephony(3)`。实现检查活动会话，将工作排入发送队列，再由 AirPlaySession 发送电话 HID 报告及释放报告。
- 日志 `DiPlay-CallKeys ... sent=true` 表示请求成功排队，不代表电话已接通或挂断。桥接只在收到可信来源、同一进程、当前操作之后的对应回执时显示“DiPlay 已接受请求，请核对手机”。

源码：[按键处理](https://github.com/shihabal3amri/DiPlay/blob/v0.2.19/common/src/main/java/com/shilapi/xcertplay/CarPlayCallKeys.kt)、[控制器](https://github.com/shihabal3amri/DiPlay/blob/v0.2.19/shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt)、[AirPlay 电话报告](https://github.com/shihabal3amri/DiPlay/blob/v0.2.19/shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlaySession.kt)。本地反编译控制器存在其他方法的反编译错误；上述三个目标方法的输出清楚可读，并与源码相符。

## 执行条件和边界

- 默认关闭，无新增 Android 权限、无障碍服务或导出广播接收器。媒体会话不播放、不申请音频焦点；开启后能作为 Key Mapper 的指定应用目标。
- 仅适配当前 Android 8.0/8.1、本机已有 loopback ADB 与 `su 0` 环境。不会改变 ADB 认证或连接其他主机。
- 只接收上一曲/下一曲；按下处理一次，释放、长按重复和短时间重复回调不重复执行。请求最长有效 1.5 秒；连接、进程、通话或开关变化就取消，不把旧来电的按键用于下一通电话。
- 普通切歌只投递给包名和媒体会话标签均匹配的 DiPlay，会话不存在或不唯一时不回退到全局媒体键。先在 CarPlay 播放一次音乐可建立源媒体会话。
- 接听采用前台 CALL 键；挂断采用定向广播。系统固件是否拦截 CALL 键、辅助接口是否支持、电话 HID 是否被 iPhone 接受，都需要停车验证。
- 结果不明不会自动重发。通话身份仅以摘要参与请求核对，不把联系人姓名、号码写入状态、偏好或桥接日志。原有仪表通话显示开关独立。

## 本地验证

17 组离线回归通过：按键路由与状态校验 134 项；本机通信协议 30 项；原通话解析 44 项、道路显示 161 项、蓝牙管理 106 项。通信测试使用内存传输，没有执行真实接听、挂断或连接车机。

APK 已完成 Android 编译和原签名校验。实车尚待核对：正常切歌、来电接听、响铃拒接、接通后挂断、通话结束恢复切歌，以及关闭功能后恢复原有行为。应同时观察 iPhone 状态；单独看到“已接受请求”不能作为成功依据。

## 车机按键无响应修正

首次安装后用户反馈指定桥接的下一曲无反应。读取映射配置确认 289/288 已正确指向桥接下一曲/上一曲，服务和媒体会话均在运行。发现 Key Mapper 使用 `new KeyEvent(action, keyCode)`，媒体事件时间戳为 0；旧校验把它当成过期事件。现在对 0 采用接收时刻，继续过滤重复、释放和长按，非零过期时间戳仍拒绝。

首次开启尚无 iAP2 实时头时，普通切歌可使用未知 PID 的空闲请求；辅助组件只在匹配且唯一的 DiPlay 媒体会话正在播放、音频模式为 NORMAL、状态和开关未变化时发送，不能走电话路径。来电和通话仍必须有明确 PID 和通话状态，不从音乐播放推断接听/挂断。

修正版已原签名覆盖安装到 MEK-MX8Q，安装文件哈希与构建一致。桥接记录“已向 DiPlay 发送下一曲”，用户确认已正常切歌；接听、拒接、挂断仍未实测。

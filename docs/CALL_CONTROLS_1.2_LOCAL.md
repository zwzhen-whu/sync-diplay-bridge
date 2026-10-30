# 1.2 本地通话控制修正

日期：2026-10-23。包名 `com.local.diplay.clusterbridge`，versionName 1.2，versionCode 20，沿用原签名。只在本地开发、编译和交付；本轮未连接或安装到车机，未推送 GitHub。

## 当前按键行为

Key Mapper 中仍把两个实体键映射为“控制指定应用媒体 → CarPlay仪表桥接 → 上一曲／下一曲”，不需要交换 Key Mapper 动作。

| DiPlay 中的 iPhone 状态 | 上一曲 | 下一曲 |
| --- | --- | --- |
| 无通话 | 上一曲 | 下一曲 |
| 来电响铃 | 接听 | 拒接 |
| 拨号、连接中、通话、保持 | 忽略 | 挂断 |
| 通话记录正在更新或无法确认 | 不执行 | 不执行 |

继续要求桥接按键入口和 DiPlay 的 CarPlay 通话按键开关开启。新装时桥接入口默认关闭，覆盖安装保留用户设置。

## 接听路径修正

旧接听路径先检查原车 HFP、Android Telecom 和音频模式，再发送通用 CALL(5)。这些条件不能用来判断 CarPlay 中 iPhone 是否来电，额外的原车电话接口查询也可能失败。本轮未获得实车日志，不能将其中某一项认定为此次故障的唯一原因。

现在按 DiPlay 的实时通话记录判断，来电的上一曲才进入接听路径。先打开 CarPlay，再核对实际输入焦点确属 `CarPlayHostActivity`，最后发送 DiPlay 明确识别的 313 键的按下与释放。不会回退到 Android CALL(5)、蓝牙拨号器或全局切歌。

发送前再次检查同一个 DiPlay 进程、同一通话身份、相同阶段、用户开关和 1.5 秒请求期限。通话帧还没收齐时暂不执行，避免这段窗口把接听按键误判为切歌。原有定向挂断广播路径保留。

回执校验增加键码匹配：只有本次操作之后、同一 DiPlay 进程、313 接听或 314 挂断的对应回执才采用。`sent=true` 只代表 DiPlay 接受请求并排队，不能据此声称 iPhone 已接通。界面继续明确提示核对手机，并显示 DiPlay 当前电话状态。

源码核对：[DiPlay 按键规则](https://github.com/shihabal3amri/DiPlay/blob/v0.2.19/shared/src/main/java/com/shilapi/xcertplay/hud/CarPlayCallKeyPolicy.kt)、[DiPlay 接听与挂断处理](https://github.com/shihabal3amri/DiPlay/blob/v0.2.19/common/src/main/java/com/shilapi/xcertplay/CarPlayCallKeys.kt)。本地留存的 0.2.19 APK 包含相同规则。

## 仪表菜单确定操作

本地留存的原厂仪表程序中，`VendorInsClusterController.onChangeEvent()` 处理两项电话菜单事件：

- 属性 560991260：`SIGNAL_BT_INCOMMING_CALL_RQ`，值 1 交给原车拨号器接听，值 2 拒接；日志为 `InsClusterController: hqj incommingByte is 1/2`。
- 属性 560991258：`SIGNAL_BT_END_TEL_SERVICE_RQ`，非零结束电话；明确操作日志为 `hqj endByte is = ...`。

本地原厂拨号器实现也核对到 `operateIncomeCall(1)` 调用 answerCall，另一分支调用 rejectCall。原先桥接只向仪表写电话显示属性，没有转发这些请求；关闭 HFP 后，原车拨号器无法通过蓝牙控制 iPhone。

新增适配只读取原厂仪表 UID、精确日志标签和精确操作消息，拒绝旧日志、未来时间、重复动作和不完整消息。必须同时满足：桥接按键入口开启、仪表通话显示开启、当前 DiPlay 通话有效、原车 HFP 未接管显示。原车 HFP 查询只决定菜单显示所有权，**不参与判断 iPhone 的通话状态，也不阻挡 Key Mapper 的 DiPlay 接听／挂断**。

来电菜单操作要求仍在同一来电阶段，事件不能早于当前通话记录；不会把上一通来电的拒接操作转成下一通电话的挂断。操作放到独立工作线程，日志读取线程继续接收 DiPlay 状态和发送回执。辅助组件的异步菜单结果不直接写入命令回复通道，避免与 Key Mapper 按键串线；桥接定期读取限定结果并显示“仪表电话菜单”。

这属于根据原车程序实现的本地适配。实车是否保留这些日志、确定键是否发出对应事件、313 键是否能通过该固件，都仍待验证。不能把本地解析测试作为仪表菜单已经可用的证据。

## 验证与交付

18 组离线回归通过，覆盖按键路由、DiPlay 状态切换、无时间戳媒体事件、旧请求和重复按键、原车焦点不匹配、严格回执、仪表菜单来源与时间校验，以及原有导航、蓝牙和通话显示回归。Android APK 构建与原签名校验另见交付目录 `validation.json`。

用户已反馈旧 1.1 修正版正常切歌，且开启 DiPlay 通话按键后挂断可用、接听无效，仪表能显示来电并出现可选择菜单。该反馈只作为旧版本行为证据。1.2 的上一曲接听、下一曲拒接／挂断和仪表菜单控制均需后续停车测试，本轮未做任何真实电话操作。

## 安装记录

2026-10-23 按用户要求，以原签名从车机 1.1/code19 覆盖安装至 1.2/code20，安装返回 Success。原有转发配置、READ_LOGS 和 DUMP 保留，按键入口与通话显示均保持开启，界面启动成功。安装本身不包含真实接听、拒接、挂断或仪表菜单测试。

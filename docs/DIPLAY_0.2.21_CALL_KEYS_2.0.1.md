# 2.0.1：DiPlay 0.2.21 通话按键兼容

2026-10-24。桥接版本 2.0.1，versionCode 25，包名及签名沿用原版本。

用户升级 DiPlay 到 0.2.21 后，上一曲、下一曲仍能控制音乐，但接听及挂断失效。实车确认 DiPlay 的通话按键开关为 true、方向盘服务仍启用，桥接的按键入口和电话显示也仍开启。现装桥接 2.0 的 APK 哈希与本地源码构建产物一致；其中 `KeyControlTool.callControlsEnabled()` 仅允许版本字符串 `0.2.19`，0.2.21 因此在发送任何通话命令前被拒绝。音乐路径不经过此条件，符合用户反馈。

核对官方 v0.2.19 与 v0.2.21 标签：`CarPlayCallKeys.kt`、`CarPlayCallKeyPolicy.kt`、`BydOutputSettings.kt`、`BydNavigationOutputs.kt` 四个文件逐字节相同；控制器的 answerCall 和 endCall 仍分别发送 Telephony Hook Switch 与 Drop。接听 313、挂断广播动作、额外参数及日志回执格式均保留。比对文件及哈希存于本地 `inspection/2026-10-24/v21-call-keys/source-comparison.json`。

本次将通话控制允许版本精确扩展为 0.2.19、0.2.21。未核对版本仍拦截，并明确提示当前 DiPlay 版本需要桥接适配，不再误报为开关关闭。保留 DiPlay 通话开关、实时电话状态、输入焦点、调用者进程、通话身份、按键时效、单次投递及精确回执检查。上一曲接听、下一曲拒接／挂断的用户习惯继续沿用；无通话时保持原方向切歌。没有回执不自动重发。

新增回归覆盖两个允许版本的接听和挂断、两版本开关关闭时禁止执行、未知或相似版本不能执行通话操作，以及版本检查不影响无通话切歌。真实来电接通、拒接、挂断仍须在更新后逐项验证；源码一致和本地测试通过不能代替手机实际响应。

官方参考：[0.2.21 通话按键入口](https://github.com/shihabal3amri/DiPlay/blob/v0.2.21/common/src/main/java/com/shilapi/xcertplay/CarPlayCallKeys.kt)、[0.2.21 通话按键策略](https://github.com/shihabal3amri/DiPlay/blob/v0.2.21/shared/src/main/java/com/shilapi/xcertplay/hud/CarPlayCallKeyPolicy.kt)。

## 本次验证

19 组离线回归通过，其中媒体及通话按键 204 项检查通过；原签名校验一致。已从桥接 2.0 原签名覆盖安装到 2.0.1，核对通话、按键、蓝牙和自启设置及无障碍服务保留。车机只读执行 APK 的实际版本检查方法，更新前 false、更新后 true，DiPlay 保持 0.2.21。随后用户确认上一曲接听、下一曲挂断均恢复；本轮没有单独确认拒接及仪表菜单入口。

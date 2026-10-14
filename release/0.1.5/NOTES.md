CarPlay 仪表桥接 0.1.5，包名 `com.local.diplay.clusterbridge`，versionCode 6。

在现有 Sync+ 车机仪表协议上转发 DiPlay 的 CarPlay 导航和歌曲信息，独立安装，不修改 DiPlay。

- 深色界面，道路、距离、方向及歌曲显示在状态顶部。
- 修复百度地图缺少专用路名字段时仪表显示 `null`：优先使用小组件路名，必要时回退到来源、方向和距离均匹配的真实导航说明。
- 缓存解析后的转向说明；仍需新导航状态匹配后才可使用。
- 保留开机自启动，以及覆盖更新时的导航和音乐开关。
- 保留导航小组件超过 5 秒不更新时停止转发的原行为。

安装 `DiPlayClusterBridge-0.1.5.apk`。最低 Android 8.0；需要已安装 DiPlay 和与接收协议兼容的仪表/车机助手。其他车型兼容性尚未验证。

首次安装后，在车辆停稳时通过 ADB 授权：

```sh
adb shell appwidget grantbind --package com.local.diplay.clusterbridge --user 0
adb shell pm grant com.local.diplay.clusterbridge android.permission.READ_LOGS
adb shell am force-stop com.local.diplay.clusterbridge
adb shell am start -n com.local.diplay.clusterbridge/.MainActivity
```

首次安装的导航、音乐转发默认关闭，需在界面中开启；自启动默认开启。此前安装的同签名版本可覆盖更新，保留设置和授权。使用原车导航或音乐前关闭对应转发，避免信息覆盖。

已通过本地编译、协议与导航日志测试、APK 签名校验；此前用户确认实车歌曲和导航显示正常。此次发布采用同一已验证的 APK，未重新连接车机。文件校验见附件 `SHA256SUMS`。

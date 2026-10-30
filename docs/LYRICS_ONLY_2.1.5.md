# 音乐文字只显示歌词 2.1.5

新增“转发设置 → 只显示歌词（用于歌词模式）”，默认关闭。音乐 App 将歌词回传为“歌词 — 歌名”时，开启后仪表音乐文字只保留歌词。关闭后立即恢复完整文字。没有可识别分隔格式时保留原文，不输出空白来替代有效内容。

此选项处理当前音乐 App 的歌词模式输出，不自动判断是否处于歌词模式。正常歌曲信息可能同样包含分隔符，未使用歌词模式时应关闭该选项。桥接不会改变 iPhone、CarPlay 或 DiPlay 原有显示，也不改播放和暂停状态。

## 实现

原始小组件歌曲继续按 `♪` / `❚❚` 解析，保留在 `song`。转发前由 `MusicTextPolicy` 生成显示副本，音乐去重使用显示结果与播放状态。相同歌词不重复提交，新一句或播放状态变化时正常提交；切换开关时无需等待切歌即可更新。

优先处理 DiPlay 实际使用的带空格长破折号 ` — `，兼容 ` – ` 和用户描述的 ` - `；使用最后一个相同分隔符。普通单词内的连字符以及官方分隔符之前的短横线不作为裁剪点。小组件只提供合并文字，若歌词或歌名本身也使用完全相同的分隔格式，无法仅凭该合并文字保证准确区分。

状态页保留源歌曲，并增加“仪表音乐文字”和“音乐文字模式”，便于核对实际提交的显示内容。未增加权限、进程注入、音频处理或定时任务。第二页定位、二级页面隐藏和屏幕尺寸适配沿用已验收代码。

## 验证

2026-10-26：29 组本地回归和完整 Android 编译通过，新增音乐文字检查 26 项，覆盖开关、分隔符、歌词内连字符、空白、播放状态、源内容不变、去重、切换与重连。APK 使用原证书签名，地图源文件及五个运行/许可资产与 2.1.4 一致。

2.1.5/code31 已覆盖安装到本车，安装包回读 SHA-256 与交付文件一致；用户测试后回复“我试了好用”。前一版的两个浮窗修复已分别得到“两项都正常”的实车反馈。其他车型、音乐 App 及不同歌词回传格式未据此宣称验证通过。

实车记录：`inspection/2026-10-26/lyrics-only/vehicle-install/validation.json`。安装包：`outputs/DiPlayClusterBridge-2.1.5-local/`。

## 对应源代码

- [DiPlay v0.2.21 BydClusterSong](https://github.com/shihabal3amri/DiPlay/blob/v0.2.21/shared/src/main/java/com/shilapi/xcertplay/hud/BydClusterSong.kt)：将标题与艺术家合并为 `Title — Artist`，歌词模式由音乐 App 提供相应字段。
- [CarPlayGlance](https://github.com/shihabal3amri/DiPlay/blob/v0.2.21/shared/src/main/java/com/shilapi/xcertplay/glance/CarPlayGlance.kt)、[NavigationWidget](https://github.com/shihabal3amri/DiPlay/blob/v0.2.21/common/src/main/java/com/shilapi/xcertplay/NavigationWidget.kt)：将合并音乐文字提供给小组件，并添加播放状态前缀。

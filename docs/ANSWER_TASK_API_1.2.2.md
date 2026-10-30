# 1.2.2 修正 Android 8.1 前台任务接口

2026-10-23。versionName 1.2.2，versionCode 22，包名和原签名保留。

用户实测 1.2 仪表拒接有效、两个接听入口无效；1.2.1 改用直接输入后，实测仍不能接听，桥接新增提示明确为“本机按键控制接口报错”。该次记录没有 DiPlay 接听回执。

随后在车机只读列出 android.app.IActivityManager.getTasks 的方法参数，实际为 [int, int]；以 (1, 0) 调用成功，返回一条任务。旧代码反射查找 getTasks(int)，该方法不存在。前台检查在进入实际输入方法前就会抛出 NoSuchMethodException。此前发现输入进程启动耗时可能超预算是另一项风险，不是这次已经确认的阻塞点。

本次改为 Android 8.0/8.1 的双参数调用，封装在 DiPlayCallControl.runningTasks()。新增 Android81TaskApi 双参数接口约定回归，验证 maxNum=1、flags=0，并由 KeyControlTool 实际使用同一适配方法。原先来电、通话身份、输入焦点和发送回执检查全部保留。

参考 AOSP Android 8.1.0_r81 IActivityManager.aidl：getTasks(int maxNum, int flags)。只读实车探针不发起电话、接听、挂断或按键。

沿用：上一曲接听，下一曲拒接／挂断；无通话正常切歌；仪表菜单和 Key Mapper 共用接听路径。保留常驻辅助组件直接输入 313 键的实现，避免临时输入进程启动开销。没有回执不会自动重发。

本地回归、构建和签名结果见 validation.json。是否实际接通仍以更新后的用户电话测试为准，不能把前台查询成功当成电话已接通。

## 实车结果

已原签名覆盖安装到 MEK-MX8Q。用户分别确认上一曲接听、仪表选择接听后确定均正常接通。两次均采集到 DiPlay 自身的 313 接听 sent=true 回执，桥接状态均由来电中转为通话中，随后结束回到无通话。此次明确验证两个接听入口；拒接和挂断沿用先前已可用的路径，未在本轮单独重新验证。18 组离线回归通过，其中媒体按键 149 项、仪表菜单 32 项，原签名校验通过。未上传 GitHub。

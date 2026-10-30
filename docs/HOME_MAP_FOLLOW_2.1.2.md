# 桌面地图跟随 2.1.2（本地候选）

原来的地图是独立窗口，原车桌面的卡片滚动不会改变其位置。本版增加“跟随桌面滑动”开关：开启后按原车桌面的实际横向滚动量移动地图，完全滑出屏幕后隐藏，滑回来恢复。关闭后立即恢复保存的固定位置和原有拖动、点击操作。

## 使用与回退

- 默认关闭；旧用户升级不会自动改变地图操作方式。
- 在“桌面地图浮窗与外观设置”打开“跟随桌面滑动”。首次使用还需在系统无障碍设置中开启“桌面地图跟随”。页面提供入口及授权、等待桌面位置、就绪等状态。
- 跟随服务收到授权且开关打开后，地图区域触摸穿透给原车桌面，便于从地图区域滑动桌面。调整浮窗位置时先关闭跟随。宽高和渐变仍可随时调节。
- 临时滚动量只保存在内存，不写入位置偏好；保存的位置始终是桌面原点处的基准位置。页面滑走、返回、改尺寸和反复开关均不累计位置误差。
- 未授权、服务断开或初始位置不可读时保留固定位置。服务已连接但等待首个位置时允许触摸穿透，以便桌面收到第一次滑动。
- 关闭开关后停止接收桌面事件，保留系统授权，之后可直接重新打开。程序不改写系统无障碍服务名单，也不影响其他已启用服务。

## 实现与开销

- 仅观察 `com.baidu.xiaoduos.launcher` 中的 `android.widget.HorizontalScrollView`、资源 ID `scrollLauncher`；不接受嵌套卡片列表、其他应用或无来源的滚动事件。
- 使用系统滚动事件，不读取屏幕图像，不注入桌面进程，不增加视频解码器，不发送手机端 UI 控制命令。地图源仍为 1280×480。
- 地图横坐标为 `保存的 x − 桌面 scrollX`，允许负坐标，避免地图到达屏幕边缘后停住。
- 多个事件合并为一个显示帧回调，取最新位置；没有滑动时没有新增的循环动画或轮询任务。地图关闭时移除待执行回调。
- 初次接入、桌面窗口重建和返回桌面时尝试读取一次位置。内容事件触发的节点查询间隔至少 250 ms；已有位置且窗口未改变时不重复查询整棵树。
- Android 8.1 初始化使用 `boundsInParent` 的 drawing rectangle，而非会被裁剪的 `boundsInScreen`；此路径限 API 27，其他系统等待明确的滚动事件。
- 滑出屏幕仅改变现有窗口坐标、透明度和触摸标志，不释放、增建或切换地图虚拟显示。原有 CarPlay 前台暂停策略和通话音量模块均保留。

新增工作主要是事件筛选、整数坐标计算和滑动时的窗口布局提交。预计开销较小，尚未进行实车 CPU/GPU 测量；不能用本地逻辑测试代替实车流畅度结论。

## 验证边界

用户仅授权本地修改和验证，本版不连接、操作或安装到车机。

本地检查覆盖来源与窗口身份、旧事件拒绝、已滑动桌面的初始位置、隐藏和返回、关闭恢复、缩放时基准坐标保留、触摸策略、帧回调合并及关闭清理；模拟 4000 次位置变化时虚拟显示创建一次，未重建、释放或重新绑定。原桥接、地图和通话音量回归随构建一起运行。

2026-10-26 本地结果：25 组检查通过，其中 `MapFollowTest` 78 项；完整 Android 编译、APK 对齐、v2/v3 签名和打包后的服务配置检查通过。与 2.1.1 安装包比较，五个既有运行组件及许可资产逐字节相同。APK 和校验值位于 `outputs/DiPlayClusterBridge-2.1.2-local/`，详细记录位于 `inspection/2026-10-26/desktop-map-follow/validation.json`。

Android 8.1 在 View 层会合并滚动通知。本版不额外延迟事件，无法取消系统自身的通知间隔。实际跟随延迟、OEM 节点行为、触摸穿透和页面布局效果仍待实车验证；本地环境没有 Android 模拟器，未做 Android UI 运行验证。

## 依据

- 本车已有桌面节点快照：`inspection/2026-10-24/home-map-overlay/before-ui.xml` 等，含 `scrollLauncher` 和 `linlayoutcontainer`。
- [Android AccessibilityEvent](https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent#TYPE_VIEW_SCROLLED)：滚动事件及横向位置。
- [Android 8.1 View 源码](https://android.googlesource.com/platform/frameworks/base/+/android-8.1.0_r1/core/java/android/view/View.java)：`onInitializeAccessibilityNodeInfoInternal`、`getDrawingRect`、`postSendViewScrolledAccessibilityEventCallback`。
- [Android 8.1 HorizontalScrollView 源码](https://android.googlesource.com/platform/frameworks/base/+/android-8.1.0_r1/core/java/android/widget/HorizontalScrollView.java)：`onInitializeAccessibilityEventInternal` 的 scrollX、maxScrollX。

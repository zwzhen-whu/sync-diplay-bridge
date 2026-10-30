package com.local.diplay.clusterbridge.homemap;

/** Accept only the OEM launcher's outer horizontal scroller, never a nested card list. */
final class LauncherScrollState {
    static final String PACKAGE = "com.baidu.xiaoduos.launcher";
    static final String VIEW_ID = PACKAGE + ":id/scrollLauncher";
    static final String CONTENT_ID = PACKAGE + ":id/linlayoutcontainer";
    static final String VIEW_CLASS = "android.widget.HorizontalScrollView";
    private static final int MAX_SCROLL = 1_000_000;

    static final class Snapshot {
        final int windowId, scrollX, maxScrollX;
        final long at;
        Snapshot(int windowId, int scrollX, int maxScrollX, long at) {
            this.windowId = windowId; this.scrollX = scrollX; this.maxScrollX = maxScrollX; this.at = at;
        }
    }
    private Snapshot latest;
    Snapshot latest() { return latest; }
    void clear() { latest = null; }

    static boolean isTarget(String packageName, String viewId, String className) {
        return PACKAGE.equals(packageName) && VIEW_ID.equals(viewId) && VIEW_CLASS.equals(className);
    }
    boolean accept(String packageName, String viewId, String className,
                   int windowId, int scrollX, int maxScrollX, long at) {
        if (!isTarget(packageName, viewId, className) || windowId < 0 || at < 0
                || maxScrollX < 0 || maxScrollX > MAX_SCROLL || scrollX < 0 || scrollX > maxScrollX
                || (latest != null && at < latest.at)) return false;
        boolean changed = latest == null || latest.windowId != windowId || latest.scrollX != scrollX
            || latest.maxScrollX != maxScrollX;
        latest = new Snapshot(windowId, scrollX, maxScrollX, at);
        return changed;
    }

    /** Android 8.1 View populates boundsInParent with its (scrolled) drawing rectangle. */
    boolean acceptDrawingBounds(String packageName, String viewId, String className, int windowId,
                                int drawingLeft, int viewportWidth, int contentWidth, long at) {
        if (viewportWidth <= 0 || contentWidth < viewportWidth) return false;
        return accept(packageName, viewId, className, windowId, drawingLeft,
            contentWidth - viewportWidth, at);
    }
}

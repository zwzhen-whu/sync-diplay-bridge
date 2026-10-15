package com.local.diplay.clusterbridge;

/** A preference is not proof that Android still owns the widget ID after an uninstall. */
public final class WidgetBindingIds {
    public static int reusable(int savedId, int[] allocatedIds) {
        if (savedId <= 0 || allocatedIds == null) return -1;
        for (int id : allocatedIds) if (id == savedId) return savedId;
        return -1;
    }
}

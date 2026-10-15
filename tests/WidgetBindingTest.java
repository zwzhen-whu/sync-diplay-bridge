package com.local.diplay.clusterbridge;

public final class WidgetBindingTest {
    private static void eq(int actual, int expected) {
        if (actual != expected) throw new AssertionError(actual + " != " + expected);
    }
    public static void main(String[] args) {
        // The observed failure: preference survives, Android's entire host is gone.
        eq(WidgetBindingIds.reusable(1, new int[0]), -1);
        eq(WidgetBindingIds.reusable(1, null), -1);
        // A bound or unbound ID still allocated to this host must be reused.
        eq(WidgetBindingIds.reusable(2, new int[]{2}), 2);
        eq(WidgetBindingIds.reusable(2, new int[]{1, 2, 3}), 2);
        // A stale preference cannot borrow an ID from another host.
        eq(WidgetBindingIds.reusable(4, new int[]{1, 2, 3}), -1);
        eq(WidgetBindingIds.reusable(-1, new int[]{1}), -1);
        eq(WidgetBindingIds.reusable(0, new int[]{0, 1}), -1);
        // After reallocation, another start must preserve the new ID.
        eq(WidgetBindingIds.reusable(2, new int[0]), -1);
        eq(WidgetBindingIds.reusable(3, new int[]{3}), 3);
        System.out.println("Widget binding tests passed (uninstall removes host, stale IDs, pending bind, stable reuse)");
    }
}

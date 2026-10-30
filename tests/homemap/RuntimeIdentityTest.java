package com.local.diplay.clusterbridge.homemap;

public final class RuntimeIdentityTest {
    public static void main(String[] args) {
        String pkg = "com.shihab.diplay";
        check(RuntimeIdentity.matchesCmdline(pkg + "\0", pkg));
        check(RuntimeIdentity.matchesCmdline(pkg + "\0\0\0\0", pkg));
        check(!RuntimeIdentity.matchesCmdline(pkg + ":service\0", pkg));
        check(!RuntimeIdentity.matchesCmdline(pkg + "\0foreign\0", pkg));
        check(!RuntimeIdentity.matchesCmdline("other\0", pkg));
        check(!RuntimeIdentity.matchesCmdline(pkg, pkg));
        System.out.println("RuntimeIdentityTest: 6 checks passed");
    }
    private static void check(boolean result) { if (!result) throw new AssertionError("Process identity mismatch"); }
}

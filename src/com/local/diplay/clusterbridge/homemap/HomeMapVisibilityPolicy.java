package com.local.diplay.clusterbridge.homemap;

/** A launcher package can contain service lists and settings as well as the real home page. */
final class HomeMapVisibilityPolicy {
    static boolean isDesktop(String packageName, String className, String homePackage, String homeClass) {
        return homePackage != null && homePackage.equals(packageName) && className != null && homeClass != null
            && expand(homePackage, homeClass).equals(expand(packageName, className));
    }
    private static String expand(String pkg, String cls) { return cls.startsWith(".") ? pkg + cls : cls; }
}

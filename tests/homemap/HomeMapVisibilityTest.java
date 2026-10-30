package com.local.diplay.clusterbridge.homemap;

public final class HomeMapVisibilityTest {
    private static int checks;
    private static final String PKG="com.baidu.xiaoduos.launcher", HOME=PKG+".ui.LauncherActivity";
    public static void main(String[] args){
        check(show(PKG,HOME),"real home visible");check(show(PKG,".ui.LauncherActivity"),"relative activity normalized");
        check(HomeMapVisibilityPolicy.isDesktop(PKG,HOME,PKG,".ui.LauncherActivity"),"relative resolved home normalized");
        check(!show(PKG,PKG+".ui.MoreServerActivity"),"more services hidden despite same package");
        check(!show(PKG,PKG+".ui.AddCardActivity"),"add-card secondary menu hidden");
        check(!show(PKG,PKG+".ui.AARActivity"),"other launcher activity hidden");
        check(!show("com.shihab.diplay","com.shilapi.xcertplay.CarPlayHostActivity"),"CarPlay hides map");
        check(!show("other",HOME),"class cannot impersonate launcher package");
        check(!show(PKG,null),"missing class cannot count as desktop");check(!show(PKG,""),"empty class hidden");
        check(!HomeMapVisibilityPolicy.isDesktop(PKG,HOME,null,HOME),"unresolved home hidden");
        MapDisplayLease lease=new MapDisplayLease();Fake display=new Fake();
        String[] route={HOME,PKG+".ui.MoreServerActivity",PKG+".ui.AddCardActivity",PKG+".ui.MoreServerActivity",HOME};
        for(String cls:route){boolean home=show(PKG,cls);lease.update(true,home,display);check(display.rendering==home,"home/menu output follows exact activity");}
        check(display.created==1&&display.released==0,"menu changes retain virtual display identity");
        check(display.changes==2,"secondary menu transitions do not repeatedly rebind output");
        System.out.println("HomeMapVisibilityTest: "+checks+" checks passed");
    }
    private static boolean show(String pkg,String cls){return HomeMapVisibilityPolicy.isDesktop(pkg,cls,PKG,HOME);}
    private static void check(boolean okay,String text){checks++;if(!okay)throw new AssertionError(text);}
    private static final class Fake implements MapDisplayLease.Port{
        int created,released,changes;boolean rendering;
        public boolean create(boolean v){created++;rendering=v;return true;}
        public void render(boolean v){changes++;rendering=v;}
        public void release(){released++;}
    }
}

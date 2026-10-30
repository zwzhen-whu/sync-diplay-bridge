package com.local.diplay.clusterbridge.homemap;

public final class MapPageAnchorTest {
    private static int checks;
    public static void main(String[] args) {
        for (int page : new int[]{555,1920,2400}) {
            MapFollowAnchor anchor = new MapFollowAnchor(-1);
            MapFollowPosition pos = new MapFollowPosition(MapViewport.screen(1920,720)); pos.geometry(44,142,650,400);
            check(!anchor.ready() && !anchor.capture(null), "wait for a real desktop sample");
            check(anchor.capture(snapshot(page)), "capture the selected page");
            equal(page,anchor.origin(),"origin is current page offset, not zero");
            pos.scrollOrigin(anchor.origin()); pos.follow(true,snapshot(page));
            equal(44,pos.x(),"enable on selected page does not move map");
            check(!anchor.capture(snapshot(page+200)),"scrolling must not recapture origin");
            pos.follow(true,snapshot(page+200)); equal(-156,pos.x(),"relative left movement");
            pos.follow(true,snapshot(Math.max(0,page-200))); equal(244,pos.x(),"relative right movement");
            pos.follow(true,snapshot(page)); equal(44,pos.x(),"return to selected page");
            pos.follow(false,snapshot(0)); equal(44,pos.x(),"disable restores fixed position");
            MapFollowAnchor restored = new MapFollowAnchor(anchor.origin());
            check(restored.ready() && !restored.capture(snapshot(0)),"process restart retains selected page");
            pos.scrollOrigin(restored.origin());pos.follow(true,snapshot(page));equal(44,pos.x(),"restart stays on selected page");
            check(!restored.capture(null),"menus and transient observer loss do not reset origin");
            MapFollowAnchor reenabled = new MapFollowAnchor(-1);check(reenabled.capture(snapshot(page+700)),"re-enable chooses a new page");
            pos.scrollOrigin(reenabled.origin());pos.follow(true,snapshot(page+700));equal(44,pos.x(),"re-enable still does not jump");
            equal(44,pos.baseX(),"all page changes leave saved x intact");equal(142,pos.baseY(),"all page changes leave saved y intact");
        }
        MapFollowPosition pos = new MapFollowPosition(MapViewport.screen(1920,720));pos.geometry(44,142,650,400);pos.scrollOrigin(1920);
        pos.follow(true,snapshot(0));check(!pos.inViewport(),"first page no longer owns second-page map");
        pos.follow(true,snapshot(3840));check(!pos.inViewport(),"later page can hide map");
        pos.follow(true,snapshot(1920));check(pos.inViewport(),"selected page restores map");
        check(!new MapFollowAnchor(Integer.MAX_VALUE).ready(),"invalid stored anchor rejected");
        check(!new MapFollowAnchor(-200).ready(),"negative stored anchor rejected");
        System.out.println("MapPageAnchorTest: "+checks+" checks passed");
    }
    private static LauncherScrollState.Snapshot snapshot(int x){return new LauncherScrollState.Snapshot(1,x,10000,1);}
    private static void equal(int a,int b,String text){check(a==b,text+": expected "+a+", got "+b);}
    private static void check(boolean okay,String text){checks++;if(!okay)throw new AssertionError(text);}
}

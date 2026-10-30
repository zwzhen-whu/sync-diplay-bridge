package com.local.diplay.clusterbridge.audio;

public final class MediaVolumeBootTest {
    private static int checks;
    private static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
    public static void main(String[] args){
        MediaVolumeBootPolicy p=new MediaVolumeBootPolicy(0,10,9,1000);
        check(!p.pending(),"disabled feature never restores");
        p=new MediaVolumeBootPolicy(1,10,10,1000);check(!p.pending(),"service restart in same boot never restores twice");
        p=new MediaVolumeBootPolicy(2,-1,9,1000);check(!p.pending(),"unknown boot count cannot authorize a restore");
        p=new MediaVolumeBootPolicy(1,10,9,180001);check(!p.pending(),"opening app later does not unexpectedly change media volume");
        for(int mode:new int[]{1,2}){
            p=new MediaVolumeBootPolicy(mode,10,9,1000);check(p.pending(),"new boot waits for initialization");
            check(!p.ready(7,30,true,1000),"boot default cannot immediately replace remembered level");
            check(!p.ready(7,30,true,1199),"short stability check before touching startup audio");
            check(p.ready(7,30,true,1200),"stable audio restores within 200 ms instead of waiting eight seconds");
            p.finish();check(!p.ready(7,30,true,1300)&&!p.pending(),"one restore only");
        }
        p=new MediaVolumeBootPolicy(1,10,9,1000);p.ready(7,30,true,1000);
        check(!p.ready(5,30,true,1100),"late system initialization resets the stability timer");
        check(!p.ready(5,30,true,1299),"wait for new level to settle");
        check(p.ready(5,30,true,1300),"stable late initialization can finish");
        p=new MediaVolumeBootPolicy(1,10,9,1000);p.ready(7,30,true,1000);
        check(!p.ready(7,30,false,1100),"call or voice audio delays restoration");
        check(!p.ready(7,30,true,1299),"normal audio must remain stable after a call");
        check(p.ready(7,30,true,1300),"restore after normal audio stabilizes");
        p=new MediaVolumeBootPolicy(1,10,9,1000);
        check(!p.ready(0,0,true,9000),"uninitialized audio range cannot restore");
        check(!p.ready(7,30,true,180001)&&!p.pending(),"stop retrying after boot window expires");
        p=new MediaVolumeBootPolicy(1,10,9,1000);check(!p.ready(7,30,true,999)&&!p.pending(),"invalid monotonic time cancels restoration");
        check(MediaVolumeBootPolicy.scale(0,30,30)==0,"remember explicit mute");
        check(MediaVolumeBootPolicy.scale(7,30,30)==7,"same device keeps exact level");
        check(MediaVolumeBootPolicy.scale(15,30,60)==30,"changed range preserves relative media volume");
        check(MediaVolumeBootPolicy.scale(30,30,15)==15,"clamp to new device maximum");
        check(MediaVolumeBootPolicy.scale(60,30,30)==30,"out of range stored volume clamps");
        check(MediaVolumeBootPolicy.scale(-1,30,30)==-1&&MediaVolumeBootPolicy.scale(7,0,30)==-1,"missing memory does not invent a volume");
        System.out.println("MediaVolumeBootTest: "+checks+" checks passed (offline; OEM boot timing unverified)");
    }
}

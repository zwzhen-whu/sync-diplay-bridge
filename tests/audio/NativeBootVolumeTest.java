package com.local.diplay.clusterbridge.audio;

import java.io.IOException;
import static com.local.diplay.clusterbridge.audio.NativeBootVolumeTransaction.*;

/** Pure in-memory fault injection: no Android APIs, root, network or real settings. */
public final class NativeBootVolumeTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static final class PowerCut extends Error { }
    private static final class Fake implements Backend, Journal {
        Pair value; Record record; int writes, saves, failWrite=-1, crashWrite=-1, crashSave=-1, failSave=-1;
        boolean ignore, unavailable, failCommit, failAfterCommit, changeExternal, writeOutage, failClear;
        final StringBuilder events=new StringBuilder();
        Fake(Pair initial) { value=initial; }
        public Pair read() throws Exception { if(unavailable)throw new IOException();return value; }
        public void writeMin(String next) throws Exception { write(next,value.max,true); }
        public void writeMax(String next) throws Exception { write(value.min,next,false); }
        private void write(String min,String max,boolean first) throws Exception {
            writes++; events.append('W');
            check(record!=null&&record.pending(),"durable journal exists before every OEM write");
            if(writes==failWrite||writeOutage&&writes>=2)throw new IOException("injected provider failure");
            if(!ignore)value=new Pair(min,max);
            if(changeExternal&&first){changeExternal=false;value=new Pair(value.min,"29");}
            if(writes==crashWrite)throw new PowerCut();
        }
        public Record load(){return record;}
        public void save(Record next) throws Exception {
            saves++;events.append('J');
            if(saves==failSave)throw new IOException("journal unavailable");
            if(failCommit&&!next.pending()){failCommit=false;throw new IOException("journal commit failure");}
            record=next;
            if(saves==crashSave)throw new PowerCut();
            if(failAfterCommit&&!next.pending()){failAfterCommit=false;throw new IOException("acknowledgement lost");}
        }
        public void clear()throws Exception{
            if(failClear){failClear=false;throw new IOException("journal clear unavailable");}
            events.append('C');record=null;
        }
        NativeBootVolumeTransaction transaction(){return new NativeBootVolumeTransaction(this,this);}
    }
    private static void rejected(int target) throws Exception {
        Fake f=new Fake(new Pair("6","18"));boolean rejected=false;
        try{f.transaction().apply(target,10);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected&&f.writes==0&&f.record==null,"invalid target has no side effect");
    }
    public static void main(String[] args) throws Exception {
        Pair[] originals={new Pair(null,null),new Pair("6","18"),new Pair(null,"18"),new Pair("6",null)};
        for(Pair original:originals)for(int target=0;target<=30;target++){
            Fake f=new Fake(original);NativeBootVolumeTransaction t=f.transaction();
            Owner o=t.apply(target,10);
            check(o.original.equals(original)&&f.value.equals(Pair.fixed(target)),"fixed volume applied and read back");
            check(f.events.charAt(0)=='J',"ownership saved before changing any native key");
            int count=f.writes;Owner again=t.apply(target,11);
            check(f.writes==count&&again.boot==10,"same target is idempotent and preserves activation boot");
            t.apply((target+1)%31,11);
            check(t.inspect().original.equals(original),"changing target never overwrites original backup");
            t.restore();
            check(f.value.equals(original)&&f.record==null,"disable restores exact values including absent keys");
            count=f.writes;t.restore();check(f.writes==count,"restore is idempotent");
        }
        rejected(-1);rejected(31);rejected(Integer.MAX_VALUE);
        for(String bad:new String[]{"-1","31","1; reboot","", "null"}){
            boolean invalid=false;try{new Pair(bad,"18");}catch(IllegalArgumentException e){invalid=true;}
            check(invalid,"unknown original setting fails without writes");
        }
        Fake f=new Fake(new Pair("6","18"));
        boolean noBoot=false;try{f.transaction().apply(1,-1);}catch(IllegalArgumentException e){noBoot=true;}
        check(noBoot&&f.writes==0,"unknown boot does not mutate settings");
        for(int at=1;at<=2;at++){
            f=new Fake(new Pair("6","18"));f.failWrite=at;
            try{f.transaction().apply(1,10);throw new AssertionError("failure acknowledged as success");}
            catch(WriteFailed expected){check(f.value.equals(new Pair("6","18"))&&f.record==null,"partial write rolled back");}
        }
        f=new Fake(new Pair("6","18"));f.ignore=true;
        try{f.transaction().apply(1,10);throw new AssertionError("readback ignored");}
        catch(WriteFailed expected){check(f.record==null&&f.value.equals(new Pair("6","18")),"ignored writes never report success");}
        f=new Fake(new Pair("6","18"));f.failCommit=true;
        try{f.transaction().apply(1,10);throw new AssertionError("missing durable commit accepted");}
        catch(WriteFailed expected){check(f.value.equals(new Pair("6","18"))&&f.record==null,"journal commit failure undoes native changes");}
        f=new Fake(new Pair("6","18"));f.failSave=1;
        try{f.transaction().apply(1,10);throw new AssertionError();}catch(IOException expected){}
        check(f.writes==0&&f.value.equals(new Pair("6","18")),"backup failure prevents every OEM write");
        f=new Fake(new Pair("6","18"));f.writeOutage=true;
        try{f.transaction().apply(1,10);throw new AssertionError();}catch(Pending expected){}
        check(f.record.pending()&&"1".equals(f.value.min),"failed undo keeps durable recovery record");
        f.writeOutage=false;check(f.transaction().inspect()==null&&f.value.equals(new Pair("6","18")),"provider recovery completes deferred undo");
        f=new Fake(new Pair("6","18"));f.transaction().apply(1,10);f.failClear=true;
        try{f.transaction().restore();throw new AssertionError();}catch(WriteFailed expected){}
        check(f.transaction().inspect().target==1&&f.value.equals(Pair.fixed(1)),"failed ownership removal keeps original active configuration");
        f.transaction().restore();check(f.record==null&&f.value.equals(new Pair("6","18")),"ownership removal can be retried");
        f=new Fake(new Pair("6","18"));f.failAfterCommit=true;
        check(f.transaction().apply(1,10).target==1&&f.transaction().inspect().target==1,"lost journal acknowledgement reconciles committed success");
        for(int at=1;at<=2;at++){
            f=new Fake(new Pair("6","18"));f.crashWrite=at;
            try{f.transaction().apply(1,10);throw new AssertionError("power cut missing");}catch(PowerCut expected){}
            check(f.record!=null&&f.record.pending(),"power cut retains undo record");
            f.crashWrite=-1;check(f.transaction().inspect()==null&&f.value.equals(new Pair("6","18")),"fresh helper recovers interrupted enable");
        }
        f=new Fake(new Pair("6","18"));f.crashSave=1;
        try{f.transaction().apply(1,10);throw new AssertionError();}catch(PowerCut expected){}
        check(f.writes==0&&f.transaction().inspect()==null,"cut after journal creation leaves OEM values unchanged");
        for(int at=1;at<=2;at++){
            f=new Fake(new Pair(null,null));f.transaction().apply(1,10);f.crashWrite=f.writes+at;
            try{f.transaction().restore();throw new AssertionError();}catch(PowerCut expected){}
            f.crashWrite=-1;check(f.transaction().inspect().target==1&&f.value.equals(Pair.fixed(1)),"interrupted disable restores prior owned state");
            f.transaction().restore();check(f.record==null&&f.value.equals(new Pair(null,null)),"retry completes disable after interruption");
        }
        f=new Fake(new Pair("6","18"));f.transaction().apply(1,10);f.value=new Pair("4","18");
        int writes=f.writes;
        try{f.transaction().restore();throw new AssertionError("external changes overwritten");}
        catch(Conflict expected){check(f.writes==writes&&f.record!=null,"foreign change preserves backup and performs no writes");}
        f=new Fake(new Pair("6","18"));f.changeExternal=true;
        try{f.transaction().apply(1,10);throw new AssertionError();}
        catch(Pending expected){check("29".equals(f.value.max)&&f.record.pending(),"concurrent external update is never overwritten");}
        f=new Fake(new Pair("6","18"));f.crashWrite=1;
        try{f.transaction().apply(1,10);}catch(PowerCut expected){}
        f.unavailable=true;
        try{f.transaction().inspect();throw new AssertionError();}catch(IOException expected){}
        check(f.record.pending(),"provider outage never drops recovery state");
        f.unavailable=false;f.crashWrite=-1;check(f.transaction().inspect()==null,"recovery resumes after provider returns");
        check(skipLateRestore(2,true,1,1,30),"native owner suppresses late second restore");
        check(skipLateRestore(2,true,0,0,30),"native mute suppresses second restore");
        check(!skipLateRestore(1,true,1,1,30)&&!skipLateRestore(0,true,1,1,30),"remember and off never treated as native fixed");
        check(!skipLateRestore(2,false,1,1,30)&&!skipLateRestore(2,true,1,2,30)&&!skipLateRestore(2,true,1,1,15),"unconfirmed target or scale is not native success");
        System.out.println("NativeBootVolumeTest: "+checks+" checks passed (offline; OEM startup sound unverified)");
    }
}

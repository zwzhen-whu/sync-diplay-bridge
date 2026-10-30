package com.local.diplay.clusterbridge.audio;

import android.content.*;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.provider.Settings;

/** Event-driven STREAM_MUSIC memory. It never sets the call, ring, navigation or alarm streams. */
public final class MediaVolumeMemory implements AutoCloseable {
    public static final String MODE="media_volume_mode", FIXED="media_volume_fixed", FIXED_MAX="media_volume_fixed_max";
    public static final String SAVED="media_volume_saved", SAVED_MAX="media_volume_saved_max", BOOT="media_volume_restored_boot";
    private final Context context;
    private final SharedPreferences prefs;
    private final Handler handler;
    private final HandlerThread worker;
    private final AudioManager audio;
    private final int boot;
    private boolean registered;
    private volatile boolean closed;
    private long ignoreUntil;
    private long configuredAt;
    private MediaVolumeBootPolicy policy;
    private volatile String status="关闭";
    private final Runnable startup=this::startup;
    private final Runnable remember=this::remember;
    private final Runnable configureChanged=this::configure;
    private final BroadcastReceiver volume=new BroadcastReceiver(){public void onReceive(Context c,Intent i){
        if(i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE",-1)!=AudioManager.STREAM_MUSIC)return;
        if(policy!=null&&policy.pending())return;
        handler.removeCallbacks(remember);handler.postDelayed(remember,600);
    }};
    private final SharedPreferences.OnSharedPreferenceChangeListener changed=this::preferencesChanged;
    private void preferencesChanged(SharedPreferences p,String key){
        if(!closed&&(MODE.equals(key)||FIXED.equals(key)||FIXED_MAX.equals(key)||BOOT.equals(key)
                ||NativeBootVolumeController.OWNED.equals(key)||NativeBootVolumeController.LEVEL.equals(key)
                ||NativeBootVolumeController.PENDING.equals(key))){
            handler.removeCallbacks(configureChanged);handler.post(configureChanged);
        }
    }
    public MediaVolumeMemory(Context context,SharedPreferences prefs){
        this.context=context.getApplicationContext();this.prefs=prefs;
        worker=new HandlerThread("BridgeBootVolume");worker.start();handler=new Handler(worker.getLooper());
        audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        int count;
        try{count=Settings.Global.getInt(context.getContentResolver(),"boot_count",-1);}catch(RuntimeException e){count=-1;}
        boot=count;
        prefs.registerOnSharedPreferenceChangeListener(changed);handler.post(configureChanged);
    }
    private void configure(){
        if(closed)return;
        handler.removeCallbacks(startup);handler.removeCallbacks(remember);
        int mode=prefs.getInt(MODE,MediaVolumeBootPolicy.OFF);
        configuredAt=SystemClock.elapsedRealtime();
        policy=new MediaVolumeBootPolicy(mode,boot,prefs.getInt(BOOT,-1),configuredAt);
        if(!prefs.getString(NativeBootVolumeController.PENDING,"").isEmpty()){
            if(registered){context.unregisterReceiver(volume);registered=false;}
            policy.finish();status="原车开机音量设置待同步，请打开音量设置重试";return;
        }
        if(prefs.getBoolean(NativeBootVolumeController.OWNED,false)){
            if(registered){context.unregisterReceiver(volume);registered=false;}
            policy.finish();
            try{
                int maximum=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int target=MediaVolumeBootPolicy.scale(prefs.getInt(FIXED,-1),prefs.getInt(FIXED_MAX,0),maximum);
                boolean matches=NativeBootVolumeTransaction.skipLateRestore(mode,true,
                    prefs.getInt(NativeBootVolumeController.LEVEL,-1),target,maximum);
                status=matches?"原车开机音量已接管："+target+" / "+maximum:"原车开机音量与设置待核对";
                if(matches&&boot>=0&&prefs.getInt(BOOT,-1)!=boot)prefs.edit().putInt(BOOT,boot).commit();
            }catch(RuntimeException error){status="原车开机参数已保存，音频状态待核对";}
            return;
        }
        if(mode==MediaVolumeBootPolicy.OFF){
            if(registered){context.unregisterReceiver(volume);registered=false;}status="关闭";return;
        }
        if(!registered){context.registerReceiver(volume,new IntentFilter("android.media.VOLUME_CHANGED_ACTION"),null,handler);registered=true;}
        if(policy.pending()){status="等待开机音频初始化";handler.post(startup);}
        else{status=mode==MediaVolumeBootPolicy.REMEMBER?"记住上次媒体音量，下次整机开机恢复":"下次整机开机使用指定媒体音量";remember();}
    }
    private void startup(){
        if(closed||policy==null||!policy.pending())return;
        if(!prefs.getString(NativeBootVolumeController.PENDING,"").isEmpty()
                ||prefs.getBoolean(NativeBootVolumeController.OWNED,false)){configure();return;}
        try{
            int current=audio.getStreamVolume(AudioManager.STREAM_MUSIC),maximum=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if(policy.ready(current,maximum,audio.getMode()==AudioManager.MODE_NORMAL,SystemClock.elapsedRealtime())){
                boolean fixed=prefs.getInt(MODE,0)==MediaVolumeBootPolicy.FIXED;
                int target=MediaVolumeBootPolicy.scale(prefs.getInt(fixed?FIXED:SAVED,-1),prefs.getInt(fixed?FIXED_MAX:SAVED_MAX,0),maximum);
                if(target>=0){
                    ignoreUntil=SystemClock.elapsedRealtime()+1000;
                    if(current!=target)audio.setStreamVolume(AudioManager.STREAM_MUSIC,target,0);
                    int actual=audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                    status=actual==target?"开机媒体音量已恢复到 "+actual+" / "+maximum:"已请求恢复媒体音量，系统当前为 "+actual+" / "+maximum;
                    android.util.Log.i("BridgeVolume","restore boot="+boot+" elapsed="+SystemClock.elapsedRealtime()
                        +" since_service_ms="+(SystemClock.elapsedRealtime()-configuredAt)+" target="+target+" actual="+actual);
                    prefs.edit().putLong("media_volume_restore_elapsed",SystemClock.elapsedRealtime())
                        .putLong("media_volume_restore_service_ms",SystemClock.elapsedRealtime()-configuredAt)
                        .putInt("media_volume_restore_target",target).putInt("media_volume_restore_actual",actual).apply();
                }else status="尚无已记忆音量，保留本次媒体音量";
                policy.finish();prefs.edit().putInt(BOOT,boot).commit();
                if(target<0)remember();return;
            }
        }catch(RuntimeException e){status="媒体音量接口暂不可用，等待开机完成";}
        if(SystemClock.elapsedRealtime()>MediaVolumeBootPolicy.BOOT_WINDOW){policy.finish();status="本次开机恢复未完成，保留系统音量";return;}
        if(policy.pending())handler.postDelayed(startup,100);
    }
    private void remember(){
        if(closed||prefs.getInt(MODE,0)!=MediaVolumeBootPolicy.REMEMBER||policy!=null&&policy.pending()
                ||prefs.getBoolean(NativeBootVolumeController.OWNED,false)
                ||!prefs.getString(NativeBootVolumeController.PENDING,"").isEmpty())return;
        long remaining=ignoreUntil-SystemClock.elapsedRealtime();
        if(remaining>0){handler.removeCallbacks(remember);handler.postDelayed(remember,remaining);return;}
        try{
            int level=audio.getStreamVolume(AudioManager.STREAM_MUSIC),maximum=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if(level<0||maximum<=0||level>maximum)return;
            if(level!=prefs.getInt(SAVED,-1)||maximum!=prefs.getInt(SAVED_MAX,0))
                prefs.edit().putInt(SAVED,level).putInt(SAVED_MAX,maximum).apply();
            status="已记忆媒体音量 "+level+" / "+maximum;
        }catch(RuntimeException e){status="暂未读到媒体音量";}
    }
    public String status(){return status;}
    public void close(){
        closed=true;handler.removeCallbacksAndMessages(null);
        prefs.unregisterOnSharedPreferenceChangeListener(changed);
        handler.post(()->{
            if(registered){context.unregisterReceiver(volume);registered=false;}
            worker.quitSafely();
        });
    }
}

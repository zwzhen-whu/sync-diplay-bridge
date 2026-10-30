package com.local.diplay.clusterbridge.audio;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** User-initiated OEM synchronization. No per-second root polling or audio hooks. */
public final class NativeBootVolumeController {
    public static final String OWNED="native_boot_volume_owned", LEVEL="native_boot_volume_level";
    public static final String PENDING="native_boot_volume_pending", STATUS="native_boot_volume_status";
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    public interface Callback { void done(boolean success, String message); }
    private NativeBootVolumeController() { }
    static boolean supportedDevice() {
        return Build.VERSION.SDK_INT == 27 && "MEK-MX8Q".equals(Build.MODEL) && "mek_8q".equals(Build.DEVICE);
    }
    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences("bridge", Context.MODE_PRIVATE); }
    private static void done(Callback callback, boolean success, String message) {
        if (callback != null) new Handler(Looper.getMainLooper()).post(()->callback.done(success,message));
    }
    private static int boot(Context context) {
        try { return Settings.Global.getInt(context.getContentResolver(),"boot_count",-1); }
        catch (RuntimeException ignored) { return -1; }
    }
    private static final class Reply {
        final boolean owned; final int target;
        Reply(String text) throws Exception {
            if ("RESULT OFF".equals(text)) { owned=false;target=-1;return; }
            if (!text.matches("RESULT FIXED (?:[0-9]|[12][0-9]|30) [0-9]{1,10}")) throw new Exception(text);
            String[] fields=text.split(" "); target=Integer.parseInt(fields[2]);
            Integer.parseInt(fields[3]);owned=true;
        }
    }
    public static String status(Context context) {
        SharedPreferences p=prefs(context);
        return p.getString(STATUS,p.getBoolean(OWNED,false)?"已使用原车开机音量":"指定音量保存后可同步原车开机设置");
    }
    public static void inspect(Context context, Callback callback) {
        Context app=context.getApplicationContext();
        WORK.execute(()->{
            SharedPreferences p=prefs(app);
            if (!p.getString(PENDING,"").isEmpty()) { complete(app,callback);return; }
            if (!supportedDevice()) { done(callback,true,"当前车机使用桥接恢复音量");return; }
            try (MicPacketShell shell=MicPacketShell.nativeBootVolume(app.getApplicationInfo().sourceDir)) {
                Reply reply=new Reply(shell.exchange("Q"));
                String text=reply.owned?"原车开机音量："+reply.target+" / 30":"尚未同步原车开机音量";
                p.edit().putBoolean(OWNED,reply.owned).putInt(LEVEL,reply.target).putString(STATUS,text).commit();
                done(callback,true,text);
            } catch (Exception error) { done(callback,false,"暂时无法核对原车设置；已保存的音量选择保留"); }
        });
    }
    public static void save(Context context, int mode, int level, int maximum, Callback callback) {
        Context app=context.getApplicationContext();
        // Capture the remembered level at the explicit user action, before any worker delay.
        AudioManager audio=(AudioManager)app.getSystemService(Context.AUDIO_SERVICE);
        int remembered=mode==MediaVolumeBootPolicy.REMEMBER?audio.getStreamVolume(AudioManager.STREAM_MUSIC):-1;
        WORK.execute(()->{
            SharedPreferences p=prefs(app);
            try {
                if (mode<0 || mode>2 || maximum<=0 || maximum>100 || level<0 || level>maximum)
                    throw new IllegalArgumentException("Invalid selection");
                JSONObject request=new JSONObject().put("mode",mode).put("level",level).put("maximum",maximum)
                    .put("remembered",remembered);
                if (!p.edit().putString(PENDING,request.toString()).commit()) {
                    done(callback,false,"设置未能保存，请重试");return;
                }
                complete(app,callback);
            } catch (Exception error) { done(callback,false,"未能保存音量设置，请重试"); }
        });
    }
    public static void recoverPending(Context context) {
        Context app=context.getApplicationContext();
        if (!prefs(app).getString(PENDING,"").isEmpty()) WORK.execute(()->complete(app,null));
    }
    private static void complete(Context app, Callback callback) {
        SharedPreferences p=prefs(app);
        String encoded=p.getString(PENDING,"");
        if (encoded.isEmpty()) { done(callback,true,status(app));return; }
        try {
            JSONObject request=new JSONObject(encoded);
            int mode=request.getInt("mode"),level=request.getInt("level"),maximum=request.getInt("maximum");
            int remembered=request.getInt("remembered");
            if (mode<0 || mode>2 || maximum<=0 || maximum>100 || level<0 || level>maximum)
                throw new IllegalArgumentException("Invalid pending selection");
            boolean owned=false;int nativeTarget=-1;
            if (supportedDevice()) {
                if (maximum!=30) throw new IllegalStateException("Unsupported volume range");
                try (MicPacketShell shell=MicPacketShell.nativeBootVolume(app.getApplicationInfo().sourceDir)) {
                    String response=shell.exchange(mode==MediaVolumeBootPolicy.FIXED?"F "+level:"R");
                    if (response.startsWith("ERR ")) {
                        // WRITE means the root-side undo and readback succeeded. All other
                        // uncertain responses retain the request for explicit retry/recovery.
                        boolean rolledBack="ERR WRITE".equals(response);
                        String message="ERR CONFLICT".equals(response)?"原车音量设置已变化，未覆盖；原值备份保留"
                            :rolledBack?"同步失败，已恢复同步前的原车设置，请重试":"原车音量同步未完成，请检查车机调试连接后重试";
                        SharedPreferences.Editor edit=p.edit().putString(STATUS,message);
                        if (rolledBack) edit.remove(PENDING);
                        edit.commit();done(callback,false,message);return;
                    }
                    Reply reply=new Reply(response);owned=reply.owned;nativeTarget=reply.target;
                    if (owned!=(mode==MediaVolumeBootPolicy.FIXED) || owned&&nativeTarget!=level)
                        throw new IllegalStateException("Unexpected native state");
                }
            } else if (p.getBoolean(OWNED,false)) {
                throw new IllegalStateException("Cannot restore on unaudited firmware");
            }
            String text=owned?"已同步原车：下次开机直接使用 "+level+" / "+maximum
                :mode==MediaVolumeBootPolicy.OFF?"已关闭媒体音量恢复"
                :mode==MediaVolumeBootPolicy.REMEMBER?"已保存：记住上次媒体音量":"已保存：由桥接恢复指定音量";
            SharedPreferences.Editor edit=p.edit().putInt(MediaVolumeMemory.MODE,mode)
                .putInt(MediaVolumeMemory.FIXED,level).putInt(MediaVolumeMemory.FIXED_MAX,maximum)
                .putInt(MediaVolumeMemory.BOOT,boot(app)).putBoolean(OWNED,owned).putInt(LEVEL,nativeTarget)
                .putString(STATUS,text).remove(PENDING);
            if (mode==MediaVolumeBootPolicy.REMEMBER && remembered>=0 && remembered<=maximum)
                edit.putInt(MediaVolumeMemory.SAVED,remembered).putInt(MediaVolumeMemory.SAVED_MAX,maximum);
            if (!edit.commit()) { done(callback,false,"原车同步完成，桥接设置保存待重试");return; }
            done(callback,true,text);
        } catch (Exception error) {
            String message="原车同步尚未确认，请稍后重新保存；未确认前不切换音量模式";
            p.edit().putString(STATUS,message).commit();done(callback,false,message);
        }
    }
}

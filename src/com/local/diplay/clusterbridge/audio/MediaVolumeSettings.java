package com.local.diplay.clusterbridge.audio;

import android.app.*;
import android.content.*;
import android.media.AudioManager;
import android.widget.*;

public final class MediaVolumeSettings {
    private MediaVolumeSettings(){}
    public static void show(Activity activity){
        SharedPreferences p=activity.getSharedPreferences("bridge",Context.MODE_PRIVATE);
        AudioManager audio=(AudioManager)activity.getSystemService(Context.AUDIO_SERVICE);
        int maximum=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),current=audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        if(maximum<=0)return;
        LinearLayout page=new LinearLayout(activity);page.setOrientation(LinearLayout.VERTICAL);int pad=(int)(24*activity.getResources().getDisplayMetrics().density);page.setPadding(pad,pad/2,pad,pad);
        RadioGroup modes=new RadioGroup(activity);String[] names={"关闭","记住上次媒体音量","每次开机使用指定音量"};
        for(int n=0;n<names.length;n++){RadioButton item=new RadioButton(activity);item.setId(100+n);item.setText(names[n]);item.setTextSize(20);modes.addView(item);}
        modes.check(100+p.getInt(MediaVolumeMemory.MODE,0));page.addView(modes);
        TextView value=new TextView(activity);value.setTextSize(20);page.addView(value);
        SeekBar seek=new SeekBar(activity);seek.setMax(maximum);
        int saved=MediaVolumeBootPolicy.scale(p.getInt(MediaVolumeMemory.FIXED,current),p.getInt(MediaVolumeMemory.FIXED_MAX,maximum),maximum);
        seek.setProgress(Math.max(0,saved));page.addView(seek,new LinearLayout.LayoutParams(-1,pad*2));
        Runnable update=()->{boolean fixed=modes.getCheckedRadioButtonId()==102;seek.setEnabled(fixed);value.setText("开机指定音量："+seek.getProgress()+" / "+maximum);value.setAlpha(fixed?1f:.5f);};
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}public void onProgressChanged(SeekBar s,int v,boolean user){update.run();}});
        modes.setOnCheckedChangeListener((g,id)->update.run());update.run();
        TextView note=new TextView(activity);note.setTextSize(16);
        note.setText(NativeBootVolumeController.supportedDevice()
            ?"指定音量会同步到原车，下次整机开机直接使用；本次音量不变。关闭或改为记忆模式时恢复原车启动参数。卸载应用前请先关闭本功能。0 为静音。"
            :"保存后从下次整机开机生效。请保持桥接开机自启动开启；本次音量不变。只调整媒体音量，0 为静音。");
        page.addView(note);
        TextView sync=new TextView(activity);sync.setTextSize(16);sync.setPadding(0,pad/2,0,0);
        sync.setText(NativeBootVolumeController.status(activity));page.addView(sync);
        ScrollView scroll=new ScrollView(activity);scroll.addView(page);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("媒体音量记忆与开机音量")
            .setView(scroll).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.show();
        final boolean[] saving={false};
        NativeBootVolumeController.inspect(activity,(ok,message)->{
            if(!activity.isFinishing()&&dialog.isShowing()&&!saving[0])sync.setText(message);
        });
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(saving[0])return;saving[0]=true;
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);dialog.setCancelable(false);
            sync.setText("正在保存并核对原车设置…");
            NativeBootVolumeController.save(activity,modes.getCheckedRadioButtonId()-100,seek.getProgress(),maximum,(ok,message)->{
                saving[0]=false;
                if(activity.isFinishing()||!dialog.isShowing())return;
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);dialog.setCancelable(true);
                sync.setText(message);
                if(ok){
                    Toast.makeText(activity,message,Toast.LENGTH_LONG).show();dialog.dismiss();
                    activity.startForegroundService(new Intent(activity,com.local.diplay.clusterbridge.BridgeService.class));
                }
            });
        });
    }
}

'use strict';

// Installed once per DiPlay process. Disable/expiry only bypasses processing; never unhook a
// method while ART may be executing its trampoline on Android 8.1.
Java.perform(function () {
    const ActivityThread = Java.use('android.app.ActivityThread');
    const Process = Java.use('android.os.Process');
    const Clock = Java.use('android.os.SystemClock');
    const JString = Java.use('java.lang.String');
    const File = Java.use('java.io.File');
    const Reader = Java.use('java.io.BufferedReader');
    const FileReader = Java.use('java.io.FileReader');
    const Output = Java.use('java.io.FileOutputStream');
    const AudioManager = Java.use('android.media.AudioManager');
    const ctx = ActivityThread.currentApplication().getApplicationContext();
    const pm = ctx.getPackageManager();
    const source = pm.getPackageInfo('com.shihab.diplay', 0);
    if (String(source.versionName.value) !== '0.2.21' || source.versionCode.value !== 40)
        throw new Error('Unaudited DiPlay audio version');
    const helper = pm.getPackageInfo('com.local.diplay.clusterbridge', 64);
    const signatures = helper.signatures.value;
    const digest = Java.use('java.security.MessageDigest').getInstance('SHA-256')
        .digest(signatures[0].toByteArray());
    let certificate = '';
    for (let i = 0; i < digest.length; i++) certificate += ('0' + (digest[i] & 255).toString(16)).slice(-2);
    if (signatures.length !== 1 || certificate !== '26e386a76a9272a40f84c3d64e608bb27ccd99956ee030d73fe086afbd106039')
        throw new Error('Unaudited bridge certificate');
    const app = helper.applicationInfo.value;
    const helperUid = app.uid.value;
    const helperCode = helper.versionCode.value;
    Java.openClassFile(String(app.sourceDir.value)).load();
    const Gain = Java.use('com.local.diplay.clusterbridge.audio.CallPcmGain');
    const Policy = Java.use('com.local.diplay.clusterbridge.audio.CallAudioPolicy');
    const Renderer = Java.use('com.shilapi.xcertplay.media.AudioRenderer');
    const method = Renderer.attenuateNavigation.overload('[B', 'int', 'int');
    const audio = Java.cast(ctx.getSystemService('audio'), AudioManager);
    const pid = Process.myPid();
    const statReader = Reader.$new(FileReader.$new('/proc/self/stat'));
    let stat;
    try { stat = String(statReader.readLine()); } finally { statReader.close(); }
    const processStart = stat.slice(stat.lastIndexOf(')') + 2).split(/\s+/)[19];
    const config = File.$new(ctx.getFilesDir(), 'bridge-call-audio-config.json');
    const reportFile = File.$new(ctx.getFilesDir(), 'bridge-call-audio-state.json');
    const temporary = File.$new(ctx.getFilesDir(), 'bridge-call-audio-state.tmp');
    let expires = 0, enabled = false, volume = -1, maximum = 0, gain = 1;
    let lastFrame = 0, processed = 0, error = '', ticks = 0;
    function lease(now) { return enabled && now < expires && expires - now <= 15000; }
    function readConfiguration() {
        enabled = false;
        if (!config.isFile() || Number(config.length()) > 4096) return;
        const input = Reader.$new(FileReader.$new(config));
        let text;
        try { text = input.readLine(); } finally { input.close(); }
        const value = JSON.parse(String(text));
        expires = Number(value.expires_elapsed);
        enabled = String(value.process_start_ticks) === processStart
            && Policy.lease(value.enabled === true, expires, Number(Clock.elapsedRealtime()),
                Number(value.pid), pid);
    }
    function report() {
        const now = Number(Clock.elapsedRealtime());
        const value = JSON.stringify({agent: 1, revision: 'call-volume-3', pid: pid,
            process_start_ticks: processStart, helper_package: 'com.local.diplay.clusterbridge',
            helper_uid: helperUid, helper_code: helperCode, source_version: '0.2.21',
            enabled: lease(now), call_active: lastFrame > 0 && now - lastFrame < 1500,
            volume: volume, maximum: maximum, gain: gain, processed_buffers: processed,
            error: error, at_elapsed: now});
        const output = Output.$new(temporary, false);
        try { output.write(JString.$new(value).getBytes('UTF-8')); } finally { output.close(); }
        if (!temporary.renameTo(reportFile)) throw new Error('Audio report unavailable');
    }
    method.implementation = function (data, offset, length) {
        method.call(this, data, offset, length);
        const now = Number(Clock.elapsedRealtime());
        if (error || !lease(now) || this.format.value.getAudioType().toString() !== 'telephony') return;
        lastFrame = now;
        try {
            if (gain !== 1) { Gain.apply(data, offset, length, gain); processed++; }
        } catch (_) { enabled = false; gain = 1; error = 'PCM gain unavailable'; }
    };
    function tick() {
        Java.perform(function () {
            try {
                // Configuration I/O occurs twice a second, never on the audio thread.
                if (ticks % 5 === 0) readConfiguration();
                if (lease(Number(Clock.elapsedRealtime()))) {
                    volume = audio.getStreamVolume(0);
                    maximum = audio.getStreamMaxVolume(0);
                    gain = Gain.gain(volume, maximum, audio.isStreamMute(0));
                } else gain = 1;
            } catch (failure) {
                enabled = false; gain = 1;
                error = ('Audio control: ' + String(failure)).slice(0, 200);
            }
            if (ticks % 20 === 0) {
                try { report(); } catch (_) { /* Never affect the original audio on a report failure. */ }
            }
            ticks++;
        });
    }
    report(); tick(); setInterval(tick, 100);
});

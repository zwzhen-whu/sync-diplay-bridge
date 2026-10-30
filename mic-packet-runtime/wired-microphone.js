'use strict';

// State is disposable diagnostic cache. An interrupted reboot may leave an empty file.
function previouslyAttached(text, pid, start, boot) {
    try {
        const value = JSON.parse(text);
        return value !== null && Number(value.pid) === pid
            && String(value.boot_id) === boot
            && String(value.process_start_ticks) === start;
    } catch (_) { return false; }
}
let applicationRetries = 0;

// One attachment per native process. Expiry bypasses new stream setup; never hot-unhook ART.
function bootstrap() { Java.perform(function () {
    const Activity = Java.use('android.app.ActivityThread');
    const Process = Java.use('android.os.Process');
    const Clock = Java.use('android.os.SystemClock');
    const File = Java.use('java.io.File');
    const Reader = Java.use('java.io.BufferedReader');
    const FileReader = Java.use('java.io.FileReader');
    const Output = Java.use('java.io.FileOutputStream');
    const JString = Java.use('java.lang.String');
    const NetworkInterface = Java.use('java.net.NetworkInterface');
    const application = Activity.currentApplication();
    if (application === null) {
        if (++applicationRetries > 100) throw new Error('DiPlay application not ready');
        setTimeout(bootstrap, 100); return;
    }
    const ctx = application.getApplicationContext();
    const packageInfo = ctx.getPackageManager().getPackageInfo('com.shihab.diplay', 64);
    if (String(packageInfo.versionName.value) !== '0.2.21' || packageInfo.versionCode.value !== 40)
        throw new Error('Unsupported DiPlay version');
    const sigs = packageInfo.signatures.value;
    if (sigs.length !== 1) throw new Error('Unsupported signing identity');
    const digest = Java.use('java.security.MessageDigest').getInstance('SHA-256').digest(sigs[0].toByteArray());
    let cert = '';
    for (let i = 0; i < digest.length; i++) cert += ('0' + (digest[i] & 255).toString(16)).slice(-2);
    if (cert !== '87b38b12788dcb202a961215f2572e30ec2dc9d8ef4bc070d05f77e49291a363')
        throw new Error('Unsupported DiPlay certificate');
    const helper = ctx.getPackageManager().getPackageInfo('com.local.diplay.clusterbridge', 64);
    const helperSignatures = helper.signatures.value;
    if (helperSignatures.length !== 1) throw new Error('Unsupported bridge signature');
    const helperDigest = Java.use('java.security.MessageDigest').getInstance('SHA-256')
        .digest(helperSignatures[0].toByteArray());
    let helperCert = '';
    for (let i = 0; i < helperDigest.length; i++) helperCert += ('0' + (helperDigest[i] & 255).toString(16)).slice(-2);
    if (helperCert !== '26e386a76a9272a40f84c3d64e608bb27ccd99956ee030d73fe086afbd106039')
        throw new Error('Unsupported bridge certificate');
    const helperApp = helper.applicationInfo.value;
    const helperUid = helperApp.uid.value, helperCode = helper.versionCode.value;
    Java.openClassFile(String(helperApp.sourceDir.value)).load();
    const Policy = Java.use('com.local.diplay.clusterbridge.audio.MicPacketPolicy');
    const pid = Process.myPid();
    function readLine(file) {
        const input = Reader.$new(FileReader.$new(file));
        try { return String(input.readLine()); } finally { input.close(); }
    }
    const stat = readLine(File.$new('/proc/self/stat'));
    const start = stat.slice(stat.lastIndexOf(')') + 2).trim().split(/\s+/)[19];
    const configFile = File.$new(ctx.getFilesDir(), 'bridge-wired-mic-config.json');
    const stateFile = File.$new(ctx.getFilesDir(), 'bridge-wired-mic-state.json');
    const stateTmp = File.$new(ctx.getFilesDir(), 'bridge-wired-mic-state.tmp');
    const bootId = String(JSON.parse(readLine(configFile)).boot_id || '');
    if (!/^[0-9a-fA-F-]{36}$/.test(bootId)) throw new Error('Boot identity unavailable');
    if (stateFile.isFile() && Number(stateFile.length()) <= 4096) {
        let previous = '';
        try { previous = readLine(stateFile); } catch (_) { /* A stale cache cannot block a new process. */ }
        if (previouslyAttached(previous, pid, start, bootId))
            throw new Error('Restart required before another microphone attachment');
    }
    let enabled = false, expires = 0, seen = 0, changed = 0, error = '', last = null;
    const Engine = Java.use('com.shilapi.xcertplay.airplay.CarPlayMediaEngine');
    const Config = Java.use('com.shilapi.xcertplay.airplay.MicrophoneConfig');
    const method = Engine.microphoneConfig.overload(
        'com.shilapi.xcertplay.airplay.AirPlaySession', 'int', 'java.util.Map',
        'com.shilapi.xcertplay.airplay.AudioFormat');
    const copy = Config.copy.overload('java.lang.String', 'int', 'int', 'int', 'int',
        'java.net.InetAddress', 'int', '[B', 'com.shilapi.xcertplay.airplay.AudioCodecKind',
        'java.lang.Integer', 'int');
    function active() {
        const now = Number(Clock.elapsedRealtime());
        return enabled && now < expires && expires - now <= 15000;
    }
    method.implementation = function (session, type, stream, format) {
        const original = method.call(this, session, type, stream, format);
        if (original === null || !active()) return original;
        seen++;
        try {
            const rate = original.getSampleRate(), channels = original.getChannels();
            const before = original.getFrameMillis();
            const codec = original.getCodec().toString();
            const audioType = original.getAudioType().toString();
            const host = original.getHost(), local = session.getLocalAddress();
            const iface = local === null ? null : NetworkInterface.getByInetAddress(local);
            const tunnel = iface !== null && iface.getName().toString().indexOf('tun') === 0;
            const wired = host.getClass().getName().toString() === 'java.net.Inet6Address'
                && host.isLinkLocalAddress() && local !== null && local.isLinkLocalAddress() && tunnel;
            const explicit = stream.get(JString.$new('framesPerPacket'));
            const explicitFrame = explicit !== null && Number(explicit.toString()) > 0;
            const ipBytes = Math.floor(rate * before / 1000) * channels * 2 + 84;
            last = {audio_type: audioType, rate: rate, channels: channels, codec: codec,
                before_ms: before, after_ms: before, wired_tunnel: wired,
                explicit_frame: explicitFrame, ip_bytes_before: ipBytes, ip_bytes_after: ipBytes};
            const after = Policy.frameMillis(wired, codec, audioType, rate, channels, before, explicitFrame);
            if (after === before) return original;
            const next = copy.call(original, original.getAudioType(), rate, channels,
                original.getPayloadType(), after, host, original.getPort(), original.getKey(),
                original.getCodec(), original.getBitrate(), original.getOpusClockRate());
            if (next.getFrameBytes() !== 960 || next.getRtpSamplesPerPacket() !== 480)
                throw new Error('Unexpected PCM frame calculation');
            changed++;
            last.after_ms = 10; last.ip_bytes_after = 1044;
            return next;
        } catch (failure) {
            enabled = false;
            error = ('Microphone setup: ' + String(failure)).slice(0, 200);
            return original;
        }
    };
    function report() {
        const value = JSON.stringify({agent: 1, revision: 'wired-mic-packet-2', pid: pid,
            helper_package: 'com.local.diplay.clusterbridge', helper_uid: helperUid,
            helper_code: helperCode, source_version: '0.2.21',
            process_start_ticks: start, enabled: active(), expires_elapsed: expires,
            boot_id: bootId,
            seen: seen, changed: changed, last: last, error: error,
            at_elapsed: Number(Clock.elapsedRealtime())});
        const output = Output.$new(stateTmp, false);
        try { output.write(JString.$new(value).getBytes('UTF-8')); } finally { output.close(); }
        if (!stateTmp.renameTo(stateFile)) throw new Error('Microphone report unavailable');
    }
    let ticks = 0;
    function tick() {
        Java.perform(function () {
            enabled = false;
            try {
                if (!error && configFile.isFile() && Number(configFile.length()) <= 4096) {
                    const value = JSON.parse(readLine(configFile));
                    expires = Number(value.expires_elapsed);
                    enabled = String(value.process_start_ticks) === start
                        && String(value.boot_id) === bootId
                        && Policy.lease(value.enabled === true, expires, Number(Clock.elapsedRealtime()),
                            Number(value.pid), pid);
                }
            } catch (_) { enabled = false; }
            if (ticks++ % 4 === 0) {
                try { report(); } catch (_) { enabled = false; }
            }
        });
    }
    report(); tick(); setInterval(tick, 500);
    console.log('WIRED_MIC_READY pid=' + pid + ' start=' + start);
}); }
bootstrap();

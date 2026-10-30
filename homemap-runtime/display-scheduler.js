'use strict';

Java.perform(function () {
    const ActivityThread = Java.use('android.app.ActivityThread');
    const Process = Java.use('android.os.Process');
    const System = Java.use('java.lang.System');
    const Host = Java.use('com.shilapi.xcertplay.CarPlayHostActivity');
    const Background = Java.use('com.shilapi.xcertplay.CarPlayBackgroundSession');
    const Presentation = Java.use('com.shilapi.xcertplay.ClusterMapPresentation');
    const Sink = Java.use('com.shilapi.xcertplay.media.AndroidMediaSink');
    const Surface = Java.use('android.view.Surface');
    const Map = Java.use('java.util.Map');
    const Integer = Java.use('java.lang.Integer');
    const DisplayManager = Java.use('android.hardware.display.DisplayManager');
    const UsageStatsManager = Java.use('android.app.usage.UsageStatsManager');
    const UsageEvent = Java.use('android.app.usage.UsageEvents$Event');
    const Log = Java.use('android.util.Log');
    const File = Java.use('java.io.File');
    const FileOutputStream = Java.use('java.io.FileOutputStream');
    const JString = Java.use('java.lang.String');
    const ctx = ActivityThread.currentApplication().getApplicationContext();
    const processReader = Java.use('java.io.BufferedReader').$new(Java.use('java.io.FileReader').$new('/proc/self/stat'));
    let processStat;
    try { processStat = String(processReader.readLine()); } finally { processReader.close(); }
    const processStart = processStat.slice(processStat.lastIndexOf(')') + 2).split(/\s+/)[19];
    const pm = ctx.getPackageManager();
    const version = String(pm.getPackageInfo('com.shihab.diplay', 0).versionName.value);
    if (version !== '0.2.21') throw new Error('Unaudited DiPlay version');
    const helperUid = pm.getApplicationInfo('com.local.diplay.clusterbridge', 0).uid.value;
    const displays = Java.cast(ctx.getSystemService('display'), DisplayManager);
    const usage = Java.cast(ctx.getSystemService('usagestats'), UsageStatsManager);
    const homeIntent = Java.use('android.content.Intent').$new('android.intent.action.MAIN');
    homeIntent.addCategory('android.intent.category.HOME');
    const homePackage = String(pm.resolveActivity(homeIntent, 65536).activityInfo.value.packageName.value);
    function field(type, name) {
        const result = type.class.getDeclaredField(name); result.setAccessible(true); return result;
    }
    const ownerField = field(Background, 'owner');
    const sessionActiveField = field(Background, 'active');
    const sessionField = field(Host, 'activeAirPlaySession');
    const presenterField = field(Host, 'clusterPresentation');
    const streamField = field(Presentation, 'streamActive');
    const sinkField = field(Host, 'sink');
    const clusterSurfaceField = field(Host, 'clusterSurface');
    const surfacesField = field(Sink, 'surfaces');
    const ownershipField = field(Sink, 'videoOwnershipLock');
    const setSurface = Sink.setSurface.overload('int', 'android.view.Surface');
    const clearSurface = Sink.clearSurface.overload('int', 'android.view.Surface');
    const stateFile = File.$new(ctx.getFilesDir(), 'homemap-runtime-state.json');
    const temporaryFile = File.$new(ctx.getFilesDir(), 'homemap-runtime-state.tmp');
    let scoped = false, helperPresent = false, sessionActive = false;
    let videoReady = false, firstVideoAt = 0, videoSessionKey = 0;
    let wanted = false, mainWanted = false, newest = 0, foreground = null;
    let since = Date.now() - 600000, lastState = '', epoch = 0, queued = false;
    let lastError = '', activeSink = null, mainBound = false, mapBound = false, mapStream = false;
    function owned(display) {
        return display !== null && display.getType() === 5 && display.getOwnerUid() === helperUid &&
            String(display.getName()) === 'fission_bg_xdjaVirtualSurface';
    }
    function helperDisplayExists() {
        const list = displays.getDisplays();
        for (let i = 0; i < list.length; i++) if (owned(list[i])) return true;
        return false;
    }
    function currentHost() {
        const raw = ownerField.get(null);
        return raw !== null && Host.class.isInstance(raw) ? Java.cast(raw, Host) : null;
    }
    function same(a, b) { return a !== null && b !== null && a.equals(b); }
    function surface(raw) { return raw === null ? null : Java.cast(raw, Surface); }
    function valid(value) { return value !== null && value.isValid(); }
    function registered(sink, type) {
        let value = null;
        Java.synchronized(ownershipField.get(sink), function () {
            value = surface(Java.cast(surfacesField.get(sink), Map).get(Integer.valueOf(type)));
        });
        return value;
    }
    function bind(sink, type, target) {
        if (type !== 111) throw new Error('Only the local map decoder may be changed');
        const old = registered(sink, type);
        if (!valid(target)) {
            if (old !== null) clearSurface.call(sink, type, old);
            return old !== null;
        }
        if (same(old, target)) return false;
        setSurface.call(sink, type, target); return true;
    }
    function refreshLater(type, generation) {
        // Decoder creation is asynchronous; a still map also needs a new reference frame.
        [400, 1400].forEach(function (delay) {
            setTimeout(function () {
                Java.perform(function () {
                    if (epoch !== generation || activeSink === null || !scoped) return;
                    if ((type === 111 && wanted) || (type === 110 && mainWanted)) activeSink.refreshPicture(type);
                });
            }, delay);
        });
    }
    function report(error) {
        const state = JSON.stringify({agent: 2, revision: 'bridge-decoder-only-1', pid: Process.myPid(), version: version,
            process_start_ticks: processStart,
            helper_package: 'com.local.diplay.clusterbridge', helper_uid: helperUid, helper_present: helperPresent, session_active: sessionActive, video_ready: videoReady, scheduling_active: scoped,
            map_wanted: wanted, main_wanted: mainWanted, foreground: foreground,
            main_bound: mainBound, map_bound: mapBound, map_stream_active: mapStream, error: error || '', at: Date.now()});
        const output = FileOutputStream.$new(temporaryFile, false);
        try { output.write(JString.$new(state).getBytes('UTF-8')); } finally { output.close(); }
        temporaryFile.renameTo(stateFile);
        Log.i('HomeMapRuntime', 'mapWanted=' + wanted + ' mainWanted=' + mainWanted +
            ' scoped=' + scoped + (error ? ' error=' + error : ''));
    }

    // Leave the primary stream's parking/ownership lifecycle entirely with DiPlay.
    setSurface.implementation = function (type, target) {
        if (scoped && same(this, activeSink) && type === 111 && !wanted) return;
        setSurface.call(this, type, target);
    };
    function sync() {
        queued = false;
        try {
            const host = currentHost();
            if (host === null) { activeSink = null; mainBound = mapBound = false; return; }
            const rawSink = sinkField.get(host);
            activeSink = rawSink === null ? null : Java.cast(rawSink, Sink);
            const rawPresenter = presenterField.get(host);
            const presenter = rawPresenter === null ? null : Java.cast(rawPresenter, Presentation);
            mapStream = presenter !== null && owned(presenter.getDisplay()) && streamField.getBoolean(presenter);
            if (videoReady && presenter !== null && owned(presenter.getDisplay())) {
                // Keeping SurfaceView alive avoids the GONE -> new-surface black-map race on Android 8.1.
                const window = presenter.getWindow();
                if (window !== null && window.getDecorView().getVisibility() !== 0) window.getDecorView().setVisibility(0);
                presenter.setMapVisible(true);
            }
            let rebound = false;
            if (activeSink !== null && scoped) {
                if (wanted) {
                    rebound = bind(activeSink, 111, surface(clusterSurfaceField.get(host)));
                } else {
                    bind(activeSink, 111, null);
                }
            }
            mainBound = activeSink !== null && valid(registered(activeSink, 110));
            mapBound = activeSink !== null && valid(registered(activeSink, 111));
            const key = [helperPresent, sessionActive, videoReady, scoped, wanted, mainWanted, mainBound, mapBound, mapStream,
                activeSink === null ? 0 : System.identityHashCode(activeSink),
                presenter === null ? 0 : System.identityHashCode(presenter)].join(':');
            if (key !== lastState || rebound) {
                epoch++;
                if (scoped && wanted) refreshLater(111, epoch);
                lastState = key; lastError = ''; report('');
            }
        } catch (error) {
            const message = String(error).slice(0, 200);
            if (message !== lastError) { lastError = message; report(message); }
        }
    }
    function tick() {
        Java.perform(function () {
            try {
                const now = Date.now(), events = usage.queryEvents(since, now), event = UsageEvent.$new();
                while (events.hasNextEvent()) {
                    events.getNextEvent(event);
                    if (event.getEventType() === 1 && Number(event.getTimeStamp()) >= newest) {
                        newest = Number(event.getTimeStamp()); foreground = String(event.getPackageName());
                    }
                }
                since = now - 1000;
                helperPresent = helperDisplayExists();
                sessionActive = sessionActiveField.getBoolean(null);
                const host = currentHost();
                const rawSink = host === null ? null : sinkField.get(host);
                const rawSession = host === null ? null : sessionField.get(host);
                const sessionKey = rawSession === null ? 0 : System.identityHashCode(rawSession);
                const counters = rawSink === null ? null : Java.cast(rawSink, Sink).liveVideoCounters();
                const hasPicture = sessionActive && counters !== null && Number(counters.getRendered()) > 0;
                if (!hasPicture || sessionKey !== videoSessionKey) {
                    firstVideoAt = hasPicture ? now : 0; videoSessionKey = sessionKey;
                } else if (firstVideoAt === 0) firstVideoAt = now;
                videoReady = hasPicture && now - firstVideoAt >= 2000;
                // A control-session callback can precede SETUP and the first main picture.
                scoped = helperPresent && videoReady;
                // OEM Home may cover a still-STARTED host. Usage events own this decision.
                wanted = scoped && foreground === homePackage;
                mainWanted = scoped && foreground === 'com.shihab.diplay';
                if (!queued) { queued = true; Java.scheduleOnMainThread(sync); }
            } catch (error) {
                const message = String(error).slice(0, 200);
                if (message !== lastError) { lastError = message; report(message); }
            }
        });
    }
    report(''); tick(); setInterval(tick, 250);
});

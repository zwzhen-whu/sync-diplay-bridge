#!/usr/bin/env python3
"""Prepare the pinned map runtime and compile-time checksums; never changes signing configuration."""
from pathlib import Path
import hashlib
import json
import lzma
import os
import shutil
import urllib.request

project = Path(__file__).resolve().parent
build = project / 'build'
assets = build / 'homemap-assets'
runtime = assets / 'runtime'
runtime.mkdir(parents=True, exist_ok=True)
binary_sha = '5e5ad93e0f12538829b5f98fb65f1056a72da5f1eb380ada1ddf8a89716b5cc9'
archive_sha = 'fba7b529de7c25915f308d40a51e13da843580e1f5f8e139d2e1cda2597fa637'
url = 'https://github.com/frida/frida/releases/download/16.7.19/frida-inject-16.7.19-android-arm64.xz'

def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(65536), b''):
            digest.update(chunk)
    return digest.hexdigest()

binary = runtime / 'frida-inject'
candidates = [binary, project / 'homemap-runtime/frida-inject']
if os.environ.get('BRIDGE_FRIDA_INJECT'):
    explicit = Path(os.environ['BRIDGE_FRIDA_INJECT'])
    if not explicit.is_file() or sha(explicit) != binary_sha:
        raise SystemExit('BRIDGE_FRIDA_INJECT must match the pinned official binary')
    candidates.insert(0, explicit)
source = next((path for path in candidates if path.is_file() and sha(path) == binary_sha), None)
if source is None:
    cache = build / 'runtime-cache'; cache.mkdir(exist_ok=True)
    archive = cache / 'frida-inject-16.7.19-android-arm64.xz'
    if not archive.is_file() or sha(archive) != archive_sha:
        temporary = archive.with_suffix('.download')
        with urllib.request.urlopen(url, timeout=30) as response, temporary.open('wb') as output:
            shutil.copyfileobj(response, output)
        if sha(temporary) != archive_sha:
            temporary.unlink(); raise SystemExit('Official runtime archive checksum mismatch')
        temporary.replace(archive)
    with lzma.open(archive, 'rb') as input_stream, binary.open('wb') as output:
        shutil.copyfileobj(input_stream, output)
elif source.resolve() != binary.resolve():
    shutil.copy2(source, binary)
if sha(binary) != binary_sha:
    raise SystemExit('Runtime binary checksum mismatch')
agent = project / 'homemap-runtime/display-scheduler.js'
shutil.copy2(agent, runtime / agent.name)
shutil.copytree(project / 'homemap-runtime/notices', assets / 'notices', dirs_exist_ok=True)
agent_sha = sha(agent)
call_agent = project / 'call-audio-runtime/call-volume.js'
shutil.copy2(call_agent, runtime / call_agent.name)
call_agent_sha = sha(call_agent)
call_generated = build / 'generated/com/local/diplay/clusterbridge/audio/CallAudioBuild.java'
call_generated.parent.mkdir(parents=True, exist_ok=True)
call_generated.write_text('package com.local.diplay.clusterbridge.audio;\nfinal class CallAudioBuild {\n'
    + ' static final String INJECT_SHA256 = "' + binary_sha + '";\n'
    + ' static final String AGENT_SHA256 = "' + call_agent_sha + '";\n}\n')
mic_agent = project / 'mic-packet-runtime/wired-microphone.js'
shutil.copy2(mic_agent, runtime / mic_agent.name)
mic_agent_sha = sha(mic_agent)
mic_generated = build / 'generated/com/local/diplay/clusterbridge/audio/MicPacketBuild.java'
mic_generated.write_text('package com.local.diplay.clusterbridge.audio;\nfinal class MicPacketBuild {\n'
    + ' static final String INJECT_SHA256 = "' + binary_sha + '";\n'
    + ' static final String AGENT_SHA256 = "' + mic_agent_sha + '";\n}\n')
generated = build / 'generated/com/local/diplay/clusterbridge/homemap/RuntimeBuild.java'
generated.parent.mkdir(parents=True, exist_ok=True)
generated.write_text('package com.local.diplay.clusterbridge.homemap;\nfinal class RuntimeBuild {\n'
    + ' static final String INJECT_SHA256 = "' + binary_sha + '";\n'
    + ' static final String AGENT_SHA256 = "' + agent_sha + '";\n}\n')
(build / 'homemap-runtime.json').write_text(json.dumps({
    'frida_version': '16.7.19', 'binary_sha256': binary_sha, 'agent_sha256': agent_sha,
    'call_audio_agent_sha256': call_agent_sha,
    'wired_microphone_agent_sha256': mic_agent_sha,
    'owner_package': 'com.local.diplay.clusterbridge', 'source_version': '0.2.21',
    'source_code': 40, 'decoder_scope': [111], 'source_apk_unchanged': True,
    'virtual_display': [1280, 480], 'legacy_retained_disabled_after_claim': True
}, indent=2) + '\n')
print('Pinned map runtime prepared for the bridge package')

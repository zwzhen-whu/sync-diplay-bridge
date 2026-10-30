#!/bin/bash
set -euo pipefail
BRIDGE_SOURCE="$(cd "$(dirname "$0")" && pwd)"
BRIDGE_TESTS="$BRIDGE_SOURCE/build/tests"
if [ -n "${JAVA_HOME:-}" ]; then
    BRIDGE_JAVAC="$JAVA_HOME/bin/javac"
    BRIDGE_JAVA="$JAVA_HOME/bin/java"
else
    BRIDGE_JAVAC=javac
    BRIDGE_JAVA=java
fi
mkdir -p "$BRIDGE_TESTS"
"$BRIDGE_JAVAC" --release 8 -encoding UTF-8 -d "$BRIDGE_TESTS" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/Protocol.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/NavigationLogState.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/MusicDelivery.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/MusicTextPolicy.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/CarPlayEntryPolicy.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/ForegroundActivityState.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/RemainingTrip.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/RemainingDelivery.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/WidgetBindingIds.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/LogAccess.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/NavigationDelivery.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/BoundedDiagnosticLog.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/DiagnosticTrace.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/DiagnosticOemTrace.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/DiagnosticReport.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/AudioRecoveryPolicy.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/SystemAudioState.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/BluetoothMediaPolicy.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/IphoneUsbPresence.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/CarPlayCallState.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/CallDisplayProtocol.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/MediaKeyPolicy.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/MediaRouting.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/RadioStateReply.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/KeyControlRequest.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/KeyControlEngine.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/CallKeyReceipt.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/DiPlayCallControl.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/InstrumentCallInput.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/LocalAdbShell.java" \
    "$BRIDGE_SOURCE/tests/ProtocolTest.java" "$BRIDGE_SOURCE/tests/MusicTextPolicyTest.java" "$BRIDGE_SOURCE/tests/MediaRoutingTest.java" "$BRIDGE_SOURCE/tests/NavigationLogTest.java" "$BRIDGE_SOURCE/tests/StartupTest.java" \
    "$BRIDGE_SOURCE/tests/RemainingTripTest.java" "$BRIDGE_SOURCE/tests/WidgetBindingTest.java" "$BRIDGE_SOURCE/tests/AuditRegressionTest.java" "$BRIDGE_SOURCE/tests/RoadSyncTest.java" "$BRIDGE_SOURCE/tests/DiagnosticLogTest.java" "$BRIDGE_SOURCE/tests/AudioRecoveryTest.java" "$BRIDGE_SOURCE/tests/SystemAudioStateTest.java" \
    "$BRIDGE_SOURCE/tests/RoadNormalizationTest.java" "$BRIDGE_SOURCE/tests/NavigationStateContinuityTest.java" \
    "$BRIDGE_SOURCE/tests/RoadDisplayModeTest.java" "$BRIDGE_SOURCE/tests/BluetoothMediaTest.java" "$BRIDGE_SOURCE/tests/CarPlayCallTest.java" "$BRIDGE_SOURCE/tests/MediaKeyControlTest.java" "$BRIDGE_SOURCE/tests/LocalAdbShellTest.java" "$BRIDGE_SOURCE/tests/InstrumentCallInputTest.java" "$BRIDGE_SOURCE/tests/VehicleDiagnosticTest.java"
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.ProtocolTest
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.MusicTextPolicyTest
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.NavigationLogTest
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.StartupTest
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.RemainingTripTest
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.WidgetBindingTest
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.AuditRegressionTest
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.RoadSyncTest
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.DiagnosticLogTest
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.AudioRecoveryTest
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.SystemAudioStateTest
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.RoadNormalizationTest
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.NavigationStateContinuityTest
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.RoadDisplayModeTest
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.BluetoothMediaTest
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.CarPlayCallTest
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.MediaKeyControlTest
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.MediaRoutingTest
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.LocalAdbShellTest
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.InstrumentCallInputTest
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.VehicleDiagnosticTest
"$BRIDGE_JAVAC" --release 8 -encoding UTF-8 -d "$BRIDGE_TESTS" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/homemap/MapDisplayLease.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/homemap/RuntimeIdentity.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/homemap/RuntimeSessionEvidence.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/homemap/MapMigrationPolicy.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/homemap/LauncherScrollState.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/homemap/MapFollowPosition.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/homemap/MapViewport.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/homemap/MapFollowAnchor.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/homemap/HomeMapVisibilityPolicy.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/homemap/MapFollowFrame.java" \
    "$BRIDGE_SOURCE/tests/homemap/MapDisplayLeaseTest.java" \
    "$BRIDGE_SOURCE/tests/homemap/RuntimeIdentityTest.java" \
    "$BRIDGE_SOURCE/tests/homemap/RuntimeSessionEvidenceTest.java" \
    "$BRIDGE_SOURCE/tests/homemap/MapMigrationPolicyTest.java" \
    "$BRIDGE_SOURCE/tests/homemap/MapFollowTest.java" \
    "$BRIDGE_SOURCE/tests/homemap/MapViewportTest.java" \
    "$BRIDGE_SOURCE/tests/homemap/MapPageAnchorTest.java" \
    "$BRIDGE_SOURCE/tests/homemap/HomeMapVisibilityTest.java"
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.homemap.MapDisplayLeaseTest
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.homemap.RuntimeIdentityTest
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.homemap.RuntimeSessionEvidenceTest
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.homemap.MapMigrationPolicyTest
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.homemap.MapFollowTest
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.homemap.MapViewportTest
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.homemap.MapPageAnchorTest
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.homemap.HomeMapVisibilityTest
"$BRIDGE_JAVAC" --release 8 -encoding UTF-8 -d "$BRIDGE_TESTS" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/audio/CallPcmGain.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/audio/CallAudioPolicy.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/audio/MediaVolumeBootPolicy.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/audio/NativeBootVolumeTransaction.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/audio/MicPacketShell.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/audio/MicPacketPolicy.java" \
    "$BRIDGE_SOURCE/tests/audio/CallAudioTest.java" \
    "$BRIDGE_SOURCE/tests/audio/MediaVolumeBootTest.java" \
    "$BRIDGE_SOURCE/tests/audio/NativeBootVolumeTest.java" \
    "$BRIDGE_SOURCE/tests/audio/NativeBootVolumeShellTest.java" \
    "$BRIDGE_SOURCE/tests/audio/MicPacketTest.java"
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.audio.CallAudioTest
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.audio.MediaVolumeBootTest
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.audio.NativeBootVolumeTest
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.audio.NativeBootVolumeShellTest
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.audio.MicPacketTest

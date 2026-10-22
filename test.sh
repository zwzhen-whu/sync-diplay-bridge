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
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/CarPlayEntryPolicy.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/ForegroundActivityState.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/RemainingTrip.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/RemainingDelivery.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/WidgetBindingIds.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/LogAccess.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/NavigationDelivery.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/BoundedDiagnosticLog.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/AudioRecoveryPolicy.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/SystemAudioState.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/BluetoothMediaPolicy.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/IphoneUsbPresence.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/CarPlayCallState.java" \
    "$BRIDGE_SOURCE/src/com/local/diplay/clusterbridge/CallDisplayProtocol.java" \
    "$BRIDGE_SOURCE/tests/ProtocolTest.java" "$BRIDGE_SOURCE/tests/NavigationLogTest.java" "$BRIDGE_SOURCE/tests/StartupTest.java" \
    "$BRIDGE_SOURCE/tests/RemainingTripTest.java" "$BRIDGE_SOURCE/tests/WidgetBindingTest.java" "$BRIDGE_SOURCE/tests/AuditRegressionTest.java" "$BRIDGE_SOURCE/tests/RoadSyncTest.java" "$BRIDGE_SOURCE/tests/DiagnosticLogTest.java" "$BRIDGE_SOURCE/tests/AudioRecoveryTest.java" "$BRIDGE_SOURCE/tests/SystemAudioStateTest.java" \
    "$BRIDGE_SOURCE/tests/RoadNormalizationTest.java" "$BRIDGE_SOURCE/tests/NavigationStateContinuityTest.java" \
    "$BRIDGE_SOURCE/tests/RoadDisplayModeTest.java" "$BRIDGE_SOURCE/tests/BluetoothMediaTest.java" "$BRIDGE_SOURCE/tests/CarPlayCallTest.java"
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.ProtocolTest
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

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
    "$BRIDGE_SOURCE/tests/ProtocolTest.java" "$BRIDGE_SOURCE/tests/NavigationLogTest.java" "$BRIDGE_SOURCE/tests/StartupTest.java" \
    "$BRIDGE_SOURCE/tests/RemainingTripTest.java" "$BRIDGE_SOURCE/tests/WidgetBindingTest.java"
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.ProtocolTest
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.NavigationLogTest
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.StartupTest
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.RemainingTripTest
"$BRIDGE_JAVA" -classpath "$BRIDGE_TESTS" com.local.diplay.clusterbridge.WidgetBindingTest

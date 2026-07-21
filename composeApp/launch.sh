#!/bin/bash

# BOSS Application Launcher Script
# This script launches the BOSS application from the JAR file

# Get the directory where this script is located
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

# Set Java options
JAVA_OPTS="-Xmx2g -Xms512m"

# Add macOS specific options
if [[ "$OSTYPE" == "darwin"* ]]; then
    JAVA_OPTS="$JAVA_OPTS -Dapple.awt.application.appearance=system"
    JAVA_OPTS="$JAVA_OPTS -Dapple.awt.application.name=BOSS"
fi

# Set native library paths
JAVA_OPTS="$JAVA_OPTS -Djava.library.path=$SCRIPT_DIR/jcef-natives:$SCRIPT_DIR/pty4j-native"
JAVA_OPTS="$JAVA_OPTS -Dpty4j.preferred.native.folder=$SCRIPT_DIR/pty4j-native"
JAVA_OPTS="$JAVA_OPTS -Djcef.path=$SCRIPT_DIR/jcef-natives"

# Launch the application
java $JAVA_OPTS -jar "$SCRIPT_DIR/BOSS-8.8.0-all.jar" "$@"
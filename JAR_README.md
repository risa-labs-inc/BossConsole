# BOSS Application JAR Distribution

## Overview
This document describes how to build and run BOSS as a JAR file.

## Building the JAR

To create an executable JAR with all dependencies:

```bash
./gradlew :composeApp:createExecutableJar
```

This creates `BOSS-8.8.0-all.jar` in `composeApp/build/libs/` (approximately 194MB).

To create a distribution package with native libraries and launch scripts:

```bash
./gradlew :composeApp:packageJarWithNatives
```

This creates `BOSS-package-8.8.0.zip` in `composeApp/build/distributions/` (approximately 190MB).

## Running the JAR

### Direct Execution
```bash
java -jar BOSS-8.8.0-all.jar
```

### With Recommended Options
```bash
java -Xmx2g -Xms512m -jar BOSS-8.8.0-all.jar
```

### macOS Specific
```bash
java -Xmx2g -Xms512m -Dapple.awt.application.appearance=system -jar BOSS-8.8.0-all.jar
```

### Using Launch Scripts
The distribution package includes launch scripts:
- `launch.sh` for macOS/Linux
- `launch.bat` for Windows

```bash
./launch.sh
```

## System Requirements

- Java 17 or higher
- At least 2GB RAM (4GB recommended)
- macOS 10.15+, Windows 10+, or Linux

## Known Limitations

1. **Native Libraries**: JxBrowser and PTY4J require platform-specific native libraries. The JAR includes these, but they may need proper extraction.

2. **Performance**: The JAR version may have slightly slower startup times compared to native distributions.

3. **Security**: Some systems may require additional permissions for the JAR to access camera/microphone for video conferencing features.

## Troubleshooting

### "Profile already in use" Error
This is normal - the application will automatically create a temporary profile.

### Native Library Loading Issues
Ensure the JAR has permissions to extract native libraries to temporary directories:
```bash
java -Djava.io.tmpdir=/path/to/writable/directory -jar BOSS-8.8.0-all.jar
```

### Memory Issues
Increase heap size:
```bash
java -Xmx4g -jar BOSS-8.8.0-all.jar
```

## Distribution Structure

When unpacked, the distribution contains:
```
BOSS-package-8.8.0/
├── BOSS-8.8.0-all.jar      # Main executable JAR
├── launch.sh               # Unix/macOS launch script
├── launch.bat              # Windows launch script
├── jcef-natives/           # Browser native libraries
└── pty4j-native/           # Terminal native libraries
```
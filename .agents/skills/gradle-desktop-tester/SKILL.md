---
name: gradle-desktop-tester
description: Executes test suites cleanly without triggering Windows resource file locks or hanging daemons.
triggers:
  - "run tests"
  - "gradlew"
  - "desktopTest"
---
# Test Execution Workflow
- Windows Safe Flag: Run tests using:
  './gradlew :composeApp:desktopTest -x :composeApp:desktopProcessResources --no-daemon --tests "<Pattern>"'
- Clean Teardown: If files lock, kill lingering daemons with './gradlew --stop'.
- Clean Streams: Ensure unit tests verify that 'stdout' remains unpolluted during error cases.

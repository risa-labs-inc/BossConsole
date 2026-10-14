#!/usr/bin/env python3
"""Verify the real owned-XID source under KWin's headless Wayland/XWayland backend."""

import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--test-binary", type=Path, required=True)
    parser.add_argument("--awt-probe", type=Path)
    parser.add_argument("--scale", type=int, choices=(1, 2), default=1)
    args = parser.parse_args()
    binary = args.test_binary.resolve(strict=True)
    with tempfile.TemporaryDirectory(prefix="boss-capture-kwin-") as directory:
        runtime = Path(directory)
        runtime.chmod(0o700)
        result = runtime / "result"
        session_log = runtime / "session.log"
        session = runtime / "session.py"
        test_command = [str(binary)]
        if args.awt_probe:
            test_command = ["java", f"-Dsun.java2d.uiScale={args.scale}", "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
                            "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED",
                            str(args.awt_probe.resolve(strict=True)), str(binary)]
        session.write_text(
            "import subprocess\nfrom pathlib import Path\n"
            f"result = subprocess.run({json.dumps(test_command)}, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)\n"
            f"Path({json.dumps(str(session_log))}).write_text(result.stdout)\n"
            f"Path({json.dumps(str(result))}).write_text(str(result.returncode))\n"
        )
        env = dict(os.environ, XDG_RUNTIME_DIR=str(runtime), LIBGL_ALWAYS_SOFTWARE="1",
                   QT_QPA_PLATFORM="offscreen", XDG_SESSION_TYPE="wayland")
        for key in ("DISPLAY", "WAYLAND_DISPLAY", "DBUS_SESSION_BUS_ADDRESS"):
            env.pop(key, None)
        command = ["dbus-run-session", "--", "kwin_wayland", "--virtual", "--xwayland",
                   "--no-lockscreen", "--no-global-shortcuts", "--exit-with-session",
                   f"python3 {session}"]
        process = subprocess.Popen(command, env=env, start_new_session=True)
        try:
            process.wait(timeout=30)
            if session_log.exists():
                print(session_log.read_text())
            if not result.exists() or result.read_text() != "0":
                raise RuntimeError("KWin/XWayland capture fixture did not pass")
            print("KWin + XWayland exact-window capture and coordinate fixture passed")
        finally:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait()


if __name__ == "__main__":
    main()

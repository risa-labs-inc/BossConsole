#!/usr/bin/env python3
"""Run the production X11 capture fixture under an isolated headless Wayland compositor."""

import argparse
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--test-binary", type=Path, required=True)
    parser.add_argument("--awt-probe", type=Path)
    parser.add_argument("--scale", choices=("auto", "1", "2"), default="auto",
                        help="Optional Java UI scale override; auto uses compositor/JDK detection")
    parser.add_argument("--compositor-scale", type=int, choices=(1, 2), default=1)
    args = parser.parse_args()
    binary = args.test_binary.resolve(strict=True)
    with tempfile.TemporaryDirectory(prefix="boss-capture-wayland-") as directory:
        runtime = Path(directory)
        runtime.chmod(0o700)
        log = runtime / "weston.log"
        env = dict(os.environ, XDG_RUNTIME_DIR=str(runtime), LIBGL_ALWAYS_SOFTWARE="1")
        env.pop("DISPLAY", None)
        env.pop("WAYLAND_DISPLAY", None)
        weston = subprocess.Popen(
            ["weston", "--backend=headless-backend.so", "--use-pixman", "--xwayland",
             "--idle-time=0", "--socket=boss-capture-test", "--no-config",
             f"--scale={args.compositor_scale}", f"--log={log}"],
            env=env, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
        )
        try:
            deadline = time.monotonic() + 15
            display = None
            while weston.poll() is None and time.monotonic() < deadline:
                if log.exists():
                    match = re.search(r"xserver listening on display (:\d+)", log.read_text())
                    if match:
                        display = match.group(1)
                        break
                time.sleep(0.05)
            if display is None:
                raise RuntimeError("Weston did not expose its isolated XWayland display")
            test_env = dict(env, DISPLAY=display, WAYLAND_DISPLAY="boss-capture-test")
            command = [str(binary)]
            if args.awt_probe:
                scale = [] if args.scale == "auto" else [f"-Dsun.java2d.uiScale={args.scale}"]
                command = ["java", *scale, "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
                           "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED",
                           str(args.awt_probe.resolve(strict=True)), str(binary)]
            subprocess.run(command, env=test_env, check=True, timeout=20)
            print("Headless Weston + XWayland exact-window capture fixture passed")
        finally:
            weston.terminate()
            try:
                weston.wait(timeout=5)
            except subprocess.TimeoutExpired:
                weston.kill()
                weston.wait()
            if log.exists():
                print(log.read_text())


if __name__ == "__main__":
    main()

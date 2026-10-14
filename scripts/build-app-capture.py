#!/usr/bin/env python3
"""Build the owned-window helper locally and install only its executable resource."""

import argparse
import os
from pathlib import Path
import platform
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--build", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    system = platform.system()
    if system not in ("Linux", "Windows"):
        parser.error("the native helper builds on Linux and Windows only")
    configure = ["cmake", "-S", str(args.source), "-B", str(args.build), "-DCMAKE_BUILD_TYPE=Release", "-DBUILD_TESTING=OFF"]
    if system == "Windows":
        configure += ["-A", "ARM64" if platform.machine().lower() in ("arm64", "aarch64") else "x64"]
    subprocess.run(configure, check=True)
    subprocess.run(["cmake", "--build", str(args.build), "--config", "Release", "--parallel", "2"], check=True)
    executable = "boss-app-capture.exe" if system == "Windows" else "boss-app-capture"
    source = args.build / "Release" / executable if system == "Windows" else args.build / executable
    if not source.is_file():
        raise RuntimeError(f"native helper was not produced at {source}")
    args.output.mkdir(parents=True, exist_ok=True)
    destination = args.output / executable
    temporary = args.output / (executable + ".pending")
    shutil.copy2(source, temporary)
    temporary.chmod(0o755)
    os.replace(temporary, destination)


if __name__ == "__main__":
    main()

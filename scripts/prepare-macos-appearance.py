#!/usr/bin/env python3
"""Opt jpackage's prebuilt launcher into macOS 26 AppKit styling before distribution.

This changes SDK metadata, not the deployment target or launcher instructions.
Apple vtool rewrites each Mach-O slice; codesign then replaces the invalidated
bundle signature. Never run this against the user's installed application.
"""
import argparse
from pathlib import Path
import plistlib
import re
import shutil
import subprocess
import tempfile

MIN_SDK = (26, 0)


def run(*args):
    return subprocess.check_output(args, text=True).strip()


def version(value):
    return tuple(int(part) for part in value.split("."))


def build_versions(binary, arch):
    output = run("xcrun", "vtool", "-arch", arch, "-show-build", str(binary))
    minimum = re.search(r"^\s*(?:minos|version)\s+(\d+(?:\.\d+)*)\s*$", output, re.M)
    sdk = re.search(r"^\s*sdk\s+(\d+(?:\.\d+)*)\s*$", output, re.M)
    if minimum is None or sdk is None:
        raise RuntimeError(f"Missing macOS build version for {arch}: {output}")
    return minimum.group(1), sdk.group(1)


def prepare(app, identity, entitlements, verify_only=False):
    with (app / "Contents/Info.plist").open("rb") as stream:
        executable = plistlib.load(stream)["CFBundleExecutable"]
    if Path(executable).name != executable:
        raise RuntimeError("Invalid bundle executable")
    launcher = app / "Contents/MacOS" / executable
    arches = run("lipo", "-archs", str(launcher)).split()
    if not arches:
        raise RuntimeError("Launcher has no Mach-O architectures")
    original = {arch: build_versions(launcher, arch) for arch in arches}
    if verify_only:
        for arch, (minimum, sdk) in original.items():
            if version(sdk) < MIN_SDK:
                raise RuntimeError(f"{arch}: SDK {sdk} does not enable Liquid Glass")
            print(f"{arch}: verified minimum macOS {minimum}, SDK {sdk}", flush=True)
        run("codesign", "--verify", "--deep", "--strict", str(app))
        return
    with tempfile.TemporaryDirectory(prefix="boss-sdk-") as scratch:
        staged = Path(scratch) / executable
        shutil.copy2(launcher, staged)
        for arch, (minimum, sdk) in original.items():
            if version(sdk) < MIN_SDK:
                output = Path(scratch) / "patched"
                run("xcrun", "vtool", "-arch", arch, "-set-build-version",
                    "macos", minimum, "26.0", "-replace", "-output", str(output), str(staged))
                output.replace(staged)
            actual_min, actual_sdk = build_versions(staged, arch)
            if actual_min != minimum or version(actual_sdk) < MIN_SDK:
                raise RuntimeError(f"Invalid launcher metadata for {arch}")
            print(f"{arch}: minimum macOS {actual_min}, SDK {sdk} -> {actual_sdk}", flush=True)
        shutil.copyfile(staged, launcher)  # preserve the launcher's executable mode
    args = ["codesign", "--force", "--options", "runtime", "--sign", identity,
            "--entitlements", str(entitlements)]
    if identity != "-":
        args.append("--timestamp")
    # Signing the bundle signs its main executable and rebuilds the resource seal.
    # Nested libraries are unchanged; the existing native-signing finalizer follows.
    run(*args, str(app))
    run("codesign", "--verify", "--strict", str(app))
    for arch, (minimum, _) in original.items():
        actual_min, sdk = build_versions(launcher, arch)
        if actual_min != minimum or version(sdk) < MIN_SDK:
            raise RuntimeError(f"Signed launcher failed SDK verification for {arch}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("app", type=Path)
    parser.add_argument("--identity", default="-")
    parser.add_argument("--entitlements", type=Path, required=True)
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    prepare(args.app.resolve(strict=True), args.identity, args.entitlements.resolve(strict=True), args.verify_only)

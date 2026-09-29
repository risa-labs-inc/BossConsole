#!/usr/bin/env python3
"""Regression checks for launcher SDK opt-in without raising deployment targets."""
import importlib.util
from pathlib import Path
import plistlib
import shutil
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / 'prepare-macos-appearance.py'
spec = importlib.util.spec_from_file_location('appearance', SCRIPT)
appearance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(appearance)


class AppearanceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.app = Path(self.temp.name) / 'BOSS.app'
        self.launcher = self.app / 'Contents/MacOS/BOSS'
        self.launcher.parent.mkdir(parents=True)
        self.launcher.write_bytes(b'fixture launcher')
        self.launcher.chmod(0o755)
        with (self.app / 'Contents/Info.plist').open('wb') as stream:
            plistlib.dump({'CFBundleExecutable': 'BOSS'}, stream)
        self.entitlements = Path(self.temp.name) / 'entitlements.plist'
        self.entitlements.write_text('<plist/>')
        self.versions = {'arm64': ('11.0', '14.2'), 'x86_64': ('10.15', '26.1')}
        self.calls = []

    def run_tool(self, *args):
        self.calls.append(args)
        if args[0] == 'lipo':
            return ' '.join(self.versions)
        if args[:2] == ('xcrun', 'vtool'):
            arch = args[args.index('-arch') + 1]
            if '-show-build' in args:
                minimum, sdk = self.versions[arch]
                return f'platform MACOS\nminos {minimum}\nsdk {sdk}\n'
            index = args.index('-set-build-version')
            self.versions[arch] = (args[index + 2], args[index + 3])
            shutil.copyfile(args[-1], args[args.index('-output') + 1])
        return ''

    def test_universal_launcher_preserves_each_minimum_and_newer_sdk(self):
        with patch.object(appearance, 'run', side_effect=self.run_tool):
            appearance.prepare(self.app, '-', self.entitlements)
        self.assertEqual({'arm64': ('11.0', '26.0'), 'x86_64': ('10.15', '26.1')}, self.versions)
        self.assertEqual(0o755, self.launcher.stat().st_mode & 0o777)
        signing = [args for args in self.calls if args[0] == 'codesign' and '--sign' in args]
        self.assertEqual(1, len(signing))
        self.assertNotIn('--timestamp', signing[0])
        self.assertIn(str(self.entitlements), signing[0])

    def test_developer_signature_requests_timestamp(self):
        with patch.object(appearance, 'run', side_effect=self.run_tool):
            appearance.prepare(self.app, 'Developer ID fixture', self.entitlements)
        signing = next(args for args in self.calls if '--sign' in args)
        self.assertIn('--timestamp', signing)
        self.assertIn('Developer ID fixture', signing)

    def test_release_verifier_rejects_old_sdk_without_mutation(self):
        with patch.object(appearance, 'run', side_effect=self.run_tool):
            with self.assertRaisesRegex(RuntimeError, 'SDK 14.2'):
                appearance.prepare(self.app, '-', self.entitlements, verify_only=True)
        self.assertFalse(any('-set-build-version' in args or '--sign' in args for args in self.calls))

    def test_release_verifier_checks_nested_signatures(self):
        self.versions = {'arm64': ('11.0', '26.0')}
        with patch.object(appearance, 'run', side_effect=self.run_tool):
            appearance.prepare(self.app, '-', self.entitlements, verify_only=True)
        self.assertIn(('codesign', '--verify', '--deep', '--strict', str(self.app)), self.calls)
        self.assertFalse(any('-set-build-version' in args or '--sign' in args for args in self.calls))


if __name__ == '__main__':
    unittest.main()

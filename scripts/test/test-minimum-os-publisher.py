"""Run the real publisher against a fake curl; no network or credentials used."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

PUBLISHER = Path(__file__).resolve().parents[1] / 'publish-supabase-release.sh'


class MinimumOsPublisherTest(unittest.TestCase):
    def test_metadata_is_written_verified_and_legacy_publish_preserves_it(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'BOSS-9.5.0.jar').write_bytes(b'test artifact')
            floors = root / 'floors.properties'
            floors.write_text('macos=13.0\n')
            curl = root / 'curl'
            curl.write_text('''#!/usr/bin/env python3
import json, os, pathlib, sys
args = sys.argv[1:]
root = pathlib.Path(os.environ['PUBLISH_TEST_ROOT'])
if '--data' in args:
    row = json.loads(args[args.index('--data') + 1])
    (root / 'row.json').write_text(json.dumps(row))
    print('204', end='')
elif '-G' in args:
    projection = next(arg for arg in args if arg.startswith('select='))
    if os.environ.get('PRE_MIGRATION') and 'min_os' in projection:
        pathlib.Path(args[args.index('-o') + 1]).write_text('{"code":"42703"}')
        print('400', end='')
        sys.exit(0)
    row = json.loads((root / 'row.json').read_text())
    if not os.environ.get('PRE_MIGRATION'):
        row.setdefault('min_os', {'macos': '14.0'})
    if os.environ.get('BAD_READBACK'):
        row['min_os'] = {'macos': '99.0'}
    pathlib.Path(args[args.index('-o') + 1]).write_text(json.dumps([row]))
    print('200', end='')
else:
    print('200', end='')
''')
            curl.chmod(0o755)
            env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ['PATH'],
                       PUBLISH_TEST_ROOT=str(root), SUPABASE_URL='https://invalid.example',
                       SUPABASE_SERVICE_ROLE_KEY='synthetic-test-key')
            command = ['bash', str(PUBLISHER), 'boss', '9.5.0', 'stable', str(root), 'test']
            def run(extra, environment=env):
                return subprocess.run(command + extra, env=environment, capture_output=True, text=True)
            result = run([str(floors)])
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual({'macos': '13.0'}, json.loads((root / 'row.json').read_text())['min_os'])
            result = run([str(floors)], dict(env, BAD_READBACK='1'))
            self.assertNotEqual(0, result.returncode, 'mismatched metadata must fail publication')
            result = run([])
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertNotIn('min_os', json.loads((root / 'row.json').read_text()))
            result = run([], dict(env, PRE_MIGRATION='1'))
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertNotIn('min_os', json.loads((root / 'row.json').read_text()))


if __name__ == '__main__':
    unittest.main()

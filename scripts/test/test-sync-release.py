#!/usr/bin/env python3
"""Run the real release-discovery/download step against a fake GitHub API."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = (ROOT / '.github/workflows/sync-release.yml').read_text()
DOWNLOAD_STEP = WORKFLOW.split('    - name: Download release assets\n', 1)[1].split('\n    - name:', 1)[0]
DOWNLOAD_SCRIPT = textwrap.dedent(DOWNLOAD_STEP.split('      run: |\n', 1)[1])
RELEASE = {
    'tag_name': 'v9.5.43', 'name': 'BOSS 9.5.43', 'prerelease': False,
    'draft': False, 'created_at': '2026-10-09T12:44:23Z',
    'published_at': '2026-10-11T14:34:12Z', 'body': 'Release notes',
}


class SyncReleaseTest(unittest.TestCase):
    def run_download(self, releases=None, **overrides):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            mock_bin = root / 'bin'
            mock_bin.mkdir()
            gh = mock_bin / 'gh'
            gh.write_text('''#!/usr/bin/env python3
import json, os, pathlib, sys
args = sys.argv[1:]
if args[0] == 'api':
    if os.environ.get('MOCK_API_FAIL') == 'true':
        sys.exit(1)
    if 'releases?per_page=10' in args[1]:
        print(os.environ['MOCK_RELEASES'])
    elif '/releases/tags/' in args[1]:
        print('5' if '--jq' in args else os.environ['MOCK_RELEASE'])
    else:
        sys.exit('Unexpected API request: ' + repr(args))
elif args[:2] == ['release', 'download']:
    target = pathlib.Path(args[args.index('--dir') + 1])
    (target / 'BOSS-9.5.43-Universal.dmg').write_text('fixture')
else:
    sys.exit('Unexpected gh request: ' + repr(args))
''')
            gh.chmod(0o755)
            sleep = mock_bin / 'sleep'
            sleep.write_text('#!/bin/sh\nexit 0\n')
            sleep.chmod(0o755)
            output = root / 'outputs'
            env = dict(os.environ, PATH=f'{mock_bin}{os.pathsep}{os.environ["PATH"]}',
                       GITHUB_OUTPUT=str(output), GITHUB_EVENT_NAME='workflow_run',
                       WORKFLOW_RUN_CREATED_AT='2026-10-09T11:43:50Z',
                       WORKFLOW_RUN_STARTED_AT='2026-10-11T14:27:50Z',
                       GITHUB_REPOSITORY='example/BossConsole',
                       MOCK_RELEASES=json.dumps([RELEASE] if releases is None else releases),
                       MOCK_RELEASE=json.dumps(RELEASE))
            env.update(overrides)
            result = subprocess.run(['bash', '-c', DOWNLOAD_SCRIPT], cwd=root, env=env,
                                    text=True, capture_output=True, timeout=10)
            outputs = dict(line.split('=', 1) for line in output.read_text().splitlines())
            return result, outputs, (root / 'release-assets').exists()

    def assert_selected(self, result):
        process, outputs, downloaded = result
        self.assertEqual(0, process.returncode, process.stdout + process.stderr)
        self.assertEqual('true', outputs['release-found'])
        self.assertEqual('v9.5.43', outputs['RELEASE_TAG'])
        self.assertTrue(downloaded)

    def test_rerun_accepts_release_from_original_run(self):
        # Publication can precede the retry start, but follows the original run.
        release = dict(RELEASE, published_at='2026-10-09T14:34:12Z')
        self.assert_selected(self.run_download([release]))

    def test_old_tag_commit_date_does_not_hide_new_publication(self):
        self.assert_selected(self.run_download(
            WORKFLOW_RUN_CREATED_AT='2026-10-11T14:27:50Z'))

    def test_build_without_release_skips_all_publishing(self):
        for releases in ([], [dict(RELEASE, published_at='2026-10-08T14:34:12Z')],
                         [dict(RELEASE, draft=True, published_at=None)]):
            with self.subTest(releases=releases):
                process, outputs, downloaded = self.run_download(releases)
                self.assertEqual(0, process.returncode, process.stderr)
                self.assertEqual({'release-found': 'false'}, outputs)
                self.assertFalse(downloaded)
        # Every subsequent publishing step must honor the no-release output.
        downstream = WORKFLOW.split('\n    - name: Create public release\n', 1)[1]
        steps = ['Create public release\n' + downstream.split('\n    - name:', 1)[0]]
        steps.extend(downstream.split('\n    - name:')[1:])
        self.assertEqual(4, len(steps))
        for step in steps:
            self.assertIn("      if: steps.download.outputs.release-found == 'true'\n", step)

    def test_api_failure_fails_closed(self):
        process, outputs, downloaded = self.run_download(MOCK_API_FAIL='true')
        self.assertNotEqual(0, process.returncode)
        self.assertEqual('false', outputs['release-found'])
        self.assertFalse(downloaded)

    def test_missing_original_timestamp_fails_closed(self):
        process, outputs, downloaded = self.run_download(WORKFLOW_RUN_CREATED_AT='')
        self.assertNotEqual(0, process.returncode)
        self.assertEqual('false', outputs['release-found'])
        self.assertFalse(downloaded)
        self.assertIn('github.event.workflow_run.created_at', DOWNLOAD_STEP)
        self.assertNotIn('github.event.workflow_run.run_started_at', DOWNLOAD_STEP)

    def test_malformed_discovered_tag_fails_closed(self):
        process, outputs, downloaded = self.run_download([dict(RELEASE, tag_name='bad/tag')])
        self.assertNotEqual(0, process.returncode)
        self.assertEqual('false', outputs['release-found'])
        self.assertFalse(downloaded)

    def test_manual_recovery_dispatch(self):
        self.assert_selected(self.run_download(
            GITHUB_EVENT_NAME='workflow_dispatch', INPUT_RELEASE_TAG=' v9.5.43 '))

    def test_published_event(self):
        self.assert_selected(self.run_download(
            GITHUB_EVENT_NAME='release', EVENT_RELEASE_TAG='v9.5.43',
            EVENT_RELEASE_NAME='BOSS 9.5.43', EVENT_RELEASE_PRERELEASE='false',
            EVENT_RELEASE_BODY='Release notes'))


if __name__ == '__main__':
    unittest.main()

#!/usr/bin/env python3
"""Exercise the real publisher against a disposable local HTTP endpoint."""

import hashlib
import http.server
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import unittest
import zipfile

PUBLISHER = Path(__file__).resolve().parents[1] / "publish-plugin"
TOKEN = "synthetic-publisher-secret-not-a-real-token"
SPECIAL = "Quotes \"' \\ & | < > ^ ! % ( ) 雪\nsecond line"


class PublisherTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.jar = Path(self.temporary.name) / "plugin with spaces.jar"
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", "Plugin-Id: original\nPlugin-Version: 1.2.3\n")
            archive.writestr("META-INF/boss-plugin/plugin.json", json.dumps({"minBossVersion": "0.7.8"}))
        self.requests = []
        self.status = 404
        self.redirect = False
        self.observed_args = ""
        owner = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def handle_request(self):
                body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                owner.requests.append((self.command, self.path, dict(self.headers), body))
                if self.command == "GET":
                    owner.observed_args = subprocess.check_output(
                        ["ps", "-eo", "args"], text=True,
                    )
                    status, value = owner.status, {"message": TOKEN}
                elif self.path.endswith("/version"):
                    status, value = 201, {
                        "versionId": SPECIAL,
                        "uploadUrl": owner.base + "/upload?signature=" + TOKEN,
                    }
                else:
                    status, value = 200, {}
                if owner.redirect:
                    status = 307
                self.send_response(status)
                if owner.redirect:
                    self.send_header("Location", owner.base + "/must-not-follow")
                self.end_headers()
                self.wfile.write(json.dumps(value).encode())

            do_GET = handle_request
            do_POST = handle_request
            do_PUT = handle_request

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.base = "http://127.0.0.1:" + str(self.server.server_port)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop_server)

    def stop_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def run_publisher(self, *extra):
        command = [
            str(PUBLISHER), str(self.jar), "--url", self.base,
            "--plugin-id", "plugin/a?b#c %雪", "--display-name", SPECIAL,
            "--description", SPECIAL, "--changelog", SPECIAL,
            "--author", SPECIAL, "--tags", 'alpha,"quoted",雪',
            "--homepage-url", "https://example.invalid/plugin", *extra,
        ]
        if isinstance(self, PowerShellPublisherTest):
            command = [os.environ["BOSS_TEST_PWSH"], "-NoLogo", "-NoProfile", "-File",
                       str(PUBLISHER.with_suffix(".ps1")), "-JarPath", str(self.jar),
                       "-StoreUrl", self.base, "-PluginId", "plugin/a?b#c %雪",
                       "-DisplayName", SPECIAL, "-Description", SPECIAL, "-Changelog", SPECIAL,
                       "-Author", SPECIAL, "-Tags", 'alpha,"quoted",雪',
                       "-HomepageUrl", "https://example.invalid/plugin", *extra]
        result = subprocess.run(command, env={**os.environ, "BOSS_PLUGIN_STORE_TOKEN": TOKEN},
            cwd=self.temporary.name, capture_output=True, text=True, timeout=30)
        self.assertNotIn(TOKEN, result.stdout + result.stderr)
        return result

    def test_publishes_serialized_metadata_and_streamed_artifact(self):
        result = self.run_publisher()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn(TOKEN, self.observed_args)
        self.assertEqual(["GET", "POST", "POST", "PUT", "POST"], [r[0] for r in self.requests])
        self.assertEqual("/plugin%2Fa%3Fb%23c%20%25%E9%9B%AA", self.requests[0][1])
        create = json.loads(self.requests[1][3])
        self.assertEqual(SPECIAL, create["displayName"])
        self.assertEqual(SPECIAL, create["description"])
        self.assertEqual(SPECIAL, create["authorName"])
        self.assertEqual(["alpha", '"quoted"', "雪"], create["tags"])
        self.assertEqual({"version": "1.2.3", "changelog": SPECIAL, "minBossVersion": "0.7.8"},
                         json.loads(self.requests[2][3]))
        jar_bytes = self.jar.read_bytes()
        self.assertEqual(jar_bytes, self.requests[3][3])
        self.assertNotIn("Authorization", self.requests[3][2])
        self.assertEqual({"versionId": SPECIAL, "sha256": hashlib.sha256(jar_bytes).hexdigest(),
                          "jarSize": len(jar_bytes)}, json.loads(self.requests[4][3]))
        for request in (self.requests[0], self.requests[1], self.requests[2], self.requests[4]):
            self.assertEqual("Bearer " + TOKEN, request[2]["Authorization"])

    def test_refuses_errors_without_creating_plugin_or_logging_body(self):
        self.status = 403
        result = self.run_publisher()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("HTTP 403", result.stdout + result.stderr)
        self.assertEqual(1, len(self.requests))

    def test_refuses_redirect_without_forwarding_credentials(self):
        self.redirect = True
        result = self.run_publisher()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(1, len(self.requests))

    def test_rejects_legacy_token_arguments(self):
        result = self.run_publisher("--token", TOKEN)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], self.requests)


@unittest.skipUnless(os.environ.get("BOSS_TEST_PWSH"), "Set BOSS_TEST_PWSH to test the PowerShell entry point")
class PowerShellPublisherTest(PublisherTest):
    pass


if __name__ == "__main__":
    unittest.main()

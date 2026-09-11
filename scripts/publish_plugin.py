#!/usr/bin/env python3
"""Publish plugin artifacts without passing credentials to subprocesses."""

import argparse
import hashlib
import http.client
import json
import os
from pathlib import Path
import sys
import urllib.error
import urllib.parse
import urllib.request
import zipfile

MAX_RESPONSE_BYTES = 1024 * 1024
DEFAULT_STORE_URL = "https://api.risaboss.com/functions/v1/plugin-store"


class PublishError(Exception):
    """An operational error safe to display without response bodies or signed URLs."""


class NoRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Never forward an API bearer token or a signed upload across a redirect.
        return None


def request(method, url, headers, data=None):
    parsed = urllib.parse.urlsplit(url)
    local = parsed.hostname in {"localhost", "127.0.0.1", "::1"}
    if parsed.scheme != "https" and not (parsed.scheme == "http" and local):
        raise PublishError("Publishing requires HTTPS, except for a local development server.")
    if parsed.username or parsed.password or parsed.fragment:
        raise PublishError("Invalid publishing URL.")
    if any("\r" in value or "\n" in value for value in headers.values()):
        raise PublishError("Invalid newline in a publishing header.")
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        response = urllib.request.build_opener(NoRedirects).open(req, timeout=60)
    except urllib.error.HTTPError as error:
        response = error
    except (urllib.error.URLError, OSError, ValueError, http.client.HTTPException):
        raise PublishError("Publishing request failed before a response was received.") from None
    try:
        with response:
            body = response.read(MAX_RESPONSE_BYTES + 1)
            if len(body) > MAX_RESPONSE_BYTES:
                raise PublishError("Publishing response exceeded the size limit.")
            return response.code, body
    except (OSError, http.client.HTTPException):
        raise PublishError("Publishing response was interrupted.") from None


def api_request(method, url, token, anon_key, payload=None):
    headers = {"Authorization": "Bearer " + token, "Content-Type": "application/json"}
    if anon_key:
        headers["apikey"] = anon_key
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    status, body = request(method, url, headers, data)
    if status not in (200, 201, 404):
        raise PublishError(f"Plugin store request failed with HTTP {status}.")
    try:
        value = json.loads(body) if body else {}
    except (ValueError, UnicodeError):
        raise PublishError("Plugin store returned invalid JSON.") from None
    if not isinstance(value, dict):
        raise PublishError("Plugin store returned an invalid response object.")
    return status, value


def manifest_metadata(jar):
    with zipfile.ZipFile(jar) as archive:
        try:
            manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8")
        except KeyError:
            manifest = ""
        # JAR manifest continuation lines begin with one space.
        unfolded = manifest.replace("\r\n ", "").replace("\n ", "")
        fields = {}
        for line in unfolded.splitlines():
            if ": " in line:
                key, value = line.split(": ", 1)
                fields[key] = value
        try:
            metadata = json.loads(archive.read("META-INF/boss-plugin/plugin.json"))
        except KeyError:
            metadata = {}
        if not isinstance(metadata, dict):
            raise PublishError("Plugin metadata must be a JSON object.")
        return fields, metadata


def nonempty_string(value, field):
    if not isinstance(value, str) or not value:
        raise PublishError(f"Missing or invalid {field}.")
    return value


def publish(args):
    token = os.environ.get("BOSS_PLUGIN_STORE_TOKEN", "")
    if not token:
        raise PublishError("Set BOSS_PLUGIN_STORE_TOKEN in the environment.")
    anon_key = args.anon_key or os.environ.get("SUPABASE_ANON_KEY", "")
    if not anon_key and Path("local.properties").is_file():
        for line in Path("local.properties").read_text().splitlines():
            if line.startswith("SUPABASE_ANON_KEY="):
                anon_key = line.split("=", 1)[1]
    jar = Path(args.jar_path)
    if not jar.is_file():
        raise PublishError("Plugin JAR does not exist.")
    manifest, metadata = manifest_metadata(jar)
    plugin_id = nonempty_string(args.plugin_id or manifest.get("Plugin-Id"), "plugin ID")
    version = nonempty_string(args.version or manifest.get("Plugin-Version"), "version")
    display_name = args.display_name or manifest.get("Plugin-Name") or plugin_id
    minimum = nonempty_string(metadata.get("minBossVersion", "1.0.0"), "minBossVersion")
    base = (args.url or os.environ.get("BOSS_PLUGIN_STORE_URL") or DEFAULT_STORE_URL).rstrip("/")
    encoded_id = urllib.parse.quote(plugin_id, safe="")
    if encoded_id in (".", ".."):
        encoded_id = encoded_id.replace(".", "%2E")
    plugin_url = base + "/" + encoded_id

    digest = hashlib.sha256()
    size = 0
    with jar.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
            size += len(chunk)
    print("Checking plugin entry...")
    status, _ = api_request("GET", plugin_url, token, anon_key)
    if status == 404:
        homepage = args.homepage_url or metadata.get("homepageUrl") or manifest.get("Plugin-Url")
        homepage = nonempty_string(homepage, "homepage URL; provide --homepage-url for a new plugin")
        payload = {
            "pluginId": plugin_id,
            "displayName": display_name,
            "description": args.description or "",
            "authorName": args.author or None,
            "homepageUrl": homepage,
            "tags": [tag.strip() for tag in args.tags.split(",")] if args.tags else [],
        }
        created, _ = api_request("POST", base + "/publish", token, anon_key, payload)
        if created not in (200, 201):
            raise PublishError(f"Plugin creation failed with HTTP {created}.")

    print("Creating plugin version...")
    status, result = api_request("POST", plugin_url + "/version", token, anon_key, {
        "version": version, "changelog": args.changelog or "", "minBossVersion": minimum,
    })
    if status not in (200, 201):
        raise PublishError(f"Version creation failed with HTTP {status}.")
    version_id = nonempty_string(result.get("versionId"), "version ID in response")
    upload_url = nonempty_string(result.get("uploadUrl"), "upload URL in response")
    print("Uploading plugin JAR...")
    with jar.open("rb") as source:
        status, _ = request("PUT", upload_url, {
            "Content-Type": "application/octet-stream", "Content-Length": str(size),
        }, source)
    if status not in (200, 201):
        raise PublishError(f"Plugin upload failed with HTTP {status}.")
    status, _ = api_request("POST", base + "/version/finalize", token, anon_key, {
        "versionId": version_id, "sha256": digest.hexdigest(), "jarSize": size,
    })
    if status != 200:
        raise PublishError(f"Version finalization failed with HTTP {status}.")
    print("Plugin published successfully.")


def main():
    if any(arg == "--token" or arg.startswith("--token=") for arg in sys.argv[1:]):
        raise PublishError("Use BOSS_PLUGIN_STORE_TOKEN in the environment, without --token.")
    parser = argparse.ArgumentParser(description=__doc__, epilog="Requires Python 3. Authentication: BOSS_PLUGIN_STORE_TOKEN.")
    parser.add_argument("jar_path")
    for name in ("plugin-id", "display-name", "version", "author", "description", "changelog", "tags", "url", "anon-key", "homepage-url"):
        parser.add_argument("--" + name)
    publish(parser.parse_args())


if __name__ == "__main__":
    try:
        main()
    except PublishError as error:
        print(f"Error: {error}", file=sys.stderr)
        sys.exit(1)
    except (OSError, ValueError, zipfile.BadZipFile):
        print("Error: Could not read plugin metadata or artifact.", file=sys.stderr)
        sys.exit(1)

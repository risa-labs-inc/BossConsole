import base64
import json
import unittest

from app.cloudrun_auth import CloudRunIdentityTokenProvider, _jwt_expiry


def segment(body: dict) -> str:
    encoded = base64.urlsafe_b64encode(json.dumps(body).encode()).decode()
    return encoded.rstrip("=")


class FakeResponse:
    text = f"header.{segment({'exp': 1_800_000_000})}.signature"

    def raise_for_status(self) -> None:
        return None


class FakeHttp:
    def __init__(self) -> None:
        self.calls = []

    async def get(self, url: str, headers: dict[str, str]):
        self.calls.append((url, headers))
        return FakeResponse()


class CloudRunIdentityTests(unittest.IsolatedAsyncioTestCase):
    async def test_local_mode_needs_no_identity_token(self) -> None:
        http = FakeHttp()
        provider = CloudRunIdentityTokenProvider(http, "")

        self.assertIsNone(await provider.token())
        self.assertEqual(http.calls, [])

    async def test_fetches_and_caches_metadata_identity_token(self) -> None:
        http = FakeHttp()
        provider = CloudRunIdentityTokenProvider(http, "https://private-service.run.app")

        first = await provider.token()
        second = await provider.token()

        self.assertEqual(first, FakeResponse.text)
        self.assertEqual(second, first)
        self.assertEqual(len(http.calls), 1)
        self.assertEqual(http.calls[0][1], {"Metadata-Flavor": "Google"})


class JwtExpiryTests(unittest.TestCase):
    def test_reads_expiry_without_using_payload_for_authentication(self) -> None:
        token = f"header.{segment({'exp': 1_900_000_000})}.signature"
        self.assertEqual(_jwt_expiry(token), 1_900_000_000)


if __name__ == "__main__":
    unittest.main()

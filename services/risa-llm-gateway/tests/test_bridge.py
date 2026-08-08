import json
import unittest

from app import bridge


class BridgeTests(unittest.TestCase):
    def test_namespace_tools_round_trip(self) -> None:
        payload = {
            "tools": [
                {
                    "type": "namespace",
                    "name": "node_repl",
                    "description": "JavaScript execution",
                    "tools": [
                        {
                            "type": "function",
                            "name": "run",
                            "description": "Run JavaScript",
                            "parameters": {"type": "object"},
                        }
                    ],
                }
            ]
        }

        rewritten, aliases = bridge.flatten_namespace_tools(payload)
        alias = rewritten["tools"][0]["name"]
        self.assertTrue(alias.startswith("cwtool_"))

        response = {
            "output": [
                {
                    "type": "function_call",
                    "name": alias,
                    "call_id": "call-1",
                    "arguments": "{}",
                }
            ]
        }
        restored = json.loads(bridge.rewrite_json_body(json.dumps(response).encode(), aliases))
        call = restored["output"][0]
        self.assertEqual(call["namespace"], "node_repl")
        self.assertEqual(call["name"], "run")

    def test_prior_namespaced_call_is_rewritten(self) -> None:
        payload = {
            "tools": [
                {
                    "type": "namespace",
                    "name": "node_repl",
                    "tools": [
                        {"type": "function", "name": "run", "parameters": {}}
                    ],
                }
            ],
            "input": [
                {"type": "function_call", "namespace": "node_repl", "name": "run"}
            ],
        }

        rewritten, aliases = bridge.flatten_namespace_tools(payload)
        alias = next(iter(aliases))
        self.assertEqual(rewritten["input"][0]["name"], alias)
        self.assertNotIn("namespace", rewritten["input"][0])

    def test_large_tool_catalog_is_rejected(self) -> None:
        payload = {
            "tools": [
                {
                    "type": "namespace",
                    "name": "many",
                    "tools": [
                        {"type": "function", "name": f"tool_{index}", "parameters": {}}
                        for index in range(bridge.MAX_FLATTENED_TOOLS + 1)
                    ],
                }
            ]
        }

        with self.assertRaises(bridge.BridgeRequestError):
            bridge.flatten_namespace_tools(payload)

    def test_upstream_errors_are_truncated(self) -> None:
        body = json.dumps({"error": {"message": "x" * 5000}}).encode()
        message = bridge.extract_error_message(body)
        self.assertLessEqual(len(message), bridge.MAX_ERROR_CHARS + 32)
        self.assertIn("truncated", message)


if __name__ == "__main__":
    unittest.main()

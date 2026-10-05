"""
test_auth.py - Unit test for parser-service API key authentication.
"""
import os
import unittest
from fastapi import HTTPException
from main import verify_api_key

class TestParserAuth(unittest.TestCase):

    def setUp(self):
        self.orig_sec_enabled = os.environ.get("QE_SECURITY_ENABLED")
        self.orig_parser_sec = os.environ.get("PARSER_SECURITY_ENABLED")
        self.orig_keys = os.environ.get("QE_SECURITY_API_KEYS")
        self.orig_parser_keys = os.environ.get("PARSER_API_KEY")

    def tearDown(self):
        for k, v in [
            ("QE_SECURITY_ENABLED", self.orig_sec_enabled),
            ("PARSER_SECURITY_ENABLED", self.orig_parser_sec),
            ("QE_SECURITY_API_KEYS", self.orig_keys),
            ("PARSER_API_KEY", self.orig_parser_keys),
        ]:
            if v is not None:
                os.environ[k] = v
            elif k in os.environ:
                del os.environ[k]

    def test_security_disabled_allows_requests(self):
        os.environ["QE_SECURITY_ENABLED"] = "false"
        os.environ["QE_SECURITY_API_KEYS"] = ""
        os.environ.pop("PARSER_API_KEY", None)

        self.assertEqual(verify_api_key(None), None)
        self.assertEqual(verify_api_key("some-key"), "some-key")

    def test_security_enabled_missing_key_raises_401(self):
        os.environ["QE_SECURITY_ENABLED"] = "true"
        os.environ["QE_SECURITY_API_KEYS"] = "secret1,secret2"

        with self.assertRaises(HTTPException) as ctx:
            verify_api_key(None)
        self.assertEqual(ctx.exception.status_code, 401)

    def test_security_enabled_invalid_key_raises_401(self):
        os.environ["QE_SECURITY_ENABLED"] = "true"
        os.environ["QE_SECURITY_API_KEYS"] = "secret1,secret2"

        with self.assertRaises(HTTPException) as ctx:
            verify_api_key("wrong-secret")
        self.assertEqual(ctx.exception.status_code, 401)

    def test_security_enabled_valid_key_succeeds(self):
        os.environ["QE_SECURITY_ENABLED"] = "true"
        os.environ["QE_SECURITY_API_KEYS"] = "secret1,secret2"

        self.assertEqual(verify_api_key("secret1"), "secret1")
        self.assertEqual(verify_api_key("secret2"), "secret2")

if __name__ == "__main__":
    unittest.main()

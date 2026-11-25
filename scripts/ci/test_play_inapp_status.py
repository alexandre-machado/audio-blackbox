"""HTTP-mocked regression tests; each docstring states its production oracle."""

import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import Mock

spec = importlib.util.spec_from_file_location("play_status", Path(__file__).with_name("play-inapp-status.py"))
play = importlib.util.module_from_spec(spec)
spec.loader.exec_module(play)


def response(status, body):
    return Mock(status_code=status, text=json.dumps(body), json=Mock(return_value=body))


class PlayStatusTest(unittest.TestCase):
    def test_active_brl(self):
        """An ACTIVE purchase option must yield 0 and exact regional price/title output."""
        session = Mock()
        session.request.return_value = response(200, {
            "purchaseOptions": [{"purchaseOptionId": "buy", "state": "ACTIVE",
                "regionalPricingAndAvailabilityConfigs": [{"regionCode": "BR",
                    "price": {"currencyCode": "BRL", "units": "19", "nanos": 900000000},
                    "availability": "AVAILABLE"}],
                "newRegionsConfig": {"usdPrice": {"currencyCode": "USD", "units": "4"}}}],
            "listings": [{"languageCode": "pt-BR", "title": "Desbloquear Pro"}]})
        code, report = play.check(session)
        self.assertEqual(0, code)
        for expected in ("Exists: yes", "Purchase option buy: ACTIVE", "Region BR: BRL 19.90",
                         "Listing pt-BR: Desbloquear Pro", "New regions usdPrice: USD 4.00",
                         "Default price: not exposed"):
            self.assertIn(expected, report)
        session.request.assert_called_once_with("GET", play.URLS[0], timeout=30, allow_redirects=False)

    def test_inactive(self):
        """Inactive, draft, missing, and unspecified option states must never yield success."""
        for state in ("INACTIVE", "DRAFT", "STATE_UNSPECIFIED", "INACTIVE_PUBLISHED", None):
            with self.subTest(state=state):
                session = Mock()
                session.request.return_value = response(200, {"purchaseOptions": [{"state": state}]})
                code, report = play.check(session)
                self.assertEqual(2, code)
                self.assertIn("Exists: yes", report)

    def test_legacy(self):
        """Only a modern 404 triggers legacy GET; legacy micros/default price and state survive."""
        for state, expected_code in (("active", 0), ("inactive", 2), ("statusUnspecified", 2), (None, 2)):
            with self.subTest(state=state):
                session = Mock()
                money = {"currency": "BRL", "priceMicros": "14900000"}
                session.request.side_effect = [response(404, {}), response(200, {
                    **({"status": state} if state is not None else {}),
                    "defaultPrice": money, "prices": {"BR": money},
                    "listings": {"pt-BR": {"title": "Pro"}}})]
                code, report = play.check(session)
                self.assertEqual(expected_code, code)
                self.assertIn(f"Status: {state if state is not None else 'unspecified'}", report)
                self.assertIn("Default price: BRL 14.90", report)
                self.assertIn("Region BR: BRL 14.90", report)
                self.assertIn("Listing pt-BR: Pro", report)
                self.assertEqual([play.URLS[0], play.URLS[1]], [call.args[1] for call in session.request.call_args_list])

    def test_not_found(self):
        """Absence is reported only after both endpoints return 404."""
        session = Mock()
        session.request.side_effect = [response(404, {}), response(404, {})]
        self.assertEqual((3, "Exists: no (404 from both product APIs)"), play.check(session))
        self.assertEqual(2, session.request.call_count)

    def test_error_redaction(self):
        """API error details survive but token, private key and account values cannot escape."""
        session = Mock()
        key = "-----BEGIN PRIVATE KEY-----\nprivate-material\n-----END PRIVATE KEY-----\n"
        session.request.return_value = response(403, {"error": {"message":
            "Permission denied Bearer token-value account@example.com", "private_key": key,
            "access_token": "other-token", "detail": "token-value"}})
        code, report = play.check(session, (key, "token-value", "account@example.com"))
        self.assertEqual(1, code)
        self.assertIn("Permission denied", report)
        self.assertIn("HTTP 403", report)
        for secret in ("token-value", "account@example.com", "private-material", "other-token"):
            self.assertNotIn(secret, report)
        session.request.assert_called_once()

    def test_redact_literal_secret(self):
        """Literal secrets with quotes/newlines must be removed without the escaped rule."""
        secret = 'literal"secret\nvalue'
        self.assertEqual("detail [REDACTED]", play.redact("detail " + secret, (secret,)))

    def test_redact_json_escaped_secret(self):
        """Escaped secrets in ordinary JSON fields must be removed without literal matching."""
        secret = 'escaped"secret\nvalue'
        body = json.dumps({"detail": secret})
        self.assertEqual('{"detail": "[REDACTED]"}', play.redact(body, (secret,)))

    def test_redact_pem(self):
        """Unknown multiline PEM material must be removed without supplied secrets."""
        body = "key -----BEGIN PRIVATE KEY-----\nmaterial\n-----END PRIVATE KEY----- end"
        self.assertEqual("key [REDACTED] end", play.redact(body))

    def test_redact_bearer(self):
        """An unknown bearer token outside a sensitive JSON field must be removed."""
        self.assertEqual("detail Bearer [REDACTED] end",
                         play.redact("detail Bearer unknown-token end"))

    def test_redact_sensitive_json_fields(self):
        """Each sensitive JSON field must hide an unknown value without other rules."""
        for field in ("access_token", "refresh_token", "private_key", "client_secret", "authorization"):
            with self.subTest(field=field):
                body = json.dumps({field: 'unknown"value\nmaterial'})
                self.assertEqual(json.dumps({field: "[REDACTED]"}), play.redact(body))

    def test_credential_redaction_scope(self):
        """All credential fields/raw file/token are hidden; public metadata stays readable."""
        info = {
            "private_key": "key-material", "private_key_id": "key-identifier",
            "client_email": "account@example.com", "client_id": "client-identifier",
            "type": "service_account", "universe_domain": "googleapis.com",
            "token_uri": "https://oauth2.googleapis.com/token",
        }
        raw = json.dumps(info)
        secrets = play.credential_secrets(raw, info, "oauth-token")
        for value in (raw, "oauth-token", *(info[key] for key in
                      ("private_key", "private_key_id", "client_email", "client_id"))):
            with self.subTest(value=value):
                self.assertEqual("[REDACTED]", play.redact(value, secrets))
        public = "service_account https://androidpublisher.googleapis.com/path " + info["token_uri"]
        self.assertEqual(public, play.redact(public, secrets))

    def test_legacy_error(self):
        """Legacy permission/server failures must remain errors, not false absence."""
        session = Mock()
        session.request.side_effect = [response(404, {}), response(500, {"error": "unavailable"})]
        code, report = play.check(session)
        self.assertEqual(1, code)
        self.assertIn("unavailable", report)

    def test_get_only(self):
        """Every non-GET is rejected before the HTTP transport can run."""
        session = Mock()
        for method in ("POST", "PATCH", "PUT", "DELETE", "HEAD", "get", "OPTIONS"):
            with self.subTest(method=method), self.assertRaisesRegex(ValueError, "Only GET"):
                play.publisher_request(session, method, play.URLS[0])
        session.request.assert_not_called()

    def test_url_allowlist(self):
        """Even GET cannot target another host, edit or batch endpoint."""
        session = Mock()
        for url in ("https://example.com", play.BASE + "/edits", play.URLS[0] + ":batchGet"):
            with self.subTest(url=url), self.assertRaisesRegex(ValueError, "not allowlisted"):
                play.publisher_request(session, "GET", url)
        session.request.assert_not_called()

    def test_nanos_precision(self):
        """Sub-cent nanos must not be rounded away or processed as floating point."""
        self.assertEqual("BRL 19.900000001", play.price({"currencyCode": "BRL", "units": "19", "nanos": 900000001}))


if __name__ == "__main__":
    unittest.main()

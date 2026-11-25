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
        for state, expected_code in (("active", 0), ("inactive", 2)):
            with self.subTest(state=state):
                session = Mock()
                money = {"currency": "BRL", "priceMicros": "14900000"}
                session.request.side_effect = [response(404, {}), response(200, {
                    "status": state, "defaultPrice": money, "prices": {"BR": money},
                    "listings": {"pt-BR": {"title": "Pro"}}})]
                code, report = play.check(session)
                self.assertEqual(expected_code, code)
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

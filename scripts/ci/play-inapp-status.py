#!/usr/bin/env python3
"""Read pro_unlock from Play; never mutate publisher resources."""

import html
import json
import os
from pathlib import Path
import re
from decimal import Decimal

PACKAGE = "cc.machado.audioblackbox"
BASE = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PACKAGE}"
URLS = (f"{BASE}/oneTimeProducts/pro_unlock", f"{BASE}/inappproducts/pro_unlock")
SCOPE = "https://www.googleapis.com/auth/androidpublisher"


def publisher_request(session, method, url):
    """Allow only the two product reads, with redirects disabled."""
    if method not in {"GET"}:
        raise ValueError("Only GET is allowed")
    if url not in URLS:
        raise ValueError("Publisher URL is not allowlisted")
    return session.request(method, url, timeout=30, allow_redirects=False)


def redact(text, secrets=()):
    for secret in sorted(filter(None, secrets), key=len, reverse=True):
        for value in (secret, json.dumps(secret)[1:-1]):
            text = text.replace(value, "[REDACTED]")
    text = re.sub(r"-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----",
                  "[REDACTED]", text, flags=re.S)
    text = re.sub(r"(?i)Bearer\s+[^\s\"<>]+", "Bearer [REDACTED]", text)
    text = re.sub(r'(?i)("(?:access_token|refresh_token|private_key|client_secret|authorization)"\s*:\s*)"(?:\\.|[^"\\])*"',
                  r'\1"[REDACTED]"', text)
    return text


def price(value):
    if not value:
        return "not provided"
    if "priceMicros" in value:
        amount = Decimal(value["priceMicros"]) / Decimal(1_000_000)
    else:
        amount = Decimal(value.get("units", "0")) + Decimal(value.get("nanos", 0)) / Decimal(1_000_000_000)
    currency = value.get("currencyCode", value.get("currency", "?"))
    decimal = f"{amount:.2f}" if amount == amount.quantize(Decimal(".01")) else f"{amount:f}"
    return f"{currency} {decimal}"


def describe(product, legacy):
    lines = ["Exists: yes"]
    if legacy:
        state = product.get("status", "unspecified")
        active = state == "active"
        lines += [f"Status: {state}", f"Default price: {price(product.get('defaultPrice'))}"]
        for region, value in sorted(product.get("prices", {}).items()):
            lines.append(f"Region {region}: {price(value)}")
        listings = [(lang, item.get("title", "")) for lang, item in product.get("listings", {}).items()]
    else:
        options = product.get("purchaseOptions", [])
        active = any(option.get("state") == "ACTIVE" for option in options)
        lines += [f"Status: {'active' if active else 'not active'} (purchase options)",
                  "Default price: not exposed by oneTimeProducts; see regional/new-region prices"]
        for option in options:
            lines.append(f"Purchase option {option.get('purchaseOptionId', '?')}: {option.get('state', 'STATE_UNSPECIFIED')}")
            for region in option.get("regionalPricingAndAvailabilityConfigs", []):
                lines.append(f"Region {region.get('regionCode', '?')}: {price(region.get('price'))}; availability: {region.get('availability', 'unspecified')}")
            new = option.get("newRegionsConfig", {})
            for key in ("usdPrice", "eurPrice"):
                if key in new:
                    lines.append(f"New regions {key}: {price(new[key])}; availability: {new.get('availability', 'unspecified')}")
        listings = [(item.get("languageCode", "?"), item.get("title", "")) for item in product.get("listings", [])]
    lines += [f"Listing {lang}: {title}" for lang, title in sorted(listings)]
    return (0 if active else 2), "\n".join(lines)


def check(session, secrets=()):
    for index, url in enumerate(URLS):
        response = publisher_request(session, "GET", url)
        if response.status_code == 404:
            continue
        if response.status_code != 200:
            return 1, redact(f"Exists: unknown\nHTTP {response.status_code}\n{response.text}", secrets)
        code, report = describe(response.json(), legacy=index == 1)
        return code, redact(report, secrets)
    return 3, "Exists: no (404 from both product APIs)"


def credential_secrets(raw, info, token):
    """Redact credentials without hiding public domains or account metadata."""
    fields = ("private_key", "private_key_id", "client_email", "client_id")
    return [raw, token] + [info[key] for key in fields if isinstance(info.get(key), str)]


def main():
    try:
        from google.auth.transport.requests import AuthorizedSession
        from google.oauth2 import service_account

        raw = Path(os.environ["PLAY_STORE_JSON_KEY"]).read_text(encoding="utf-8")
        info = json.loads(raw)
        credentials = service_account.Credentials.from_service_account_info(info, scopes=[SCOPE])
        # Refresh separately: OAuth token exchange is authentication, not a publisher mutation.
        from google.auth.transport.requests import Request
        credentials.refresh(Request())
        secrets = credential_secrets(raw, info, credentials.token)
        # Avoid hidden refresh/retry so the redaction token stays current.
        with AuthorizedSession(credentials, max_refresh_attempts=0) as session:
            code, report = check(session, secrets)
    except Exception:
        # Auth/transport exceptions can contain credentials; never dump them or a traceback.
        code, report = 1, "Exists: unknown\nError: credential, transport, or response processing failed."
    print(report)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary:
            summary.write(f"## Check pro_unlock\n\nPackage: `{PACKAGE}`\n\n<pre>{html.escape(report)}</pre>\n\nExit code: {code}\n")
    return code


if __name__ == "__main__":
    raise SystemExit(main())

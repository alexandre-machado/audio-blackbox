"""Exercise production HCL through the real provider against a loopback API.

--mutations proves both historical defects fail during data-source reads, not
syntax validation. All mutations happen in disposable copies of the module.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / "infra/terraform/cloudflare-ci-token"
ACCOUNT = "a" * 32
ZONE = "b" * 32
ACCOUNT_SCOPE = "com.cloudflare.api.account"
ZONE_SCOPE = ACCOUNT_SCOPE + ".zone"
NAMES = ["Workers Scripts Write", "Workers Routes Write", "Zone Read"]
IDS = ["1" * 32, "2" * 32, "3" * 32]
GROUPS = [
    dict(id=id_, name=name, scopes=[scope], is_selectable=True)
    for id_, name, scope in zip(IDS, NAMES, [ACCOUNT_SCOPE, ZONE_SCOPE, ZONE_SCOPE])
]
# Overinclusive results exercise local exact-name/scope/selectability guards.
GROUPS += [dict(id="4" * 32, name="Workers Scripts Write Extra",
                scopes=[ACCOUNT_SCOPE], is_selectable=True),
           dict(id="5" * 32, name=NAMES[1], scopes=[ACCOUNT_SCOPE], is_selectable=True),
           dict(id="6" * 32, name=NAMES[2], scopes=[ZONE_SCOPE], is_selectable=False),
           dict(id="7" * 32, name=NAMES[2], scopes=None, is_selectable=None)]
ZONE_OBJECT = dict(id=ZONE, name="machado.cc", account={"id": ACCOUNT}, status="active")


class API(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def respond(self, result, status=200):
        body = json.dumps(dict(success=status == 200, errors=[], messages=[],
                               result=result, result_info=dict(page=1, per_page=1000,
                               count=len(result) if isinstance(result, list) else 1,
                               total_count=len(result) if isinstance(result, list) else 1,
                               total_pages=1))).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        url = urlsplit(self.path)
        query = parse_qs(url.query)
        self.server.requests.append(("GET", url.path, query))
        if url.path == f"/client/v4/accounts/{ACCOUNT}/tokens/permission_groups":
            name = query.get("name", [""])[0]
            self.respond([g for g in GROUPS if name in g["name"]])
        elif url.path == "/client/v4/zones":
            assert query.get("name") == ["machado.cc"], query
            assert query.get("account.id") == [ACCOUNT], query
            self.respond([ZONE_OBJECT])
        elif url.path == f"/client/v4/zones/{ZONE}":
            self.respond(ZONE_OBJECT)
        else:
            self.respond(None, 404)

    def do_POST(self):
        # Included for API completeness; plan must never create a token.
        self.server.requests.append(("POST", self.path, {}))
        if self.path == f"/client/v4/accounts/{ACCOUNT}/tokens":
            request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            self.respond(dict(request, id="c" * 32, value="t" * 40, status="active"))
        else:
            self.respond(None, 404)


def run_case(terraform, server, mutation=None):
    with tempfile.TemporaryDirectory(prefix="cloudflare-token-plan-") as directory:
        work = Path(directory)
        for source in [*MODULE.glob("*.tf"), MODULE / ".terraform.lock.hcl"]:
            # Explicit allowlist: never copy local overrides, state or inputs.
            if source.name in {"main.tf", "variables.tf", "versions.tf", "outputs.tf", ".terraform.lock.hcl"}:
                shutil.copyfile(source, work / source.name)
        main = work / "main.tf"
        if mutation == "urlencode":
            original = main.read_text()
            changed = re.sub(r"name\s*= each\.key", 'name = replace(urlencode(each.key), "+", "%20")', original)
            assert changed != original, "URL mutation did not change production HCL"
            main.write_text(changed)
        elif mutation == "old-data-source":
            main.write_text(main.read_text().replace(
                "cloudflare_account_api_token_permission_groups_list",
                "cloudflare_account_api_token_permission_groups").replace(".result", ".permission_groups"))
        # Clean environment prevents ambient tokens, TF_VARs, CLI args or logs.
        env = {"PATH": os.environ["PATH"], "HOME": str(work),
               "TF_CLI_CONFIG_FILE": str(work / "empty.tfrc"),
               "CHECKPOINT_DISABLE": "1", "TF_IN_AUTOMATION": "1",
               "CLOUDFLARE_API_TOKEN": "t" * 40,
               "CLOUDFLARE_BASE_URL": f"http://127.0.0.1:{server.server_port}/client/v4/",
               "TF_VAR_account_id": ACCOUNT, "TF_VAR_expires_on": "2099-01-01T00:00:00Z"}
        (work / "empty.tfrc").write_text("")

        def tf(*args, check=True):
            result = subprocess.run([terraform, *args], cwd=work, env=env,
                                    text=True, stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT, timeout=120)
            if check and result.returncode:
                raise AssertionError(result.stdout)
            return result

        tf("init", "-backend=false", "-input=false", "-lockfile=readonly", "-no-color")
        tf("validate", "-no-color")
        server.requests.clear()
        plan = tf("plan", "-input=false", "-no-color", "-out=mock.tfplan", check=False)
        if mutation:
            assert plan.returncode != 0, f"Mutation survived: {mutation}"
            assert "Expected exactly one selectable permission group" in plan.stdout, plan.stdout
            print(f"PASS mutation {mutation}: Error: Resource postcondition failed; Expected exactly one selectable permission group", flush=True)
            return
        assert plan.returncode == 0, plan.stdout
        document = json.loads(tf("show", "-json", "mock.tfplan").stdout)
        changes = [c for c in document["resource_changes"] if c["mode"] == "managed"]
        assert len(changes) == 1, changes
        change = changes[0]
        assert change["address"] == "cloudflare_account_token.ci", change
        assert change["change"]["actions"] == ["create"], change
        token = change["change"]["after"]
        assert token["account_id"] == ACCOUNT, token
        policies = token["policies"]
        assert len(policies) == 2, policies
        actual = {(p["effect"], tuple(sorted(g["id"] for g in p["permission_groups"])),
                   tuple(sorted(json.loads(p["resources"]).items()))) for p in policies}
        expected = {("allow", (IDS[0],), ((f"{ACCOUNT_SCOPE}.{ACCOUNT}", "*"),)),
                    ("allow", (IDS[1], IDS[2]), ((f"{ZONE_SCOPE}.{ZONE}", "*"),))}
        assert actual == expected, (actual, expected)
        assert not any(r[0] == "POST" for r in server.requests), server.requests
        queries = [r[2] for r in server.requests if r[1].endswith("permission_groups")]
        assert sorted(q["name"][0] for q in queries) == sorted(NAMES), queries
        print("PASS plan: exactly two least-privilege policies; literal names; zone list + detail decoded; no create calls", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mutations", action="store_true")
    args = parser.parse_args()
    terraform = shutil.which("terraform")
    if not terraform:
        parser.error("Terraform 1.17.2 must be on PATH")
    with ThreadingHTTPServer(("127.0.0.1", 0), API) as server:
        server.requests = []
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()  # Socket is already bound/listening; no sleep or readiness race.
        try:
            run_case(terraform, server)
            if args.mutations:
                for mutation in ("urlencode", "old-data-source"):
                    run_case(terraform, server, mutation)
        finally:
            server.shutdown()
            thread.join(timeout=5)


if __name__ == "__main__":
    main()

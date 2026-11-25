#!/usr/bin/env python3
"""Convert `am instrument -r -w` text (not -m protobuf) to JUnit XML.

Reporting only: exit status does not encode test success; the tier owns its gates.
Android reports total seconds in the final stream, but normally no per-test time.
Missing completion, crashes and empty runs are errors, never green empty suites.
"""

import argparse
import math
from pathlib import Path
import re
import xml.etree.ElementTree as ET


def xml_safe(value):
    return re.sub(r"[^\x09\x0a\x0d\x20-\ud7ff\ue000-\ufffd\U00010000-\U0010ffff]", "\ufffd", value)


def convert(transcript, phase):
    suite = ET.Element("testsuite", name=phase)
    bundle, results, active = {}, {}, {}
    continuation = None
    final_code = None
    problems = []

    def testcase(fields, code, message=None):
        case = ET.SubElement(suite, "testcase", classname=fields.get("class", "instrumentation"),
                             name=fields.get("test", "run"))
        # Optional custom runner metric, explicitly milliseconds; never invent timings.
        try:
            seconds = float(fields["duration_ms"]) / 1000
            if math.isfinite(seconds) and seconds >= 0:
                case.set("time", str(seconds))
        except (KeyError, ValueError):
            pass
        kind = {-2: "failure", -1: "error", -3: "skipped", -4: "skipped"}.get(code)
        if kind:
            detail = message or fields.get("stack") or fields.get("stream", "").strip()
            detail = detail or {-2: "Test failed", -1: "Instrumentation error",
                                -3: "Ignored", -4: "Assumption failed"}[code]
            ET.SubElement(case, kind, message=detail.splitlines()[0]).text = detail

    for line in xml_safe(transcript).splitlines():
        match = re.match(r"INSTRUMENTATION_(STATUS|RESULT): ([^=]+)=(.*)", line)
        if match:
            target = bundle if match[1] == "STATUS" else results
            target[match[2]] = match[3]
            continuation = (target, match[2])
        elif line.startswith("INSTRUMENTATION_STATUS_CODE:"):
            continuation = None
            try:
                code = int(line.split(":", 1)[1])
            except ValueError:
                problems.append(line)
                bundle = {}
                continue
            key = (bundle.get("class"), bundle.get("test"))
            if code == 1:
                active[key] = bundle.copy()
            elif code in (0, -1, -2, -3, -4):
                fields = active.pop(key, {}).copy()
                fields.update(bundle)
                if fields.get("class") and fields.get("test"):
                    testcase(fields, code)
                else:
                    problems.append("Test status missing class/method: " + repr(bundle))
            elif code != 2:  # Android's IN_PROGRESS informational bundle
                problems.append("Unknown status: " + line)
            bundle = {}
        elif line.startswith("INSTRUMENTATION_CODE:"):
            continuation = None
            final_code = line.split(":", 1)[1].strip()
        elif line.startswith(("INSTRUMENTATION_FAILED:", "INSTRUMENTATION_ABORTED:")):
            continuation = None
            problems.append(line)
        elif continuation:
            target, key = continuation
            target[key] += "\n" + line

    crash = results.get("shortMsg") or results.get("longMsg")
    if crash:
        problems.append(crash)
    if final_code != "-1":  # Activity.RESULT_OK; NOT the per-test error status
        problems.append("Instrumentation did not complete successfully (code %s)" % final_code)
    for fields in active.values():
        testcase(fields, -1, crash or "Test started but never completed")
    if not suite.findall("testcase"):
        problems.append("No test results were reported")
    if problems:
        testcase({}, -1, "\n".join(problems))
    for attr, tag in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
        suite.set(attr, str(len(suite.findall("testcase/" + tag))))
    suite.set("tests", str(len(suite.findall("testcase"))))
    elapsed = re.search(r"(?:^|\n)Time: ([0-9]+(?:\.[0-9]+)?)", results.get("stream", ""))
    if elapsed:
        suite.set("time", elapsed[1])
    ET.SubElement(suite, "system-out").text = xml_safe(transcript)
    return suite


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--phase", required=True)
    args = parser.parse_args()
    suite = convert(args.log.read_text(encoding="utf-8", errors="replace"), args.phase)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(suite).write(args.output, encoding="utf-8", xml_declaration=True)


if __name__ == "__main__":
    main()

"""Protocol fixtures, runnable with pytest or unittest; no Android runtime needed.

These are representative transcripts, not claimed device captures.
"""
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

SCRIPT = Path(__file__).with_name("am-instrument-to-junit.py")
SPEC = importlib.util.spec_from_file_location("converter", SCRIPT)
CONVERTER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CONVERTER)

START = """INSTRUMENTATION_STATUS: class=example.AudioTest
INSTRUMENTATION_STATUS: test=records
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS_CODE: 1
"""
END = """INSTRUMENTATION_RESULT: stream=
Time: 1.25

OK (1 test)

INSTRUMENTATION_CODE: -1
"""


def status(code, extra=""):
    return ("INSTRUMENTATION_STATUS: class=example.AudioTest\n"
            "INSTRUMENTATION_STATUS: test=records\n" + extra +
            "INSTRUMENTATION_STATUS_CODE: %s\n" % code)


class ConverterTest(unittest.TestCase):
    def parse(self, text):
        # Round-trip also proves every fixture produces well-formed XML.
        return ET.fromstring(ET.tostring(CONVERTER.convert(text, "phase1")))

    def test_all_pass(self):
        """Oracle: completed success retains identity/count and reported timings."""
        suite = self.parse(START + status(0, "INSTRUMENTATION_STATUS: duration_ms=250\n") + END)
        self.assertEqual(suite.attrib, dict(name="phase1", tests="1", failures="0",
                                          errors="0", skipped="0", time="1.25"))
        self.assertEqual(suite.find("testcase").attrib,
                         dict(classname="example.AudioTest", name="records", time="0.25"))

    def test_failure_with_stack(self):
        """Oracle: -2 yields failure on the named test, preserving multiline assertion."""
        stack = "java.lang.AssertionError: expected <1> & got 0\n\tat example.AudioTest.records(AudioTest.kt:42)"
        suite = self.parse(START + status(-2, "INSTRUMENTATION_STATUS: stack=" + stack + "\n") + END)
        self.assertEqual(suite.get("failures"), "1")
        self.assertEqual(suite.find("testcase/failure").text, stack)
        self.assertEqual(suite.find("testcase").get("name"), "records")

    def test_error(self):
        """Oracle: per-test -1 is an error, unlike final RESULT_OK (-1)."""
        suite = self.parse(START + status(-1) + END)
        self.assertEqual(suite.get("errors"), "1")

    def test_ignored(self):
        """Oracle: ignored tests may lack a start and must count as skipped."""
        suite = self.parse(status(-3) + END)
        self.assertEqual(suite.get("skipped"), "1")
        self.assertEqual(suite.find("testcase/skipped").get("message"), "Ignored")

    def test_assumption_failure(self):
        """Oracle: assumptions skip rather than fail and preserve the reason."""
        suite = self.parse(START + status(-4, "INSTRUMENTATION_STATUS: stack=Assumption violated\n") + END)
        self.assertEqual(suite.get("skipped"), "1")
        self.assertEqual(suite.get("failures"), "0")
        self.assertEqual(suite.find("testcase/skipped").text, "Assumption violated")

    def test_process_crash_mid_run(self):
        """Oracle: unfinished named test and process crash cannot become green XML."""
        suite = self.parse(START + "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n")
        self.assertGreater(int(suite.get("errors")), 0)
        self.assertEqual(suite.find("testcase").get("name"), "records")
        self.assertIn("Process crashed", suite.find("testcase/error").text)

    def test_zero_tests(self):
        """Oracle: even a nominally successful empty runner creates a failing case."""
        suite = self.parse(END.replace("1 test", "0 tests"))
        self.assertEqual(suite.get("tests"), "1")
        self.assertEqual(suite.get("errors"), "1")
        self.assertIn("No test results", suite.find("testcase/error").text)

    def test_truncated_after_success(self):
        """Oracle: completed tests cannot hide a missing final runner result."""
        suite = self.parse(START + status(0))
        self.assertEqual(suite.get("errors"), "1")

    def test_crash_after_success(self):
        """Oracle: a crash after a completed test still produces an error."""
        suite = self.parse(START + status(0) + "INSTRUMENTATION_RESULT: shortMsg=Process crashed\n" + END)
        self.assertEqual(suite.get("errors"), "1")

    def test_cli(self):
        """Oracle: CLI creates parent directories and serializes safe, failing XML."""
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / "run.log"
            out = Path(tmp) / "reports" / "run.xml"
            log.write_text(START + status(-2, "INSTRUMENTATION_STATUS: stack=bad\x00value\n") + END)
            result = subprocess.run([sys.executable, str(SCRIPT), str(log), str(out),
                                     "--phase", "splice"], check=False)
            self.assertEqual(result.returncode, 0)  # Reporting does not replace shell gates.
            suite = ET.parse(out).getroot()
            self.assertEqual(suite.get("name"), "splice")
            self.assertEqual(suite.get("failures"), "1")


if __name__ == "__main__":
    unittest.main()

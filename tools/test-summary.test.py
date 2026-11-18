# Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
import importlib.util
from pathlib import Path
import tempfile
import unittest


spec = importlib.util.spec_from_file_location("test_summary", Path(__file__).with_name("test-summary.py"))
summary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(summary)


class TestSummary(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.module = Path(self.directory.name) / "plugin"
        self.reports = self.module / "build/test-results/test"
        self.reports.mkdir(parents=True)

    def report(self, xml, name="TEST-suite.xml"):
        (self.reports / name).write_text(xml, encoding="utf-8")

    def render(self, outcome="success", coverage=None):
        return summary.summarize(self.module, outcome, coverage)

    def test_missing_reports_do_not_pass(self):
        self.assertIn("Tests not run", self.render("skipped"))
        self.assertIn("No JUnit reports produced", self.render("failure"))
        self.assertIn("no passing result", self.render())

    def test_gradle_reports_aggregate_outcomes(self):
        self.report('<testsuite tests="4" failures="1" errors="1" skipped="1">'
                    '<testcase classname="Example" name="fails"><failure/></testcase></testsuite>')
        self.report('<testsuite tests="14" failures="0" errors="0" skipped="0"/>', "TEST-other.xml")
        self.assertIn("| 18 | 1 | 1 | 1 |", self.render("failure"))
        self.assertIn("Example\\.fails", self.render("failure"))

    def test_namespaced_wrappers_do_not_double_count(self):
        self.report('<testsuites xmlns="urn:junit" tests="100" failures="10">'
                    '<testsuite tests="99"><testsuite tests="3" failures="1" errors="0" skipped="1"/>'
                    '</testsuite><testsuite tests="2" failures="0" errors="1" skipped="0"/></testsuites>')
        self.assertIn("| 5 | 1 | 1 | 1 |", self.render())

    def test_case_counts_when_attributes_absent(self):
        self.report('<testsuite><testcase/><testcase><error/></testcase>'
                    '<testcase><skipped/></testcase></testsuite>')
        self.assertIn("| 3 | 0 | 1 | 1 |", self.render())

    def test_failure_names_are_escaped_and_capped(self):
        self.report('<testsuite>' + ''.join(
            '<testcase classname="&lt;script&gt;" name="[link](url)|**bad**&#10;# title"><failure/></testcase>'
            for _ in range(12)) + '</testsuite>')
        rendered = self.render("failure")
        self.assertEqual(10, sum(line.startswith("- ") for line in rendered.splitlines()))
        self.assertIn('&lt;script&gt;', rendered)
        self.assertIn(r'\[link\]\(url\)\|\*\*bad\*\*', rendered)
        self.assertNotIn("\n# title", rendered)

    def test_invalid_and_empty_reports_are_explicit(self):
        self.report('<broken')
        self.report('<testsuite tests="-1"/>', "TEST-negative.xml")
        self.assertIn("2 unreadable or invalid", self.render())
        self.assertIn("no passing result", self.render())

    def test_optional_coverage(self):
        coverage = Path(self.directory.name) / "coverage.xml"
        self.assertNotIn("coverage", self.render())
        self.assertIn("coverage unavailable", self.render(coverage=coverage))
        coverage.write_text('<report><package><counter type="LINE" covered="99" missed="0"/></package>'
                            '<counter type="LINE" covered="3" missed="1"/></report>')
        self.assertIn("Line coverage: 3/4 (75.00%)", self.render(coverage=coverage))

    def test_explicit_node_report_pattern(self):
        report = Path(self.directory.name) / "node-junit.xml"
        report.write_text('<testsuites tests="2"><testsuite tests="2" failures="0" errors="0" skipped="0"/></testsuites>')
        rendered = summary.summarize("Node tooling", "success", reports_pattern=str(report))
        self.assertIn("| 2 | 0 | 0 | 0 |", rendered)


if __name__ == "__main__":
    unittest.main()

"""Prevent accidental raw-JDBC fixture bypasses of the shared authorization boundary."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCES = ROOT / "test/src/dev/brikk/trino/doris"


class LiveSafetyBoundaryTest(unittest.TestCase):
    def test_raw_fixture_connections_are_centralized(self):
        offenders = [str(path.relative_to(ROOT)) for path in SOURCES.glob("*.kt")
                     if path.name != "DorisTestSafety.kt" and
                     re.search(r"DriverManager\s*\.\s*getConnection\s*\(", path.read_text())]
        self.assertEqual(offenders, [])
        query_runner = (SOURCES / "DorisQueryRunner.kt").read_text()
        self.assertIn("fun openDirectConnection(): Connection = DorisTestCluster.openRootConnection()", query_runner)
        self.assertIn("DorisTestSafety.connectSingle", (SOURCES / "DorisTestCluster.kt").read_text())

    def test_lifecycle_actions_use_verified_ids_not_names_or_ports(self):
        overlay = (SOURCES / "TestDorisMultiFeFailover.kt").read_text()
        self.assertNotIn("tcpReachable", overlay)
        self.assertIn("DorisTestSafety.overlayRequested()", overlay)
        self.assertIn("DorisTestSafety.actionContainerId(container, starting = false)", overlay)
        self.assertIn("DorisTestSafety.actionContainerId(container, starting = true)", overlay)
        self.assertNotRegex(overlay, r'docker\("(?:stop|start)",\s*container\)')


if __name__ == "__main__":
    unittest.main()

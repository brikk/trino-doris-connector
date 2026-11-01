"""The real shared fixture entry must fail before Docker/SQL without explicit opt-in."""
import os
from pathlib import Path
import subprocess
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]


class LiveHarnessGuardTest(unittest.TestCase):
    def test_default_access_is_refused_at_the_real_fixture_boundary(self):
        env = dict(os.environ)
        env.pop("DORIS_TEST_ALLOW_MUTATION", None)
        env.pop("DORIS_TEST_ALLOW_FAILOVER", None)
        started = time.time_ns()
        result = subprocess.run(
            [str(ROOT / "gradlew"), "test", "--tests", "*TestDorisCaseFoldLocal", "--no-daemon", "--console=plain",
             "-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8"],
            cwd=ROOT, env=env, capture_output=True, text=True, timeout=300)
        self.assertNotEqual(result.returncode, 0, "Unauthorized live fixture execution unexpectedly passed")
        reports = "\n".join(p.read_text() for p in (ROOT / "build/test-results/test").glob("TEST-*.xml")
                            if p.stat().st_mtime_ns >= started)
        self.assertIn("DORIS_TEST_ALLOW_MUTATION=1", reports, result.stdout + result.stderr)
        self.assertIn("Live tests may DROP/rebuild fixture schemas", reports)


if __name__ == "__main__":
    unittest.main()

"""Exercise startup orchestration without touching Docker or the real assembly."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]


class ManualPluginTest(unittest.TestCase):
    def test_exact_current_plugin_and_verification_failure(self):
        with tempfile.TemporaryDirectory(prefix="manual plugin ") as temp:
            root = Path(temp)
            sandbox = root / "compose/trino"
            sandbox.mkdir(parents=True)
            shutil.copy2(ROOT / "compose/trino/up.sh", sandbox / "up.sh")
            current = root / "build/trino-plugin/trino-doris-484-0.2.0-SNAPSHOT"
            old = root / "build/trino-plugin/trino-doris-483-0.1.0"
            old.mkdir(parents=True)
            (old / "obsolete.jar").write_text("must never be selected")
            current.mkdir()
            with zipfile.ZipFile(current / "trino-doris-connector-484-0.2.0-SNAPSHOT.jar", "w") as jar:
                jar.writestr("META-INF/services/io.trino.spi.Plugin", "dev.brikk.trino.doris.DorisPlugin")
            (root / "gradlew").write_text(
                '#!/bin/bash\nset -eu\nprintf "%s\\n" "$@" > gradle-args.txt\n'
                '[[ "${FAIL_VERIFY:-0}" == 0 ]] || exit 17\n'
                'printf "%s\\n" "$PWD/build/trino-plugin/trino-doris-484-0.2.0-SNAPSHOT" > build/manual-plugin-path.txt\n')
            (root / "gradlew").chmod(0o755)
            binaries = root / "bin"
            binaries.mkdir()
            (binaries / "docker").write_text(
                '#!/bin/bash\nprintf "%s\\n" "$*" >> "$TEST_ROOT/docker-calls.txt"\n'
                '[[ "$1" != logs ]] || echo "Registering connector doris"\nexit 0\n')
            (binaries / "docker").chmod(0o755)
            java = root / "jdk/bin"
            java.mkdir(parents=True)
            (java / "java").write_text("#!/bin/sh\nexit 0\n")
            (java / "java").chmod(0o755)
            env = dict(os.environ, PATH=str(binaries) + os.pathsep + os.environ["PATH"],
                       JAVA_HOME=str(java.parent), TEST_ROOT=str(root))
            for args in [[], ["--rebuild"]]:
                result = subprocess.run(["bash", str(sandbox / "up.sh"), *args], env=env,
                                        capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn(str(current), result.stdout)
                self.assertFalse((sandbox / ".plugin-snapshot/obsolete.jar").exists())
                self.assertEqual(len(list((sandbox / ".plugin-snapshot").glob("*.jar"))), 1)
                task_args = (root / "gradle-args.txt").read_text().splitlines()
                self.assertIn("prepareManualPlugin", task_args)
                self.assertEqual("--rerun-tasks" in task_args, bool(args))
            calls = (root / "docker-calls.txt").read_text()
            result = subprocess.run(["bash", str(sandbox / "up.sh")], env=dict(env, FAIL_VERIFY="1"),
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 17)
            # Even a stale path manifest cannot bypass the failed verification.
            self.assertEqual((root / "docker-calls.txt").read_text(), calls)


if __name__ == "__main__":
    unittest.main()

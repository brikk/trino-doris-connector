"""Actual Gradle regressions: no live Doris needed. Owns only injected output files."""
from pathlib import Path
import subprocess
import tempfile
import tomllib
import unittest

ROOT = Path(__file__).resolve().parents[2]
TRINO = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())["versions"]["trino"]


class PackagingGuardsTest(unittest.TestCase):
    def gradle(self, *args, success=True, message=None):
        command = [str(ROOT / "gradlew"), "--no-daemon", "--console=plain",
                   "-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8", *args]
        result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=300)
        if success:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        if message:
            self.assertIn(message, result.stdout + result.stderr)
        return result

    def test_incremental_inventory_and_bytes(self):
        self.gradle("prepareManualPlugin")
        plugin = Path((ROOT / "build/manual-plugin-path.txt").read_text().strip())
        stale = plugin / "injected-stale-dependency-0.0.0.jar"
        self.assertFalse(stale.exists(), "Refuse to overwrite an existing file")
        try:
            stale.write_bytes(b"obsolete dependency")
            self.gradle("verifyPluginAssembly", "-x", "pluginAssemble", success=False,
                        message="Plugin inventory differs from resolved inputs")
            # No clean and no project version change: Sync must remove the stale JAR.
            self.gradle("prepareManualPlugin")
            self.assertFalse(stale.exists())
            connector = next(plugin.glob("trino-doris-connector-*.jar"))
            with connector.open("ab") as jar:
                jar.write(b"injected-corruption")
            self.gradle("verifyPluginAssembly", "-x", "pluginAssemble", success=False,
                        message="Plugin JAR differs from resolved input")
        finally:
            # Restore the owned assembly through its normal Sync task, not by
            # deleting/replacing arbitrary files in other versioned directories.
            self.gradle("prepareManualPlugin")

    def test_release_grammar_and_abi(self):
        self.gradle("validateReleaseVersion", f"-Pversion={TRINO}-0.2.0")
        self.gradle("validateReleaseVersion", f"-Pversion={TRINO}-0.2.0-rc.1")
        self.gradle("validateReleaseVersion", f"-Pversion={int(TRINO) + 1}-0.2.0", success=False,
                    message="but the build targets Trino")
        for version in [f"{TRINO}-2", f"{TRINO}-01.0.0", f"{TRINO}-1.0.0/escape"]:
            self.gradle("help", f"-Pversion={version}", success=False, message="Plugin version must be")
        self.gradle("validateReleaseVersion", f"-Pversion={TRINO}-0.2.0-SNAPSHOT", success=False,
                    message="Cannot publish a SNAPSHOT")

    def test_resolved_compile_abi_is_checked_not_just_the_catalog(self):
        with tempfile.TemporaryDirectory(prefix="trino-abi-guard-") as temp:
            init = Path(temp) / "force-wrong-spi.gradle"
            init.write_text(
                "gradle.projectsEvaluated { gradle.rootProject.configurations.compileClasspath."
                f"resolutionStrategy.force('io.trino:trino-spi:{int(TRINO) - 1}') }}\n")
            self.gradle("validateReleaseVersion", f"-Pversion={TRINO}-0.2.0", "-I", str(init),
                        success=False, message="Resolved Trino compile dependencies do not all match")


if __name__ == "__main__":
    unittest.main()

import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("release_version", ROOT / ".github/scripts/release_version.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ReleaseVersionTest(unittest.TestCase):
    def test_valid_labels(self):
        for prefix in ["release-", "release/"]:
            for version in ["484-0.2.0", "484-1.0.0-rc.1", "484-1.0.0-alpha-beta"]:
                self.assertEqual(MODULE.release_version(prefix + version), version)

    def test_unsafe_and_malformed_labels(self):
        for suffix in ["", "484-2", "484-01.0.0", "484-1.0.0-01", "484-1.0.0-SNAPSHOT",
                       "484-1.0.0+build", "484-1.0.0/x", "484-1.0.0\nother=value",
                       '484-1.0.0-$(touch hacked)', '484-1.0.0-";exit 0', "484-1.0.0."]:
            with self.subTest(suffix=suffix), self.assertRaises(ValueError):
                MODULE.release_version("release-" + suffix)
        with self.assertRaises(ValueError):
            MODULE.release_version("main")


if __name__ == "__main__":
    unittest.main()

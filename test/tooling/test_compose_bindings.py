"""Rendered Compose contract, without starting or mutating containers."""
import json
import os
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]


class ComposeBindingsTest(unittest.TestCase):
    def test_default_and_explicit_bindings(self):
        for filename, count in [("docker-compose.yml", 2), ("multi-fe/docker-compose.yml", 6),
                                ("trino/docker-compose.yml", 1)]:
            for address in [None, "192.0.2.10"]:
                with self.subTest(compose=filename, address=address):
                    env = dict(os.environ, TRINO_DORIS_PLUGIN_DIR=str(ROOT / "resources"))
                    for key in ["DORIS_BIND_ADDRESS", "TRINO_BIND_ADDRESS"]:
                        env.pop(key, None)
                        if address:
                            env[key] = address
                    result = subprocess.run(
                        ["docker", "compose", "-f", str(ROOT / "compose" / filename),
                         "config", "--format", "json"], env=env, check=True, capture_output=True, text=True)
                    config = json.loads(result.stdout)
                    ports = [p for s in config["services"].values() for p in s.get("ports", [])]
                    self.assertEqual(len(ports), count)
                    self.assertTrue(all(p["host_ip"] == (address or "127.0.0.1") for p in ports))


if __name__ == "__main__":
    unittest.main()

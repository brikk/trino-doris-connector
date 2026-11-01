"""Validate a release branch before exposing its suffix to later workflow steps."""
import os
from pathlib import Path
import re


def release_version(branch):
    if branch.startswith("release-"):
        version = branch[len("release-"):]
    elif branch.startswith("release/"):
        version = branch[len("release/"):]
    else:
        raise ValueError("Expected a release- or release/ branch")
    number = r"(?:0|[1-9][0-9]*)"
    identifier = rf"(?:{number}|[0-9]*[A-Za-z-][0-9A-Za-z-]*)"
    pattern = rf"[1-9][0-9]*-{number}\.{number}\.{number}(?:-{identifier}(?:\.{identifier})*)?"
    if not re.fullmatch(pattern, version) or "snapshot" in version.lower():
        raise ValueError("Expected <trino>-<major>.<minor>.<patch> with an optional non-SNAPSHOT SemVer prerelease")
    return version


if __name__ == "__main__":
    version = release_version(os.environ["GITHUB_REF_NAME"])
    with Path(os.environ["GITHUB_OUTPUT"]).open("a") as output:
        output.write(f"version={version}\ntag=v{version}\n")
    print(f"Release candidate label: {version}; compiled ABI check follows before live setup")

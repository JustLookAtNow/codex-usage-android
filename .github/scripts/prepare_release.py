"""Validate a release tag and preserve its commit message as release notes."""

import os
import re
import subprocess
from pathlib import Path


def version_from_tag(tag):
    match = re.fullmatch(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", tag)
    if not match:
        raise ValueError("Release tag must have the form v1.2.3 without leading zeros")
    major, minor, patch = map(int, match.groups())
    code = major * 1_000_000 + minor * 1_000 + patch + 1
    if minor > 999 or patch > 999 or not 1 <= code <= 2_100_000_000:
        raise ValueError("Version exceeds supported Android versionCode range")
    return tag[1:], code


def write_metadata(tag, output_path, notes_path):
    version, code = version_from_tag(tag)

    def git(*args):
        return subprocess.check_output(["git", *args])

    if git("rev-parse", "HEAD") != git("rev-parse", f"refs/tags/{tag}^{{commit}}"):
        raise ValueError("Checked-out commit does not match the release tag")
    Path(notes_path).write_bytes(git("log", "-1", "--format=%B", "HEAD"))
    with Path(output_path).open("a", encoding="utf-8") as output:
        output.write(f"version_name={version}\nversion_code={code}\n")
        output.write(f"apk_name=CodexUsage-{tag}-arm64-v8a.apk\n")


if __name__ == "__main__":
    write_metadata(
        os.environ["RELEASE_TAG"],
        os.environ["GITHUB_OUTPUT"],
        Path(os.environ["RUNNER_TEMP"]) / "release-notes.md",
    )

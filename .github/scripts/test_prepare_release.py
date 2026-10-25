import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from prepare_release import version_from_tag, write_metadata


class ReleaseMetadataTest(unittest.TestCase):
    def test_versions_are_ordered_and_within_android_limits(self):
        self.assertEqual(version_from_tag("v0.0.0"), ("0.0.0", 1))
        self.assertEqual(version_from_tag("v1.2.3"), ("1.2.3", 1_002_004))
        self.assertLess(version_from_tag("v1.999.999")[1], version_from_tag("v2.0.0")[1])
        self.assertEqual(version_from_tag("v2099.999.999")[1], 2_100_000_000)

    def test_rejects_invalid_tags(self):
        for tag in ("1.2.3", "v01.2.3", "v1.2.3-beta", "v1.1000.0", "v1.0.1000",
                    "v2100.0.0", "v1.2.3\n", "v1.2.3; echo bad"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                version_from_tag(tag)

    def test_both_tag_types_use_commit_message_and_reject_wrong_checkout(self):
        message = '发布：修复额度\n\n正文含 `backticks`、$(touch injected)、"引号" 和 $HOME。\n'
        with tempfile.TemporaryDirectory() as directory:
            with patch.dict(os.environ, {"GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull}):
                def git(*args, input=None):
                    return subprocess.check_output(
                        ["git", "-C", directory, *args], input=input, stderr=subprocess.DEVNULL,
                    )

                git("init")
                git("config", "user.name", "Release test")
                git("config", "user.email", "release@example.invalid")
                git("-c", "commit.gpgsign=false", "commit", "--allow-empty", "-F", "-", input=message.encode())
                git("tag", "v1.2.3")
                git("-c", "tag.gpgsign=false", "tag", "-a", "v1.2.4", "-m", "Must not become release notes")
                output = Path(directory) / "output"
                notes = Path(directory) / "notes.md"
                old_directory = Path.cwd()
                try:
                    os.chdir(directory)
                    for tag in ("v1.2.3", "v1.2.4"):
                        write_metadata(tag, output, notes)
                        self.assertEqual(notes.read_bytes(), git("log", "-1", "--format=%B", "HEAD"))
                        self.assertIn(message.strip(), notes.read_text())
                        self.assertFalse((Path(directory) / "injected").exists())
                    self.assertIn("apk_name=CodexUsage-v1.2.3-arm64-v8a.apk", output.read_text())
                    git("-c", "commit.gpgsign=false", "commit", "--allow-empty", "-m", "Another commit")
                    with self.assertRaises(ValueError):
                        write_metadata("v1.2.3", output, notes)
                finally:
                    os.chdir(old_directory)


if __name__ == "__main__":
    unittest.main()

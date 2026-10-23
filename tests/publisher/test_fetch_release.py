import hashlib
import io
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("fetch_release", Path(__file__).parents[2] / "tools" / "fetch_release.py")
fetcher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(fetcher)


def release():
    assets = []
    for number, module in enumerate(sorted(fetcher.MODULES), 1):
        name = f"folkways-{module}-neoforge-1.21.1-0.1.0.jar"
        data = module.encode()
        assets.append({"id": number, "name": name, "size": len(data), "digest": "sha256:" + hashlib.sha256(data).hexdigest(),
                       "state": "uploaded", "updated_at": "2026-10-23T00:00:00Z",
                       "browser_download_url": "https://github.com/IzakyL/folkways/releases/download/nightly/" + name})
    return {"id": 100, "tag_name": "nightly", "target_commitish": "a" * 40,
            "updated_at": "2026-10-23T00:00:00Z", "draft": False, "assets": assets}


class FetchReleaseTests(unittest.TestCase):
    def temporary(self):
        root = Path.cwd() / "work" / "publisher-tmp"
        root.mkdir(parents=True, exist_ok=True)
        return tempfile.TemporaryDirectory(dir=root)

    def test_real_proxy_route_shape(self):
        urls = fetcher.routes("https://api.github.com/repos/IzakyL/folkways/releases/tags/nightly")
        self.assertEqual(urls[1], "https://github.davisye.cn/_github/api.github.com/repos/IzakyL/folkways/releases/tags/nightly")
        with self.assertRaises(ValueError):
            fetcher.routes("https://evil.example/asset.jar")

    def test_bad_asset_digest_falls_back_to_other_route(self):
        class Response(io.BytesIO):
            url = "https://github.com/IzakyL/folkways/releases/download/nightly/x.jar"

        with patch.object(fetcher.urllib.request, "urlopen", side_effect=[Response(b"bad!"), Response(b"good")]) as opened:
            data = fetcher.read(Response.url, 4, False, hashlib.sha256(b"good").hexdigest())
        self.assertEqual(data, b"good")
        self.assertEqual(opened.call_count, 2)

    def test_html_api_response_falls_back(self):
        class Response(io.BytesIO):
            url = "https://api.github.com/repos/IzakyL/folkways/releases/tags/nightly"

        with patch.object(fetcher.urllib.request, "urlopen", side_effect=[Response(b"<html>error</html>"), Response(b'{"id":1}')]):
            self.assertEqual(fetcher.metadata(Response.url, False), {"id": 1})

    def test_matching_release_outputs_verified_provenance(self):
        r = release()
        with self.temporary() as t:
            with patch.object(fetcher, "metadata", side_effect=[r, r]), patch.object(fetcher, "commit_for_release", return_value="a" * 40), patch.object(
                fetcher, "read", side_effect=lambda url, maximum, mirror, digest: next(a["name"].split("-")[1].encode() for a in r["assets"] if a["browser_download_url"] == url)
            ):
                result = fetcher.fetch("IzakyL/folkways", "nightly", Path(t) / "fetch")
            self.assertEqual(len(result["files"]), 5)
            self.assertTrue((Path(t) / "fetch" / "provenance.json").exists())

    def test_changed_nightly_discards_entire_output(self):
        before, after = release(), release()
        after["id"] += 1
        with self.temporary() as t:
            output = Path(t) / "fetch"
            with patch.object(fetcher, "metadata", side_effect=[before, after]), patch.object(fetcher, "commit_for_release", return_value="a" * 40), patch.object(
                fetcher, "read", side_effect=lambda url, maximum, mirror, digest: next(a["name"].split("-")[1].encode() for a in before["assets"] if a["browser_download_url"] == url)
            ):
                with self.assertRaisesRegex(ValueError, "changed"):
                    fetcher.fetch("IzakyL/folkways", "nightly", output)
            self.assertFalse(output.exists())

    def test_missing_digest_refuses_to_create_output(self):
        r = release()
        r["assets"][0]["digest"] = None
        with self.temporary() as t:
            output = Path(t) / "fetch"
            with patch.object(fetcher, "metadata", return_value=r), patch.object(fetcher, "commit_for_release", return_value="a" * 40):
                with self.assertRaisesRegex(ValueError, "lacks SHA256"):
                    fetcher.fetch("IzakyL/folkways", "nightly", output)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()

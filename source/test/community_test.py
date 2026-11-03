"""Проверки границы доверия: рисунки, пакеты заявок и пути каталога."""
import base64
import copy
import importlib.util
import json
from pathlib import Path
import sys
import unittest
import zlib

ROOT = Path(__file__).resolve().parents[1]
SITE = ROOT / "repo" if (ROOT / "repo").is_dir() else ROOT.parent
sys.path.insert(0, str(ROOT))
import community as c


class CommunityTest(unittest.TestCase):
    def setUp(self):
        self.skin = json.loads((ROOT / "skins/cartoon.json").read_text(encoding="utf-8"))

    def test_all_current_art_compatible(self):
        for p in list((ROOT / "skins").glob("*.json")) + list((SITE / "skins").glob("*.json")):
            with self.subTest(p=p.name):
                c.pe_validate(p.read_text(encoding="utf-8"))

    def test_attack_fields(self):
        cases = [dict(script="exec('bad')"), dict(url="https://evil.test/a.py"),
                 dict(id="../../plugin"), dict(id=["bad"]), dict(palette={"w": "https://x"}),
                 dict(lid=[]), dict(format=True), dict(eye=["w" * 33]), dict(eye=["w"] * 33),
                 dict(eye=["?"]), dict(behavior={"speed": float("nan")}),
                 dict(behavior={"speed": 10**300}), dict(behavior={"callback": "os.system"}),
                 dict(layout={"eyes": [{"x": 120, "scale": 3}]}), dict(layout={"eyes": [{}]*13}),
                 dict(name="fake\u202egnp.exe"), dict(tags=["x"] * 9), dict(frames={"run": ["w"]})]
        for changes in cases:
            with self.subTest(changes=changes):
                d = copy.deepcopy(self.skin)
                d.update(changes)
                with self.assertRaises(ValueError):
                    c.pe_validate(json.dumps(d))

    def test_resource_and_parser_limits(self):
        for text in ('{"id":"a","id":"b"}', '{"a":' * 1100 + '0' + '}' * 1100,
                     '{"x":NaN}', 'x' * 65537, '{"name":"' + 'я' * 40000 + '"}'):
            with self.subTest(text=text[:30]), self.assertRaises(ValueError):
                c.pe_validate(text)

    def test_submission_roundtrip_and_bombs(self):
        packed = c.pe_pack(json.dumps(self.skin))
        self.assertEqual(c.pe_unpack("```\n" + packed + "\n```"), self.skin)
        bomb = c.PE_MARKER + base64.urlsafe_b64encode(zlib.compress(b'x' * 500000)).decode()
        trailing = c.PE_MARKER + base64.urlsafe_b64encode(zlib.compress(b'{}') + b'payload').decode()
        for value in (bomb, trailing, packed + "\n" + packed, c.PE_MARKER + "not_valid", packed[:-5]):
            with self.subTest(value=value[:40]), self.assertRaises(ValueError):
                c.pe_unpack(value)

    def test_filters(self):
        a = copy.deepcopy(self.skin)
        b = copy.deepcopy(self.skin)
        b.update(id="other", name="Другие", author="@friend", tags=["жуткие"])
        skins = [json.dumps(a), json.dumps(b)]
        self.assertEqual(c.pe_filtered(skins, "FRIEND"), skins[1:])
        self.assertEqual(c.pe_filtered(skins, tag="жуткие"), skins[1:])
        self.assertEqual(c.pe_filtered(skins, installed={a["id"]}, only_new=True), skins[1:])
        self.assertEqual(c.pe_filtered(skins, "не существует"), [])

    def test_prepare_cannot_replace_or_impersonate(self):
        spec = importlib.util.spec_from_file_location("process_submission", SITE / "community/process_submission.py")
        m = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(m)
        issue = {"number": 42, "user": {"id": 1234, "login": "friend"},
                 "body": c.pe_pack(json.dumps(self.skin))}
        sid, text, index = m.prepare(issue, {"skins": []})
        self.assertTrue(sid.startswith("u1234_i42_"))
        self.assertEqual(json.loads(text)["author"], "@friend")
        self.assertEqual(index["skins"][0]["sha256"], c.pe_hashlib.sha256(text.encode()).hexdigest())
        with self.assertRaises(ValueError):
            m.prepare(issue, index)
        issue["number"] = 43
        with self.assertRaises(ValueError):
            m.prepare(issue, index)

    def test_workflow_rejects_changed_or_unapproved_issue(self):
        import tempfile
        from unittest.mock import patch
        spec = importlib.util.spec_from_file_location("submission_test", SITE / "community/process_submission.py")
        m = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(m)
        issue = {"number": 45, "body": c.pe_pack(json.dumps(self.skin)),
                 "user": {"login": "friend", "id": 123}, "state": "open"}
        event = {"issue": issue, "action": "labeled", "label": {"name": "approved-skin"},
                 "sender": {"login": "stranger"}, "repository": {"owner": {"login": "owner"}}}
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "event.json"
            path.write_text(json.dumps(event),encoding="utf-8")
            with patch.dict(m.os.environ, {"GITHUB_EVENT_PATH": str(path)}), patch.object(m, "api") as api:
                m.main()
                api.assert_not_called()
            event["sender"]["login"] = "owner"
            path.write_text(json.dumps(event),encoding="utf-8")
            changed = dict(issue, body=issue["body"] + " changed")
            with patch.dict(m.os.environ, {"GITHUB_EVENT_PATH": str(path)}), patch.object(m, "api", return_value=changed) as api:
                with self.assertRaises(ValueError):
                    m.main()
                self.assertEqual(api.call_count, 1)
                self.assertEqual(api.call_args.args[0], "GET")

    def test_approved_submission_only_creates_two_data_files(self):
        import tempfile
        from unittest.mock import patch
        spec = importlib.util.spec_from_file_location("submission_success", SITE / "community/process_submission.py")
        m = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(m)
        issue = {"number": 76, "body": c.pe_pack(json.dumps(self.skin)),
                 "user": {"login": "friend", "id": 123}, "state": "open"}
        event = {"issue": issue, "action": "labeled", "label": {"name": "approved-skin"},
                 "sender": {"login": "owner"}, "repository": {"owner": {"login": "owner"}, "default_branch": "main"}}
        calls = []
        def api(method, path, data=None):
            calls.append((method,path,data))
            if path.startswith("/issues/"):
                return issue
            if path.startswith("/pulls?"):
                return []
            if path.startswith("/git/ref/heads/"):
                return {"object": {"sha": "base"}}
            if path == "/git/commits/base":
                return {"tree": {"sha": "tree"}}
            return {"sha": "created"}
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/"event.json"
            path.write_text(json.dumps(event),encoding="utf-8")
            with patch.dict(m.os.environ, {"GITHUB_EVENT_PATH":str(path),"GITHUB_REPOSITORY":"owner/repo"}), patch.object(m,"api",side_effect=api):
                m.main()
        tree=next(data for method,path,data in calls if path=="/git/trees")
        self.assertEqual({x["path"] for x in tree["tree"]}, {"skins/u123_i76_cartoon.json","index.json"})
        self.assertTrue(any(path=="/pulls" and method=="POST" for method,path,_ in calls))
        self.assertFalse(any(method=="PATCH" for method,_,_ in calls))


if __name__ == "__main__":
    unittest.main()

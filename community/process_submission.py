"""Заявка из Issues -> проверенный JSON -> PR. Код заявки никогда не исполняется."""
import importlib.util
import json
import os
from pathlib import Path
import re
import urllib.request
import urllib.error

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("pe_community", ROOT / "source/community.py")
validation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validation)


def api(method, path, data=None):
    repo = os.environ["GITHUB_REPOSITORY"]
    if not re.fullmatch(r"[\w.-]+/[\w.-]+", repo):
        raise ValueError("Invalid repository")
    payload = json.dumps(data).encode() if data is not None else None
    req = urllib.request.Request("https://api.github.com/repos/" + repo + path, data=payload, method=method,
        headers={"Authorization": "Bearer " + os.environ["GH_TOKEN"], "Accept": "application/vnd.github+json",
                 "X-GitHub-Api-Version": "2022-11-28", "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def prepare(issue, index):
    data = validation.pe_unpack(issue.get("body") or "")
    uid = issue["user"]["id"]
    number = issue["number"]
    if type(uid) is not int or type(number) is not int or uid <= 0 or number <= 0:
        raise ValueError("Invalid issue identity")
    login = issue["user"]["login"]
    if not re.fullmatch(r"[A-Za-z0-9-]{1,39}", login):
        raise ValueError("Invalid GitHub login")
    # Заявка не может выдавать себя за другого автора или заменить существующий набор.
    sid = ("u%d_i%d_" % (uid, number) + data["id"])[:40]
    data["id"] = sid
    data["author"] = "@" + login
    canonical = validation.pe_canonical(data)
    validation.pe_validate(canonical)
    skins = index.get("skins", [])
    if not isinstance(skins, list) or len(skins) >= 120:
        raise ValueError("Каталог достиг лимита 120 наборов: нужна пагинация")
    if any(s.get("id") == sid for s in skins):
        raise ValueError("Эта заявка уже добавлена")
    digest = validation.pe_hashlib.sha256(canonical.encode()).hexdigest()
    # Идентичный рисунок с другим названием всё равно считается дубликатом.
    art = {k: v for k, v in data.items() if k not in ("id", "name", "author", "description", "tags")}
    art_hash = validation.pe_hashlib.sha256(validation.pe_canonical(art).encode()).hexdigest()
    if any(s.get("art_sha256") == art_hash for s in skins):
        raise ValueError("Такой рисунок уже есть в каталоге")
    entry = {k: data.get(k, [] if k == "tags" else "") for k in ("id", "name", "author", "description", "tags")}
    entry.update(url="skins/" + sid + ".json", sha256=digest, art_sha256=art_hash,
                 submission=number)
    updated = dict(index)
    updated["skins"] = skins + [entry]
    return sid, canonical, updated


def main():
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text(encoding="utf-8"))
    issue = event["issue"]
    body = issue.get("body") or ""
    if "pull_request" in issue or validation.PE_MARKER not in body:
        return
    number = issue["number"]
    if type(number) is not int or number <= 0:
        return
    # Ветка исходников и валидатор всегда из default branch, не из PR автора.
    index = json.loads((ROOT / "index.json").read_text(encoding="utf-8"))
    if event.get("action") == "opened":
        try:
            prepare(issue, index)
            try:
                api("POST", "/labels", {"name": "approved-skin", "color": "6f42c1",
                    "description": "Owner approved the submitted eyes"})
            except urllib.error.HTTPError as e:
                if e.code != 422:
                    raise
            message = ("Формат прошёл проверку. В каталог ещё не опубликовано. "
                       "Владелец проверит рисунок и при одобрении поставит метку `approved-skin`. "
                       "После этого появится PR с JSON и записью каталога. "
                       "Автор набора в каталоге: аккаунт GitHub отправителя.")
        except (ValueError, TypeError, KeyError) as e:
            message = "Заявка отклонена проверкой формата: " + str(e)[:300] + ". Исправьте набор в плагине и создайте новую заявку."
        api("POST", "/issues/%d/comments" % number, {"body": message})
        return
    # Зелёный чек не публикует ничего сам: право дать команду имеет только владелец.
    if event.get("action") != "labeled" or event.get("label", {}).get("name") != "approved-skin":
        return
    if event.get("sender", {}).get("login") != event["repository"]["owner"]["login"]:
        return
    # Перечитываем тело: правки заявки после проверки тоже проходят весь валидатор.
    approved_body = issue.get("body") or ""
    issue = api("GET", "/issues/%d" % number)
    if (issue.get("body") or "") != approved_body or issue.get("state") != "open":
        raise ValueError("Заявка изменена после одобрения или закрыта: одобрите заново")
    sid, canonical, updated = prepare(issue, index)
    branch = "community/issue-%d" % number
    prs = api("GET", "/pulls?state=all&head=" + event["repository"]["owner"]["login"] + ":" + branch)
    if prs:
        return
    base = event["repository"]["default_branch"]
    commit = api("GET", "/git/ref/heads/" + base)["object"]["sha"]
    tree = api("GET", "/git/commits/" + commit)["tree"]["sha"]
    entries = []
    for path, content in (("skins/" + sid + ".json", canonical + "\n"),
                          ("index.json", json.dumps(updated, ensure_ascii=False, indent=2) + "\n")):
        entries.append({"path": path, "mode": "100644", "type": "blob", "content": content})
    new_tree = api("POST", "/git/trees", {"base_tree": tree, "tree": entries})["sha"]
    new_commit = api("POST", "/git/commits", {"message": "Add community eyes from issue #%d" % number,
                     "tree": new_tree, "parents": [commit]})["sha"]
    try:
        api("POST", "/git/refs", {"ref": "refs/heads/" + branch, "sha": new_commit})
    except urllib.error.HTTPError as e:
        if e.code != 422:
            raise
        # Повтор после сбоя создания PR: свою существующую ветку не перезаписываем.
    try:
        api("POST", "/pulls", {"title": "Community eyes #%d" % number, "head": branch, "base": base,
             "body": "Closes #%d\n\nJSON проверен. Проверьте рисунок перед объединением.\n"
                     "После изменения index.json в другом PR обновите эту ветку перед merge." % number})
    except urllib.error.HTTPError as e:
        if e.code != 403:
            raise
        # GitHub по умолчанию может запрещать автоматическое создание PR.
        compare = "https://github.com/" + os.environ["GITHUB_REPOSITORY"] + "/compare/" + base + "..." + branch
        api("POST", "/issues/%d/comments" % number,
            {"body": "Проверенный набор подготовлен. Создайте PR по ссылке: " + compare})


if __name__ == "__main__":
    main()

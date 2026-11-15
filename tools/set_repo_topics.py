# -*- coding: utf-8 -*-
"""设置 GitHub 仓库 description / topics / homepage。

用法：
    python tools/set_repo_topics.py <PAT>

需要一个具备 repo 权限的 classic PAT 或 fine-grained token（需 Administration: write）。
令牌只从命令行参数读，不落盘。
"""
import json
import sys
import urllib.error
import urllib.request

REPO = "CYQawa/YunX"
API = "https://api.github.com"

DESCRIPTION = "杂鱼Max (Zayu) ✨ 安卓网盘解析 + 视频平台解析下载 App ✨ 支持 15 家网盘，Range 分片并发下载"
HOMEPAGE = "https://zayu.pw/downloads/Zayu-latest.apk"

TOPICS = [
    # —— 20 个上限，按搜索价值排序，前面的一定写进去 ——
    # 项目标识
    "zayu",
    # 核心能力（用户真会在 GitHub 搜的词）
    "netdisk",
    "cloud-drive",
    "share-link",
    "share-link-parser",
    "downloader",
    "resume-download",
    "multi-thread-download",
    "range-request",
    # 技术栈（技术向搜索）
    "android",
    "kotlin",
    "jetpack-compose",
    "material3",
    "liquid-glass",
    # 覆盖平台（挑最有辨识度的，不是全列）
    "quark-drive",
    "baidu-netdisk",
    "xunlei",
    "pikpak",
    "lanzou",
    # 领域
    "video-parser",
]

# 备选：GitHub 上限 20 个，以下不写入，留作后续替换候选
TOPICS_SPARE = [
    "uc-drive", "pan123", "c139", "pan115", "guangya", "feijipan",
    "ctfile", "wopan", "tianyi", "bilibili", "douyin", "agsl",
    "agpl", "open-source",
]


def call(token, method, path, payload=None):
    url = API + path
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Authorization", "token " + token)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("User-Agent", "zayu-release-bot")
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=30) as r:
        raw = r.read().decode("utf-8")
        return json.loads(raw) if raw.strip() else {}


def main():
    if len(sys.argv) < 2:
        print("用法: python tools/set_repo_topics.py <PAT>")
        return 1
    token = sys.argv[1].strip()

    # 1) 校验令牌
    try:
        me = call(token, "GET", "/user")
        print("[1/4] 令牌有效，账号:", me.get("login"))
    except urllib.error.HTTPError as e:
        print("[1/4] 令牌无效 HTTP", e.code, "—", e.read().decode("utf-8", "ignore")[:200])
        return 1

    # 2) 改 description / homepage
    try:
        r = call(token, "PATCH", "/repos/" + REPO,
                 {"description": DESCRIPTION, "homepage": HOMEPAGE})
        print("[2/4] description 已更新:", r.get("description"))
    except urllib.error.HTTPError as e:
        print("[2/4] 改 description 失败 HTTP", e.code, "—", e.read().decode("utf-8", "ignore")[:300])

    # 3) 设 topics（PUT 是全量覆盖，单次上限 20 个）
    want = TOPICS[:20]
    try:
        r = call(token, "PUT", "/repos/%s/topics" % REPO, {"names": want})
        got = r.get("names") or []
        print("[3/4] topics 已写入 %d 个:" % len(got))
        print("       ", ", ".join(got))
    except urllib.error.HTTPError as e:
        print("[3/4] 设 topics 失败 HTTP", e.code, "—", e.read().decode("utf-8", "ignore")[:300])

    # 4) 回读确认
    try:
        r = call(token, "GET", "/repos/" + REPO)
        print("[4/4] 回读 description:", r.get("description"))
        print("      回读 topics:", ", ".join(r.get("topics") or []))
        print("      homepage:", r.get("homepage"))
    except urllib.error.HTTPError as e:
        print("[4/4] 回读失败 HTTP", e.code)

    return 0


if __name__ == "__main__":
    sys.exit(main())

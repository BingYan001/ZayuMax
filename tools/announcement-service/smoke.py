# -*- coding: utf-8 -*-
"""公告站冒烟测试：健康检查 / 列表 / 发布 / 详情(浏览量+1) / 改 / 删 / 鉴权"""
import json
import sys

from deploy import connect, run

DOMAIN = "zayu.pw"
BASE = "http://127.0.0.1"


def api(method, path, body=None, token=None, show=True):
    parts = ["curl -s -m 15", "-X " + method, "-H 'Host: %s'" % DOMAIN,
             "-H 'Content-Type: application/json'"]
    if token:
        parts.append("-H 'Authorization: Bearer %s'" % token)
    if body is not None:
        parts.append("-d %s" % shq(json.dumps(body, ensure_ascii=False)))
    parts.append("'%s%s'" % (BASE, path))
    cmd = " ".join(parts)
    code, out, err = run(c, cmd, check=False)
    if show:
        print("  <- %s" % out.strip()[:400])
    return out.strip()


def shq(s):
    return "'" + s.replace("'", "'\\''") + "'"


c = connect()
_, tok, _ = run(c, "cat /www/ann-data/zayu-ann/admin.token", check=False)
tok = tok.strip()

print("=" * 60)
print("1) 健康检查")
h = json.loads(api("GET", "/api/v1/health"))
assert h["success"], h
print("   OK version=%s php=%s" % (h["data"]["version"], h["data"]["php"]))

print("=" * 60)
print("2) 无 token 访问管理接口（应 401）")
r = api("GET", "/api/v1/admin/stats", show=False)
print("   ->", r[:120])
assert '"success":false' in r, r

print("=" * 60)
print("3) 发布公告")
created = json.loads(api("POST", "/api/v1/admin/announcements", {
    "title": "杂鱼Max 公告系统上线",
    "summary": "公告后端已切换为自建服务，此条用于验证发布与拉取链路。",
    "content": "## 公告服务已上线\n\n- 后端：自建 PHP + SQLite\n- 客户端：`AnnouncementApi` 已指向新域名\n\n这是一条**测试内容**，可随时在后台删除。",
    "author": "杂鱼团队",
    "publisherName": "杂鱼团队",
    "isPinned": True,
}, tok))
assert created["success"], created
aid = created["data"]["id"]
print("   OK id=%s" % aid)

print("=" * 60)
print("4) 客户端列表")
lst = json.loads(api("GET", "/api/v1/announcements?page=1&pageSize=10"))
assert lst["success"] and lst["data"]["total"] == 1, lst
print("   OK total=%d 首条=%s" % (lst["data"]["total"], lst["data"]["list"][0]["title"]))
assert "content" not in lst["data"]["list"][0], "列表不该带正文"

print("=" * 60)
print("5) 客户端详情（浏览量应 +1）")
d1 = json.loads(api("GET", "/api/v1/announcements/" + aid))
print("   viewCount=%s" % d1["data"]["viewCount"])
d2 = json.loads(api("GET", "/api/v1/announcements/" + aid))
print("   viewCount=%s" % d2["data"]["viewCount"])
assert d2["data"]["viewCount"] == d1["data"]["viewCount"] + 1

print("=" * 60)
print("6) 修改标题")
up = json.loads(api("PUT", "/api/v1/admin/announcements/" + aid, {"title": "杂鱼Max 公告系统上线（已改）"}, tok))
assert up["data"]["title"] == "杂鱼Max 公告系统上线（已改）", up
print("   OK")

print("=" * 60)
print("7) 置顶开关")
p = api("POST", "/api/v1/admin/announcements/%s/pin" % aid, {"pinned": False}, tok)
print("   ->", p[:120])

print("=" * 60)
print("8) 删除并确认列表归零")
dl = api("DELETE", "/api/v1/admin/announcements/" + aid, None, tok)
assert '"success":true' in dl, dl
lst2 = json.loads(api("GET", "/api/v1/announcements?page=1&pageSize=10"))
print("   OK total=%d" % lst2["data"]["total"])
assert lst2["data"]["total"] == 0

print("=" * 60)
print("9) 磁盘与数据目录")
run(c, "du -sh /www/wwwroot/zayu-ann /www/ann-data/zayu-ann; ls -l /www/ann-data/zayu-ann; df -h / | tail -1", check=False)
c.close()
print()
print("*** 全部通过 ***")

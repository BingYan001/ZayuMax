# -*- coding: utf-8 -*-
"""公告站全链路冒烟（用 paramiko 直接发 HTTP，绕开 shell 转义）"""
import json

from deploy import connect, run

DOMAIN = "zayu.pw"
BASE = "/api/v1"


def http(c, method, path, body=None, token=None):
    """在服务器上用 python3 发请求，避免 shell 引号问题"""
    py = "import json,urllib.request,sys\n"
    py += "req=urllib.request.Request('http://127.0.0.1%s', method='%s')\n" % (path, method)
    py += "req.add_header('Host','%s')\n" % DOMAIN
    py += "req.add_header('Content-Type','application/json')\n"
    if token:
        py += "req.add_header('Authorization','Bearer %s')\n" % token
    if body is not None:
        py += "d=json.loads(sys.argv[1])\n" if False else ""
        b64 = json.dumps(body, ensure_ascii=False)
        py += "req.data=%s.encode('utf-8')\n" % repr(b64)
    py += "r=urllib.request.urlopen(req,timeout=15)\n"
    py += "print(r.status, r.read().decode('utf-8'))\n"
    open("_tmp_http.py", "w", encoding="utf-8").write(py)
    sftp = c.open_sftp()
    sftp.put("_tmp_http.py", "/tmp/_tmp_http.py")
    sftp.close()
    code, out, err = run(c, "python3 /tmp/_tmp_http.py 2>&1", check=False)
    return out.strip()


c = connect()
_, tok, _ = run(c, "cat /www/ann-data/zayu-ann/admin.token", check=False)
tok = tok.strip()

print("1) health:", http(c, "GET", BASE + "/health")[:160])
print()
print("2) 无 token 管理接口:", http(c, "GET", BASE + "/admin/stats")[:160])
print()

print("3) 发布:")
payload = {
    "title": "杂鱼Max 公告系统上线",
    "summary": "公告后端已切换为自建服务，此条用于验证发布与拉取链路。",
    "content": "## 公告服务已上线\n\n- 后端：自建 PHP + SQLite\n- 客户端 AnnouncementApi 已指向新域名\n\n这是测试内容，可随时删除。",
    "author": "杂鱼团队",
    "publisherName": "杂鱼团队",
    "isPinned": True,
}
res = http(c, "POST", BASE + "/admin/announcements", payload, tok)
print("   ", res[:400])
try:
    aid = json.loads(res.split(" ", 1)[1])["data"]["id"]
except Exception:
    c.close()
    raise SystemExit("发布失败，终止")
print("    id =", aid)
print()

print("4) 列表:", http(c, "GET", BASE + "/announcements?page=1&pageSize=10")[:400])
print()
print("5) 详情 x2:")
r1 = http(c, "GET", BASE + "/announcements/" + aid)
r2 = http(c, "GET", BASE + "/announcements/" + aid)
v1 = json.loads(r1.split(" ", 1)[1])["data"]["viewCount"]
v2 = json.loads(r2.split(" ", 1)[1])["data"]["viewCount"]
print("    viewCount: %s -> %s  (%s)" % (v1, v2, "OK" if v2 == v1 + 1 else "FAIL"))
print()
print("6) 改标题:", http(c, "PUT", BASE + "/admin/announcements/" + aid, {"title": "杂鱼Max 公告系统上线（已改）"}, tok)[:200])
print()
print("7) 取消置顶:", http(c, "POST", BASE + "/admin/announcements/%s/pin" % aid, {"pinned": False}, tok)[:200])
print()
print("8) 删除:", http(c, "DELETE", BASE + "/admin/announcements/" + aid, None, tok)[:200])
print()
print("9) 列表复验:", http(c, "GET", BASE + "/announcements?page=1&pageSize=10")[:220])

run(c, "rm -f /tmp/_tmp_http.py", check=False)
c.close()
print()
print("*** 冒烟结束 ***")

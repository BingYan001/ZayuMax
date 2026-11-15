# -*- coding: utf-8 -*-
"""模拟管理端 App（com.zayu.admin）的真实调用顺序，验证客户端契约与后端一致。

只读为主：发布 -> 列表 -> 详情 -> 编辑(先GET回填再PUT) -> 置顶 -> 未置顶 -> 删除。
每一步都断言 HTTP 状态与关键字段，任何一步不符就报 FAIL 并终止。
"""
import json
import sys

from deploy import connect, run

DOMAIN = "zayu.pw"
BASE = "/api/v1"

FAILED = []


def call(c, method, path, body=None, token=None):
    py = "import json,urllib.request,urllib.error,sys\n"
    py += "req=urllib.request.Request('http://127.0.0.1%s', method='%s')\n" % (path, method)
    py += "req.add_header('Host','%s')\n" % DOMAIN
    py += "req.add_header('Content-Type','application/json')\n"
    py += "req.add_header('User-Agent','ZayuAdmin/1.0 Android')\n"
    if token:
        py += "req.add_header('Authorization','Bearer %s')\n" % token
    if body is not None:
        py += "req.data=%s.encode('utf-8')\n" % repr(json.dumps(body, ensure_ascii=False))
    py += (
        "try:\n"
        "    r=urllib.request.urlopen(req,timeout=20)\n"
        "    print(r.status); print(r.read().decode('utf-8'))\n"
        "except urllib.error.HTTPError as e:\n"
        "    print(e.code); print(e.read().decode('utf-8','replace'))\n"
    )
    open("_tmp_admin_app.py", "w", encoding="utf-8").write(py)
    sftp = c.open_sftp()
    sftp.put("_tmp_admin_app.py", "/tmp/_tmp_admin_app.py")
    sftp.close()
    _, out, _ = run(c, "python3 /tmp/_tmp_admin_app.py 2>&1", check=False)
    lines = out.strip().split("\n", 1)
    try:
        code = int(lines[0].strip())
    except Exception:
        return 0, {"_raw": out}
    raw = lines[1] if len(lines) > 1 else ""
    try:
        return code, json.loads(raw)
    except Exception:
        return code, {"_raw": raw}


def check(name, ok, detail=""):
    print(("  [PASS] " if ok else "  [FAIL] ") + name + (" -> " + detail if detail else ""))
    if not ok:
        FAILED.append(name)
    return ok


def main():
    c = connect()
    _, tok, _ = run(c, "cat /www/ann-data/zayu-ann/admin.token", check=False)
    tok = tok.strip()
    if not tok:
        print("拿不到 admin.token，终止")
        c.close()
        return 1

    print("A0 登录校验 /admin/stats")
    code, j = call(c, "GET", BASE + "/admin/stats", token=tok)
    check("stats 200", code == 200, "HTTP %s" % code)
    check("stats 有 data", isinstance(j.get("data"), dict), str(list(j.get("data", {}).keys())))

    print("\nA1 错误 token 必须 401")
    code, _ = call(c, "GET", BASE + "/admin/stats", token="deadbeef")
    check("错误 token 401", code == 401, "HTTP %s" % code)

    print("\nA2 发布（含 isPinned）")
    payload = {
        "title": "【契约测试】管理端 App 联调",
        "summary": "由 smoke_admin_app.py 生成，用于验证管理端发布与回读。",
        "content": "## 契约测试\n\n- 发布\n- 回读\n- 编辑\n- 置顶\n- 删除",
        "author": "冰焰",
        "publisherName": "杂鱼团队",
        "isPinned": True,
    }
    code, j = call(c, "POST", BASE + "/admin/announcements", payload, tok)
    if not check("发布 200/201", code in (200, 201), "HTTP %s" % code):
        print("    ", json.dumps(j, ensure_ascii=False)[:300])
        c.close()
        return 1
    d = j.get("data") or {}
    aid = d.get("id")
    check("返回 id", bool(aid), str(aid))
    check("isPinned 落库为真", d.get("isPinned") is True, str(d.get("isPinned")))
    check("标题回显一致", d.get("title") == payload["title"], str(d.get("title")))
    check("viewCount 初始为 0", d.get("viewCount") == 0, str(d.get("viewCount")))
    if not aid:
        c.close()
        return 1

    print("\nA3 公开列表里能看到它")
    code, j = call(c, "GET", BASE + "/announcements?page=1&pageSize=20")
    items = ((j.get("data") or {}).get("list")) or (j.get("data") or {}).get("items") or []
    check("列表 200", code == 200, "HTTP %s" % code)
    hit = [x for x in items if str(x.get("id")) == str(aid)]
    check("列表含新公告", bool(hit), "共 %d 条" % len(items))
    if hit:
        check("列表无正文 content", "content" not in hit[0] or not hit[0].get("content"),
              "字段=%s" % ",".join(list(hit[0].keys())[:8]))

    print("\nA4 详情 + 浏览量自增")
    code, j1 = call(c, "GET", BASE + "/announcements/" + str(aid))
    v1 = ((j1.get("data") or {}).get("viewCount"))
    code2, j2 = call(c, "GET", BASE + "/announcements/" + str(aid))
    v2 = ((j2.get("data") or {}).get("viewCount"))
    check("详情 200", code == 200 and code2 == 200, "HTTP %s/%s" % (code, code2))
    check("viewCount 自增 1", isinstance(v1, int) and v2 == v1 + 1, "%s -> %s" % (v1, v2))
    check("详情含正文", bool(((j2.get("data") or {}).get("content"))), "len=%d" % len(str((j2.get("data") or {}).get("content") or "")))

    print("\nA5 编辑（管理端流程：GET 回填 -> PUT 全量）")
    cur = (j2.get("data") or {})
    put = {
        "title": "【契约测试】管理端 App 联调（已改）",
        "summary": cur.get("summary") or payload["summary"],
        "content": (cur.get("content") or payload["content"]) + "\n\n> 编辑轮追加",
        "author": cur.get("author") or "冰焰",
        "publisherName": cur.get("publisherName") or "杂鱼团队",
        "isPinned": cur.get("isPinned"),
    }
    code, j3 = call(c, "PUT", BASE + "/admin/announcements/" + str(aid), put, tok)
    check("PUT 200", code == 200, "HTTP %s" % code)
    d3 = j3.get("data") or {}
    check("标题已更新", d3.get("title") == put["title"], str(d3.get("title")))
    check("追加内容保留", "编辑轮追加" in str(d3.get("content")), "len=%d" % len(str(d3.get("content") or "")))
    check("PUT 不改 viewCount", d3.get("viewCount") == v2, "%s vs %s" % (d3.get("viewCount"), v2))

    print("\nA6 置顶切换")
    code, j4 = call(c, "POST", BASE + "/admin/announcements/%s/pin" % aid, {"pinned": False}, tok)
    check("取消置顶 200", code == 200, "HTTP %s" % code)
    check("isPinned=False", (j4.get("data") or {}).get("isPinned") is False, str((j4.get("data") or {}).get("isPinned")))
    code, j5 = call(c, "POST", BASE + "/admin/announcements/%s/pin" % aid, {"pinned": True}, tok)
    check("重新置顶 200", code == 200, "HTTP %s" % code)
    check("isPinned=True", (j5.get("data") or {}).get("isPinned") is True, str((j5.get("data") or {}).get("isPinned")))

    print("\nA7 删除 + 404 复验")
    code, j6 = call(c, "DELETE", BASE + "/admin/announcements/" + str(aid), None, tok)
    check("DELETE 200", code == 200, "HTTP %s" % code)
    code, j7 = call(c, "GET", BASE + "/announcements/" + str(aid))
    check("删除后详情 404", code == 404, "HTTP %s" % code)
    code, j8 = call(c, "GET", BASE + "/announcements?page=1&pageSize=20")
    items2 = ((j8.get("data") or {}).get("list")) or (j8.get("data") or {}).get("items") or []
    check("列表不再含它", not [x for x in items2 if str(x.get("id")) == str(aid)], "共 %d 条" % len(items2))

    run(c, "rm -f /tmp/_tmp_admin_app.py", check=False)
    c.close()

    print("\n==== 结果 ====")
    if FAILED:
        print("FAILED %d 项: %s" % (len(FAILED), ", ".join(FAILED)))
        return 1
    print("全部 PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())

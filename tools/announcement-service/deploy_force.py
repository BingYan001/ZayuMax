# -*- coding: utf-8 -*-
"""一次性部署：强更通道（api.php / vhost / APK / app_update.json）+ 冒烟"""
import json
import os
import sys

from deploy import connect, run, SITE_ROOT

HERE = os.path.dirname(os.path.abspath(__file__))
APK_LOCAL = r"E:\AI创作文件\杂鱼Max-v1.3.5.apk"

c = connect()
sftp = c.open_sftp()

print("== 1) 上传 api.php / vhost.conf ==")
sftp.put(os.path.join(HERE, "api.php"), SITE_ROOT + "/api.php")
sftp.put(os.path.join(HERE, "vhost.conf"), "/etc/nginx/conf.d/20-zayu.pw.conf")

print("== 2) APK → /downloads/ ==")
run(c, "mkdir -p " + SITE_ROOT + "/downloads", check=True)
sftp.put(APK_LOCAL, SITE_ROOT + "/downloads/Zayu-latest.apk")
run(c, "chmod 644 " + SITE_ROOT + "/downloads/Zayu-latest.apk", check=True)

print("== 3) 写 app_update.json（数据目录，Web 不可访问）==")
cfg = {
    "minVersionCode": 19,
    "updateUrl": "https://zayu.pw/downloads/Zayu-latest.apk",
    "title": "请立即更新杂鱼Max",
    "summary": "当前版本已停止支持，请更新到最新版（v1.3.5+）后继续使用。",
}
run(c, "cat > /www/ann-data/zayu-ann/app_update.json <<'EOF'\n" + json.dumps(cfg, ensure_ascii=False) + "\nEOF", check=True)
_, out, _ = run(c, "cat /www/ann-data/zayu-ann/app_update.json", check=True)
print("   ", out.strip())

print("== 4) PHP 语法检查 + nginx -t + reload ==")
print("   php -l:", run(c, "php -l " + SITE_ROOT + "/api.php", check=False)[1].strip()[-40:])
print("   nginx -t:", run(c, "nginx -t 2>&1", check=False)[1].strip().splitlines()[-1])
print("   reload:", run(c, "nginx -s reload", check=False)[0])

sftp.close()

print("== 5) 冒烟（服务器本机 curl）==")
for ua, name in [("Zayu", "旧版UA"), ("Zayu/1.3.5 (19)", "新版UA")]:
    code, out = run(c,
        "curl -s -o /tmp/sm.json -w '%{http_code}' -H 'User-Agent: %s' -H 'Host: zayu.pw' "
        "'http://127.0.0.1/api/v1/announcements?page=1&pageSize=5'" % ua, check=False)[1:3]
    body = out
    # run() 返回 (code,out,err)，重新取
_, out, _ = run(c, "curl -s -H 'User-Agent: Zayu' -H 'Host: zayu.pw' 'http://127.0.0.1/api/v1/announcements?page=1&pageSize=3'", check=False)
try:
    j = json.loads(out)
    d = j["data"]
    print("旧版UA: total=%s minVersionCode=%s updateUrl=%s 首条=%s pinned=%s" % (
        d["total"], d["minVersionCode"], d["updateUrl"], d["list"][0]["id"], d["list"][0]["isPinned"]))
except Exception as e:
    print("旧版UA 解析失败:", e, out[:300])

_, out, _ = run(c, "curl -s -H 'User-Agent: Zayu/1.3.5 (19)' -H 'Host: zayu.pw' 'http://127.0.0.1/api/v1/announcements?page=1&pageSize=3'", check=False)
try:
    j = json.loads(out)
    d = j["data"]
    print("新版UA: total=%s minVersionCode=%s 首条=%s" % (
        d["total"], d["minVersionCode"], d["list"][0]["id"] if d["list"] else "-"))
except Exception as e:
    print("新版UA 解析失败:", e, out[:300])

print()
_, out, _ = run(c, "curl -s -H 'User-Agent: Zayu' -H 'Host: zayu.pw' 'http://127.0.0.1/api/v1/announcements/sys-force-update-19'", check=False)
try:
    j = json.loads(out)
    print("系统强更详情: success=%s title=%s contentLen=%s" % (
        j["success"], j["data"]["title"], len(j["data"].get("content") or "")))
except Exception as e:
    print("系统强更详情 失败:", e, out[:300])

print()
_, out, _ = run(c, "curl -s -o /dev/null -w '%{http_code} %{size_download}' -H 'Host: zayu.pw' 'http://127.0.0.1/downloads/Zayu-latest.apk'", check=False)
print("APK 直链:", out)
_, out, _ = run(c, "curl -s -o /dev/null -w '%{http_code}' -H 'Host: zayu.pw' 'http://127.0.0.1/downloads/evil.php'", check=False)
print("APK 目录 PHP 探测(应404):", out)

c.close()
print()
print("*** 部署完成 ***")

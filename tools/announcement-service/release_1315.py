# -*- coding: utf-8 -*-
"""一键发布 v1.3.15：分块上传 APK → 提 minVersionCode 强更 → 发更新公告 → 冒烟"""
import base64
import hashlib
import json
import socket
import sys
import time

import deploy
from deploy import HOST, PORT, USER, PWD, connect

C = None

def connect_proxy():
    """本机直连被干扰（80/SSH 认证均超时），改走本地 HTTP 代理 CONNECT 隧道"""
    import os
    import re
    import paramiko
    proxy = os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy") \
        or os.environ.get("HTTP_PROXY") or os.environ.get("http_proxy")
    m = re.match(r"https?://([^:/]+):(\d+)", proxy or "")
    if not m:
        return connect()
    s = socket.create_connection((m.group(1), int(m.group(2))), timeout=15)
    s.sendall(("CONNECT %s:%d HTTP/1.1\r\nHost: %s:%d\r\n\r\n" % (HOST, PORT, HOST, PORT)).encode())
    resp = b""
    while b"\r\n\r\n" not in resp:
        chunk = s.recv(256)
        if not chunk:
            raise RuntimeError("代理无响应")
        resp += chunk
    if b" 200 " not in resp.splitlines()[0]:
        raise RuntimeError("代理拒绝: %s" % resp.splitlines()[0])
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, port=PORT, username=USER, password=PWD, sock=s, timeout=25,
              banner_timeout=40, auth_timeout=60, allow_agent=False, look_for_keys=False)
    return c

def run(cmd, check=False, timeout=60):
    """带重连重试的 run：SSH 通道不稳（开 channel 偶发超时）时最多重试 5 次"""
    global C
    last = None
    for attempt in range(5):
        try:
            return deploy.run(C, cmd, check=check, timeout=timeout)
        except Exception as e:
            last = e
            print("run 重试 %d: %s" % (attempt + 1, e), flush=True)
            time.sleep(3)
            try:
                C.close()
            except Exception:
                pass
            C = connect_proxy()
    raise last

def connect_retry():
    """connect_proxy 带重试（10 次，每次间隔 5s）"""
    last = None
    for i in range(10):
        try:
            return connect_proxy()
        except Exception as e:
            last = e
            print("connect 重试 %d: %s" % (i + 1, type(e).__name__), flush=True)
            time.sleep(45)
    raise last

APK_LOCAL = r"E:\AI创作文件\杂鱼Max-v1.3.16.apk"
REMOTE = "/www/wwwroot/zayu-ann/downloads/Zayu-latest.apk"
PART_DIR = "/tmp/apkparts"
CHUNK = 24 * 1024
MIN_VC = 36

C = connect_retry()

# ---------- 1) 分块上传 APK ----------
data = open(APK_LOCAL, "rb").read()
md5 = hashlib.md5(data).hexdigest()
print("local: %d bytes md5=%s" % (len(data), md5), flush=True)

# 不清空分块目录：脚本中断后重跑可断点续传（已存在且大小正确的分块直接跳过）
run("mkdir -p " + PART_DIR, check=True)
_, existing, _ = run("cd %s && wc -c part_* 2>/dev/null | tail -n +1" % PART_DIR, check=False)
done_sizes = {}
for line in (existing or "").splitlines():
    f = line.split()
    if len(f) == 2 and f[1] != "total":
        done_sizes[f[1]] = int(f[0])
nparts = (len(data) + CHUNK - 1) // CHUNK
for i in range(nparts):
    part = "%s/part_%04d" % (PART_DIR, i)
    expect = len(data[i * CHUNK:(i + 1) * CHUNK])
    if done_sizes.get("part_%04d" % i) == expect:
        if i % 40 == 0:
            print("  part_%04d 已存在，跳过" % i, flush=True)
        continue
    b64 = base64.b64encode(data[i * CHUNK:(i + 1) * CHUNK]).decode()
    for attempt in range(4):
        try:
            code, out, _ = run("echo '%s' | base64 -d > %s && wc -c < %s" % (b64, part, part),
                               check=False, timeout=60)
            if code == 0 and out.strip() == str(expect):
                break
        except Exception as e:
            print("part %d attempt %d: %s" % (i, attempt, e), flush=True)
    else:
        print("part %d 连续失败，中止（重跑本脚本可续传）" % i, flush=True)
        sys.exit(2)
    if (i + 1) % 20 == 0 or i == nparts - 1:
        print("  %d/%d 块" % (i + 1, nparts), flush=True)

code, out, _ = run("cat %s/part_* > %s && md5sum %s" % (PART_DIR, REMOTE, REMOTE), check=False)
remote_md5 = out.split()[0] if out else ""
run("chmod 644 %s && rm -rf %s" % (REMOTE, PART_DIR), check=True)
if remote_md5 != md5:
    print("MD5 不匹配！中止（remote=%s）" % remote_md5)
    sys.exit(1)
print("APK 上传完成，md5 一致")

# ---------- 2) 强更门槛：读现有 app_update.json，只改 minVersionCode ----------
_, out, _ = run("cat /www/ann-data/zayu-ann/app_update.json", check=True)
cfg = json.loads(out)
old_vc = cfg.get("minVersionCode")
cfg["minVersionCode"] = MIN_VC
cfg.setdefault("updateUrl", "https://zayu.pw/downloads/Zayu-latest.apk")
run("cat > /www/ann-data/zayu-ann/app_update.json <<'EOF'\n" + json.dumps(cfg, ensure_ascii=False) + "\nEOF", check=True)
print("minVersionCode: %s -> %s" % (old_vc, MIN_VC))

# ---------- 3) 发公告（走管理端 API） ----------
_, tok, _ = run("cat /www/ann-data/zayu-ann/admin.token", check=True)
tok = tok.strip()
ann = {
    "title": "杂鱼Max v1.3.16 紧急修复：启动崩溃",
    "summary": "修复 v1.3.15 升级用户启动即崩的问题（数据库迁移错误），请立即更新。",
    "content": (
        "## 紧急修复\n\n"
        "**问题**\n"
        "- v1.3.15 存在数据库迁移错误：解析历史表建表语句的列顺序与实体定义不一致，"
        "升级用户启动时 Room 校验失败，直接崩溃（Migration didn't properly handle: parse_history）\n"
        "- 全新安装用户不受影响\n\n"
        "**修复**\n"
        "- 数据库版本 24 → 25，删除错误的解析历史表并按正确结构重建（历史记录为本地快照，重建不影响其他数据）\n"
        "- 同时修正 v23→24 的迁移语句，全新升级路径不再出错\n\n"
        "**升级说明**\n"
        "- 本版本为强制更新，打开应用会弹出不可关闭的升级提示\n"
        "- 下载地址：https://zayu.pw/downloads/Zayu-latest.apk\n"
    )
}
payload = "/root/zayu-release.json"
run("cat > %s <<'EOF'\n%s\nEOF" % (payload, json.dumps(ann, ensure_ascii=False)), check=True)
cmd = (
    'curl -s -m 15 -X POST -H "Host: zayu.pw" -H "Content-Type: application/json" '
    '-H "Authorization: Bearer ' + tok + '" --data-binary @' + payload +
    ' "http://127.0.0.1/api/v1/admin/announcements"; echo'
)
_, out, _ = run(cmd, check=False)
print("公告响应:", out.strip()[:300])
run("rm -f " + payload, check=False)

# ---------- 4) 冒烟 ----------
_, out, _ = run("curl -s -o /dev/null -w '%{http_code} %{size_download}' -H 'Host: zayu.pw' http://127.0.0.1/downloads/Zayu-latest.apk", check=False)
print("APK 冒烟:", out.strip())
_, out, _ = run("curl -s -H 'User-Agent: Zayu/1.3.10 (30)' -H 'Host: zayu.pw' http://127.0.0.1/api/v1/announcements | head -c 200", check=False)
print("旧版UA 公告:", out.strip()[:200])
C.close()
print("ALL DONE")

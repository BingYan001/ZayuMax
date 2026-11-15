# -*- coding: utf-8 -*-
"""base64 分块经 SSH exec 上传（sftp 大文件写入会卡死，改走命令通道，每块落盘即校验）"""
import base64
import hashlib
import sys

from deploy import connect, run

APK_LOCAL = r"E:\AI创作文件\杂鱼Max-v1.3.23.apk"
REMOTE_DIR = "/www/wwwroot/zayu-ann/downloads"
REMOTE = REMOTE_DIR + "/Zayu-latest.apk"
PART_DIR = "/tmp/apkparts"
# 每块原始 24KB → base64 32KB：大命令（≥256KB）会被 sshd 直接断连（实测 10054），小块稳
CHUNK = 24 * 1024

data = open(APK_LOCAL, "rb").read()
total = len(data)
md5 = hashlib.md5(data).hexdigest()
print("local: %d bytes md5=%s" % (total, md5), flush=True)

c = connect()
run(c, "mkdir -p " + PART_DIR + " && rm -f " + PART_DIR + "/*", check=True)

nparts = (total + CHUNK - 1) // CHUNK
for i in range(nparts):
    chunk = data[i * CHUNK:(i + 1) * CHUNK]
    b64 = base64.b64encode(chunk).decode()
    part = "%s/part_%04d" % (PART_DIR, i)
    for attempt in range(4):
        try:
            code, out, err = run(
                c, "echo '%s' | base64 -d > %s && wc -c < %s" % (b64, part, part), check=False, timeout=60)
            if code == 0 and out.strip() == str(len(chunk)):
                break
            print("part %d attempt %d: code=%s" % (i, attempt, code), flush=True)
        except Exception as e:
            # 连接被打断就重连（sshd 偶发 10054）
            print("part %d attempt %d reconnect: %s" % (i, attempt, e), flush=True)
            try:
                c.close()
            except Exception:
                pass
            c = connect()
            run(c, "mkdir -p " + PART_DIR, check=True)
    if (i + 1) % 8 == 0 or i == nparts - 1:
        print("  %d/%d 块完成" % (i + 1, nparts), flush=True)

code, out, _ = run(c, "cat %s/part_* > %s && md5sum %s" % (PART_DIR, REMOTE, REMOTE), check=False)
remote_md5 = out.split()[0] if out else ""
print("remote md5:", remote_md5, flush=True)
run(c, "chmod 644 " + REMOTE + " && rm -rf " + PART_DIR, check=True)
c.close()
print("OK" if remote_md5 == md5 else "MD5 不匹配！", flush=True)
sys.exit(0 if remote_md5 == md5 else 1)

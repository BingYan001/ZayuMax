# -*- coding: utf-8 -*-
"""上传 api.php 并跑一次最小 POST 校验（避免命令行转义地狱）"""
import json
import sys

from deploy import connect, run

c = connect()

# 1. 上传
sftp = c.open_sftp()
sftp.put("api.php", "/tmp/api.php")
sftp.close()
run(c, "install -o www -g www -m 640 /tmp/api.php /www/wwwroot/zayu-ann/api.php && rm -f /tmp/api.php", check=False)
run(c, "php -l /www/wwwroot/zayu-ann/api.php", check=False)

# 2. 把请求体写成本地文件再上传，彻底避开 shell 引号
payload = {"title": "probe-by-file", "summary": "s", "content": "c"}
p = "payload.json"
with open(p, "w", encoding="utf-8") as f:
    f.write(json.dumps(payload, ensure_ascii=False))

sftp = c.open_sftp()
sftp.put(p, "/root/zayu-payload.json")
sftp.close()

_, tok, _ = run(c, "cat /www/ann-data/zayu-ann/admin.token", check=False)
tok = tok.strip()

cmd = (
    'curl -s -m 10 -X POST '
    '-H "Host: zayu.pw" '
    '-H "Content-Type: application/json" '
    '-H "Authorization: Bearer ' + tok + '" '
    '--data-binary @/root/zayu-payload.json '
    '"http://127.0.0.1/api/v1/admin/announcements"; echo'
)
run(c, cmd, check=False)
c.close()

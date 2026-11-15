# -*- coding: utf-8 -*-
"""临时诊断：直接调 PHP CLI 跑同一个请求体，看真实报错"""
import json

from deploy import connect, run

c = connect()

payload = {"title": "probe-cli", "summary": "s", "content": "c"}
sftp = c.open_sftp()
with sftp.open("/root/zayu-cli.json", "w") as f:
    f.write(json.dumps(payload, ensure_ascii=False))
with sftp.open("/root/zayu-cli.php", "w") as f:
    f.write("""<?php
$cfg = require '/www/wwwroot/zayu-ann/config.local.php';
$_SERVER['REQUEST_METHOD']='POST';
$_SERVER['REQUEST_URI']='/api/v1/admin/announcements';
$_SERVER['HTTP_AUTHORIZATION']='Bearer '.$cfg['ADMIN_TOKEN'];
include '/www/wwwroot/zayu-ann/api.php';
""")
sftp.close()

# 用 php -d 打开 display_errors，并把 stdin 当请求体（api.php 用 php://input，CLI 下读不到，走 $_POST 兜底不了）
run(c, "php -d display_errors=1 -d error_reporting=E_ALL /root/zayu-cli.php 2>&1 | head -40", check=False)

print("=" * 50)
print("直接测 DB 写入（排除 PHP 逻辑，只测 SQLite 权限）")
run(c, r"""php -r '
$pdo = new PDO("sqlite:/www/ann-data/zayu-ann/announcement.sqlite", null, null, [PDO::ATTR_ERRMODE=>PDO::ERRMODE_EXCEPTION]);
$pdo->exec("PRAGMA journal_mode=WAL");
$pdo->exec("INSERT INTO announcements (id,title,summary,content,images,author,publisher_name,status,is_pinned,sort_order,view_count,created_at,updated_at) VALUES (\"probe_x\",\"t\",\"s\",\"c\",\"[]\",\"a\",\"p\",\"published\",0,0,0,\"2026-01-01T00:00:00.000Z\",\"2026-01-01T00:00:00.000Z\")");
echo "insert ok\n";
$pdo->exec("DELETE FROM announcements WHERE id=\"probe_x\"");
echo "delete ok\n";
' 2>&1 | head -20""", check=False)

print("=" * 50)
print("以 www 身份测试同一段 SQL")
run(c, r"""su -s /bin/sh www -c 'php -r "
\$pdo = new PDO(\"sqlite:/www/ann-data/zayu-ann/announcement.sqlite\", null, null, [PDO::ATTR_ERRMODE=>PDO::ERRMODE_EXCEPTION]);
\$pdo->exec(\"PRAGMA journal_mode=WAL\");
echo \"open ok\n\";
"' 2>&1 | head -20""", check=False)

run(c, "ls -l /www/ann-data/zayu-ann/", check=False)
c.close()

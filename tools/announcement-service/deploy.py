# -*- coding: utf-8 -*-
"""
杂鱼Max 公告站 —— 一键部署 / 回滚

只做「新增」：不动任何现有 vhost、端口、站点、数据库。
  · 站点目录   /www/wwwroot/zayu-ann      （新增）
  · 私有数据   /www/ann-data/zayu-ann     （新增，Web 不可访问）
  · nginx conf /etc/nginx/conf.d/20-ann.zayu-*.conf  （新增，reload 前已备份全量 conf）
部署前后各打一次磁盘快照，用于精确报告占用。

用法：
  python deploy.py deploy --domain ann.example.com
  python deploy.py rollback
  python deploy.py status
"""
import argparse
import hashlib
import os
import re
import secrets
import sys
import time

import paramiko

HOST = "107.151.245.169"
PORT = 22
USER = "root"
PWD = "f2nQGmX0MPf3"

SITE_ROOT = "/www/wwwroot/zayu-ann"
DATA_DIR = "/www/ann-data/zayu-ann"
LOG_DIR = "/www/wwwlogs"
LOCAL = os.path.dirname(os.path.abspath(__file__))

PUBLIC_IP = "107.151.245.232"


def connect():
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, port=PORT, username=USER, password=PWD, timeout=20,
              banner_timeout=40, auth_timeout=90, allow_agent=False, look_for_keys=False)
    return c


def run(c, cmd, check=True, timeout=180):
    _, out, err = c.exec_command(cmd, timeout=timeout)
    o = out.read().decode("utf-8", "replace")
    e = err.read().decode("utf-8", "replace")
    code = out.channel.recv_exit_status()
    if o.strip():
        print(o.rstrip())
    if e.strip():
        print("[stderr]", e.rstrip()[:1500])
    if check and code != 0:
        raise SystemExit("命令失败(%d): %s" % (code, cmd[:120]))
    return code, o, e


def disk_used_kb(c):
    _, o, _ = run(c, "df -k / | tail -1 | awk '{print $3}'", check=False)
    return int(o.strip())


def nginx_conf_hash(c):
    _, o, _ = run(c, "find /etc/nginx -type f -name '*.conf' | sort | xargs cat | md5sum", check=False)
    return o.strip()


def deploy(domain):
    c = connect()

    print("=" * 62)
    print("STEP 0  环境核对")
    print("=" * 62)
    _, os_rel, _ = run(c, "grep PRETTY_NAME /etc/os-release | cut -d= -f2 | tr -d '\"'", check=False)
    _, php_v, _ = run(c, "php -r 'echo PHP_VERSION;'", check=False)
    _, disk0, _ = run(c, "df -h / | tail -1", check=False)
    _, meminfo, _ = run(c, "free -m | head -2", check=False)
    print("OS: %s | PHP: %s" % (os_rel.strip(), php_v.strip()))
    kb_before = disk_used_kb(c)
    print("占用基线: %.2f GB" % (kb_before / 1024 / 1024))
    conf_hash_before = nginx_conf_hash(c)
    print("nginx 全量 conf 指纹: %s" % conf_hash_before)
    print("mem:\n%s" % meminfo.strip())

    print()
    print("=" * 62)
    print("STEP 1  建目录（只新增）")
    print("=" * 62)
    run(c, "mkdir -p %s %s && chmod 750 %s && ls -ld %s %s"
        % (SITE_ROOT, DATA_DIR, DATA_DIR, SITE_ROOT, DATA_DIR))

    print()
    print("=" * 62)
    print("STEP 2  上传两个 PHP 文件")
    print("=" * 62)
    sftp = c.open_sftp()
    for name in ("api.php", "admin.php"):
        local = os.path.join(LOCAL, name)
        remote = "%s/%s" % (SITE_ROOT, name)
        sftp.put(local, remote)
        print("  %-10s %7d B -> %s" % (name, os.path.getsize(local), remote))
    sftp.close()
    run(c, "chown -R www:www %s %s && chmod 640 %s/*.php && ls -l %s"
        % (SITE_ROOT, DATA_DIR, SITE_ROOT, SITE_ROOT))

    print()
    print("=" * 62)
    print("STEP 3  生成 ADMIN_TOKEN")
    print("=" * 62)
    token_file = "%s/admin.token" % DATA_DIR
    _, existing, _ = run(c, "cat %s 2>/dev/null || true" % token_file, check=False)
    token = existing.strip()
    if token:
        print("  已存在，沿用（长度 %d）" % len(token))
    else:
        token = "zym_" + secrets.token_urlsafe(30)
        run(c, "umask 077 && printf '%s' '%s' > %s && chown www:www %s && chmod 600 %s"
            % (token, token, token_file, token_file, token_file))
        print("  已生成（长度 %d），落盘 %s（600，www 属主）" % (len(token), token_file))

    cfg_local = """<?php
return [
    'DB_PATH'     => '%s/announcement.sqlite',
    'ADMIN_TOKEN' => '%s',
    'TIMEZONE'    => 'Asia/Shanghai',
];
""" % (DATA_DIR, token)
    sftp = c.open_sftp()
    with sftp.open("%s/config.local.php" % SITE_ROOT, "w") as f:
        f.write(cfg_local)
    sftp.close()
    run(c, "chown www:www %s/config.local.php && chmod 640 %s/config.local.php" % (SITE_ROOT, SITE_ROOT))

    print()
    print("=" * 62)
    print("STEP 4  写 nginx vhost（新增，不动任何既有 conf）")
    print("=" * 62)
    conf_path = "/etc/nginx/conf.d/20-ann.zayu-%s.conf" % re.sub(r"[^a-z0-9]", "", domain.replace(".", ""))[:16]
    conf = """# 杂鱼Max 公告站（新增于 %s，不影响其它站点）
# 纯 HTTP：Android 侧 network_security_config 已为该域名单独开 cleartext 例外。
server {
    listen 80;
    listen [::]:80;
    server_name %s;

    root %s;
    index admin.php;

    access_log  %s/ann.zayu.log;
    error_log   %s/ann.zayu.error.log;

    client_max_body_size 4m;

    # 管理页与 API
    # ★ 只用 fastcgi.conf，不要 include pathinfo.conf：
    #    宝塔的 pathinfo.conf 会用 $real_script_name 覆盖 SCRIPT_FILENAME，
    #    正则不匹配时该变量为空 ⇒ nginx 侧直接 404，PHP 一秒都不执行（本机踩过）。
    location ~ ^/(api|admin)\\.php$ {
        try_files $uri =404;
        fastcgi_pass  unix:/tmp/php-cgi-74.sock;
        include /etc/nginx/fastcgi.conf;
        fastcgi_read_timeout 30s;
    }

    # API 走 PATH_INFO 风格：/api/v1/xxx -> /api.php/api/v1/xxx
    # （纯 rewrite，不进正则 location，避免 php 正则会话被二次匹配）
    location ^~ /api/ {
        rewrite ^/api/(.*)$ /api.php/$1 last;
    }

    # 静态资源直接由 nginx 出去
    location ~* \\.(css|js|png|jpg|jpeg|webp|svg|ico|woff2?)$ {
        expires 7d;
        access_log off;
    }

    # 其余一律 404：这个站点只有两个 PHP，没有别的入口
    location / {
        return 404;
    }

    # 数据目录在站点根之外，这里再兜一层
    location ~* /\\.(git|env|sqlite|sql|bak|token) {
        return 404;
    }
}
""" % (time.strftime("%Y-%m-%d %H:%M:%S"), domain, SITE_ROOT, LOG_DIR, LOG_DIR)

    sftp = c.open_sftp()
    with sftp.open(conf_path, "w") as f:
        f.write(conf)
    sftp.close()
    print("  写入 %s" % conf_path)

    print()
    print("=" * 62)
    print("STEP 5  nginx 语法检查 + 平滑 reload")
    print("=" * 62)
    code, _, _ = run(c, "nginx -t", check=False)
    if code != 0:
        print("!! nginx -t 失败，已删除新 conf，未 reload")
        run(c, "rm -f %s" % conf_path, check=False)
        raise SystemExit(1)
    conf_hash_after_add = nginx_conf_hash(c)
    print("新增 conf 后指纹: %s" % conf_hash_after_add)

    # 只 reload，不 restart —— 9 个生产站点零中断
    run(c, "systemctl reload nginx && systemctl is-active nginx")

    print()
    print("=" * 62)
    print("STEP 6  本机自检（绕 DNS，用 Host 头打 127.0.0.1）")
    print("=" * 62)
    run(c, "curl -s -m 8 -H 'Host: %s' http://127.0.0.1/api/v1/health; echo" % domain, check=False)
    run(c, "curl -s -m 8 -H 'Host: %s' 'http://127.0.0.1/api/v1/announcements?page=1&pageSize=20'; echo" % domain, check=False)

    kb_after = disk_used_kb(c)
    delta_kb = kb_after - kb_before
    print()
    print("=" * 62)
    print("STEP 7  公网可达性（DNS 可能还没生效，NXDOMAIN 属正常）")
    print("=" * 62)
    run(c, "curl -s -m 8 -o /dev/null -w 'public via domain: %%{http_code}\\n' http://%s/api/v1/health || true" % domain, check=False)
    run(c, "curl -s -m 8 -o /dev/null -w 'public via IP+Host: %%{http_code}\\n' -H 'Host: %s' http://%s/api/v1/health || true" % (domain, PUBLIC_IP), check=False)

    _, disk_after_h, _ = run(c, "df -h / | tail -1", check=False)
    print()
    print("=" * 62)
    print("部署完成 · 空间账单")
    print("=" * 62)
    print("部署前 df:\n  " + disk0.strip())
    print("部署后 df:\n  " + disk_after_h.strip())
    print("本次新增占用: %.2f MB" % (delta_kb / 1024.0))
    print()
    print("站点根 : %s" % SITE_ROOT)
    print("数据目录: %s" % DATA_DIR)
    print("vhost  : %s" % conf_path)
    print("后台   : http://%s/admin.php" % domain)
    print("API    : http://%s/api/v1/announcements" % domain)
    print("TOKEN  : 见 %s" % token_file)
    print()
    print(">>> 下一步：把 %s 的 DNS A 记录指向 %s" % (domain, PUBLIC_IP))

    c.close()


def rollback():
    c = connect()
    print("回滚：删除本次新增的 conf / 站点 / 数据（仅这些，别的一概不碰）")
    run(c, "ls -d /etc/nginx/conf.d/20-ann.zayu-*.conf 2>/dev/null || echo '无'", check=False)
    run(c, "rm -f /etc/nginx/conf.d/20-ann.zayu-*.conf")
    code, _, _ = run(c, "nginx -t", check=False)
    if code == 0:
        run(c, "systemctl reload nginx && echo reloaded", check=False)
    run(c, "rm -rf %s %s /www/wwwlogs/ann.zayu*.log" % (SITE_ROOT, DATA_DIR), check=False)
    print("已回滚。")
    c.close()


def status():
    c = connect()
    run(c, "df -h / | tail -1; echo; free -m | head -2", check=False)
    run(c, "du -sh %s %s 2>/dev/null; echo; ls -l %s 2>/dev/null; echo; ls -l %s 2>/dev/null"
        % (SITE_ROOT, DATA_DIR, SITE_ROOT, DATA_DIR), check=False)
    run(c, "systemctl is-active nginx php7.4-fpm 2>/dev/null", check=False)
    c.close()


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("action", choices=["deploy", "rollback", "status"])
    ap.add_argument("--domain", default="zayu.pw")
    a = ap.parse_args()
    if a.action == "deploy":
        deploy(a.domain)
    elif a.action == "rollback":
        rollback()
    else:
        status()

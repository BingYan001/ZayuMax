# -*- coding: utf-8 -*-
"""只重写 vhost 并 reload（部署已跑完，做增量修复用）"""
import sys
import time

from deploy import connect, run, nginx_conf_hash, SITE_ROOT, LOG_DIR

DOMAIN = "zayu.pw"
CONF = "/etc/nginx/conf.d/20-ann.zayu-annbingyanshop.conf"

conf = """# 杂鱼Max 公告站（新增于 %s，不影响其它站点）
server {
    listen 80;
    listen [::]:80;
    server_name %s;

    root %s;
    index admin.php;

    access_log  %s/ann.zayu.log;
    error_log   %s/ann.zayu.error.log;

    client_max_body_size 4m;

    # 管理页与 API —— 只用 fastcgi.conf，禁止 include pathinfo.conf
    # （pathinfo.conf 会用 $real_script_name 覆盖 SCRIPT_FILENAME，正则不匹配时为空 => 全站 404）
    location ~ ^/(api|admin)\\.php$ {
        try_files $uri =404;
        fastcgi_pass  unix:/tmp/php-cgi-74.sock;
        include /etc/nginx/fastcgi.conf;
        fastcgi_read_timeout 30s;
    }

    # API 走 PATH_INFO：/api/v1/xxx -> /api.php/api/v1/xxx
    location ^~ /api/ {
        rewrite ^/api/(.*)$ /api.php/$1 last;
    }

    location / {
        return 404;
    }
}
""" % (time.strftime("%Y-%m-%d %H:%M:%S"), DOMAIN, SITE_ROOT, LOG_DIR, LOG_DIR)

c = connect()
print("当前 conf 指纹:", nginx_conf_hash(c))

sftp = c.open_sftp()
with sftp.open("/tmp/zayu-ann.conf", "w") as f:
    f.write(conf)
sftp.close()
run(c, "install -m 644 /tmp/zayu-ann.conf %s && rm -f /tmp/zayu-ann.conf" % CONF)

code, _, _ = run(c, "nginx -t", check=False)
if code != 0:
    print("!! nginx -t 失败，未 reload")
    sys.exit(1)
print("新 conf 指纹:", nginx_conf_hash(c))
run(c, "systemctl reload nginx && sleep 1 && systemctl is-active nginx")

print("=" * 50)
run(c, "curl -s -m 8 -H 'Host: %s' http://127.0.0.1/api/v1/health; echo" % DOMAIN, check=False)
run(c, "curl -s -m 8 -H 'Host: %s' 'http://127.0.0.1/api/v1/announcements?page=1&pageSize=20'; echo" % DOMAIN, check=False)
run(c, "curl -s -m 8 -o /dev/null -w 'admin page: %%{http_code}\\n' -H 'Host: %s' http://127.0.0.1/admin.php" % DOMAIN, check=False)
run(c, "tail -3 %s/ann.zayu.log" % LOG_DIR, check=False)
c.close()

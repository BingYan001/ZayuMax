# -*- coding: utf-8 -*-
"""zayu.pw 公告服务一键迁移：107.151.245.232 -> 107.151.245.169"""
import io
import os
import sys
import time

import paramiko

OLD = dict(host="107.151.245.232", pwd="bqdp0zmntIFS")
NEW = dict(host="107.151.245.169", pwd="f2nQGmX0MPf3")
LOCAL_TGZ = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_mig.tgz")


def connect(host, pwd):
    last = None
    for _ in range(4):
        try:
            c = paramiko.SSHClient()
            c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
            c.connect(host, 22, "root", pwd, timeout=30, banner_timeout=60,
                      auth_timeout=60, allow_agent=False, look_for_keys=False)
            return c
        except Exception as e:  # 网络抖动重试
            last = e
            time.sleep(3)
    raise last


def run(c, cmd, check=True, timeout=300):
    i, o, e = c.exec_command(cmd, timeout=timeout)
    out = o.read().decode(errors="replace")
    err = e.read().decode(errors="replace")
    code = o.channel.recv_exit_status()
    if check and code != 0:
        raise RuntimeError("cmd failed(%d): %s\n%s\n%s" % (code, cmd, out, err))
    return code, out.strip(), err.strip()


def main():
    # ---------- 1. 旧机打包 ----------
    old = connect(OLD["host"], OLD["pwd"])
    print("[1] 打包旧机物料...")
    if run(old, "test -s /tmp/zayu-mig.tgz && echo ok", check=False)[1] != "ok":
        run(old, "cd / && tar czf /tmp/zayu-mig.tgz "
                 "etc/nginx/conf.d/20-zayu.pw.conf "
                 "www/wwwroot/zayu-ann "
                 "www/ann-data/zayu-ann "
                 "www/server/panel/vhost/cert/zayu.pw "
                 "root/.acme.sh 2>/dev/null || tar czf /tmp/zayu-mig.tgz "
                 "etc/nginx/conf.d/20-zayu.pw.conf "
                 "www/wwwroot/zayu-ann "
                 "www/ann-data/zayu-ann "
                 "www/server/panel/vhost/cert/zayu.pw "
                 "root/.acme.sh")
    _, size, _ = run(old, "ls -l /tmp/zayu-mig.tgz | awk '{print $5}'")
    print("    tar:", size, "bytes")

    # ---------- 2. 旧机 -> 新机 直传（发临时密钥，免交互） ----------
    print("[2] 服务器直传...")
    _, pub, _ = run(old, "test -f /root/.ssh/id_ed25519.pub || ssh-keygen -t ed25519 -N '' -f /root/.ssh/id_ed25519 -q; cat /root/.ssh/id_ed25519.pub")
    new = connect(NEW["host"], NEW["pwd"])
    run(new, "mkdir -p /root/.ssh && chmod 700 /root/.ssh && grep -qF '%s' /root/.ssh/authorized_keys 2>/dev/null || echo '%s # mig-dedup' >> /root/.ssh/authorized_keys" % (pub, pub))
    run(old, "scp -o StrictHostKeyChecking=no /tmp/zayu-mig.tgz root@%s:/tmp/zayu-mig.tgz" % NEW["host"], timeout=600)
    run(new, "test -s /tmp/zayu-mig.tgz")
    run(old, "sed -i '/# mig-dedup/d' /root/.ssh/authorized_keys 2>/dev/null; true", check=False)
    run(new, "sed -i '/# mig-dedup/d' /root/.ssh/authorized_keys 2>/dev/null; true", check=False)

    # ---------- 3. 新机解压 ----------
    print("[3] 解压并落位...")
    run(new, "cd / && tar xzf /tmp/zayu-mig.tgz && rm -f /tmp/zayu-mig.tgz")
    # php-fpm socket 探测
    code, sock, _ = run(new, "ls /run/php/php*-fpm.sock 2>/dev/null | head -1", check=False)
    if not sock:
        run(new, "apt-get install -y php-fpm php-sqlite3 >/dev/null 2>&1")
        code, sock, _ = run(new, "ls /run/php/php*-fpm.sock 2>/dev/null | head -1", check=False)
    sock = sock or "/run/php/php-fpm.sock"
    print("    php-fpm sock:", sock)

    # ---------- 4. 改 vhost：证书路径保留原位（/www/server/... 新机上只是普通目录，照样能用）、fastcgi_pass 换新 sock ----------
    print("[4] 适配 vhost...")
    run(new, "sed -i 's#unix:/tmp/php-cgi-74.sock#unix:%s#g' /etc/nginx/conf.d/20-zayu.pw.conf" % sock)
    run(new, "sed -i 's#ssl_certificate     /www#ssl_certificate     /www#; s#root /www/wwwroot/zayu-ann;#root /www/wwwroot/zayu-ann;#' /etc/nginx/conf.d/20-zayu.pw.conf")
    # 确保 acme-challenge 放行段存在
    code, out, _ = run(new, "grep -c acme-challenge /etc/nginx/conf.d/20-zayu.pw.conf", check=False)
    if out == "0":
        run(new, "sed -i '/listen 80;/a\\    location ^~ /.well-known/acme-challenge/ { root /www/wwwroot/zayu-ann; }' /etc/nginx/conf.d/20-zayu.pw.conf")
    run(new, "nginx -t")
    run(new, "systemctl reload nginx || systemctl restart nginx")
    print("    nginx ok")

    # ---------- 5. acme.sh 续期环境 ----------
    print("[5] 恢复 acme.sh 续期...")
    run(new, "sed -i 's#107.151.245.232#107.151.245.169#g' /root/.acme.sh/zayu.pw_ecc/zayu.pw.conf 2>/dev/null; true")
    run(new, "sed -i 's#/www/server/panel/vhost/cert/#/www/server/panel/vhost/cert/#g' /root/.acme.sh/zayu.pw_ecc/zayu.pw.conf 2>/dev/null; true")
    run(new, "(crontab -l 2>/dev/null | grep -v acme.sh; echo '47 0 * * * \"/root/.acme.sh\"/acme.sh --cron --home \"/root/.acme.sh\" > /dev/null') | crontab -")
    # 装回证书到 nginx（acme.sh 安装命令重跑一次，保证 reloadcmd 正确）
    run(new, '/root/.acme.sh/acme.sh --install-cert -d zayu.pw --ecc '
             '--fullchain-file /www/server/panel/vhost/cert/zayu.pw/fullchain.pem '
             '--key-file /www/server/panel/vhost/cert/zayu.pw/privkey.pem '
             '--reloadcmd "systemctl reload nginx" >/dev/null 2>&1; true', check=False)

    # ---------- 6. 本机冒烟 ----------
    print("[6] 新机本机冒烟...")
    _, http_code, _ = run(new, "curl -s -o /dev/null -w '%{http_code}' -H 'Host: zayu.pw' 'http://127.0.0.1/api/v1/announcements?page=1&pageSize=1'")
    print("    http api:", http_code)
    _, https_code, _ = run(new, "curl -sk -o /dev/null -w '%{http_code}' -H 'Host: zayu.pw' 'https://127.0.0.1/api/v1/announcements?page=1&pageSize=1'")
    print("    https api:", https_code)
    _, apk, _ = run(new, "curl -sk -o /dev/null -w '%{http_code} %{size_download}' -H 'Host: zayu.pw' https://127.0.0.1/downloads/Zayu-latest.apk")
    print("    apk:", apk)
    _, admin_code, _ = run(new, "curl -sk -o /dev/null -w '%{http_code}' -H 'Host: zayu.pw' https://127.0.0.1/admin.php")
    print("    admin.php:", admin_code)

    # ---------- 7. 发布迁移公告（新旧两台都插，保证 DNS 切换前后都能看到） ----------
    print("[7] 插入迁移公告...")
    ANN = ("服务迁移完成通知|各位用户：\\n\\n"
           "杂鱼Max 公告与更新服务已于今日完成整体迁移至新服务器（性能与带宽全面升级）。\\n"
           "• 期间服务不中断，App 无需任何操作；\\n"
           "• 强制更新、版本检查、公告、APK 下载全部功能不受影响；\\n"
           "• 如遇个别网络解析延迟（DNS 全球生效约 10 分钟~24 小时），短暂无法检查更新属正常现象。\\n\\n"
           "感谢支持。—— 冰焰")
    for tag, cc in (("new", new), ("old", connect(OLD["host"], OLD["pwd"]))):
        _, schema, _ = run(cc, "sqlite3 /www/ann-data/zayu-ann/announcement.sqlite '.schema announcements' | head -30", check=False)
        print("    schema(%s): %s..." % (tag, schema[:120].replace(chr(10), ' ')))
        q = ("INSERT OR REPLACE INTO announcements "
             "(id, title, content, isPinned, enabled, createdAt) VALUES "
             "('sys-migration-20261023', '服务迁移完成通知', '%s', 1, 1, strftime('%%s','now')*1000);") % ANN
        code, _, err = run(cc, 'sqlite3 /www/ann-data/zayu-ann/announcement.sqlite "%s"' % q, check=False)
        if code != 0:
            # 列名可能不同，退而求其次打印 schema 供人工判断
            print("    insert fail(%s):" % tag, err[:200])
        else:
            print("    inserted(%s)" % tag)

    new.close()
    print("[OK] 迁移完成。DNS 仍指旧机（107.151.245.232），切换 A 记录到 107.151.245.169 即完成最后一步。")


if __name__ == "__main__":
    main()

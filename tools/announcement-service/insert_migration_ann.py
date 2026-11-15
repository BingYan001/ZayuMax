# -*- coding: utf-8 -*-
"""迁移公告插入：按真实表结构在两台服务器各插一条置顶公告。"""
import time
import paramiko

OLD = dict(host="107.151.245.232", pwd="bqdp0zmntIFS")
NEW = dict(host="107.151.245.169", pwd="f2nQGmX0MPf3")

ANN_ID = "sys-migration-20261023"
TITLE = "服务迁移完成通知"
SUMMARY = "公告与更新服务已整体迁移至新服务器，App 无需任何操作。"
CONTENT = "\n".join([
    "各位用户：",
    "",
    "杂鱼Max 公告与更新服务已于今日完成整体迁移至新服务器（性能与带宽全面升级）。",
    "· 期间服务不中断，App 无需任何操作；",
    "· 强制更新、版本检查、公告、APK 下载全部功能不受影响；",
    "· 如遇个别网络解析延迟（DNS 全球生效约 10 分钟~24 小时），短暂无法检查更新属正常现象。",
    "",
    "感谢支持。—— 冰焰",
])


def conn(host, pwd):
    last = None
    for _ in range(4):
        try:
            c = paramiko.SSHClient()
            c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
            c.connect(host, 22, "root", pwd, timeout=30, banner_timeout=60,
                      allow_agent=False, look_for_keys=False)
            return c
        except Exception as e:
            last = e
            time.sleep(3)
    raise last


def run(c, cmd, timeout=60):
    i, o, e = c.exec_command(cmd, timeout=timeout)
    code = o.channel.recv_exit_status()
    return code, o.read().decode(errors="replace").strip(), e.read().decode(errors="replace").strip()


def sqlite_quote(s):
    return s.replace("'", "''")


SQL = (
    "INSERT OR REPLACE INTO announcements "
    "(id,title,summary,content,is_pinned,pinned_at,status,author,created_at,updated_at) VALUES ("
    "'%s','%s','%s','%s',1,strftime('%%s','now'),'published','冰焰',datetime('now'),datetime('now'));"
    % (ANN_ID, sqlite_quote(TITLE), sqlite_quote(SUMMARY), sqlite_quote(CONTENT))
)

for tag, cfg in (("new", NEW), ("old", OLD)):
    c = conn(cfg["host"], cfg["pwd"])
    code, out, err = run(c, "sqlite3 /www/ann-data/zayu-ann/announcement.sqlite \"%s\"" % SQL)
    print(tag, "insert rc=", code, err[:200])
    code, out, err = run(c,
        'sqlite3 /www/ann-data/zayu-ann/announcement.sqlite '
        '"select id||\' | \'||title||\' | pin=\'||is_pinned||\' | \'||status '
        'from announcements where id=\'%s\';"' % ANN_ID)
    print("  verify:", out)
    c.close()

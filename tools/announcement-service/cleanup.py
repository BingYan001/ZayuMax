# -*- coding: utf-8 -*-
"""清理测试数据 + 打印最终状态"""
import json

from deploy import connect, run

DOMAIN = "zayu.pw"

c = connect()

# 用服务器上的 python3 发请求（避免 shell 转义）
script = r'''
import json, urllib.request
tok = open('/www/ann-data/zayu-ann/admin.token').read().strip()
def req(method, path, body=None):
    r = urllib.request.Request('http://127.0.0.1' + path, method=method)
    r.add_header('Host', 'zayu.pw')
    r.add_header('Content-Type', 'application/json')
    r.add_header('Authorization', 'Bearer ' + tok)
    if body is not None:
        r.data = json.dumps(body, ensure_ascii=False).encode('utf-8')
    try:
        with urllib.request.urlopen(r, timeout=20) as resp:
            return json.loads(resp.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode('utf-8'))

# 1. 列出全部并删除（清空测试数据）
d = req('GET', '/api/v1/admin/announcements')
print('清理前共 %d 条' % d['data']['total'])
for a in d['data']['list']:
    r = req('DELETE', '/api/v1/admin/announcements/' + a['id'])
    print('  删除 %-24s %s' % (a['id'], a['title']))
    assert r.get('success'), r

# 2. 发一条正式的欢迎公告
welcome = {
    "title": "杂鱼Max 公告系统已启用",
    "summary": "公告服务已从第三方迁回自建后端，今后所有更新通知都会在这里发布。",
    "content": (
        "## 欢迎\n\n"
        "杂鱼Max 的公告服务已经切换到自建后端，不再依赖第三方站点，"
        "内容也只由我们维护。\n\n"
        "### 后续会在这里发布\n\n"
        "- 版本更新说明\n"
        "- 接口变动 / 兼容性提醒\n"
        "- 常见问题与使用技巧\n\n"
        "> 如果你有任何建议或遇到问题，欢迎在群里反馈。"
    ),
    "author": "杂鱼团队",
    "publisherName": "杂鱼团队",
    "isPinned": True,
}
r = req('POST', '/api/v1/admin/announcements', welcome)
assert r.get('success'), r
print('已发布：', r['data']['id'], r['data']['title'])

# 3. 最终状态
s = req('GET', '/api/v1/admin/stats')
print('统计：', json.dumps(s['data'], ensure_ascii=False))
lst = req('GET', '/api/v1/announcements?page=1&pageSize=10')
print('客户端可见条数：%d' % lst['data']['total'])
for a in lst['data']['list']:
    print('   %s  %s  (%s)' % ('置顶' if a['isPinned'] else '    ', a['title'], a['author']))
'''

sftp = c.open_sftp()
with sftp.open("/root/zayu-clean.py", "w") as f:
    f.write(script)
sftp.close()

run(c, "python3 /root/zayu-clean.py 2>&1", check=False)
run(c, "rm -f /root/zayu-clean.py", check=False)

print()
print("=" * 50)
run(c, "du -sh /www/wwwroot/zayu-ann /www/ann-data/zayu-ann; echo; ls -l /www/wwwroot/zayu-ann /www/ann-data/zayu-ann; echo; df -h / | tail -1", check=False)
c.close()

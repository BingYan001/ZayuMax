# -*- coding: utf-8 -*-
"""
杂鱼公告管理台：zayu.pw 公告后台的桌面客户端（Edge --app 壳）。

Why 用本地包装页而不是直接 --app 指向 admin.php：
  --app 窗口标题 = 页面标题，而生命周期窗口探测按 APP_NAME 前缀匹配；
  本地包装页把 <title> 固定为「杂鱼公告管理台」，iframe 全屏加载后台。
"""
import os
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from shell_common import (LocalServer, cleanup_port_file, free_port,  # noqa: E402
                          launch_edge, monitor_lifecycle, write_port_file)

ADMIN_URL = "https://zayu.pw/admin.php"

PAGE = """<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<title>杂鱼公告管理台</title>
<style>html,body{margin:0;height:100%;background:#0b1020}
iframe{border:0;width:100%;height:100%;display:block}
#tip{color:#9aa6c0;font:14px "Segoe UI","Microsoft YaHei";padding:40px;text-align:center}</style>
</head><body>
<div id="tip">正在加载公告管理台…（若长时间空白，请检查网络）</div>
<iframe src="__URL__" style="display:none" onload="this.style.display='block';document.getElementById('tip').style.display='none'"></iframe>
</body></html>"""


def main():
    port = free_port(19331)
    port_file = os.path.join(os.path.dirname(os.path.abspath(sys.argv[0])), "admin-ui.port")
    write_port_file(port_file, port)

    html = PAGE.replace("__URL__", ADMIN_URL)
    beat = monitor_lifecycle("杂鱼公告管理台")

    from http.server import BaseHTTPRequestHandler

    class H(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            body = html.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_POST(self):
            # 兜底心跳（窗口探测不可用时保活）
            self.rfile.read(int(self.headers.get("Content-Length", 0) or 0))
            beat()
            self.send_response(200)
            self.send_header("Content-Length", "2")
            self.end_headers()
            self.wfile.write(b"{}")

    srv = LocalServer(("127.0.0.1", port), H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    launch_edge("http://127.0.0.1:%d/" % port, 1200, 880)

    try:
        while True:
            time.sleep(3600)
    finally:
        srv.shutdown()
        cleanup_port_file()


if __name__ == "__main__":
    main()

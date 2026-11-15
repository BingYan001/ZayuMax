# -*- coding: utf-8 -*-
"""
zayu 桌面客户端公共壳：Edge --app 窗口 + 本地 HTTP 服务 + 生命周期管理。

生命周期采用「双信号取或」方案（见 edge-app-ui-shell skill 实测教训）：
  1. 主信号：Win32 EnumWindows 找标题以 APP_NAME 开头的可见顶层窗口；
  2. 兜底：页面心跳（后台窗口会被 Chromium 节流，不能只靠心跳）。
窗口消失 + 心跳停止（含 10s 宽限）才退出，绝不留孤儿窗口。
"""
import ctypes
import json
import os
import socket
import subprocess
import sys
import threading
import time
from http.server import ThreadingHTTPServer

APP_PORT_FILE = None  # 由各客户端 main() 设置

if sys.platform == "win32":
    import ctypes.wintypes  # noqa: F401

    _user32 = ctypes.windll.user32


def free_port(preferred):
    """取一个可用端口：优先 preferred，被占则随机。"""
    for p in (preferred, 0):
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            s.bind(("127.0.0.1", p))
            port = s.getsockname()[1]
            return port
        except OSError:
            continue
        finally:
            s.close()
    raise RuntimeError("no free port")


class LocalServer(ThreadingHTTPServer):
    """Windows 下 Python 默认 allow_reuse_address=True 会造成双进程同绑各接一半连接，
    现象是「接口时好时坏」，必须显式关掉。"""

    allow_reuse_address = False
    daemon_threads = True


def find_edge():
    """Edge / WebView2 探测顺序：x86 → x64 → WebView2 运行时。"""
    cands = [
        os.path.join(os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)"),
                     r"Microsoft\Edge\Application\msedge.exe"),
        os.path.join(os.environ.get("ProgramFiles", r"C:\Program Files"),
                     r"Microsoft\Edge\Application\msedge.exe"),
        os.path.join(os.environ.get("LOCALAPPDATA", ""),
                     r"Microsoft\EdgeWebView\Application"),
    ]
    for c in cands:
        if c.lower().endswith("msedge.exe") and os.path.isfile(c):
            return c
        if os.path.isdir(c):  # WebView2 目录：取版本号子目录里的 msedgewebview2.exe
            try:
                for ver in sorted(os.listdir(c), reverse=True):
                    exe = os.path.join(c, ver, "msedgewebview2.exe")
                    if os.path.isfile(exe):
                        return exe
            except OSError:
                pass
    return None


def launch_edge(url, width, height):
    exe = find_edge()
    if not exe:
        raise RuntimeError("未找到 Edge，请确认系统已安装 Microsoft Edge")
    args = [
        exe, f"--app={url}", f"--window-size={width},{height}",
        "--disable-background-timer-throttling",
        "--disable-backgrounding-occluded-windows",
        "--disable-renderer-backgrounding",
        "--disable-features=CalculateNativeWinOcclusion",
        "--proxy-server=direct://",  # 本地页面一律直连，杜绝系统代理劫持 127.0.0.1
    ]
    subprocess.Popen(args)


def ui_window_present(app_name):
    """Win32 枚举顶层窗口：是否存在「标题以 app_name 开头」的可见窗口。
    返回 True/False；非 Windows 或枚举失败返回 None（调用方回退心跳）。"""
    if sys.platform != "win32":
        return None
    hit = [False]

    @ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)
    def cb(hwnd, _):
        try:
            if not _user32.IsWindowVisible(hwnd):
                return True
            n = _user32.GetWindowTextLengthW(hwnd)
            if n <= 0:
                return True
            buf = ctypes.create_unicode_buffer(n + 1)
            _user32.GetWindowTextW(hwnd, buf, n + 1)
            if buf.value.strip().startswith(app_name):
                hit[0] = True
                return False
        except Exception:
            pass
        return True

    try:
        _user32.EnumWindows(cb, 0)
    except Exception:
        return None
    return hit[0]


def write_port_file(path, port):
    global APP_PORT_FILE
    APP_PORT_FILE = path
    try:
        with open(path, "w", encoding="utf-8") as f:
            f.write(str(port))
    except OSError:
        APP_PORT_FILE = None


def cleanup_port_file():
    if APP_PORT_FILE and os.path.isfile(APP_PORT_FILE):
        try:
            # 只删自己写的内容（防竞态：端口文件已被别人换写时不动）
            with open(APP_PORT_FILE, "r", encoding="utf-8") as f:
                if f.read().strip().isdigit():
                    os.remove(APP_PORT_FILE)
        except OSError:
            pass


def monitor_lifecycle(app_name, on_dead=None, grace_seconds=10, fallback_seconds=180):
    """后台线程：双信号判定窗口死亡后调用 on_dead（默认退出进程）。
    - 窗口在（或探测不可用但有心跳）→ 继续；
    - 窗口消失 → 进入宽限期；宽限内心跳恢复则续命；
    - 从未见过窗口也没心跳 → fallback_seconds 兜底退出（窗口没打开起来）。"""
    import threading as _t

    state = {"heartbeat": 0.0, "ever_seen": False, "ever_heartbeat": False}

    def beat():
        state["heartbeat"] = time.time()
        state["ever_heartbeat"] = True

    def run():
        gone_since = None
        while True:
            time.sleep(3)
            present = ui_window_present(app_name)
            now = time.time()
            hb_recent = (now - state["heartbeat"]) < 60
            if present is True:
                state["ever_seen"] = True
                gone_since = None
                continue
            if present is None:
                # 探测不可用：纯心跳兜底（60s 容忍后台节流）
                if state["ever_heartbeat"] and not hb_recent:
                    if on_dead:
                        on_dead()
                    return
                continue
            # present is False
            if state["ever_heartbeat"] and hb_recent:
                gone_since = None  # 心跳还活着（页面开在别的窗口），续命
                continue
            if gone_since is None:
                gone_since = now
                continue
            if now - gone_since > grace_seconds:
                if state["ever_seen"] or state["ever_heartbeat"] or now - _start_ts > fallback_seconds:
                    if on_dead:
                        on_dead()
                    return

    _start_ts = time.time()
    t = _t.Thread(target=run, daemon=True, name="lifecycle")
    t.start()
    return beat


def json_response(obj, code=200):
    body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
    return code, {"Content-Type": "application/json; charset=utf-8",
                  "Cache-Control": "no-store"}, body

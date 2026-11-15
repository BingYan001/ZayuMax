# -*- coding: utf-8 -*-
"""
杂鱼互传（zayu-lan-1）PC 端：与杂鱼Max App 局域网互传功能互通的 Windows 客户端。

协议（与 app/src/main/kotlin/com/zayu/app/data/lan/ 逐字节对齐）：
  - 发现：UDP 52390 广播 announce {"protocol":"zayu-lan-1","type":"announce",
          "id","name","port":52391,"fp"}，3s 周期，10s 超时摘除；
  - 文本：TCP 单行 JSON {"protocol","type":"text","token","fp","body"}+\\n
          （token 32位hex 一次性；fp 必须等于接收方自 token 的 SHA-256 前 12 位）；
  - 文件：offer 行 JSON {"protocol","type":"offer","token","fp","name","size"}+\\n
          <- ack 行 {"ok":true}+\\n  -> 恰好 size 字节裸流（64KB 分块）；
  - 安全：只收发私网地址；文件名严校验拒路径穿越；上限 512MB。

UI：Edge --app 玻璃深色单页（内嵌 HTML，Lucide 风格内联 SVG 图标）。
"""
import hashlib
import json
import os
import queue
import re
import socket
import subprocess
import sys
import threading
import time
import urllib.parse
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from shell_common import (LocalServer, cleanup_port_file, free_port,  # noqa: E402
                          json_response, launch_edge, monitor_lifecycle,
                          ui_window_present, write_port_file)

APP_NAME = "杂鱼互传"
APP_VERSION = "1.0.0"
PROTO = "zayu-lan-1"
DISCOVERY_PORT = 52390
TRANSFER_PORT = 52391
ANNOUNCE_INTERVAL = 3.0
PEER_TIMEOUT = 10.0
MAX_TEXT = 32768
MAX_FILE = 512 * 1024 * 1024
CHUNK = 64 * 1024
MAX_HEADER = 64 * 1024

BASE_DIR = os.path.dirname(os.path.abspath(sys.argv[0]))  # onefile 下 argv[0] 即 exe 路径
RECEIVED_DIR = os.path.join(BASE_DIR, "Received")


def now_ms():
    return int(time.time() * 1000)


def fingerprint(token):
    return hashlib.sha256(token.encode("utf-8")).hexdigest()[:12]


def display_code(fp):
    return "-".join(fp[i:i + 4] for i in range(0, 12, 4))


def is_private_ip(ip):
    m = re.match(r"^(\d+)\.(\d+)\.(\d+)\.(\d+)$", ip or "")
    if not m:
        return False
    a, b, c, d = map(int, m.groups())
    if any(v > 255 for v in (a, b, c, d)):
        return False
    return a == 10 or a == 127 or (a == 192 and b == 168) or (a == 172 and 16 <= b <= 31)


def sanitize_name(raw):
    if not raw or len(raw) > 255 or "/" in raw or "\\" in raw or ".." in raw:
        return None
    if raw == "." or raw.endswith(":") or any(ord(ch) < 32 for ch in raw):
        return None
    cleaned = re.sub(r'[<>:"|?*]', "_", raw)
    return cleaned or None


def local_ip():
    """本机私网 IPv4（授权链接用）；枚举路由表探测默认出口最准。"""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))  # 不实际发包，只让内核选路由
        ip = s.getsockname()[0]
        return ip if is_private_ip(ip) and not ip.startswith("127.") else None
    except OSError:
        return None
    finally:
        s.close()


class LanNode:
    """发现 + 传输一体的本机节点。线程模型：UDP 收/发、TCP accept、摘除各一线程。"""

    def __init__(self, name):
        self.name = name
        self.id = uuid.uuid4().hex
        self.token = uuid.uuid4().hex  # 32 位 hex，与 App TOKEN_LEN 一致
        self.fp = fingerprint(self.token)
        self.port = TRANSFER_PORT
        self.listening = False
        self.peers = {}          # id -> peer dict
        self.manual_peers = {}   # "ip:port" -> peer dict
        self.records = []        # 接收记录 [{kind, from, detail, at}]
        self.lock = threading.RLock()
        self.udp = None
        self.tcp = None
        self.threads = []

    # ---- 对外状态 ----
    def status(self):
        ip = local_ip()
        with self.lock:
            peers = sorted(self.peers.values(), key=lambda p: p["name"])
            for m in self.manual_peers.values():
                if not any(p["ip"] == m["ip"] and p["port"] == m["port"] for p in peers):
                    peers.append(m)
            return {
                "self": {
                    "name": self.name, "id": self.id,
                    "code": display_code(self.fp), "fp": self.fp,
                    "ip": ip or "", "port": self.port,
                    "link": ("zayu://lan?ip=%s&port=%d&fp=%s&name=%s"
                             % (ip, self.port, self.fp, urllib.parse.quote(self.name))) if ip else "",
                    "listening": self.listening,
                },
                "peers": [{"id": p["id"], "name": p["name"], "ip": p["ip"],
                           "port": p["port"], "code": display_code(p["fp"]),
                           "manual": p.get("manual", False)} for p in peers],
                "records": self.records[:50],
            }

    # ---- 生命周期 ----
    def start(self):
        if self.listening:
            return
        try:
            self.udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self.udp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:  # Windows 上还要 SO_REUSEADDR 之外的显式绑定许可
                self.udp.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            except OSError:
                pass
            self.udp.bind(("", DISCOVERY_PORT))
            self.udp.settimeout(1.0)
        except OSError as e:
            self.add_record("错误", "本机", "发现端口 %d 绑定失败: %s" % (DISCOVERY_PORT, e))
            self.udp = None

        try:
            self.tcp = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.tcp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, False)
            self.tcp.bind(("", self.port))
            self.tcp.listen(8)
        except OSError as e:
            self.add_record("错误", "本机", "传输端口 %d 绑定失败: %s" % (self.port, e))
            self.tcp = None

        self.listening = True
        for name, fn in (("announce", self._loop_announce),
                         ("discover", self._loop_discover),
                         ("accept", self._loop_accept),
                         ("prune", self._loop_prune)):
            t = threading.Thread(target=fn, daemon=True, name="zayu-" + name)
            t.start()
            self.threads.append(t)

    def stop(self):
        self.listening = False
        for s in (self.udp, self.tcp):
            try:
                if s:
                    s.close()
            except OSError:
                pass
        self.udp = self.tcp = None
        with self.lock:
            self.peers.clear()

    # ---- 发现 ----
    def _loop_announce(self):
        payload = json.dumps({"protocol": PROTO, "type": "announce", "id": self.id,
                              "name": self.name, "port": self.port, "fp": self.fp}
                             ).encode("utf-8")
        while self.listening:
            if self.udp:
                try:
                    self.udp.sendto(payload, ("255.255.255.255", DISCOVERY_PORT))
                except OSError:
                    pass
            time.sleep(ANNOUNCE_INTERVAL)

    def _loop_discover(self):
        while self.listening:
            if not self.udp:
                time.sleep(1.0)
                continue
            try:
                data, addr = self.udp.recvfrom(2048)
            except socket.timeout:
                continue
            except OSError:
                break
            ip = addr[0]
            if not is_private_ip(ip):
                continue
            try:
                obj = json.loads(data.decode("utf-8"))
            except (ValueError, UnicodeDecodeError):
                continue
            if (obj.get("protocol") != PROTO or obj.get("type") != "announce"
                    or not obj.get("id") or len(obj.get("fp", "")) != 12):
                continue
            if obj["id"] == self.id or obj["fp"] == self.fp:  # 自包过滤
                continue
            peer = {"id": obj["id"], "name": str(obj.get("name", ip))[:64],
                    "ip": ip, "port": int(obj.get("port", 0) or 0),
                    "fp": obj["fp"], "seen": time.time()}
            if not (0 < peer["port"] < 65536):
                continue
            changed = False
            with self.lock:
                old = self.peers.get(peer["id"])
                if not old or old["name"] != peer["name"] or old["port"] != peer["port"]:
                    changed = True
                self.peers[peer["id"]] = peer
            if changed:
                pass  # UI 轮询拉取，无需主动推

    def _loop_prune(self):
        while self.listening:
            time.sleep(2.0)
            cutoff = time.time() - PEER_TIMEOUT
            with self.lock:
                for pid in [k for k, v in self.peers.items() if v["seen"] < cutoff]:
                    del self.peers[pid]

    # ---- 接收 ----
    def _loop_accept(self):
        while self.listening:
            if not self.tcp:
                time.sleep(1.0)
                continue
            try:
                conn, addr = self.tcp.accept()
            except OSError:
                break
            threading.Thread(target=self._handle, args=(conn, addr),
                             daemon=True, name="zayu-conn").start()

    def _handle(self, conn, addr):
        ip = addr[0]
        try:
            conn.settimeout(15)
            if not is_private_ip(ip):
                conn.close()
                return
            first = self._read_line(conn)
            if not first:
                conn.close()
                return
            try:
                head = json.loads(first)
            except ValueError:
                conn.close()
                return
            t = head.get("type")
            if t == "text":
                self._recv_text(conn, head, ip)
            elif t == "offer":
                self._recv_file(conn, head, ip)
            else:
                conn.close()
        except (OSError, ValueError):
            try:
                conn.close()
            except OSError:
                pass

    def _verify(self, head):
        token = head.get("token", "")
        if not re.match(r"^[0-9a-f]{32}$", token or ""):
            raise ValueError("非法 token")
        if head.get("fp") != self.fp:  # 对端必须回带「我方 token 指纹」
            raise ValueError("握手指纹校验失败")

    def _recv_text(self, conn, head, ip):
        try:
            self._verify(head)
            body = str(head.get("body", ""))
            if not body or len(body) > MAX_TEXT:
                raise ValueError("文本为空或超长")
            self.add_record("文本", ip, body)
        except ValueError as e:
            self.add_record("拒绝", ip, "文本被拒: %s" % e)
        finally:
            try:
                conn.close()
            except OSError:
                pass

    def _recv_file(self, conn, head, ip):
        path = None
        try:
            self._verify(head)
            name = sanitize_name(str(head.get("name", "")))
            if not name:
                raise ValueError("非法文件名")
            size = int(head.get("size", -1))
            if not (0 <= size <= MAX_FILE):
                raise ValueError("文件大小越界")
            conn.sendall(b'{"protocol":"%s","type":"ack","ok":true,"reason":""}\n' % PROTO.encode())
            conn.settimeout(30)
            os.makedirs(RECEIVED_DIR, exist_ok=True)
            path = self._unique_path(name)
            got = 0
            with open(path, "wb") as f:
                while got < size:
                    want = min(CHUNK, size - got)
                    data = conn.recv(want)
                    if not data:
                        break
                    f.write(data)
                    got += len(data)
            if got != size:
                raise ValueError("传输不完整(%d/%d)" % (got, size))
            self.add_record("文件", ip, "%s (%s)" % (name, human(size)))
        except ValueError as e:
            self.add_record("拒绝", ip, "文件被拒: %s" % e)
            if path and os.path.isfile(path):
                try:
                    os.remove(path)
                except OSError:
                    pass
        except OSError as e:
            self.add_record("错误", ip, "接收失败: %s" % e)
            if path and os.path.isfile(path):
                try:
                    os.remove(path)
                except OSError:
                    pass
        finally:
            try:
                conn.close()
            except OSError:
                pass

    def _unique_path(self, name):
        path = os.path.join(RECEIVED_DIR, name)
        if not os.path.exists(path):
            return path
        base, ext = os.path.splitext(name)
        return os.path.join(RECEIVED_DIR, "%s_%d%s" % (base, int(time.time() * 1000), ext))

    def _read_line(self, conn):
        buf = bytearray()
        while True:
            b = conn.recv(1)
            if not b:
                return bytes(buf).decode("utf-8", "replace") if buf else None
            if b == b"\n":
                return buf.decode("utf-8", "replace")
            if b != b"\r":
                buf += b
            if len(buf) > MAX_HEADER:
                return None

    # ---- 发送 ----
    def send_text(self, peer, body):
        if not body or len(body) > MAX_TEXT:
            raise ValueError("文本为空或超长")
        if not is_private_ip(peer["ip"]):
            raise ValueError("目标地址非私网")
        msg = json.dumps({"protocol": PROTO, "type": "text", "token": uuid.uuid4().hex,
                          "fp": peer["fp"], "body": body})
        with socket.create_connection((peer["ip"], peer["port"]), timeout=5) as s:
            s.settimeout(10)
            s.sendall((msg + "\n").encode("utf-8"))
            time.sleep(0.2)  # 与 App 同款：给对端处理时间再收尾

    def send_file(self, peer, path, progress=None):
        if not is_private_ip(peer["ip"]):
            raise ValueError("目标地址非私网")
        size = os.path.getsize(path)
        if size > MAX_FILE:
            raise ValueError("文件超过 512MB 上限")
        name = sanitize_name(os.path.basename(path))
        if not name:
            raise ValueError("文件名不合法")
        offer = json.dumps({"protocol": PROTO, "type": "offer", "token": uuid.uuid4().hex,
                            "fp": peer["fp"], "name": name, "size": size})
        with socket.create_connection((peer["ip"], peer["port"]), timeout=5) as s:
            s.settimeout(15)
            s.sendall((offer + "\n").encode("utf-8"))
            ack_line = self._read_line(s)
            if not ack_line:
                raise ValueError("对端无应答")
            try:
                ack = json.loads(ack_line)
            except ValueError:
                raise ValueError("对端应答非法")
            if not ack.get("ok"):
                raise ValueError("对端拒绝: %s" % ack.get("reason", ""))
            s.settimeout(30)
            sent = 0
            with open(path, "rb") as f:
                while True:
                    data = f.read(CHUNK)
                    if not data:
                        break
                    s.sendall(data)
                    sent += len(data)
                    if progress:
                        progress(sent, size)
            if sent != size:
                raise ValueError("发送不完整(%d/%d)" % (sent, size))
        return sent

    # ---- 手动配对 ----
    def pair(self, raw):
        """解析授权链接或「IP + 授权码」，成功返回 peer dict，失败返回 None。"""
        text = (raw or "").strip()
        if not text:
            return None
        peer = None
        if text.lower().startswith("zayu://lan?"):
            q = dict(kv.split("=", 1) for kv in text.split("?", 1)[1].split("&") if "=" in kv)
            fp = (q.get("fp") or "").replace("-", "").lower()
            ip = q.get("ip", "")
            port = int(q.get("port", "0") or 0)
            if re.match(r"^[0-9a-f]{12}$", fp) and is_private_ip(ip) and 0 < port < 65536:
                from urllib.parse import unquote
                peer = {"id": "manual-%s-%d" % (ip, port), "name": unquote(q.get("name", ip)) or ip,
                        "ip": ip, "port": port, "fp": fp, "seen": time.time() + 1e9, "manual": True}
        else:
            m = re.match(r"^([0-9.]{7,15})[\s:]+([0-9a-fA-F-]{12,16})$", text)
            if m:
                ip = m.group(1)
                fp = m.group(2).replace("-", "").lower()
                if is_private_ip(ip) and re.match(r"^[0-9a-f]{12}$", fp):
                    peer = {"id": "manual-%s" % ip, "name": ip, "ip": ip,
                            "port": TRANSFER_PORT, "fp": fp, "seen": time.time() + 1e9, "manual": True}
        if peer:
            with self.lock:
                self.manual_peers["%s:%d" % (peer["ip"], peer["port"])] = peer
        return peer

    def add_record(self, kind, frm, detail):
        with self.lock:
            self.records.insert(0, {"kind": kind, "from": frm, "detail": detail, "at": now_ms()})
            del self.records[60:]


def human(n):
    if n >= 1024 * 1024:
        return "%.1f MB" % (n / 1048576.0)
    if n >= 1024:
        return "%.1f KB" % (n / 1024.0)
    return "%d B" % n


def pick_files_win32():
    """Win32 公共「打开文件」对话框（多选），返回绝对路径列表。
    Why 不用 tkinter：打包环境不带 tk；comdlg32 是系统组件，零依赖。"""
    if sys.platform != "win32":
        return []
    import ctypes
    from ctypes import wintypes

    class OPENFILENAME(ctypes.Structure):
        _fields_ = [
            ("lStructSize", wintypes.DWORD),
            ("hwndOwner", wintypes.HWND),
            ("hInstance", wintypes.HINSTANCE),
            ("lpstrFilter", wintypes.LPCWSTR),
            ("lpstrCustomFilter", wintypes.LPWSTR),
            ("nMaxCustFilter", wintypes.DWORD),
            ("nFilterIndex", wintypes.DWORD),
            ("lpstrFile", wintypes.LPWSTR),
            ("nMaxFile", wintypes.DWORD),
            ("lpstrFileTitle", wintypes.LPWSTR),
            ("nMaxFileTitle", wintypes.DWORD),
            ("lpstrInitialDir", wintypes.LPCWSTR),
            ("lpstrTitle", wintypes.LPCWSTR),
            ("Flags", wintypes.DWORD),
            ("nFileOffset", wintypes.WORD),
            ("nFileExtension", wintypes.WORD),
            ("lpstrDefExt", wintypes.LPCWSTR),
            ("lCustData", ctypes.c_void_p),
            ("lpfnHook", ctypes.c_void_p),
            ("lpTemplateName", wintypes.LPCWSTR),
            ("pvReserved", ctypes.c_void_p),
            ("dwReserved", wintypes.DWORD),
            ("FlagsEx", wintypes.DWORD),
        ]

    OFN_ALLOWMULTISELECT = 0x200
    OFN_EXPLORER = 0x00080000
    OFN_FILEMUSTEXIST = 0x1000
    OFN_NOCHANGEDIR = 0x8
    buf = ctypes.create_unicode_buffer(65536)
    ofn = OPENFILENAME()
    ofn.lStructSize = ctypes.sizeof(OPENFILENAME)
    ofn.lpstrFilter = "所有文件\0*.*\0"
    ofn.lpstrFile = ctypes.cast(buf, wintypes.LPWSTR)
    ofn.nMaxFile = 65536
    ofn.lpstrTitle = "选择要发送的文件（可多选）"
    ofn.Flags = OFN_ALLOWMULTISELECT | OFN_EXPLORER | OFN_FILEMUSTEXIST | OFN_NOCHANGEDIR
    if not ctypes.windll.comdlg32.GetOpenFileNameW(ctypes.byref(ofn)):
        return []
    parts = buf.value.split("\x00")
    parts = [p for p in parts if p]
    if not parts:
        return []
    if len(parts) == 1:
        return [parts[0]]
    folder = parts[0]
    out = []
    for name in parts[1:]:
        out.append(name if "\\" in name or "/" in name else os.path.join(folder, name))
    return out


# ======================================================================
# HTTP 服务与前端
# ======================================================================
NODE = LanNode(socket.gethostname()[:24] or "PC")
JOBS = {}
JOB_SEQ = [0]

from http.server import BaseHTTPRequestHandler  # noqa: E402


def make_handler(page_html):
    class ApiHandler(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def _send(self, code, headers, body):
            self.send_response(code)
            for k, v in headers.items():
                self.send_header(k, v)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.path in ("/", "/index.html"):
                self._send(200, {"Content-Type": "text/html; charset=utf-8",
                                 "Cache-Control": "no-store"}, page_html.encode("utf-8"))
            elif self.path == "/api/status":
                self._send(*json_response(NODE.status()))
            elif self.path == "/api/job":
                from urllib.parse import parse_qs, urlparse
                q = parse_qs(urlparse(self.path).query)
                jid = (q.get("id") or [""])[0]
                job = JOBS.get(jid, {"state": "gone"})
                self._send(*json_response(job))
            else:
                self._send(404, {"Content-Type": "text/plain"}, b"not found")

        def do_POST(self):
            length = int(self.headers.get("Content-Length", 0) or 0)
            try:
                body = json.loads(self.rfile.read(length).decode("utf-8") or "{}") if length else {}
            except ValueError:
                body = {}
            path = self.path
            if path == "/api/ping":
                self._beat()
                self._send(*json_response({"ok": True}))
            elif path == "/api/bye":
                self._send(*json_response({"ok": True}))  # no-op：刷新/跳转不能杀服务
            elif path == "/api/toggle":
                if body.get("enabled"):
                    NODE.start()
                else:
                    NODE.stop()
                self._send(*json_response(NODE.status()))
            elif path == "/api/pair":
                peer = NODE.pair(body.get("input", ""))
                if peer:
                    self._send(*json_response({"ok": True, "name": peer["name"]}))
                else:
                    self._send(*json_response({"ok": False,
                                               "msg": "格式不对：请粘贴授权链接或「IP + 授权码」"}))
            elif path == "/api/send_text":
                peer = self._find_peer(body.get("peer_id", ""))
                if not peer:
                    self._send(*json_response({"ok": False, "msg": "未找到目标设备"}))
                    return
                try:
                    NODE.send_text(peer, str(body.get("body", "")))
                    self._send(*json_response({"ok": True}))
                except (OSError, ValueError) as e:
                    self._send(*json_response({"ok": False, "msg": str(e)}))
            elif path == "/api/send_file":
                peer = self._find_peer(body.get("peer_id", ""))
                path_ = body.get("path", "")
                if not peer or not path_ or not os.path.isfile(path_):
                    self._send(*json_response({"ok": False, "msg": "目标设备或文件无效"}))
                    return
                JOB_SEQ[0] += 1
                jid = "j%d" % JOB_SEQ[0]
                JOBS[jid] = {"state": "running", "sent": 0, "size": 0, "name": os.path.basename(path_)}

                def run(jid=jid, peer=peer, path_=path_):
                    try:
                        def prog(sent, size):
                            JOBS[jid].update(sent=sent, size=size)
                        NODE.send_file(peer, path_, prog)
                        JOBS[jid]["state"] = "done"
                    except (OSError, ValueError) as e:
                        JOBS[jid].update(state="fail", msg=str(e))
                threading.Thread(target=run, daemon=True).start()
                self._send(*json_response({"ok": True, "id": jid}))
            elif path == "/api/browse_file":
                paths = pick_files_win32()
                self._send(*json_response({"ok": True, "paths": paths}))
            elif path == "/api/open_received":
                try:
                    os.makedirs(RECEIVED_DIR, exist_ok=True)
                    subprocess.Popen(["explorer", os.path.normpath(RECEIVED_DIR)])
                    self._send(*json_response({"ok": True}))
                except OSError as e:
                    self._send(*json_response({"ok": False, "msg": str(e)}))
            else:
                self._send(404, {"Content-Type": "text/plain"}, b"not found")

        def _find_peer(self, pid):
            with NODE.lock:
                for p in list(NODE.peers.values()) + list(NODE.manual_peers.values()):
                    if p["id"] == pid:
                        return p
            return None

        def _beat(self):
            # 心跳由 shell_common.monitor_lifecycle 返回的 beat 写入，这里借助全局
            global HEARTBEAT_FN
            if HEARTBEAT_FN:
                HEARTBEAT_FN()

    return ApiHandler


# ======================================================================
# 内嵌前端（深色玻璃拟态，Lucide 风格内联 SVG）
# ======================================================================
PAGE = r"""<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>__APP_NAME__</title>
<style>
:root{--bg1:#0b1020;--bg2:#141a2e;--panel:rgba(255,255,255,.06);--stroke:rgba(255,255,255,.12);
--txt:#e8ecf6;--dim:#9aa6c0;--pri:#7aa2ff;--ok:#4ade80;--err:#f87171}
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:"Segoe UI","Microsoft YaHei",system-ui;background:
radial-gradient(900px 500px at 12% -8%,rgba(122,162,255,.16),transparent 60%),
radial-gradient(700px 420px at 96% 8%,rgba(167,139,250,.13),transparent 60%),
linear-gradient(160deg,var(--bg1),var(--bg2));color:var(--txt);min-height:100vh;padding:18px 20px}
.glass{background:linear-gradient(150deg,rgba(255,255,255,.085),rgba(255,255,255,.045));
border:1px solid var(--stroke);border-radius:16px;backdrop-filter:blur(14px);padding:14px 16px}
.row{display:flex;align-items:center;gap:10px}
.sp{flex:1}
h1{font-size:17px;font-weight:600}
.sub{color:var(--dim);font-size:12px}
.ico{width:17px;height:17px;stroke:currentColor;fill:none;stroke-width:2;
stroke-linecap:round;stroke-linejoin:round;flex:none}
.hero{display:flex;align-items:center;gap:16px;margin-bottom:14px}
.hero .badge{width:52px;height:52px;border-radius:50%;display:flex;align-items:center;justify-content:center;
background:linear-gradient(140deg,rgba(122,162,255,.22),rgba(122,162,255,.08));
border:1px solid rgba(122,162,255,.35)}
.hero .badge .ico{width:24px;height:24px;color:var(--pri)}
.code{font-family:Consolas,monospace;font-size:21px;letter-spacing:2px;color:var(--pri)}
button{font:inherit;color:var(--txt);background:rgba(255,255,255,.08);border:1px solid var(--stroke);
border-radius:10px;padding:7px 14px;cursor:pointer;display:inline-flex;align-items:center;gap:7px;
transition:background .15s}
button:hover:not(:disabled){background:rgba(255,255,255,.14)}
button:disabled{opacity:.45;cursor:default}
button.pri{background:linear-gradient(140deg,rgba(122,162,255,.3),rgba(122,162,255,.14));
border-color:rgba(122,162,255,.45)}
button.ico-btn{padding:6px;border-radius:8px}
input[type=text]{font:inherit;color:var(--txt);background:rgba(0,0,0,.25);border:1px solid var(--stroke);
border-radius:10px;padding:8px 12px;width:100%}
input[type=text]:focus{outline:none;border-color:rgba(122,162,255,.5)}
.switch{position:relative;width:44px;height:24px;border-radius:999px;background:rgba(255,255,255,.14);
border:1px solid var(--stroke);cursor:pointer;transition:background .2s;flex:none}
.switch.on{background:rgba(122,162,255,.45)}
.switch::after{content:"";position:absolute;top:2px;left:2px;width:18px;height:18px;border-radius:50%;
background:#fff;transition:left .2s}
.switch.on::after{left:22px}
.peer{display:flex;align-items:center;gap:11px;padding:9px 11px;border-radius:12px;cursor:pointer;
border:1px solid transparent;transition:background .15s}
.peer:hover{background:rgba(255,255,255,.05)}
.peer.sel{background:rgba(122,162,255,.13);border-color:rgba(122,162,255,.4)}
.peer .avatar{width:34px;height:34px;border-radius:10px;background:rgba(255,255,255,.07);
border:1px solid var(--stroke);display:flex;align-items:center;justify-content:center;flex:none}
.peer .avatar .ico{color:var(--dim)}
.peer.sel .avatar .ico{color:var(--pri)}
.empty{color:var(--dim);font-size:12.5px;padding:10px 4px}
.rec{padding:7px 2px;border-bottom:1px solid rgba(255,255,255,.06);font-size:13px}
.rec .meta{color:var(--dim);font-size:11.5px;margin-bottom:2px}
.tag{display:inline-block;font-size:10.5px;padding:1px 7px;border-radius:999px;
background:rgba(122,162,255,.16);color:var(--pri);margin-right:6px}
.tag.err{background:rgba(248,113,113,.15);color:var(--err)}
.tag.ok{background:rgba(74,222,128,.14);color:var(--ok)}
.progress{height:5px;border-radius:99px;background:rgba(255,255,255,.09);overflow:hidden;margin-top:5px}
.progress>div{height:100%;background:linear-gradient(90deg,#7aa2ff,#a78bfa);width:0;transition:width .3s}
.dot{width:8px;height:8px;border-radius:50%;background:var(--dim);flex:none}
.dot.on{background:var(--ok);box-shadow:0 0 8px rgba(74,222,128,.7);animation:pulse 1.6s infinite}
@keyframes pulse{50%{opacity:.5}}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}
@media(max-width:820px){.grid{grid-template-columns:1fr}}
#toast{position:fixed;bottom:22px;left:50%;transform:translateX(-50%);background:rgba(20,26,46,.95);
border:1px solid var(--stroke);border-radius:12px;padding:10px 18px;font-size:13px;opacity:0;
transition:opacity .25s;pointer-events:none;z-index:9;max-width:80%}
#toast.show{opacity:1}
#toast.err{border-color:rgba(248,113,113,.5)}
h2{font-size:13.5px;font-weight:600;margin-bottom:8px;display:flex;align-items:center;gap:7px}
h2 .ico{width:15px;height:15px;color:var(--pri)}
</style></head><body>

<div class="hero glass">
  <div class="badge"><svg class="ico" viewBox="0 0 24 24"><rect x="9" y="2" width="6" height="6" rx="1"/><rect x="2" y="16" width="6" height="6" rx="1"/><rect x="16" y="16" width="6" height="6" rx="1"/><path d="M12 8v4M5 16v-2a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v2"/></svg></div>
  <div style="flex:1">
    <h1>杂鱼互传 <span class="sub" id="selfname"></span></h1>
    <div class="row" style="margin-top:3px">
      <span class="code" id="selfcode">····-····-····</span>
      <button class="ico-btn" title="复制授权码" onclick="copyCode()"><svg class="ico" viewBox="0 0 24 24"><rect x="9" y="9" width="12" height="12" rx="2"/><path d="M5 15V5a2 2 0 0 1 2-2h10"/></svg></button>
    </div>
  </div>
  <div class="row"><span class="dot" id="statedot"></span><span class="sub" id="statetext">未开启</span>
  <div class="switch" id="sw" onclick="toggle()"></div></div>
</div>

<div class="grid">
  <div class="glass">
    <h2><svg class="ico" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M12 3a15 15 0 0 1 0 18M12 3a15 15 0 0 0 0 18M3 12h18"/></svg>附近设备</h2>
    <div id="peers"><div class="empty">正在搜索…</div></div>
    <div style="margin-top:10px">
      <div class="sub" style="margin-bottom:5px">搜索不到？粘贴对方授权链接，或输入「IP + 授权码」：</div>
      <div class="row">
        <input type="text" id="pair" placeholder="zayu://lan?… 或 192.168.1.5 A1B2-C3D4-E5F6">
        <button class="pri" onclick="pair()">添加</button>
      </div>
    </div>
  </div>

  <div class="glass">
    <h2><svg class="ico" viewBox="0 0 24 24"><path d="m22 2-7 20-4-9-9-4Z"/></svg>发送 <span class="sub" id="target"></span></h2>
    <div class="row">
      <input type="text" id="msg" placeholder="输入要发送的文本或链接" style="flex:1">
      <button class="pri" id="sendbtn" onclick="sendText()" disabled><svg class="ico" viewBox="0 0 24 24"><path d="m22 2-7 20-4-9-9-4Z"/></svg>发送</button>
    </div>
    <div class="row" style="margin-top:9px">
      <button onclick="pickFile()" id="filebtn"><svg class="ico" viewBox="0 0 24 24"><path d="M21.44 11.05 12.25 20.24a6 6 0 0 1-8.49-8.49l8.57-8.57A4 4 0 1 1 18 8.84l-8.59 8.57a2 2 0 0 1-2.83-2.83l8.49-8.48"/></svg>发送文件…</button>
      <div id="progwrap" style="flex:1;display:none">
        <div class="sub" id="progname"></div>
        <div class="progress"><div id="progbar"></div></div>
      </div>
    </div>
  </div>
</div>

<div class="glass" style="margin-top:12px">
  <h2><svg class="ico" viewBox="0 0 24 24"><path d="M3 7v10a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-6l-2-2H5a2 2 0 0 0-2 2Z"/></svg>接收记录
    <span class="sp"></span><button class="ico-btn" title="打开接收目录" onclick="openDir()"><svg class="ico" viewBox="0 0 24 24"><path d="M18 6 6 18M6 6l12 12"/></svg></button>
    <span class="sub">Received\</span><button onclick="openDir()" style="padding:4px 10px;font-size:12px">打开目录</button></h2>
  <div id="records"><div class="empty">暂无记录</div></div>
</div>

<div id="toast"></div>
<script>
const $=id=>document.getElementById(id);
let selected=null, pollTimer=null, jobTimer=null, enabled=false;

function toast(msg,err){const t=$('toast');t.textContent=msg;t.className=err?'show err':'show';
clearTimeout(t._h);t._h=setTimeout(()=>t.className='',2600);}

function copyText(s,label){
  (navigator.clipboard?navigator.clipboard.writeText(s):Promise.reject())
  .then(()=>toast(label+' 已复制'))
  .catch(()=>{const i=document.createElement('textarea');i.value=s;document.body.appendChild(i);
    i.select();document.execCommand('copy');i.remove();toast(label+' 已复制');});}
function copyCode(){copyText(state.self.fp,'授权码');}
function copyLink(){copyText(state.self.link,'授权链接');}

async function api(path,body){
  for(let i=0;i<3;i++){
    try{const r=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json'},
      body:JSON.stringify(body||{})});return await r.json();}catch(e){await new Promise(r=>setTimeout(r,400*(i+1)));}
  }
  toast('本地服务连接失败，请点此窗口任意处重试',true);return null;
}
let state={self:{}};
async function poll(){
  try{const r=await fetch('/api/status');state=await r.json();render();}catch(e){}
}
function esc(s){const d=document.createElement('div');d.textContent=s;return d.innerHTML;}

function render(){
  const s=state.self;
  $('selfname').textContent='· '+s.name+' ('+(s.ip||'未联网')+')';
  $('selfcode').textContent=s.code||'····-····-····';
  enabled=!!s.listening;
  $('sw').className='switch'+(enabled?' on':'');
  $('statedot').className='dot'+(enabled?' on':'');
  $('statetext').textContent=enabled?(state.peers.length?('已连接 '+state.peers.length+' 台设备'):'发现中…'):'未开启';
  const box=$('peers');
  if(!state.peers.length){box.innerHTML='<div class="empty">'+(enabled?'正在搜索同一网络下的设备…（也可用授权码手动添加）':'开关未开启')+'</div>';}
  else{
    box.innerHTML=state.peers.map(p=>
      `<div class="peer${selected===p.id?' sel':''}" onclick="selectPeer('${p.id}')">
        <div class="avatar"><svg class="ico" viewBox="0 0 24 24"><rect x="4" y="4" width="16" height="10" rx="2"/><path d="M8 20h8M12 14v6"/></svg></div>
        <div style="flex:1"><div>${esc(p.name)}${p.manual?' <span class="tag">手动</span>':''}</div>
        <div class="sub">${p.ip}:${p.port} · ${p.code}</div></div></div>`).join('');
  }
  const tp=state.peers.find(p=>p.id===selected);
  $('target').textContent=tp?('→ '+tp.name):'（先选择设备）';
  $('sendbtn').disabled=!tp||!enabled;
  $('filebtn').disabled=!tp||!enabled;
  const rs=state.records||[];
  $('records').innerHTML=rs.length?rs.map(r=>
    `<div class="rec"><div class="meta"><span class="tag ${r.kind==='错误'||r.kind==='拒绝'?'err':(r.kind==='文件'?'ok':'')}">${r.kind}</span>来自 ${esc(r.from)} · ${new Date(r.at).toLocaleTimeString()}</div>${esc(r.detail)}</div>`).join('')
    :'<div class="empty">暂无记录</div>';
}
function selectPeer(id){selected=(selected===id)?null:id;render();}
async function toggle(){await api('/api/toggle',{enabled:!enabled});poll();}
async function pair(){const v=$('pair').value.trim();if(!v)return;
  const r=await api('/api/pair',{input:v});if(!r)return;
  if(r.ok){toast('已添加「'+r.name+'」');$('pair').value='';poll();}else toast(r.msg,true);}
async function sendText(){if(selected===null)return;const v=$('msg').value.trim();if(!v)return;
  const r=await api('/api/send_text',{peer_id:selected,body:v});
  if(r){if(r.ok){$('msg').value='';toast('已发送');}else toast(r.msg||'发送失败',true);}}
async function pickFile(){
  const r=await api('/api/browse_file',{});if(!r||!r.paths||!r.paths.length)return;
  for(const p of r.paths){await sendFile(p);}}
async function sendFile(path){
  const r=await api('/api/send_file',{peer_id:selected,path:path});if(!r)return;
  if(!r.ok){toast(r.msg,true);return;}
  $('progwrap').style.display='block';$('progname').textContent=r.name;
  clearInterval(jobTimer);
  jobTimer=setInterval(async()=>{
    const j=await(await fetch('/api/job?id='+r.id)).json();
    if(j.state==='done'){clearInterval(jobTimer);$('progwrap').style.display='none';
      $('progbar').style.width='100%';toast('文件已发送');}
    else if(j.state==='fail'){clearInterval(jobTimer);$('progwrap').style.display='none';toast(j.msg||'发送失败',true);}
    else if(j.size){$('progbar').style.width=Math.round(j.sent/j.size*100)+'%';}
  },400);}
function openDir(){api('/api/open_received',{});}

setInterval(poll,1000);poll();
// 兜底心跳：窗口探测不可用时由它保活
setInterval(()=>api('/api/ping',{}),20000);
</script></body></html>"""


def main():
    # 窗口化 exe 没有 stderr，所有异常落日志文件，便于远程排查
    log_path = os.path.join(BASE_DIR, "lan_client.log")

    import traceback

    def log(msg):
        try:
            with open(log_path, "a", encoding="utf-8") as f:
                f.write("[%s] %s\n" % (time.strftime("%H:%M:%S"), msg))
        except OSError:
            pass

    try:
        port = free_port(19321)
        port_file = os.path.join(BASE_DIR, "lan-ui.port")
        if os.path.exists(port_file):  # 单实例检测：端口文件已存在则不重复起
            try:
                with open(port_file) as f:
                    old = int(f.read().strip())
                s = socket.socket()
                s.settimeout(1)
                alive = s.connect_ex(("127.0.0.1", old)) == 0
                s.close()
                if alive:
                    log("already running on %d" % old)
                    return
            except (OSError, ValueError):
                pass
        write_port_file(port_file, port)
        log("started on %d" % port)

        html = PAGE.replace("__APP_NAME__", APP_NAME)
        global HEARTBEAT_FN
        stop_event = threading.Event()
        HEARTBEAT_FN = monitor_lifecycle(APP_NAME, on_dead=stop_event.set)

        srv = LocalServer(("127.0.0.1", port), make_handler(html))
        threading.Thread(target=srv.serve_forever, daemon=True).start()

        # 进页面即开启监听（PC 是前台应用，符合「用户手动开启」语义；关窗即停）
        NODE.start()
        try:
            launch_edge("http://127.0.0.1:%d/" % port, 980, 860)
        except RuntimeError as e:
            log("edge launch failed: %s" % e)
            try:  # 无 Edge：退回默认浏览器
                os.startfile("http://127.0.0.1:%d/" % port)  # noqa: S606
            except OSError:
                pass

        # 生命周期信号到达即优雅收尾（finally 保证端口文件清理）
        while not stop_event.is_set():
            stop_event.wait(1)
        log("lifecycle dead, exiting")
    except Exception:
        log("FATAL:\n" + traceback.format_exc())
        raise
    finally:
        NODE.stop()
        try:
            srv.shutdown()
        except Exception:
            pass
        cleanup_port_file()
        log("cleanup done")


HEARTBEAT_FN = None

if __name__ == "__main__":
    main()

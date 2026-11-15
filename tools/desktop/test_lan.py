# -*- coding: utf-8 -*-
"""lan_client 进程内联调：模拟一台 App 设备，验证发现/收发文本/收发文件全链路。"""
import hashlib
import json
import os
import socket
import sys
import threading
import time
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lan_client as L

results = []


def check(name, ok, extra=""):
    results.append((name, ok, extra))
    print(("PASS " if ok else "FAIL ") + name + (" | " + str(extra) if extra else ""))


# ---- 起 PC 节点 ----
node = L.LanNode("测试PC")
node.start()
time.sleep(0.5)
check("节点启动", node.listening)

# ---- 模拟 App 设备：自带 token，UDP 广播 announce ----
app_token = uuid.uuid4().hex
app_fp = L.fingerprint(app_token)
udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
udp.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
announce = json.dumps({"protocol": "zayu-lan-1", "type": "announce", "id": "app-1",
                       "name": "杂鱼Max手机", "port": 52999, "fp": app_fp}).encode()
udp.sendto(announce, ("127.0.0.1", 52390))
time.sleep(1.0)
st = node.status()
peers = st["peers"]
check("发现模拟App", len(peers) == 1 and peers[0]["name"] == "杂鱼Max手机", peers)

# ---- App -> PC 发文本（握手 fp = PC token 指纹）----
s = socket.create_connection(("127.0.0.1", 52391), timeout=5)
msg = json.dumps({"protocol": "zayu-lan-1", "type": "text", "token": uuid.uuid4().hex,
                  "fp": node.fp, "body": "hello from app"})
s.sendall((msg + "\n").encode())
time.sleep(0.8)
s.close()
check("App->PC 文本", any(r["detail"] == "hello from app" for r in node.records), node.records[:1])

# ---- App -> PC 发文件 ----
payload = os.urandom(300_000)
s = socket.create_connection(("127.0.0.1", 52391), timeout=5)
offer = json.dumps({"protocol": "zayu-lan-1", "type": "offer", "token": uuid.uuid4().hex,
                    "fp": node.fp, "name": "app.bin", "size": len(payload)})
s.sendall((offer + "\n").encode())
# 逐字节读 ack 行
ack = bytearray()
while not ack.endswith(b"\n"):
    b = s.recv(1)
    if not b:
        break
    ack += b
ack_ok = json.loads(ack.decode()).get("ok") is True
check("PC ack", ack_ok, ack)
s.sendall(payload)
s.close()
time.sleep(1.0)
recv_path = os.path.join(L.RECEIVED_DIR, "app.bin")
ok = os.path.isfile(recv_path) and open(recv_path, "rb").read() == payload
check("App->PC 文件", ok, recv_path)

# ---- PC -> App 发文本/文件：App 侧起 TCP 服务 ----
received = {}
srv = socket.socket()
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, True)
srv.bind(("127.0.0.1", 52999))
srv.listen(1)


def app_server():
    while True:
        try:
            c, _ = srv.accept()
        except OSError:
            return
        line = bytearray()
        while not line.endswith(b"\n"):
            b = c.recv(1)
            if not b:
                break
            line += b
        head = json.loads(bytes(line).decode())
        assert head["fp"] == app_fp, "fp mismatch: " + head["fp"]
        if head["type"] == "text":
            received["text"] = head["body"]
        else:
            c.sendall(b'{"protocol":"zayu-lan-1","type":"ack","ok":true,"reason":""}\n')
            data = b""
            while len(data) < head["size"]:
                chunk = c.recv(65536)
                if not chunk:
                    break
                data += chunk
            received["file"] = (head["name"], len(data) == head["size"])
        c.close()


threading.Thread(target=app_server, daemon=True).start()
time.sleep(0.3)
app_peer = {"id": "app-1", "name": "杂鱼Max手机", "ip": "127.0.0.1",
            "port": 52999, "fp": app_fp}

node.send_text(app_peer, "hello from pc")
time.sleep(0.5)
check("PC->App 文本", received.get("text") == "hello from pc")

tmp = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_test_upload.bin")
open(tmp, "wb").write(os.urandom(150_000))
node.send_file(app_peer, tmp)
time.sleep(0.5)
name, ok = received.get("file", ("", False))
check("PC->App 文件", name == "_test_upload.bin" and ok, received.get("file"))
os.remove(tmp)

# ---- 手动配对解析 ----
p = node.pair("192.168.1.5 " + L.display_code("a1b2c3d4e5f6"))
check("配对简写解析", p and p["ip"] == "192.168.1.5" and p["fp"] == "a1b2c3d4e5f6", p)
p2 = node.pair("zayu://lan?ip=10.0.0.3&port=52391&fp=A1B2-C3D4-E5F6&name=%E6%89%8B%E6%9C%BA")
check("配对链接解析", p2 and p2["name"] == "手机", p2)
p3 = node.pair("乱七八糟")
check("配对拒绝非法输入", p3 is None)

# ---- 状态接口形状 ----
st = node.status()
check("status 形状", all(k in st for k in ("self", "peers", "records"))
      and all(k in st["self"] for k in ("name", "code", "link", "listening")))

node.stop()
srv.close()
fails = [r for r in results if not r[1]]
print("\n%d/%d PASS" % (len(results) - len(fails), len(results)))
sys.exit(1 if fails else 0)

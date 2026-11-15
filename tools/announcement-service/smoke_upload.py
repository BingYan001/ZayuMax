# -*- coding: utf-8 -*-
"""上传接口冒烟：造一张 PNG -> POST /api/v1/admin/upload -> GET 回读"""

from deploy import connect, run

c = connect()
_, tok, _ = run(c, "cat /www/ann-data/zayu-ann/admin.token", check=False)
tok = tok.strip()

MAKE = r'''
import zlib, struct, binascii
def chunk(t, d):
    body = t + d
    return struct.pack('>I', len(d)) + body + struct.pack('>I', binascii.crc32(body) & 0xffffffff)
w = h = 16
raw = b''.join(b'\x00' + (b'\xff\x33\x33' * w) for _ in range(h))
png = (b'\x89PNG\r\n\x1a\n'
       + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0))
       + chunk(b'IDAT', zlib.compress(raw))
       + chunk(b'IEND', b''))
open('/tmp/t.png', 'wb').write(png)
print('made png', len(png), 'bytes')
'''
open("_mk.py", "w", encoding="utf-8").write(MAKE)
s = c.open_sftp()
s.put("_mk.py", "/tmp/_mk.py")
s.close()
run(c, "python3 /tmp/_mk.py", check=False)

rc, out, err = run(
    c,
    "curl -s -m 25 -X POST -H 'Authorization: Bearer %s' -F 'file=@/tmp/t.png' https://zayu.pw/api/v1/admin/upload" % tok,
    check=False,
)
print("UPLOAD:", out.strip()[:400])

import json
try:
    url = json.loads(out)["data"]["url"]
except Exception:
    url = None
    print("!! 上传失败")
if url:
    print("URL:", url)
    run(c, "curl -s -o /dev/null -w 'GET %s => %%{http_code} %%{content_type} %%{size_download}B\\n' %s" % (url, url), check=False)
    # 顺手验证：目录里塞 .php 不该被执行
    run(c, "echo '<?php echo 1;' > /www/wwwroot/zayu-ann/uploads/evil.php; curl -s -o /dev/null -w 'evil.php => %{http_code}\\n' https://zayu.pw/uploads/evil.php; rm -f /www/wwwroot/zayu-ann/uploads/evil.php", check=False)

run(c, "rm -f /tmp/_mk.py /tmp/t.png", check=False)
c.close()

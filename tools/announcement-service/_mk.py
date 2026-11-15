
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

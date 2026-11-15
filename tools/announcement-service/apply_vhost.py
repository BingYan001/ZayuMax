# -*- coding: utf-8 -*-
"""上传 vhost.conf -> 语法检查 -> reload -> 自检"""
from deploy import connect, run

c = connect()
sftp = c.open_sftp()
sftp.put("vhost.conf", "/tmp/zayu-ann.conf")
sftp.close()

code, _, _ = run(c, "install -m 644 /tmp/zayu-ann.conf /etc/nginx/conf.d/20-ann.zayu-annbingyanshop.conf && rm -f /tmp/zayu-ann.conf", check=False)
run(c, "nginx -t", check=False)
run(c, "systemctl reload nginx && sleep 1 && systemctl is-active nginx", check=False)

print("=" * 50, "自检")
run(c, "printf '<?php echo \"HELLO\";' > /www/wwwroot/zayu-ann/hello.php; chown www:www /www/wwwroot/zayu-ann/hello.php; chmod 640 /www/wwwroot/zayu-ann/hello.php", check=False)
run(c, "curl -s -m 8 -o /dev/null -w 'hello.php   = %{http_code}\\n' -H 'Host: zayu.pw' http://127.0.0.1/hello.php", check=False)
run(c, "curl -s -m 10 -o /dev/null -w 'api health  = %{http_code}\\n' -H 'Host: zayu.pw' http://127.0.0.1/api/v1/health", check=False)
run(c, "curl -s -m 10 -w '\\n' -H 'Host: zayu.pw' http://127.0.0.1/api/v1/health", check=False)
run(c, "curl -s -m 10 -o /dev/null -w 'admin.php   = %{http_code}\\n' -H 'Host: zayu.pw' http://127.0.0.1/admin.php", check=False)
run(c, "rm -f /www/wwwroot/zayu-ann/hello.php", check=False)
run(c, "tail -3 /www/wwwlogs/ann.zayu.error.log 2>/dev/null || echo '(no error)'", check=False)
c.close()

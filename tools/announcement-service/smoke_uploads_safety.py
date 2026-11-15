# -*- coding: utf-8 -*-
"""安全回归：/uploads/ 下只允许图片，任何脚本都必须 404"""

from deploy import connect, run

c = connect()
run(c, "ls /www/wwwroot/zayu-ann/uploads/ | head -5", check=False)
cmds = r'''
printf '<?php echo 777;' > /www/wwwroot/zayu-ann/uploads/x.php
printf '<?php echo 777;' > /www/wwwroot/zayu-ann/uploads/x.phtml
printf 'x' > /www/wwwroot/zayu-ann/uploads/x.txt
printf 'x' > /www/wwwroot/zayu-ann/uploads/noext
cp /www/wwwroot/zayu-ann/uploads/x.php /www/wwwroot/zayu-ann/uploads/x.PHP
chown -R www:www /www/wwwroot/zayu-ann/uploads
for f in x.php x.phtml x.txt noext x.PHP; do
  printf '%-10s => %s\n' "$f" "$(curl -sk -o /dev/null -w '%{http_code}' https://zayu.pw/uploads/$f)"
done
IMG=$(ls /www/wwwroot/zayu-ann/uploads/*.png 2>/dev/null | head -1 | xargs -r basename)
if [ -n "$IMG" ]; then
  printf '%-10s => %s (should be 200)\n' "$IMG" "$(curl -sk -o /dev/null -w '%{http_code}' https://zayu.pw/uploads/$IMG)"
fi
'''
run(c, cmds, check=False)
# 清掉测试文件，保留图片
run(c, "rm -f /www/wwwroot/zayu-ann/uploads/x.php /www/wwwroot/zayu-ann/uploads/x.phtml /www/wwwroot/zayu-ann/uploads/x.txt /www/wwwroot/zayu-ann/uploads/noext /www/wwwroot/zayu-ann/uploads/x.PHP", check=False)
c.close()

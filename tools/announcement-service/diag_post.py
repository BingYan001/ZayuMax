# -*- coding: utf-8 -*-
"""最小 POST 往返验证：写一个 echo.php，返回 php://input 内容"""
from deploy import connect, run

c = connect()
sftp = c.open_sftp()
with sftp.open("/www/wwwroot/zayu-ann/echo.php", "w") as f:
    f.write("""<?php
header('Content-Type: application/json; charset=utf-8');
$raw = @file_get_contents('php://input');
echo json_encode([
  'method' => $_SERVER['REQUEST_METHOD'] ?? null,
  'ct' => $_SERVER['CONTENT_TYPE'] ?? null,
  'cl' => $_SERVER['CONTENT_LENGTH'] ?? null,
  'raw_len' => strlen((string)$raw),
  'raw' => substr((string)$raw, 0, 200),
  'post' => $_POST,
], JSON_UNESCAPED_UNICODE);
""")
sftp.close()
run(c, "chown www:www /www/wwwroot/zayu-ann/echo.php; chmod 640 /www/wwwroot/zayu-ann/echo.php", check=False)

# 该文件不在白名单 location 里，所以临时加一条 location 不行 —— 直接走 /api/rewrite 是不通。
# 改走 admin.php 同款精确 location 不行，这里直接测 api.php 的 echo 分支不现实。
# 因此：改为在 server 里加 location = /echo.php 不方便，直接用 PHP-FPM 直连测试。
run(c, """php -r '
$sock = "/tmp/php-cgi-74.sock";
$body = "{\\"title\\":\\"x\\"}";
$params = [
  "REQUEST_METHOD"=>"POST",
  "SCRIPT_FILENAME"=>"/www/wwwroot/zayu-ann/echo.php",
  "SCRIPT_NAME"=>"/echo.php",
  "REQUEST_URI"=>"/echo.php",
  "CONTENT_TYPE"=>"application/json",
  "CONTENT_LENGTH"=>(string)strlen($body),
  "GATEWAY_INTERFACE"=>"CGI/1.1",
  "SERVER_PROTOCOL"=>"HTTP/1.1",
  "REMOTE_ADDR"=>"127.0.0.1",
  "SERVER_ADDR"=>"127.0.0.1",
  "SERVER_PORT"=>"80",
  "SERVER_NAME"=>"zayu.pw",
  "REDIRECT_STATUS"=>"200",
];
$fp = stream_socket_client("unix://".$sock);
$req = "";
foreach ($params as $k=>$v) { $req .= $k.": ".$v."\\r\\n"; }
$req .= "\\r\\n".$body;
fwrite($fp, $req);
echo stream_get_contents($fp);
fclose($fp);
' 2>&1 | head -30""", check=False)

run(c, "rm -f /www/wwwroot/zayu-ann/echo.php", check=False)
c.close()

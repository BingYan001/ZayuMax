import json,urllib.request,sys
req=urllib.request.Request('http://127.0.0.1/api/v1/announcements?page=1&pageSize=10', method='GET')
req.add_header('Host','zayu.pw')
req.add_header('Content-Type','application/json')
r=urllib.request.urlopen(req,timeout=15)
print(r.status, r.read().decode('utf-8'))

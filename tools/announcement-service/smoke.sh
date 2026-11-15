#!/bin/bash
# 公告站冒烟测试（在服务器上跑）
TOK=$(cat /www/ann-data/zayu-ann/admin.token)
H="Host: zayu.pw"
B="http://127.0.0.1"
P=/root/zayu-payload.json

echo "── 1. health ──"
curl -s -m 10 -H "$H" "$B/api/v1/health"; echo

echo "── 2. 无 token (期望 401) ──"
curl -s -m 10 -H "$H" "$B/api/v1/admin/stats"; echo

echo "── 3. 发布 ──"
cat > $P <<'JSON'
{"title":"杂鱼Max 公告系统上线","summary":"公告后端已切换为自建服务，此条用于验证发布与拉取链路。","content":"## 公告服务已上线\n\n- 后端：自建 PHP + SQLite\n- 客户端 AnnouncementApi 已指向新域名\n\n这是测试内容，可随时在后台删除。","author":"杂鱼团队","publisherName":"杂鱼团队","isPinned":true}
JSON
CREATE=$(curl -s -m 10 -X POST -H "$H" -H "Content-Type: application/json" -H "Authorization: Bearer $TOK" --data-binary @$P "$B/api/v1/admin/announcements")
echo "$CREATE" | head -c 300; echo

ID=$(echo "$CREATE" | grep -oP '"id":"\K[^"]+' | head -1)
echo "ID=$ID"

echo "── 4. 列表 ──"
curl -s -m 10 -H "$H" "$B/api/v1/announcements?page=1&pageSize=10" | head -c 400; echo

echo "── 5. 详情 x2（浏览量应 +1）──"
curl -s -m 10 -H "$H" "$B/api/v1/announcements/$ID" | grep -oP '"viewCount":\K\d+'
curl -s -m 10 -H "$H" "$B/api/v1/announcements/$ID" | grep -oP '"viewCount":\K\d+'

echo "── 6. 修改标题 ──"
echo '{"title":"杂鱼Max 公告系统上线（已改）"}' > $P
curl -s -m 10 -X PUT -H "$H" -H "Content-Type: application/json" -H "Authorization: Bearer $TOK" --data-binary @$P \
  "$B/api/v1/admin/announcements/$ID" | grep -oP '"title":"\K[^"]+'

echo "── 7. 取消置顶 ──"
echo '{"pinned":false}' > $P
curl -s -m 10 -X POST -H "$H" -H "Content-Type: application/json" -H "Authorization: Bearer $TOK" --data-binary @$P \
  "$B/api/v1/admin/announcements/$ID/pin"; echo

echo "── 8. 删除 + 复验 ──"
curl -s -m 10 -X DELETE -H "$H" -H "Authorization: Bearer $TOK" "$B/api/v1/admin/announcements/$ID"; echo
curl -s -m 10 -H "$H" "$B/api/v1/announcements?page=1&pageSize=10" | head -c 200; echo

rm -f $P
echo "── 9. 磁盘 ──"
du -sh /www/wwwroot/zayu-ann /www/ann-data/zayu-ann
ls -l /www/ann-data/zayu-ann
df -h / | tail -1

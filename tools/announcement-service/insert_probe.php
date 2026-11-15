<?php
// 复现 api.php 的完整 INSERT，逐步缩小范围
$cfg = require '/www/wwwroot/zayu-ann/config.local.php';
$pdo = new PDO('sqlite:' . $cfg['DB_PATH'], null, null, [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);

$sql = 'INSERT INTO announcements
 (id, title, summary, content, cover_image, images, publish_at, author,
  publisher_name, publisher_avatar, status, is_pinned, pinned_at, pin_expire_at,
  sort_order, view_count, created_at, updated_at)
 VALUES
 (:id, :title, :summary, :content, :cover, :images, :publish_at, :author,
  :pub_name, :pub_avatar, :st, :is_pinned, :pinned_at, :pin_expire_at,
  :sort_order, 0, :created_at, :updated_at)';

$args = [
    ':id'            => 'dbg_' . time(),
    ':title'         => 'dbg',
    ':summary'       => '',
    ':content'       => '',
    ':cover'         => null,
    ':images'        => '[]',
    ':publish_at'    => null,
    ':author'        => '',
    ':pub_name'      => '',
    ':pub_avatar'    => null,
    ':st'            => 'published',
    ':is_pinned'     => 0,
    ':pinned_at'     => null,
    ':pin_expire_at' => null,
    ':sort_order'    => 0,
    ':created_at'    => '2026-01-01T00:00:00.000Z',
    ':updated_at'    => '2026-01-01T00:00:00.000Z',
];

try {
    $st = $pdo->prepare($sql);
    $st->execute($args);
    echo "FULL INSERT OK\n";
    $pdo->exec("DELETE FROM announcements WHERE id LIKE 'dbg_%'");
} catch (Throwable $e) {
    echo "FULL INSERT FAIL: " . $e->getMessage() . "\n";
}

// 逐个占位符检查是不是被解析到了
echo "\n=== 占位符检查 ===\n";
preg_match_all('/:([a-zA-Z_][a-zA-Z0-9_]*)/', $sql, $m);
$inSql = array_unique($m[1]);
$inArgs = array_map(fn($k) => ltrim($k, ':'), array_keys($args));
echo "SQL 中的占位符: " . implode(', ', $inSql) . "\n";
echo "传入的参数键:   " . implode(', ', $inArgs) . "\n";
echo "SQL 有但未传:   " . implode(', ', array_diff($inSql, $inArgs)) . "\n";
echo "传了但 SQL 没有: " . implode(', ', array_diff($inArgs, $inSql)) . "\n";

// 用 ? 位置参数再试一次，验证是不是具名绑定本身的问题
try {
    $st = $pdo->prepare(str_replace(
        [':st'], ['?'], $sql
    ));
    echo "\n（跳过位置参数测试，占位符过多）\n";
} catch (Throwable $e) {
    echo "positional FAIL: " . $e->getMessage() . "\n";
}

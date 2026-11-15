<?php
// 打印实际表结构 + 一次 INSERT 的最小复现（部署自检用，测完删除）
$cfg = require '/www/wwwroot/zayu-ann/config.local.php';
$pdo = new PDO('sqlite:' . $cfg['DB_PATH'], null, null, [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
$sql = $pdo->query("SELECT sql FROM sqlite_master WHERE name='announcements'")->fetchColumn();
echo "=== SCHEMA ===\n$sql\n\n";

echo "=== PRAGMA ===\n";
foreach ($pdo->query("PRAGMA table_info(announcements)") as $c) {
    echo sprintf("  %-18s %-10s notnull=%d default=%s\n", $c['name'], $c['type'], $c['notnull'], var_export($c['dflt_value'], true));
}

echo "\n=== 最小 INSERT ===\n";
try {
    $st = $pdo->prepare("INSERT INTO announcements (id,title,status) VALUES (:id,:t,:st)");
    $st->execute([':id' => 'probe_' . time(), ':t' => 'x', ':st' => 'published']);
    echo "  OK\n";
} catch (Throwable $e) {
    echo "  FAIL: " . $e->getMessage() . "\n";
}

<?php
/**
 * 杂鱼Max 公告系统 —— 单文件后端（PHP 7.4 + SQLite）
 *
 * 设计原则：
 *  1. 零依赖：只用 PDO_SQLite，不需要 MySQL、Composer、框架。整站只有一个 PHP 文件 + 一个 .sqlite。
 *  2. 接口契约与原 YunX 公告系统 v1.0.0 完全一致（success/data/code/message 信封），
 *     所以 Android 端 AnnouncementApi 只改 BASE_URL 一处即可。
 *  3. 数据库放在 site 根之外的私有目录（由 config 的 DB_PATH 指定），Web 不可直链下载。
 *
 * 客户端公开接口：
 *    GET /api/v1/announcements?page=1&pageSize=20        列表（不含正文）
 *    GET /api/v1/announcements/{id}                      详情（含正文，viewCount +1）
 *
 * 管理接口（需 Bearer Token）：
 *    POST   /api/v1/admin/announcements                  新建
 *    PUT    /api/v1/admin/announcements/{id}             修改（局部更新）
 *    DELETE /api/v1/admin/announcements/{id}             删除
 *    GET    /api/v1/admin/announcements                  列表（含正文、含草稿）
 *    POST   /api/v1/admin/announcements/{id}/pin         置顶 / 取消置顶
 *    GET    /api/v1/admin/stats                          概览
 *
 * 字段语义见 parseBody()。
 */

declare(strict_types=1);

// ============================ 配置 ============================
// 部署时由 deploy 脚本写入 config.local.php（含 ADMIN_TOKEN 与 DB_PATH），
// 本文件不保存任何密钥，可安全提交到仓库。
$CONFIG = [
    'DB_PATH'     => __DIR__ . '/data/announcement.sqlite',
    'ADMIN_TOKEN' => '',           // 必填，否则管理接口全 503
    'TIMEZONE'    => 'Asia/Shanghai',
];
if (is_file(__DIR__ . '/config.local.php')) {
    /** @noinspection PhpIncludeInspection */
    $local = require __DIR__ . '/config.local.php';
    if (is_array($local)) {
        $CONFIG = array_merge($CONFIG, $local);
    }
}

date_default_timezone_set($CONFIG['TIMEZONE']);

// ★ 所有异常统一转成 JSON，绝不能漏成「HTTP 500 + 空响应体」
//    （PHP display_errors=Off 时 PDO 异常会直接 500 且 body 为空，客户端只能看到 "HTTP 500"，无法定位）
set_exception_handler(static function (Throwable $e): void {
    error_log('[zayu-ann] ' . get_class($e) . ': ' . $e->getMessage()
        . ' @ ' . $e->getFile() . ':' . $e->getLine());
    if (!headers_sent()) {
        http_response_code(500);
        header('Content-Type: application/json; charset=utf-8');
    }
    echo json_encode([
        'success' => false,
        'code'    => 50000,
        'message' => '服务器内部错误：' . $e->getMessage(),
        'data'    => null,
    ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
});

// ============================ 基础设施 ============================

const APP_VERSION = '1.0.0';

function jsonOut(int $httpCode, array $payload): void
{
    if (!headers_sent()) {
        http_response_code($httpCode);
        header('Content-Type: application/json; charset=utf-8');
        header('X-Content-Type-Options: nosniff');
        header('Cache-Control: no-store');
        header('Access-Control-Allow-Origin: *');
        header('Access-Control-Allow-Methods: GET, POST, PUT, PATCH, DELETE, OPTIONS');
        header('Access-Control-Allow-Headers: Content-Type, Authorization, X-Requested-With, X-Client-Platform');
        header('Access-Control-Max-Age: 86400');
    }
    echo json_encode($payload, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

function ok($data, string $message = 'ok'): void
{
    jsonOut(200, ['success' => true, 'code' => 0, 'message' => $message, 'data' => $data]);
}

function fail(int $httpCode, int $code, string $message): void
{
    jsonOut($httpCode, ['success' => false, 'code' => $code, 'message' => $message, 'data' => null]);
}

function db(array $cfg): PDO
{
    static $pdo = null;
    if ($pdo instanceof PDO) {
        return $pdo;
    }
    $path = $cfg['DB_PATH'];
    $dir = dirname($path);
    if (!is_dir($dir) && !@mkdir($dir, 0750, true) && !is_dir($dir)) {
        fail(500, 50001, '数据目录不可写');
    }
    try {
        $pdo = new PDO('sqlite:' . $path, null, null, [
            PDO::ATTR_ERRMODE            => PDO::ERRMODE_EXCEPTION,
            PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
            PDO::ATTR_TIMEOUT            => 5,
        ]);
    } catch (Throwable $e) {
        fail(500, 50001, '数据库连接失败');
    }
    // WAL：并发读不阻塞写，单文件场景下最稳；两库各有自己的 WAL，互不影响
    $pdo->exec('PRAGMA journal_mode = WAL');
    $pdo->exec('PRAGMA synchronous = NORMAL');
    $pdo->exec('PRAGMA foreign_keys = ON');
    migrate($pdo);
    return $pdo;
}

function migrate(PDO $pdo): void
{
    $pdo->exec(<<<SQL
CREATE TABLE IF NOT EXISTS announcements (
    id           TEXT PRIMARY KEY,
    title        TEXT NOT NULL,
    summary      TEXT NOT NULL DEFAULT '',
    content      TEXT NOT NULL DEFAULT '',
    cover_image  TEXT,
    images       TEXT NOT NULL DEFAULT '[]',
    publish_at   TEXT,
    author       TEXT NOT NULL DEFAULT '',
    publisher_name   TEXT NOT NULL DEFAULT '',
    publisher_avatar TEXT,
    status       TEXT NOT NULL DEFAULT 'published',
    is_pinned    INTEGER NOT NULL DEFAULT 0,
    pinned_at    TEXT,
    pin_expire_at TEXT,
    sort_order   INTEGER NOT NULL DEFAULT 0,
    view_count   INTEGER NOT NULL DEFAULT 0,
    created_at   TEXT NOT NULL,
    updated_at   TEXT NOT NULL
);
SQL
    );
    $pdo->exec('CREATE INDEX IF NOT EXISTS idx_ann_status_pub ON announcements(status, publish_at)');
    $pdo->exec('CREATE INDEX IF NOT EXISTS idx_ann_pin ON announcements(is_pinned, pinned_at)');
}

/** 统一的 ISO-8601 毫秒时间（与客户端 AnnouncementTime 解析口径一致：末尾 Z 表示 UTC） */
function isoNow(): string
{
    $t = microtime(true);
    $sec = (int) floor($t);
    $ms = (int) round(($t - $sec) * 1000);
    if ($ms >= 1000) {
        $sec++;
        $ms -= 1000;
    }
    return gmdate('Y-m-d\TH:i:s', $sec) . sprintf('.%03dZ', $ms);
}

/** 容忍 "2026-10-05T05:23:00Z" / "...+08:00" / "2026-10-05 05:23:00"，统一转成 UTC ISO 毫秒 */
function normTime(?string $v): ?string
{
    if ($v === null) {
        return null;
    }
    $v = trim($v);
    if ($v === '') {
        return null;
    }
    try {
        $d = new DateTimeImmutable($v);
    } catch (Throwable $e) {
        return null;
    }
    return $d->setTimezone(new DateTimeZone('UTC'))->format('Y-m-d\TH:i:s') . '.000Z';
}

function isPinnedActive(array $row, int $nowMs): bool
{
    if ((int) $row['is_pinned'] !== 1) {
        return false;
    }
    $exp = normTime($row['pin_expire_at'] ?? null);
    if ($exp === null) {
        return true;
    }
    $expMs = (int) (strtotime($exp) * 1000);
    return $expMs > $nowMs;
}

/** 客户端可见判定：published 且 publish_at 为空或已到期 */
function isVisible(array $row, int $nowMs): bool
{
    if (($row['status'] ?? 'published') !== 'published') {
        return false;
    }
    $pub = normTime($row['publish_at'] ?? null);
    if ($pub === null) {
        return true;
    }
    return (int) (strtotime($pub) * 1000) <= $nowMs;
}

// ============================ 模型 ============================

function rowToApi(array $r, bool $withContent): array
{
    $out = [
        'id'        => $r['id'],
        'title'     => $r['title'],
        'summary'   => $r['summary'],
        'coverImage' => $r['cover_image'],
        'images'    => json_decode($r['images'] ?: '[]', true) ?: [],
        'publishAt' => $r['publish_at'],
        'author'    => $r['author'],
        'viewCount' => (int) $r['view_count'],
        'publisher' => [
            'name'      => $r['publisher_name'],
            'avatarUrl' => $r['publisher_avatar'],
        ],
        'isPinned'     => isPinnedActive($r, (int) (microtime(true) * 1000)),
        'pinnedAt'     => $r['pinned_at'],
        'pinExpireAt'  => $r['pin_expire_at'],
        'sortOrder'    => (int) $r['sort_order'],
        'createdAt'    => $r['created_at'],
        'updatedAt'    => $r['updated_at'],
    ];
    if ($withContent) {
        $out['content'] = $r['content'];
    }
    return $out;
}

// ============================ 强更（系统公告） ============================

/** 强更配置文件路径（数据目录下，Web 不可访问；不存在 = 关闭强更） */
function appUpdateConfigPath(): string
{
    return '/www/ann-data/zayu-ann/app_update.json';
}

/** 读强更配置：返回关联数组；文件缺失 / JSON 坏 / 非数组一律返回空数组（= 关闭强更，宁可漏不可误伤） */
function appUpdateConfig(): array
{
    static $cache = null;
    if ($cache !== null) {
        return $cache;
    }
    $path = appUpdateConfigPath();
    if (!is_file($path)) {
        return $cache = [];
    }
    $json = json_decode((string) file_get_contents($path), true);
    return $cache = is_array($json) ? $json : [];
}

/**
 * 生成「系统强更公告」（id 前缀 sys-force-update，非数据库行）。
 *
 * 给**旧版客户端**（UA 裸 "Zayu"、解析不了 minVersionCode 字段的那批）用：
 * 它启动时的公告弹窗/未读逻辑会把这条当普通置顶公告处理 —— 旧版唯一能触达的强更入口。
 * [$withContent] = true 时带正文（详情接口用），false 只给列表字段。
 */
function sysForceAnnouncement(array $upd, bool $withContent): array
{
    $nowIso = gmdate('Y-m-d\TH:i:s') . '.000Z';
    $min = (int) ($upd['minVersionCode'] ?? 0);
    $title = (string) ($upd['title'] ?? '请立即更新杂鱼Max');
    $summary = (string) ($upd['summary'] ?? '当前版本已停止支持，请更新到最新版后继续使用。');
    $out = [
        'id'        => 'sys-force-update-' . $min,
        'title'     => $title,
        'summary'   => $summary,
        'coverImage' => null,
        'images'    => [],
        'publishAt' => null,
        'author'    => '杂鱼团队',
        'viewCount' => 0,
        'publisher' => [
            'name'      => '杂鱼团队',
            'avatarUrl' => null,
        ],
        'isPinned'     => true,
        'pinnedAt'     => $nowIso,
        'pinExpireAt'  => null,
        'sortOrder'    => -1000,
        'createdAt'    => $nowIso,
        'updatedAt'    => $nowIso,
    ];
    if ($withContent) {
        $defaultContent = "## 版本已过期\n\n"
            . "你正在使用的版本已停止支持，**无法继续接收公告与更新通知**。\n\n"
            . "### 如何更新\n\n"
            . "- 点「立即更新」直接下载最新 APK\n"
            . "- 或复制群号 `436393968` 到 QQ 加群，群文件里拿最新版\n\n"
            . "> 更新包与正式版签名一致，可直接覆盖安装，无需卸载。";
        $out['content'] = (string) ($upd['content'] ?? $defaultContent);
    }
    return $out;
}

// ============================ 鉴权 ============================

function requireAdmin(array $cfg): void
{
    $expect = (string) ($cfg['ADMIN_TOKEN'] ?? '');
    if ($expect === '') {
        fail(503, 50301, '管理接口未启用（ADMIN_TOKEN 未配置）');
    }
    $hdr = $_SERVER['HTTP_AUTHORIZATION'] ?? '';
    if ($hdr === '') {
        // 少数 FastCGI 传递下 Authorization 会被吞，兼容一下
        $alt = $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ?? '';
        if ($alt !== '') {
            $hdr = 'Bearer ' . $alt;
        }
    }
    $token = '';
    if (stripos($hdr, 'Bearer ') === 0) {
        $token = trim(substr($hdr, 7));
    } elseif ($hdr !== '') {
        $token = trim($hdr);
    }
    if ($token === '' || !hash_equals($expect, $token)) {
        fail(401, 40101, '未授权');
    }
}

// ============================ 请求体 ============================

function body(): array
{
    $raw = @file_get_contents('php://input');
    if ($raw === false || trim($raw) === '') {
        if (!empty($_POST)) {
            return $_POST;
        }
        // 兜底：本机某些 FastCGI 组合下 php://input 取空，退而读「nginx 落盘的请求体」
        $raw = @file_get_contents('php://stdin') ?: '';
        if (trim($raw) === '') {
            return [];
        }
    }
    $j = json_decode($raw, true);
    if (is_array($j)) {
        return $j;
    }
    if (!empty($_POST)) {
        return $_POST;
    }
    error_log('[zayu-ann] body 解析失败，前 200 字=' . substr($raw, 0, 200));
    return [];
}

/** 只取白名单字段，缺省即「不修改」；显式 null 表示清空 */
function pick(array $src, array $keys): array
{
    $out = [];
    foreach ($keys as $k) {
        if (array_key_exists($k, $src)) {
            $out[$k] = $src[$k];
        }
    }
    return $out;
}

function strOrNull($v): ?string
{
    if ($v === null) {
        return null;
    }
    if (!is_scalar($v)) {
        return null;
    }
    $s = trim((string) $v);
    return $s === '' ? null : $s;
}

function newId(): string
{
    return 'ann_' . bin2hex(random_bytes(10));
}

// ============================ 路由 ============================

$method = strtoupper($_SERVER['REQUEST_METHOD'] ?? 'GET');
$uri = parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH) ?: '/';
// ★ 支持 PATH_INFO 风格：/api.php/api/v1/xxx -> /api/v1/xxx
//   （nginx 侧用 `location ^~ /api/ { rewrite ... /api.php/$1; }` 转发过来，
//     此时 REQUEST_URI 仍是原始 /api/v1/xxx；若反代成 /api.php/... 则靠 PATH_INFO 剥离）
$pathInfo = (string) ($_SERVER['PATH_INFO'] ?? '');
if ($pathInfo !== '') {
    $uri = $pathInfo;
} elseif (strpos($uri, '/api.php/') === 0) {
    $uri = substr($uri, strlen('/api.php'));
} elseif ($uri === '/api.php' || $uri === '/api') {
    $uri = '/';
}
$uri = rtrim($uri, '/');
if ($uri === '') {
    $uri = '/';
}

if ($method === 'OPTIONS') {
    jsonOut(204, []);
}

// ---- 健康检查（部署自检用）----
if ($uri === '/api/v1/health' && $method === 'GET') {
    ok(['version' => APP_VERSION, 'php' => PHP_VERSION, 'time' => isoNow()]);
}

// ---- 调试：POST 回显（仅用于自检，无副作用；token 已配时可删）----
if ($uri === '/api/v1/_echo' && $method === 'POST') {
    $raw = @file_get_contents('php://input');
    ok([
        'ct'      => $_SERVER['CONTENT_TYPE'] ?? null,
        'cl'      => $_SERVER['CONTENT_LENGTH'] ?? null,
        'rawLen'  => strlen((string) $raw),
        'raw'     => substr((string) $raw, 0, 300),
        'post'    => $_POST,
        'parsed'  => body(),
    ]);
}

// ---- 客户端：列表 ----
if ($uri === '/api/v1/announcements' && $method === 'GET') {
    $pdo = db($CONFIG);
    $page = max(1, (int) ($_GET['page'] ?? 1));
    $pageSize = (int) ($_GET['pageSize'] ?? 20);
    $pageSize = max(1, min(100, $pageSize));
    $nowMs = (int) (microtime(true) * 1000);

    // ★ 强更配置：/www/ann-data/zayu-ann/app_update.json
    //   { "minVersionCode": 19, "updateUrl": "https://zayu.pw/downloads/Zayu-latest.apk",
    //     "title": "...", "summary": "...", "content": "markdown" }
    //   文件不存在 / 解析失败 = 关闭强更（minVersionCode 视为 0）。管理员改配置即生效，不用动代码。
    $upd = appUpdateConfig();
    $minVersionCode = (int) ($upd['minVersionCode'] ?? 0);
    $updateUrl = (string) ($upd['updateUrl'] ?? '');

    // UA 识别：新版是 `Zayu/1.3.5 (19)`，旧版只有裸 "Zayu"（minVersionCode 字段出现之前）。
    // 旧版客户端解析不了 data.minVersionCode（会忽略），所以对它们用另一个入口：
    // 把一条「系统强更公告」拼在列表最前面 —— 旧版的启动弹窗/未读角标逻辑会把它当普通置顶公告处理。
    $ua = (string) ($_SERVER['HTTP_USER_AGENT'] ?? '');
    $uaIsLegacy = preg_match('/^Zayu\//', $ua) !== 1;

    // 可见性过滤在 SQL 侧做（status=publish 且 publish_at 已到），排序：置顶优先 → sort_order → 发布时间倒序
    $where = "status = 'published' AND (publish_at IS NULL OR publish_at <= :nowIso)";
    $bind = [':nowIso' => gmdate('Y-m-d\TH:i:s', (int) ($nowMs / 1000)) . '.999Z'];

    $stTotal = $pdo->prepare("SELECT COUNT(*) FROM announcements WHERE $where");
    $stTotal->execute($bind);
    $total = (int) $stTotal->fetchColumn();

    $offset = ($page - 1) * $pageSize;
    $sql = "SELECT * FROM announcements WHERE $where
            ORDER BY is_pinned DESC,
                     CASE WHEN is_pinned = 1 THEN pinned_at END DESC,
                     sort_order ASC,
                     COALESCE(publish_at, created_at) DESC
            LIMIT :lim OFFSET :off";
    $st = $pdo->prepare($sql);
    foreach ($bind as $k => $v) {
        $st->bindValue($k, $v);
    }
    $st->bindValue(':lim', $pageSize, PDO::PARAM_INT);
    $st->bindValue(':off', $offset, PDO::PARAM_INT);
    $st->execute();

    $list = [];
    foreach ($st->fetchAll() as $row) {
        // 置顶过期的不再排在最前：SQL 层用 pinned_at 兜了，这里再兜一次过期语义
        $list[] = rowToApi($row, false);
    }
    // 旧版 + 强更开启：系统强更公告永远排第一（第一页才注，翻页不注）
    if ($uaIsLegacy && $minVersionCode > 0 && $page === 1) {
        array_unshift($list, sysForceAnnouncement($upd, false));
        $total += 1;
    }

    ok([
        'total'      => $total,
        'page'       => $page,
        'pageSize'   => $pageSize,
        'totalPages' => $pageSize > 0 ? (int) ceil($total / $pageSize) : 0,
        'hasMore'    => $page * $pageSize < $total,
        'list'       => $list,
        // 新版客户端读这两个字段自己弹硬阻断弹窗；旧版不认识就忽略（有上面的注入公告兜底）
        'minVersionCode' => $minVersionCode,
        'updateUrl'      => $updateUrl,
    ]);
}

// ---- 客户端：详情 ----
if (preg_match('#^/api/v1/announcements/([^/]+)$#', $uri, $m) && $method === 'GET') {
    $id = urldecode($m[1]);
    // 系统强更公告的详情：不走数据库（它不是真实存在的公告行），直接按配置生成
    if (strpos($id, 'sys-force-update') === 0) {
        $upd = appUpdateConfig();
        if ((int) ($upd['minVersionCode'] ?? 0) <= 0) {
            fail(404, 40401, '公告不存在或已下架');
        }
        ok(sysForceAnnouncement($upd, true));
    }
    $pdo = db($CONFIG);
    $st = $pdo->prepare('SELECT * FROM announcements WHERE id = :id');
    $st->execute([':id' => $id]);
    $row = $st->fetch();
    if (!$row || !isVisible($row, (int) (microtime(true) * 1000))) {
        fail(404, 40401, '公告不存在或已下架');
    }
    // 浏览量 +1（文档 §4：详情接口的副作用，客户端已按 id 缓存避免重复请求）
    $pdo->prepare('UPDATE announcements SET view_count = view_count + 1 WHERE id = :id')
        ->execute([':id' => $id]);
    $row['view_count'] = (int) $row['view_count'] + 1;
    ok(rowToApi($row, true));
}

// ---- 管理：列表（含草稿/正文）----
if ($uri === '/api/v1/admin/announcements' && $method === 'GET') {
    requireAdmin($CONFIG);
    $pdo = db($CONFIG);
    $rows = $pdo->query('SELECT * FROM announcements ORDER BY created_at DESC')->fetchAll();
    $list = array_map(static fn(array $r) => rowToApi($r, true), $rows);
    ok(['total' => count($list), 'list' => $list]);
}

// ---- 管理：新建 ----
if ($uri === '/api/v1/admin/announcements' && $method === 'POST') {
    requireAdmin($CONFIG);
    $b = body();
    $title = strOrNull($b['title'] ?? null);
    if ($title === null) {
        fail(400, 40001, 'title 不能为空');
    }
    $id = strOrNull($b['id'] ?? null) ?? newId();
    if (!preg_match('/^[A-Za-z0-9_\-]{1,128}$/', $id)) {
        fail(400, 40001, 'id 只能包含字母数字下划线连字符，长度 1-128');
    }
    $now = isoNow();
    $pdo = db($CONFIG);
    $exists = $pdo->prepare('SELECT 1 FROM announcements WHERE id = :id');
    $exists->execute([':id' => $id]);
    if ($exists->fetchColumn()) {
        fail(409, 40901, '公告 id 已存在');
    }
    // ★ 不能写 `$b['status'] ?? 'published'`：body 里 status 显式为 null 时 ?? 会穿透成 null
    $reqStatus = $b['status'] ?? 'published';
    $status = (is_string($reqStatus) && in_array($reqStatus, ['published', 'draft', 'archived'], true))
        ? $reqStatus
        : 'published';
    $images = $b['images'] ?? [];
    $images = is_array($images) ? array_values(array_filter(array_map('strOrNull', $images))) : [];

    $st = $pdo->prepare(
        'INSERT INTO announcements
         (id, title, summary, content, cover_image, images, publish_at, author,
          publisher_name, publisher_avatar, status, is_pinned, pinned_at, pin_expire_at,
          sort_order, view_count, created_at, updated_at)
         VALUES
         (:id, :title, :summary, :content, :cover, :images, :publish_at, :author,
          :pub_name, :pub_avatar, :st, :is_pinned, :pinned_at, :pin_expire_at,
          :sort_order, 0, :created_at, :updated_at)'
    );
    $st->execute([
        ':id'            => $id,
        ':title'         => $title,
        ':summary'       => (string) ($b['summary'] ?? ''),
        ':content'       => (string) ($b['content'] ?? ''),
        ':cover'         => strOrNull($b['coverImage'] ?? null),
        ':images'        => json_encode($images, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES),
        ':publish_at'    => normTime(strOrNull($b['publishAt'] ?? null)),
        ':author'        => (string) ($b['author'] ?? ''),
        ':pub_name'      => (string) ($b['publisherName'] ?? ($b['publisher']['name'] ?? '')),
        ':pub_avatar'    => strOrNull($b['publisherAvatar'] ?? ($b['publisher']['avatarUrl'] ?? null)),
        // ★ 占位符不能叫 :status —— PDO 会把 `status` 当作保留字/参数冲突，静默绑定为 NULL，
        //    直接触发 NOT NULL 约束（SQLSTATE 23000），表现为 HTTP 500 且响应体为空。
        ':st'            => $status,
        ':is_pinned'     => !empty($b['isPinned']) ? 1 : 0,
        ':pinned_at'     => !empty($b['isPinned']) ? ($b['pinnedAt'] ?? $now) : null,
        ':pin_expire_at' => strOrNull($b['pinExpireAt'] ?? null),
        ':sort_order'    => (int) ($b['sortOrder'] ?? 0),
        ':created_at'    => $now,
        ':updated_at'    => $now,
    ]);
    $st = $pdo->prepare('SELECT * FROM announcements WHERE id = :id');
    $st->execute([':id' => $id]);
    ok(rowToApi($st->fetch(), true), 'created');
}

// ---- 管理：修改 / 删除 ----
if (preg_match('#^/api/v1/admin/announcements/([^/]+)$#', $uri, $m)) {
    requireAdmin($CONFIG);
    $pdo = db($CONFIG);
    $id = urldecode($m[1]);
    $st = $pdo->prepare('SELECT * FROM announcements WHERE id = :id');
    $st->execute([':id' => $id]);
    $row = $st->fetch();
    if (!$row) {
        fail(404, 40401, '公告不存在');
    }

    if ($method === 'DELETE') {
        $pdo->prepare('DELETE FROM announcements WHERE id = :id')->execute([':id' => $id]);
        ok(['id' => $id], 'deleted');
    }

    if ($method === 'PUT' || $method === 'PATCH') {
        $b = body();
        $map = [
            'title'           => ['title', 'str'],
            'summary'         => ['summary', 'str'],
            'content'         => ['content', 'str'],
            'coverImage'      => ['cover_image', 'nullable'],
            'publishAt'       => ['publish_at', 'time'],
            'author'          => ['author', 'str'],
            'publisherName'   => ['publisher_name', 'str'],
            'publisherAvatar' => ['publisher_avatar', 'nullable'],
            'pinExpireAt'     => ['pin_expire_at', 'time'],
            'sortOrder'       => ['sort_order', 'int'],
            'status'          => ['status', 'status'],
            'isPinned'        => ['is_pinned', 'bool'],
        ];
        $sets = [];
        $args = [':id' => $id];
        foreach ($map as $from => [$to, $type]) {
            if (!array_key_exists($from, $b)) {
                continue;
            }
            $v = $b[$from];
            switch ($type) {
                case 'str':      $v = (string) ($v ?? ''); break;
                case 'nullable': $v = strOrNull($v); break;
                case 'time':     $v = normTime(strOrNull($v)); break;
                case 'int':      $v = (int) $v; break;
                case 'bool':     $v = !empty($v) ? 1 : 0; break;
                case 'status':
                    $v = in_array($v, ['published', 'draft', 'archived'], true) ? $v : $row['status'];
                    break;
            }
            // ★ 占位符加 p_ 前缀：直接用列名做占位符会在 status 上触发 PDO 绑定冲突（静默绑 NULL）
            $ph = ':p_' . $to;
            $sets[] = "$to = $ph";
            $args[$ph] = $v;
        }
        if (array_key_exists('images', $b)) {
            $imgs = is_array($b['images']) ? array_values(array_filter(array_map('strOrNull', $b['images']))) : [];
            $sets[] = 'images = :images';
            $args[':images'] = json_encode($imgs, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
        }
        if (array_key_exists('isPinned', $b) && !empty($b['isPinned']) && empty($row['pinned_at'])) {
            $sets[] = 'pinned_at = :pinned_at';
            $args[':pinned_at'] = isoNow();
        }
        if (!$sets) {
            fail(400, 40002, '没有可更新字段');
        }
        $sets[] = 'updated_at = :updated_at';
        $args[':updated_at'] = isoNow();
        $sql = 'UPDATE announcements SET ' . implode(', ', $sets) . ' WHERE id = :id';
        $pdo->prepare($sql)->execute($args);

        $st = $pdo->prepare('SELECT * FROM announcements WHERE id = :id');
        $st->execute([':id' => $id]);
        ok(rowToApi($st->fetch(), true), 'updated');
    }
}

// ---- 管理：置顶 ----
if (preg_match('#^/api/v1/admin/announcements/([^/]+)/pin$#', $uri, $m) && $method === 'POST') {
    requireAdmin($CONFIG);
    $pdo = db($CONFIG);
    $id = urldecode($m[1]);
    $b = body();
    $on = array_key_exists('pinned', $b) ? !empty($b['pinned']) : true;
    $now = isoNow();
    $st = $pdo->prepare(
        'UPDATE announcements SET is_pinned = :p, pinned_at = :pa, pin_expire_at = :pe, updated_at = :ua
         WHERE id = :id'
    );
    $st->execute([
        ':p'  => $on ? 1 : 0,
        ':pa' => $on ? $now : null,
        ':pe' => $on ? normTime(strOrNull($b['pinExpireAt'] ?? null)) : null,
        ':ua' => $now,
        ':id' => $id,
    ]);
    if ($st->rowCount() === 0) {
        fail(404, 40401, '公告不存在');
    }
    ok(['id' => $id, 'isPinned' => $on]);
}

// ---- 管理：图片上传（multipart/form-data，字段名 file）----
// 说明：
//  · 为什么单独走 /upload 而不是塞进公告 JSON：图片是二进制，base64 进 JSON 会让
//    body 膨胀 33% 且要全量读进内存；multipart 由 PHP 直接落临时文件，更稳。
//  · 落盘目录在 site 根**之下**（/www/wwwroot/zayu-ann/uploads），因为图片必须能被
//    <img> 直链加载；文件名用随机 hex + 白名单扩展名，杜绝原文件名带来的路径穿越 / XSS。
//  · 返回**绝对 URL**（https://zayu.pw/uploads/xxx.jpg），与客户端「图片字段是完整直链」的
//    约定一致，客户端不需要再拼域名。
if ($uri === '/api/v1/admin/upload' && $method === 'POST') {
    requireAdmin($CONFIG);
    if (empty($_FILES['file'])) {
        fail(400, 40001, '缺少 file 字段');
    }
    $f = $_FILES['file'];
    if (!empty($f['error'])) {
        $hint = [
            UPLOAD_ERR_INI_SIZE   => '超过 php.ini upload_max_filesize',
            UPLOAD_ERR_FORM_SIZE  => '超过表单限制',
            UPLOAD_ERR_PARTIAL    => '上传中断',
            UPLOAD_ERR_NO_FILE    => '没有文件',
            UPLOAD_ERR_NO_TMP_DIR => '缺少临时目录',
            UPLOAD_ERR_CANT_WRITE => '写临时文件失败',
        ][$f['error']] ?? '未知上传错误';
        fail(400, 40002, '上传失败：' . $hint);
    }
    $maxBytes = 8 * 1024 * 1024;
    if ((int) $f['size'] > $maxBytes) {
        fail(413, 41301, '图片超过 8MB');
    }
    // ★ 不信扩展名与 Content-Type，一律用 getimagesize 嗅探真实类型
    $info = @getimagesize($f['tmp_name']);
    $allowed = [
        IMAGETYPE_JPEG => 'jpg',
        IMAGETYPE_PNG  => 'png',
        IMAGETYPE_GIF  => 'gif',
        IMAGETYPE_WEBP => 'webp',
    ];
    if ($info === false || !isset($allowed[$info[2]])) {
        fail(400, 40003, '只支持 JPG / PNG / GIF / WEBP');
    }
    $ext = $allowed[$info[2]];

    $dir = __DIR__ . '/uploads';
    if (!is_dir($dir) && !@mkdir($dir, 0755, true) && !is_dir($dir)) {
        fail(500, 50001, '创建上传目录失败');
    }
    $name = date('Ym') . '_' . bin2hex(random_bytes(12)) . '.' . $ext;
    $target = $dir . '/' . $name;
    if (!@move_uploaded_file($f['tmp_name'], $target)) {
        fail(500, 50002, '保存文件失败（检查目录权限）');
    }
    @chmod($target, 0644);

    // 绝对直链：优先用请求 Host，取不到再退回配置里的 PUBLIC_BASE
    $scheme = (!empty($_SERVER['HTTP_X_FORWARDED_PROTO']) ? explode(',', $_SERVER['HTTP_X_FORWARDED_PROTO'])[0]
        : ((!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off') ? 'https' : 'http'));
    $host = (string) ($_SERVER['HTTP_HOST'] ?? '');
    $base = $host !== '' ? ($scheme . '://' . $host) : (string) ($CONFIG['PUBLIC_BASE'] ?? 'https://zayu.pw');
    ok([
        'url'    => rtrim($base, '/') . '/uploads/' . $name,
        'path'   => 'uploads/' . $name,
        'bytes'  => (int) $f['size'],
        'width'  => (int) $info[0],
        'height' => (int) $info[1],
        'ext'    => $ext,
    ], 'uploaded');
}

// ---- 管理：统计 ----
if ($uri === '/api/v1/admin/stats' && $method === 'GET') {
    requireAdmin($CONFIG);
    $pdo = db($CONFIG);
    $total = (int) $pdo->query('SELECT COUNT(*) FROM announcements')->fetchColumn();
    $pub = (int) $pdo->query("SELECT COUNT(*) FROM announcements WHERE status = 'published'")->fetchColumn();
    $views = (int) $pdo->query('SELECT COALESCE(SUM(view_count),0) FROM announcements')->fetchColumn();
    $dbsize = is_file($CONFIG['DB_PATH']) ? filesize($CONFIG['DB_PATH']) : 0;
    ok([
        'version'   => APP_VERSION,
        'php'       => PHP_VERSION,
        'total'     => $total,
        'published' => $pub,
        'views'     => $views,
        'dbBytes'   => $dbsize,
    ]);
}

fail(404, 40400, 'Not Found');

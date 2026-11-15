<?php
/**
 * 杂鱼Max 公告后台 —— 单文件管理页（无 JS 框架、无 CDN，纯手写）。
 *
 * 鉴权：管理员 Token 保存在 localStorage，随 Bearer 头发到管理接口。
 * 本文件不需要服务端会话；敏感操作全部由 api.php 校验 token。
 */
declare(strict_types=1);
?>
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<title>杂鱼Max 公告后台</title>
<style>
  :root {
    --bg: #0f1115;
    --panel: rgba(255,255,255,0.045);
    --panel-solid: #171a21;
    --line: rgba(255,255,255,0.09);
    --text: #e8ecf3;
    --muted: #8b93a5;
    --accent: #5ac8fa;
    --ok: #34c759;
    --warn: #ff9f0a;
    --danger: #ff453a;
  }
  * { box-sizing: border-box; }
  body {
    margin: 0; min-height: 100vh; color: var(--text);
    background: radial-gradient(1200px 600px at 10% -10%, #1b2a3a 0%, transparent 60%),
                radial-gradient(900px 500px at 100% 0%, #20232e 0%, transparent 55%), var(--bg);
    font: 14px/1.6 -apple-system, "PingFang SC", "Microsoft YaHei", system-ui, sans-serif;
  }
  a { color: var(--accent); }
  .wrap { max-width: 1080px; margin: 0 auto; padding: 28px 20px 80px; }
  h1 { font-size: 20px; margin: 0 0 4px; letter-spacing: .3px; }
  .sub { color: var(--muted); font-size: 12.5px; margin-bottom: 22px; }
  .panel {
    background: var(--panel); border: 1px solid var(--line); border-radius: 14px;
    padding: 18px; margin-bottom: 16px; backdrop-filter: blur(8px);
  }
  .row { display: flex; gap: 10px; flex-wrap: wrap; align-items: center; }
  input, textarea, select {
    width: 100%; background: rgba(0,0,0,.28); color: var(--text);
    border: 1px solid var(--line); border-radius: 9px; padding: 9px 11px;
    font: inherit; outline: none; transition: border-color .15s;
  }
  input:focus, textarea:focus, select:focus { border-color: rgba(90,200,250,.55); }
  textarea { resize: vertical; min-height: 90px; }
  label { display: block; font-size: 12px; color: var(--muted); margin: 10px 0 5px; }
  button {
    background: rgba(90,200,250,.14); color: var(--accent);
    border: 1px solid rgba(90,200,250,.4); border-radius: 9px;
    padding: 8px 15px; font: inherit; cursor: pointer; transition: .15s;
  }
  button:hover { background: rgba(90,200,250,.24); }
  button.ghost { background: transparent; color: var(--muted); border-color: var(--line); }
  button.ghost:hover { color: var(--text); }
  button.danger { background: rgba(255,69,58,.12); color: var(--danger); border-color: rgba(255,69,58,.35); }
  button.sm { padding: 4px 10px; font-size: 12.5px; border-radius: 7px; }
  .grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
  @media (max-width: 640px) { .grid2 { grid-template-columns: 1fr; } }
  .list-item {
    border: 1px solid var(--line); border-radius: 11px; padding: 12px 14px;
    margin-bottom: 9px; background: rgba(255,255,255,.02);
  }
  .list-item .t { font-weight: 600; }
  .meta { color: var(--muted); font-size: 12px; margin-top: 3px; word-break: break-all; }
  .tag {
    display: inline-block; font-size: 11px; padding: 1px 7px; border-radius: 20px;
    border: 1px solid var(--line); color: var(--muted); margin-right: 6px;
  }
  .tag.pin { color: var(--warn); border-color: rgba(255,159,10,.4); }
  .tag.draft { color: var(--muted); }
  .msg { margin-top: 10px; font-size: 13px; min-height: 20px; }
  .msg.ok { color: var(--ok); } .msg.err { color: var(--danger); }
  .hide { display: none !important; }
  code { background: rgba(0,0,0,.35); padding: 1px 5px; border-radius: 5px; font-size: 12.5px; }
</style>
</head>
<body>
<div class="wrap">
  <h1>杂鱼Max 公告后台</h1>
  <div class="sub">单文件 PHP + SQLite · 无框架无 CDN · 与 App 侧 AnnouncementApi 契约一致</div>

  <!-- 登录 -->
  <div class="panel" id="loginPanel">
    <label>管理员 Token</label>
    <div class="row">
      <input id="token" type="password" placeholder="部署时生成的 ADMIN_TOKEN" style="flex:1;min-width:220px">
      <button onclick="saveToken()">进入后台</button>
    </div>
    <div class="msg" id="loginMsg"></div>
  </div>

  <!-- 主体 -->
  <div id="app" class="hide">
    <div class="panel">
      <div class="row" style="justify-content:space-between">
        <div><strong>概览</strong> <span class="meta" id="stats"></span></div>
        <div class="row">
          <button class="ghost sm" onclick="loadList()">刷新</button>
          <button class="ghost sm" onclick="logout()">退出</button>
        </div>
      </div>
    </div>

    <div class="panel">
      <strong id="formTitle">新建公告</strong>
      <input type="hidden" id="editId">
      <label>标题 *</label>
      <input id="f_title" placeholder="例如：v1.4.0 更新说明">
      <label>摘要（列表页显示，支持 Markdown）</label>
      <textarea id="f_summary" style="min-height:70px"></textarea>
      <label>正文（支持 Markdown / HTML）</label>
      <textarea id="f_content" style="min-height:180px"></textarea>
      <div class="grid2">
        <div><label>作者</label><input id="f_author" placeholder="杂鱼团队"></div>
        <div><label>发布者名称</label><input id="f_pubname" placeholder="杂鱼团队"></div>
        <div><label>封面图 URL</label><input id="f_cover" placeholder="https://…"></div>
        <div><label>发布时间（留空=立即）</label><input id="f_publishAt" placeholder="2026-10-20 12:00"></div>
        <div><label>排序（越小越前）</label><input id="f_sort" type="number" value="0"></div>
        <div><label>状态</label>
          <select id="f_status">
            <option value="published">已发布</option>
            <option value="draft">草稿</option>
            <option value="archived">已归档</option>
          </select>
        </div>
      </div>
      <label>配图 URL（每行一个）</label>
      <textarea id="f_images" style="min-height:60px"></textarea>
      <div class="row" style="margin-top:14px">
        <button onclick="submitForm()">保存</button>
        <button class="ghost" onclick="resetForm()">清空</button>
        <button class="ghost" onclick="preview()">打开客户端预览</button>
      </div>
      <div class="msg" id="formMsg"></div>
    </div>

    <div class="panel">
      <strong>全部公告</strong>
      <div class="meta" style="margin-bottom:12px">含草稿。置顶会同时作用于 App 列表首屏。</div>
      <div id="list"></div>
    </div>
  </div>
</div>

<script>
const API = './api.php';
const $ = (id) => document.getElementById(id);
let TOKEN = localStorage.getItem('zayu_ann_token') || '';

function msg(el, text, ok) {
  const n = $(el);
  n.textContent = text;
  n.className = 'msg ' + (ok ? 'ok' : 'err');
  if (ok) setTimeout(() => { if (n.textContent === text) { n.textContent = ''; n.className = 'msg'; } }, 3500);
}

async function req(path, opts = {}) {
  const res = await fetch(API + path, {
    ...opts,
    headers: {
      'Content-Type': 'application/json',
      'Authorization': 'Bearer ' + TOKEN,
      ...(opts.headers || {})
    }
  });
  let j;
  try { j = await res.json(); } catch (e) { throw new Error('HTTP ' + res.status + ' 响应非 JSON'); }
  if (!j.success) throw new Error(j.message || ('HTTP ' + res.status));
  return j.data;
}

function saveToken() {
  TOKEN = $('token').value.trim();
  if (!TOKEN) { msg('loginMsg', '请填写 Token'); return; }
  localStorage.setItem('zayu_ann_token', TOKEN);
  enter();
}

function logout() {
  localStorage.removeItem('zayu_ann_token');
  TOKEN = '';
  $('app').classList.add('hide');
  $('loginPanel').classList.remove('hide');
}

async function enter() {
  try {
    const s = await req('/api/v1/admin/stats');
    $('loginPanel').classList.add('hide');
    $('app').classList.remove('hide');
    $('stats').textContent = `共 ${s.total} 条 / 已发布 ${s.published} / 总浏览 ${s.views} / 库 ${(s.dbBytes/1024).toFixed(1)} KB / PHP ${s.php}`;
    loadList();
  } catch (e) {
    msg('loginMsg', '鉴权失败：' + e.message);
    localStorage.removeItem('zayu_ann_token');
  }
}

async function loadList() {
  try {
    const d = await req('/api/v1/admin/announcements');
    const box = $('list');
    if (!d.list.length) { box.innerHTML = '<div class="meta">还没有公告，用上面的表单发第一条。</div>'; return; }
    box.innerHTML = d.list.map(a => `
      <div class="list-item">
        <div class="t">${esc(a.title)}</div>
        <div class="meta">
          ${a.isPinned ? '<span class="tag pin">置顶</span>' : ''}
          ${a.id}
          · 浏览 ${a.viewCount} · 作者 ${esc(a.author || '-')} · ${esc(a.publishAt || a.createdAt)}
        </div>
        <div class="row" style="margin-top:8px">
          <button class="sm ghost" onclick='edit(${JSON.stringify(a).replace(/'/g, "&#39;")})'>编辑</button>
          <button class="sm ghost" onclick="togglePin('${a.id}', ${a.isPinned ? 'false' : 'true'})">${a.isPinned ? '取消置顶' : '置顶'}</button>
          <button class="sm danger" onclick="del('${a.id}')">删除</button>
        </div>
      </div>`).join('');
  } catch (e) { msg('formMsg', '加载失败：' + e.message); }
}

function esc(s) { return String(s == null ? '' : s).replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c])); }

function edit(a) {
  $('editId').value = a.id;
  $('formTitle').textContent = '编辑公告 · ' + a.id;
  $('f_title').value = a.title || '';
  $('f_summary').value = a.summary || '';
  $('f_content').value = a.content || '';
  $('f_author').value = a.author || '';
  $('f_pubname').value = (a.publisher && a.publisher.name) || '';
  $('f_cover').value = a.coverImage || '';
  $('f_publishAt').value = (a.publishAt || '').replace('T', ' ').replace(/\..*$/, '').replace(/Z$/, '');
  $('f_sort').value = a.sortOrder || 0;
  $('f_status').value = 'published';
  $('f_images').value = (a.images || []).join('\n');
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

function resetForm() {
  $('editId').value = '';
  $('formTitle').textContent = '新建公告';
  ['f_title','f_summary','f_content','f_author','f_pubname','f_cover','f_publishAt','f_images'].forEach(i => $(i).value = '');
  $('f_sort').value = 0;
  $('f_status').value = 'published';
  $('formMsg').textContent = '';
}

function payload() {
  const images = $('f_images').value.split('\n').map(s => s.trim()).filter(Boolean);
  const p = {
    title: $('f_title').value.trim(),
    summary: $('f_summary').value,
    content: $('f_content').value,
    author: $('f_author').value.trim(),
    publisherName: $('f_pubname').value.trim(),
    coverImage: $('f_cover').value.trim() || null,
    sortOrder: parseInt($('f_sort').value || '0', 10) || 0,
    status: $('f_status').value,
    images
  };
  const pa = $('f_publishAt').value.trim();
  if (pa) p.publishAt = pa;
  return p;
}

async function submitForm() {
  const id = $('editId').value;
  const p = payload();
  if (!p.title) { msg('formMsg', '标题不能为空'); return; }
  try {
    if (id) await req('/api/v1/admin/announcements/' + encodeURIComponent(id), { method: 'PUT', body: JSON.stringify(p) });
    else await req('/api/v1/admin/announcements', { method: 'POST', body: JSON.stringify(p) });
    msg('formMsg', id ? '已保存' : '已发布', true);
    resetForm();
    enter();
  } catch (e) { msg('formMsg', '保存失败：' + e.message); }
}

async function togglePin(id, on) {
  try { await req('/api/v1/admin/announcements/' + encodeURIComponent(id) + '/pin', { method: 'POST', body: JSON.stringify({ pinned: on }) }); loadList(); }
  catch (e) { alert('操作失败：' + e.message); }
}

async function del(id) {
  if (!confirm('确认删除 ' + id + ' ？此操作不可恢复。')) return;
  try { await req('/api/v1/admin/announcements/' + encodeURIComponent(id), { method: 'DELETE' }); loadList(); }
  catch (e) { alert('删除失败：' + e.message); }
}

function preview() { window.open(API + '/api/v1/announcements', '_blank'); }

if (TOKEN) { $('token').value = TOKEN; enter(); }
</script>
</body>
</html>

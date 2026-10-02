/**
 * DeepSeek Harness —— 悬浮球状态自动推送器
 *
 * 由 App 的 EngineService 在引擎启动时拉起（res/raw/overlay_watch.js → files/overlay-watch.js）。
 *
 * 解决的问题：悬浮球上的内容原来靠 AI 自己调 /overlay-status 推 ——
 * 推不推全看它记不记得，用户看到的就是"时有时无"。这个常驻小进程盯的是
 * 【引擎自己写的 session 日志】，与 AI 的自觉、与插件系统都无关，所以状态一定连续。
 *
 * 两个必须知道的实现细节（都踩过坑）：
 *
 *   1) session.v4.jsonl.zstd 是【多帧拼接】的 —— 引擎每 append 一批就写一帧。
 *      一次性 zstdDecompressSync(整个文件) 只解第一帧（实测 11MB 只解出 240 字符），
 *      所以必须从文件尾部往前扫 zstd magic(28 B5 2F FD)，只解最后几帧。
 *
 *   2) 日志里最后一条事件往往是无趣的记账事件（step/end、session-log-deepseek/…），
 *      所以要【往回扫】，找最近一条能映射成人话的事件。
 *      另外事件的负载嵌在 ev.data 里，不是平铺在顶层。
 *
 * 用法（App 会这样调）：
 *   node overlay-watch.js --port 3094 --home <DSH_HOME> --diag <日志路径>
 */
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');
const http = require('node:http');

const argv = process.argv.slice(2);
function opt(name, dflt) {
  const i = argv.indexOf('--' + name);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : dflt;
}

const PORT = Number(opt('port', '3094'));
const INTERVAL = Math.max(200, Number(opt('interval', '700')));
const DSH_HOME = opt('home', process.env.DSH_HOME || '');
const DIAG = opt('diag', '');
const SESSIONS = DSH_HOME ? path.join(DSH_HOME, 'sessions') : '';

/** 没有任何写入超过这么久 → 认为空闲。 */
const IDLE_AFTER_MS = 90 * 1000;

function diag(msg) {
  if (!DIAG) return;
  try { fs.appendFileSync(DIAG, new Date().toTimeString().slice(0, 8) + ' ' + msg + '\n'); } catch {}
}

// ─────────────────────────── 会话文件 ───────────────────────────

/** 找最近被写过的 session 日志。 */
function newestSessionFile() {
  if (!SESSIONS) return null;
  let best = null;
  let bestM = 0;
  let projects;
  try { projects = fs.readdirSync(SESSIONS); } catch { return null; }
  for (const p of projects) {
    const pdir = path.join(SESSIONS, p);
    let sids;
    try { sids = fs.readdirSync(pdir); } catch { continue; }
    for (const s of sids) {
      const f = path.join(pdir, s, 'session.v4.jsonl.zstd');
      try {
        const st = fs.statSync(f);
        if (st.mtimeMs > bestM) { bestM = st.mtimeMs; best = f; }
      } catch { /* 不是每个子目录都有日志 */ }
    }
  }
  return best;
}

/** 尾部若干 zstd 帧里的所有事件（从旧到新，最多 max 条）。 */
function readTailEvents(file, max) {
  let fd;
  try { fd = fs.openSync(file, 'r'); } catch { return []; }
  try {
    const size = fs.fstatSync(fd).size;
    if (size <= 0) return [];
    const WINDOW = 512 * 1024;
    const start = Math.max(0, size - WINDOW);
    const len = size - start;
    const buf = Buffer.allocUnsafe(len);
    fs.readSync(fd, buf, 0, len, start);

    const heads = [];
    for (let i = len - 4; i >= 0; i--) {
      if (buf[i] === 0x28 && buf[i + 1] === 0xB5 && buf[i + 2] === 0x2F && buf[i + 3] === 0xFD) {
        heads.push(i);
        if (heads.length >= 8) break;
      }
    }
    if (!heads.length) {
      try { return parseLines(zlib.zstdDecompressSync(buf).toString('utf8'), max); } catch { return []; }
    }
    let text = '';
    for (const off of heads.slice(0, 3)) {
      try { text = zlib.zstdDecompressSync(buf.subarray(off)).toString('utf8') + '\n' + text; }
      catch { /* 尾部可能是半截帧，跳过它试更早的 */ }
    }
    return parseLines(text, max);
  } finally {
    try { fs.closeSync(fd); } catch {}
  }
}

function parseLines(text, max) {
  const out = [];
  for (const line of text.split('\n')) {
    const l = line.trim();
    if (!l || l[0] !== '{') continue;
    try { out.push(JSON.parse(l)); } catch {}
  }
  return out.slice(-max);
}

// ─────────────────────────── 事件 → 显示 ───────────────────────────

const TOOL_LABEL = {
  bash: '执行命令', shell: '执行命令', shizuku_shell: '特权命令',
  read: '读文件', write: '写文件', edit: '改文件',
  grep: '搜索', glob: '找文件',
  android_screen: '看屏幕', android_see: '看屏幕', android_screenshot: '截图',
  android_tap: '点屏幕', android_swipe: '滑屏幕', android_gesture: '手势',
  android_type: '输入', android_input: '输入', android_hold: '长按',
  subagent: '派子任务', subagent_fork: '派子任务',
  job_output: '看任务', job_list: '看任务', job_kill: '停任务',
  web_search: '搜网络', web_fetch: '抓网页',
  skill: '读技能', present: '交付文件', todo_write: '记进度',
};

function short(s, n) {
  return String(s == null ? '' : s).replace(/\s+/g, ' ').trim().slice(0, n);
}

function describeTool(name, argStr) {
  let a = {};
  try { a = JSON.parse(argStr || '{}') || {}; } catch {}
  switch (name) {
    case 'bash': case 'shell': case 'shizuku_shell': return short(a.command, 58);
    case 'read': case 'write': case 'edit': return short(String(a.file_path || '').split('/').pop(), 42);
    case 'grep': case 'glob': return short(a.pattern, 34);
    case 'subagent': case 'subagent_fork': return short(a.description, 34);
    default: return '';
  }
}

/** 事件 → {state, step, index, total}；返回 null 表示这条不改变显示。 */
function mapEvent(ev) {
  if (!ev || !ev.type) return null;
  const d = ev.data || ev;   // ★ 负载嵌在 data 里
  switch (ev.type) {
    case 'turn/start':
      return { state: 'working', step: '思考中', index: 0, total: 0 };
    case 'step/start':
      return { state: 'working', step: '想事情', index: d.step || 0, total: 0 };
    case 'tool/call': {
      const label = TOOL_LABEL[d.name] || d.name || '工具';
      const detail = describeTool(d.name, d.arguments);
      return { state: 'working', step: detail ? label + ' · ' + detail : label, index: d.step || 0, total: 0 };
    }
    case 'assistant/message':
      return { state: 'working', step: '写回复', index: 0, total: 0 };
    case 'user/message':
      return { state: 'working', step: '收到', index: 0, total: 0 };
    case 'turn/end':
      return { state: 'done', step: '完成', index: 0, total: 0 };
    default:
      return null;
  }
}

// ─────────────────────────── 推送 ───────────────────────────

let lastPushAt = 0;

function push(payload) {
  const body = JSON.stringify(payload);
  lastPushAt = Date.now();
  try {
    const req = http.request({
      host: '127.0.0.1', port: PORT, path: '/overlay-status', method: 'POST', timeout: 1500,
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) },
    }, (res) => res.resume());
    req.on('error', () => {});                                   // 悬浮窗没开就安静失败
    req.on('timeout', () => { try { req.destroy(); } catch {} });
    req.write(body);
    req.end();
  } catch {}
}

// ─────────────────────────── 主循环 ───────────────────────────

diag('=== 启动 port=' + PORT + ' interval=' + INTERVAL + ' home=' + DSH_HOME + ' ===');

let lastFile = null;
let lastMtime = 0;
let lastSig = '';
let idlePushed = false;

setInterval(() => {
  try {
    const f = newestSessionFile();
    if (!f) return;

    if (f !== lastFile) {
      diag('跟踪会话 ' + f.replace(SESSIONS + '/', ''));
      lastFile = f;
      lastMtime = 0;
      lastSig = '';
      idlePushed = false;
    }

    const m = fs.statSync(f).mtimeMs;

    // 长时间没写入 → 推一次空闲
    if (Date.now() - m > IDLE_AFTER_MS) {
      if (!idlePushed && lastSig !== 'idle') {
        lastSig = 'idle';
        idlePushed = true;
        diag('→ idle（' + Math.round((Date.now() - m) / 1000) + 's 无活动）');
        push({ state: 'idle', step: '空闲', index: 0, total: 0 });
      }
      return;
    }
    idlePushed = false;

    if (m === lastMtime) return;
    lastMtime = m;

    const events = readTailEvents(f, 60);
    if (!events.length) { diag('尾部没解出事件'); return; }

    let picked = null;
    for (let i = events.length - 1; i >= 0; i--) {
      const st = mapEvent(events[i]);
      if (st) { picked = { ev: events[i], st }; break; }
    }
    if (!picked) {
      diag('尾部 ' + events.length + ' 条都不可映射: ' + events.slice(-4).map((e) => e.type).join(','));
      return;
    }

    const sig = picked.st.state + '|' + picked.st.step;
    if (sig === lastSig) return;
    lastSig = sig;
    diag('→ ' + picked.st.state + ' / ' + picked.st.step + '   (' + picked.ev.type + ')');
    push(picked.st);
  } catch (e) {
    diag('ERR ' + (e && e.message));
  }
}, INTERVAL);

process.on('SIGTERM', () => { diag('收到 SIGTERM，退出'); process.exit(0); });
process.on('SIGINT', () => { diag('收到 SIGINT，退出'); process.exit(0); });

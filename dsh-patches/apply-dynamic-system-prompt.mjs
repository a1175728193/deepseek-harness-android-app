#!/usr/bin/env node
// 动态系统提示词补丁（v1.19）
// 不改 DSH 内核源码：在 @deepseek-ai/dsh-llm-deepseek 的请求构建出口（messages 组装函数尾部）
// 拦截发往 DeepSeek 的 system 字段 ——
//   优先读 DSH_SYSTEM_PROMPT_FILE（Android 壳层指向工作区 system_prompt.txt），
//   其次 DSH_WORKSPACE/system_prompt.txt；
//   文件存在且内容非空 → 用它整体替换系统提示词；
//   文件缺失 / 读取失败 / 内容为空 → 返回 null，保留内核原始提示词（兜底，聊天功能不受影响）。
// 绝不触碰 tools 数组与上下文压缩/日志逻辑。
//
// 用法：node dsh-patches/apply-dynamic-system-prompt.mjs <dshroot/lib 绝对路径>
// 幂等：已打过补丁的文件会跳过。锚点不匹配会 FAIL LOUD（防止覆盖到错误版本内核）。
import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const [root] = process.argv.slice(2);
if (!root) {
  console.error("usage: node apply-dynamic-system-prompt.mjs <dshroot/lib>");
  process.exit(2);
}

const f = join(root, "node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-llm-deepseek/lib/index.js");
const src = readFileSync(f, "utf8");
const MARK = "/* dsp-dynamic-system-prompt */";
if (src.includes(MARK)) {
  console.log("已打过补丁，跳过: " + f);
  process.exit(0);
}

const IMPORT_OLD = `import { mkdir, readFile } from "node:fs/promises";`;
const IMPORT_NEW = `import { mkdir, readFile } from "node:fs/promises";
import { readFileSync as __dsp_readFileSync } from "node:fs";`;
if (!src.includes(IMPORT_OLD)) {
  console.error("!! 找不到 import 锚点（内核版本不匹配？）");
  process.exit(1);
}

const SYSTEM_OLD = `const system = [options.system, historySystem].filter(Boolean).join("\\n\\n");`;
const SYSTEM_NEW = `const __dsp_dynamic = __dsp_readDynamicSystemPrompt();
const system = __dsp_dynamic != null && __dsp_dynamic.length > 0 ? __dsp_dynamic : [options.system, historySystem].filter(Boolean).join("\\n\\n");`;
if (!src.includes(SYSTEM_OLD)) {
  console.error("!! 找不到 system 组装锚点（内核版本不匹配？）");
  process.exit(1);
}

const HELPER = `
/** ${MARK}
 * 动态系统提示词读取：优先 DSH_SYSTEM_PROMPT_FILE，其次 DSH_WORKSPACE/system_prompt.txt。
 * 文件不存在、读取失败或内容为空 → 返回 null，由调用方保留内核原始提示词。
 * 只替换系统提示词文本，不触碰 messages 里的工具调用、工具结果与上下文压缩逻辑。
 */
function __dsp_readDynamicSystemPrompt() {
  const candidates = [];
  if (process.env.DSH_SYSTEM_PROMPT_FILE) candidates.push(process.env.DSH_SYSTEM_PROMPT_FILE);
  if (process.env.DSH_WORKSPACE) candidates.push(join(process.env.DSH_WORKSPACE, "system_prompt.txt"));
  for (const c of candidates) {
    try {
      const s = __dsp_readFileSync(c, "utf8").trim();
      if (s.length > 0) return s;
    } catch (_) { /* 读取失败则尝试下一个候选 */ }
  }
  return null;
}
`;

const ANCHOR = `//#region lib/types/transport.js`;
if (!src.includes(ANCHOR)) {
  console.error("!! 找不到 helper 锚点（内核版本不匹配？）");
  process.exit(1);
}

let out = src.replace(IMPORT_OLD, IMPORT_NEW);
out = out.replace(SYSTEM_OLD, SYSTEM_NEW);
out = out.replace(ANCHOR, HELPER + "\n" + ANCHOR);
writeFileSync(f, out);
console.log("✅ 动态系统提示词补丁已应用: " + f);

#!/usr/bin/env node
// 聊天模式补丁：给 dsh-web-app 加一个「零工具」预设（id: chat，order: 5）。
//
// 机制（见 @deepseek-ai/dsh-agent-preset-registry 的文档）：preset 是白名单 ——
// plugins 里没写的工具在该模式下根本不会被注册，模型侧连工具表都没有。
// 所以「不给工具」不是靠提示词求它别动手，而是内核层面它就没有手。
//
// 做法与内核自带的 presets/*.patch.yml 完全一致：
//   1. 新增 presets/chat.patch.yml（插入一行 @deepseek-ai/dsh-agent-preset）
//   2. 在 dsh-web-app/package.json 的 dsh.bundle.patch 里登记该文件
// 不改任何工具插件、不改内核源码。
//
// 用法：node dsh-patches/apply-chat-preset.mjs <dshroot/lib 绝对路径>
// 幂等：重复执行只重写这两个文件，结果一致。

import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const [root] = process.argv.slice(2);
if (!root) {
  console.error('usage: apply-chat-preset.mjs <dshroot/lib>');
  process.exit(2);
}

const pkgDir = join(root, 'node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-app');
if (!existsSync(pkgDir)) {
  console.error('!! 找不到 dsh-web-app 目录: ' + pkgDir);
  process.exit(1);
}

const REL = './presets/chat.patch.yml';
const PRESET_PATH = join(pkgDir, 'presets/chat.patch.yml');

const PREFIX_LINES = [
  '你是一个只能说话的助手：你没有手，也没有任何工具。',
  '',
  '你读不了文件、搜不了网、跑不了命令、碰不到这台手机，也没法派出别的助手替你做事。',
  '遇到需要动手的请求，直接说做不到；你可以陪他把事情想清楚、把主意出明白，但不要假装已经做完，也不要编造执行过程。',
  '',
  '你擅长的是把事讲清楚、把想法理成条理、陪他把一件难事想明白。说人话，别端着。',
];

const indent = (lines, n) => lines.map((l) => (l === '' ? '' : ' '.repeat(n) + l)).join('\n');

const presetYaml = [
  '# Agent preset chat: 只有对话的模式 —— plugins 里除了人格提示词和上下文压缩，',
  '# 不声明任何 @deepseek-ai/dsh-tool-* 。preset 是白名单，没声明的工具在该模式下不存在。',
  '# 本文件由 dsh-patches/apply-chat-preset.mjs 生成；重跑该脚本会覆盖它。',
  '- insert:',
  '    - id: preset-chat',
  "      name: '@deepseek-ai/dsh-agent-preset'",
  '      config:',
  '        id: chat',
  '        name: 聊天模式',
  '        description: 只跟你说话。没有任何工具：读不了文件、上不了网、碰不到手机。',
  '        order: 5',
  '        plugins:',
  '          - id: persona',
  "            name: '@deepseek-ai/dsh-persona'",
  '            config:',
  '              # complete：这条人格提示词独占系统提示词，宿主那句「你是 coding agent」不再拼接。',
  '              # includeRuntimeContext: false：不注入运行时上下文，否则它会读到自己「本该有」的工具说明而幻想动手。',
  '              prefix: >-',
  indent(PREFIX_LINES, 16),
  '              complete: true',
  '              includeRuntimeContext: false',
  '          # 上下文压缩：不是工具，是纯内核行为，留着让长对话不至于撑爆上下文。',
  '          # 与 standard / ptc / cordis 三个预设逐字一致，避免 isolate 交互上的意外。',
  '          - id: compaction',
  '            name: cordis:group',
  '            group: true',
  '            isolate:',
  '              compaction: true',
  '              toolResultPruner: true',
  '            config:',
  '              - id: compaction-basic',
  "                name: '@deepseek-ai/dsh-compaction-basic'",
  '              - id: command-compact',
  "                name: '@deepseek-ai/dsh-command-compact'",
  '              - id: tool-result-pruner',
  "                name: '@deepseek-ai/dsh-compaction-tool-result-pruner'",
  '                config:',
  '                  thresholdChars: 8192',
  '                  headChars: 4096',
  '                  tailChars: 1024',
  '',
].join('\n');

writeFileSync(PRESET_PATH, presetYaml, 'utf8');
console.log('✅ 已写入 ' + PRESET_PATH);

const pkgPath = join(pkgDir, 'package.json');
const pkg = JSON.parse(readFileSync(pkgPath, 'utf8'));
const patchList = pkg?.dsh?.bundle?.patch;
if (!Array.isArray(patchList)) {
  console.error('!! package.json 里找不到 dsh.bundle.patch 数组（内核版本不匹配？）');
  process.exit(1);
}
if (!patchList.includes('./cordis.patch.yml')) {
  console.error('!! dsh.bundle.patch 里没有 ./cordis.patch.yml，锚点不对，拒绝继续');
  process.exit(1);
}
if (!patchList.includes(REL)) patchList.push(REL);
if (Array.isArray(pkg.files) && !pkg.files.includes('presets/chat.patch.yml')) {
  pkg.files.push('presets/chat.patch.yml');
}
writeFileSync(pkgPath, JSON.stringify(pkg, null, 2) + '\n', 'utf8');
console.log('✅ 已登记到 dsh.bundle.patch：' + patchList.join(' '));

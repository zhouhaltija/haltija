#!/usr/bin/env node
/**
 * 把 SillyTavern 的 `getStreamingReply` 抽出来跑，生成流式解析的跨语言对照表。
 *
 * 这个函数在 `public/scripts/openai.js:3222-3306`，只依赖三样外部东西：
 *   - `chat_completion_sources`（枚举，同文件 177 行）
 *   - `isDataURL`（utils.js:1198，纯函数）
 *   - `oai_settings`（读 chat_completion_source / show_thoughts）
 *
 * 三样都能桩掉，所以可以原样运行真实实现。
 *
 * 用例覆盖三家：OpenAI 兼容（含 DeepSeek / OpenRouter 的思维链字段差异）、
 * Anthropic（`data.delta.text` / `thinking`）、Gemini（parts 里的 thought 与 inlineData）。
 *
 * 用法：node tools/gen-streaming-goldens.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..');
const ST = path.join(ROOT, 'SillyTavern');
const OUT = path.join(ROOT, 'fixtures/streaming-goldens.json');

const openaiSrc = fs.readFileSync(path.join(ST, 'public/scripts/openai.js'), 'utf8');
const utilsSrc = fs.readFileSync(path.join(ST, 'public/scripts/utils.js'), 'utf8');

/**
 * 按「找到签名，再找下一个**顶格** `}`」抽取函数。
 *
 * 不用括号配平：`isDataURL` 的正则里有 `',(` 这样的字符类，
 * 朴素的扫描器会把它当成字符串开头，一路吃掉几百行。
 * 这两个函数体内都没有顶格的 `}`，所以按顶格收尾更稳。
 */
function extractFunction(src, signature) {
    const start = src.indexOf(signature);
    if (start < 0) throw new Error(`找不到 ${signature}`);
    const lines = src.slice(start).split('\n');
    const collected = [];
    for (const line of lines) {
        collected.push(line);
        if (collected.length > 1 && line === '}') break;
        if (collected.length > 4000) throw new Error(`${signature} 抽取越界`);
    }
    if (collected[collected.length - 1] !== '}') throw new Error(`${signature} 未找到收尾`);
    return collected.join('\n');
}

/** 抽出 `export const chat_completion_sources = { ... };` 的对象字面量。 */
function extractSourceEnum(src) {
    const marker = 'export const chat_completion_sources = ';
    const start = src.indexOf(marker);
    if (start < 0) throw new Error('找不到 chat_completion_sources');
    const braceStart = src.indexOf('{', start);
    let depth = 0;
    let i = braceStart;
    for (; i < src.length; i++) {
        if (src[i] === '{') depth++;
        else if (src[i] === '}') { depth--; if (depth === 0) { i++; break; } }
    }
    return src.slice(braceStart, i);
}

const fn = extractFunction(openaiSrc, 'export function getStreamingReply').replace(/^export /, '');
const isDataURL = extractFunction(utilsSrc, 'export function isDataURL').replace(/^export /, '');
const sources = extractSourceEnum(openaiSrc);

const dir = fs.mkdtempSync('/tmp/streaming-extract-');
const file = path.join(dir, 'extracted.mjs');
fs.writeFileSync(file, `
${isDataURL}
const chat_completion_sources = ${sources};
export const oai_settings = { chat_completion_source: 'openai', show_thoughts: true };
${fn}
export { getStreamingReply, chat_completion_sources };
`);

const { getStreamingReply, oai_settings } = await import(file);
console.log(`抽取完成：getStreamingReply（${fn.split('\n').length} 行）+ isDataURL`);
console.log(`chat_completion_sources 有 ${Object.keys((await import(file)).chat_completion_sources).length} 项`);

// ---------------------------------------------------------------- 用例

const cases = [
    // —— OpenAI 兼容 ——
    { source: 'openai', desc: '普通正文增量', data: { choices: [{ delta: { content: 'Hel' } }] } },
    { source: 'openai', desc: '空 delta', data: { choices: [{ delta: {} }] } },
    { source: 'openai', desc: '非 delta 的 message 形式', data: { choices: [{ message: { content: 'Hi' } }] } },
    { source: 'openai', desc: 'choices 为空', data: { choices: [] } },
    // —— DeepSeek：思维链在 reasoning_content ——
    { source: 'deepseek', desc: '思维链', data: { choices: [{ delta: { reasoning_content: '想一下' } }] } },
    { source: 'deepseek', desc: '正文', data: { choices: [{ delta: { content: '答案' } }] } },
    { source: 'xai', desc: '同 DeepSeek 的字段', data: { choices: [{ delta: { reasoning_content: 'r' } }] } },
    // —— OpenRouter：字段名是 reasoning ——
    { source: 'openrouter', desc: 'reasoning 字段', data: { choices: [{ delta: { reasoning: 'think' } }] } },
    { source: 'openrouter', desc: '正文', data: { choices: [{ delta: { content: 'out' } }] } },
    { source: 'openrouter', desc: 'message.reasoning', data: { choices: [{ message: { reasoning: 'mr' } }] } },
    // —— Anthropic ——
    // 真实的 Anthropic SSE 里 data 一定带 type；ST 的 getStreamingReply 只读 delta，
    // 但把 type 写全才能反映真实报文形状
    { source: 'claude', desc: '正文', data: { type: 'content_block_delta', delta: { type: 'text_delta', text: 'Claude says' } } },
    { source: 'claude', desc: 'thinking', data: { type: 'content_block_delta', delta: { type: 'thinking_delta', thinking: '内心活动' } } },
    { source: 'claude', desc: '空 delta', data: { type: 'content_block_delta', delta: {} } },
    { source: 'claude', desc: 'message_delta', data: { type: 'message_delta', delta: { stop_reason: 'end_turn' } } },
    // —— Gemini ——
    { source: 'makersuite', desc: '正文 part', data: { candidates: [{ content: { parts: [{ text: 'Gem' }] } }] } },
    { source: 'makersuite', desc: 'thought part', data: { candidates: [{ content: { parts: [{ text: '思考', thought: true }] } }] } },
    { source: 'makersuite', desc: '正文 + thought 混在一起', data: { candidates: [{ content: { parts: [{ text: '想', thought: true }, { text: '答' }] } }] } },
    { source: 'makersuite', desc: 'inline 图片', data: { candidates: [{ content: { parts: [{ inlineData: { mimeType: 'image/png', data: 'AAAA' } }] } }] } },
    { source: 'makersuite', desc: '空 candidates', data: { candidates: [] } },
    { source: 'vertexai', desc: '同 makersuite', data: { candidates: [{ content: { parts: [{ text: 'V' }] } }] } },
    // —— 其它契约（供参考）——
    { source: 'cohere', desc: 'cohere 形状', data: { delta: { message: { content: { text: 'C' } } } } },
    { source: 'mistralai', desc: 'mistral thinking', data: { choices: [{ delta: { content: [{ thinking: [{ text: 'mt' }] }] } }] } },
];

const results = [];
for (const c of cases) {
    oai_settings.chat_completion_source = c.source;
    oai_settings.show_thoughts = true;
    const state = { reasoning: '', images: [] };
    let text = null;
    let error = null;
    try {
        text = getStreamingReply(c.data, state, { chatCompletionSource: c.source, overrideShowThoughts: true });
    } catch (e) {
        error = String(e.message ?? e).split('\n')[0];
    }
    results.push({
        source: c.source,
        desc: c.desc,
        data: c.data,
        text,
        reasoning: state.reasoning,
        images: state.images,
        error,
    });
}

const payload = {
    _comment: '由 tools/gen-streaming-goldens.mjs 生成；getStreamingReply 在生成时从 SillyTavern 源码逐字抽取并运行',
    extractedFrom: 'public/scripts/openai.js:3222-3306',
    cases: results,
};

fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, JSON.stringify(payload, null, 2) + '\n');
console.log(`\n生成 ${results.length} 条用例，已写出 ${path.relative(ROOT, OUT)}`);

for (const r of results) {
    console.log(`  [${r.source.padEnd(11)}] ${r.desc.padEnd(24)} text=${JSON.stringify(r.text)} reasoning=${JSON.stringify(r.reasoning)} images=${r.images.length}`);
}

fs.rmSync(dir, { recursive: true, force: true });

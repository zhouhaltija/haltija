#!/usr/bin/env node
/**
 * 抽出 SillyTavern 的两个时间戳函数做对照。
 *
 *   - `getMessageTimeStamp()`（RossAscends-mods.js:192）→ 消息体里的 `send_date`
 *   - `humanizedDateTime()`  （RossAscends-mods.js:169）→ 聊天文件名里那一段
 *
 * 两个都是纯函数，直接抽出来跑。
 *
 * **必须跨时区取样本**：`humanizedDateTime` 用的是本地时间（`getFullYear()` 等），
 * 所以同一个瞬间在不同时区下文件名不同；而 `getMessageTimeStamp` 恒为 UTC。
 * 这个差异写错，ST 那边就会出现「文件名时间对不上」。
 *
 * 用法：node tools/gen-time-goldens.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..');
const ST = path.join(ROOT, 'SillyTavern');
const OUT = path.join(ROOT, 'fixtures/time-goldens.json');

const src = fs.readFileSync(path.join(ST, 'public/scripts/RossAscends-mods.js'), 'utf8');

function extractFunction(source, signature) {
    const start = source.indexOf(signature);
    if (start < 0) throw new Error(`找不到 ${signature}`);
    const lines = source.slice(start).split('\n');
    const collected = [];
    for (const line of lines) {
        collected.push(line);
        if (collected.length > 1 && line === '}') break;
        if (collected.length > 200) throw new Error(`${signature} 抽取越界`);
    }
    return collected.join('\n').replace(/^export /, '');
}

const humanized = extractFunction(src, 'export function humanizedDateTime');
const messageStamp = extractFunction(src, 'export function getMessageTimeStamp');

const dir = fs.mkdtempSync('/tmp/time-extract-');
const file = path.join(dir, 'extracted.mjs');
fs.writeFileSync(file, `${humanized}\n${messageStamp}\nexport { humanizedDateTime, getMessageTimeStamp };\n`);

const { humanizedDateTime, getMessageTimeStamp } = await import(file);
console.log(`抽取完成：humanizedDateTime（${humanized.split('\n').length} 行）+ getMessageTimeStamp`);

// 挑几个有代表性的瞬间：整秒、带毫秒、跨天、闰年、DST 边界
const instants = [
    Date.UTC(2026, 9, 5, 8, 30, 45, 123),   // 2026-10-05 08:30:45.123Z
    Date.UTC(2026, 9, 5, 8, 30, 45, 0),     // 毫秒为 0（ISO 仍要补 .000）
    Date.UTC(2026, 9, 5, 8, 30, 45, 7),     // 毫秒只有一位（要补成 .007）
    Date.UTC(2026, 0, 1, 0, 0, 0, 0),       // 跨年
    Date.UTC(2024, 1, 29, 12, 0, 0, 1),     // 闰日
    Date.UTC(2026, 2, 8, 7, 0, 0, 999),     // 美国 DST 开始前后
    Date.UTC(2026, 10, 1, 6, 0, 0, 500),    // 美国 DST 结束前后
];

const timezones = ['UTC', 'Asia/Shanghai', 'America/New_York', 'Europe/London'];

const cases = [];
for (const tz of timezones) {
    process.env.TZ = tz;
    // Node 缓存时区，改 TZ 后需要重新取一次本地时间；
    // 用 Intl 校验当前生效的时区，避免静默用错
    const effective = Intl.DateTimeFormat().resolvedOptions().timeZone;
    for (const ts of instants) {
        cases.push({
            timezone: tz,
            effectiveTimezone: effective,
            epochMillis: ts,
            iso: new Date(ts).toISOString(),
            humanized: humanizedDateTime(ts),
            messageTimestamp: getMessageTimeStamp(ts),
        });
    }
}

fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, JSON.stringify({
    _comment: '由 tools/gen-time-goldens.mjs 生成；两个函数在生成时从 SillyTavern 源码逐字抽取并运行',
    extractedFrom: 'public/scripts/RossAscends-mods.js:169-195',
    cases,
}, null, 2) + '\n');

console.log(`\n生成 ${cases.length} 条用例（${timezones.length} 时区 × ${instants.length} 瞬间），已写出 ${path.relative(ROOT, OUT)}`);
for (const c of cases.filter((c) => c.timezone === 'Asia/Shanghai').slice(0, 4)) {
    console.log(`  ${c.iso}  →  ${c.humanized}`);
}

fs.rmSync(dir, { recursive: true, force: true });

#!/usr/bin/env node
/**
 * 从 SillyTavern 的世界书引擎里，把**可独立运行的纯函数**原样抽出来，
 * 用它们生成跨语言对照表（golden），供 Kotlin 实现比对。
 *
 * 为什么是「抽取」而不是「复制」：抽取是在生成时从 ST 源码里按括号配平
 * 现场取出的，ST 一升级、重新跑一次就能发现行为漂移；
 * 如果直接在脚本里抄一份，就成了自己测自己。
 *
 * 用法：node tools/gen-worldinfo-goldens.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..');
const SOURCE = path.join(ROOT, 'SillyTavern/public/scripts/world-info.js');
const OUT = path.join(ROOT, 'fixtures/worldinfo-goldens.json');

const source = fs.readFileSync(SOURCE, 'utf8');

/** 从源码里按括号配平抽出一个具名函数（含其前的 JSDoc 之外的函数体）。 */
function extractFunction(src, name) {
    const sig = new RegExp(`^export function ${name}\\s*\\(`, 'm');
    const m = sig.exec(src);
    if (!m) throw new Error(`找不到函数 ${name}`);

    const start = m.index;
    const braceStart = src.indexOf('{', m.index);
    let depth = 0;
    let i = braceStart;
    let inString = null;
    let inLineComment = false;
    let inBlockComment = false;
    let inRegex = false;

    for (; i < src.length; i++) {
        const c = src[i];
        const prev = src[i - 1];

        if (inLineComment) { if (c === '\n') inLineComment = false; continue; }
        if (inBlockComment) { if (c === '*' && src[i + 1] === '/') { inBlockComment = false; i++; } continue; }
        if (inString) {
            if (c === '\\') { i++; continue; }
            if (c === inString) inString = null;
            continue;
        }
        if (inRegex) {
            if (c === '\\') { i++; continue; }
            if (c === '/') inRegex = false;
            continue;
        }

        if (c === '/' && src[i + 1] === '/') { inLineComment = true; i++; continue; }
        if (c === '/' && src[i + 1] === '*') { inBlockComment = true; i++; continue; }
        if (c === '"' || c === "'" || c === '`') { inString = c; continue; }
        // 粗糙但够用的正则字面量识别：`=` 或 `(` 或 `,` 之后的 `/`
        if (c === '/' && /[=(,:[!&|?\n]\s*$/.test(src.slice(Math.max(0, i - 3), i))) { inRegex = true; continue; }

        if (c === '{') depth++;
        else if (c === '}') { depth--; if (depth === 0) { i++; break; } }
        void prev;
    }

    return src.slice(start, i);
}

/** 用抽取出来的源码拼一个可 import 的临时模块。 */
async function loadExtracted(names) {
    const bodies = names.map((n) => extractFunction(source, n).replace(/^export /, ''));
    const dir = fs.mkdtempSync('/tmp/wi-extract-');
    const file = path.join(dir, 'extracted.mjs');
    fs.writeFileSync(file, bodies.join('\n\n') + '\n\nexport { ' + names.join(', ') + ' };\n');
    return { module: await import(file), dir, bodies };
}

// parseRegexFromString 来自 world-info.js
const { module: wi, dir, bodies } = await loadExtracted(['parseRegexFromString']);

// getStringHash 来自 utils.js，单独抽一次
const utilsSource = fs.readFileSync(path.join(ROOT, 'SillyTavern/public/scripts/utils.js'), 'utf8');
const hashFn = extractFunction(utilsSource, 'getStringHash').replace(/^export /, '');
const utilsDir = fs.mkdtempSync('/tmp/wi-extract-utils-');
const utilsFile = path.join(utilsDir, 'hash.mjs');
fs.writeFileSync(utilsFile, hashFn + '\n\nexport { getStringHash };\n');
const { getStringHash } = await import(utilsFile);
console.log(`从 public/scripts/utils.js 抽取了 getStringHash（${hashFn.split('\n').length} 行）`);

console.log(`从 ${path.relative(ROOT, SOURCE)} 抽取了 parseRegexFromString（${bodies[0].split('\n').length} 行）`);

// ---------------------------------------------------------------- 用例表

const regexCases = [
    // 正常
    '/abc/', '/abc/i', '/abc/gimsuy', '/abc/ig', '/whisper(s)?/i', '/a\\/b/', '/\\d+/', '/^start/',
    '/end$/m', '/a.c/s', '/[a-z]+/gi', '/abc/u', '/abc/y',
    // 非法：不是斜杠分隔
    'abc', '/abc', 'abc/', '', '/',
    // 非法：未转义的内部斜杠
    '/a/b/', '/a/b/i',
    // 非法：修饰符不合法
    '/abc/x', '/abc/ii', '/abc/1',
    // 非法：正则本身编译不过
    '/[/', '/(/', '/a{2,1}/',
    // 边界：空模式（`[\\w\\W]+?` 要求至少一个字符，所以非法）
    '//', '//i',
    // 中文、转义、命名组、环视
    '/中文/', '/\\/\\//', '/(?<name>\\w+)/', '/(?<=a)b/',
    // Java / JS 语义可能分歧的点，专门放进来
    '/\\p{L}+/u', '/\\p{L}+/', '/\\w+/', '/\\s/', '/^$/m', '/\\bword\\b/',
];

// 用固定探针串比对**实际匹配行为**。
// 不比对 `.source` —— JS 会把 `/` 转义成 `\/`，而 ST 内部变量是未转义的，
// 两边字符串形态不同但语义相同，比字符串只会得到假阴性。
const probes = [
    'abc', 'ABC', 'xabcx', 'whisper', 'whispers', 'a/b', '中文', 'aaa',
    'start here', 'the end', 'x1y2', 'a\nb', '', '  ', 'word', 'a word here',
    'p{L}', 'café', '\u00e9',
];

const regexResults = regexCases.map((input) => {
    const r = wi.parseRegexFromString(input);
    if (r === null) {
        return { input, valid: false };
    }
    // String.match 不依赖 lastIndex（无 g 时返回首个匹配对象，有 g 时返回数组），
    // 所以对带 g 的正则也是无状态的，可以安全复用。
    const matched = probes.map((probe) => probe.match(r) !== null);
    return {
        input,
        valid: true,
        // JS 的 .flags 是按固定顺序归一化过的；用集合比较避免顺序差异
        flagSet: [...new Set(r.flags.split(''))].sort(),
        matched,
    };
});

// 探针串写进文件，Kotlin 侧必须用同一批
const probeList = probes;

const valid = regexResults.filter((r) => r.valid).length;
console.log(`正则用例 ${regexResults.length} 条：合法 ${valid}，非法 ${regexResults.length - valid}`);
console.log(`每条合法用例对 ${probes.length} 个探针串比对匹配行为`);

// ---------------------------------------------------------------- 字符串哈希

const hashInputs = [
    '', 'a', 'abc', '中文', 'hello world',
    '{"uid":0,"key":["dragon"],"content":"x"}',
    '{"uid":3,"disable":true,"content":"This entry is disabled."}',
    '\u0001\n\u0001abc',
    'a'.repeat(500),
    JSON.stringify({ world: 'fixture-book', uid: 2, comment: 'Regex key + at depth', sticky: 3 }),
    '😀🎉',            // 代理对
    '\u0000\u0001\u001f',
];

const hashResults = hashInputs.map((input) => ({ input, hash: getStringHash(input) }));
console.log(`哈希用例 ${hashResults.length} 条，示例 'abc' -> ${getStringHash('abc')}`);

const payload = {
    _comment: '由 tools/gen-worldinfo-goldens.mjs 生成；函数源码在生成时从 SillyTavern 逐字抽取，请勿手改',
    generatedFrom: 'SillyTavern/public/scripts/world-info.js',
    probes: probeList,
    getStringHash: hashResults,
    parseRegexFromString: regexResults,
};

fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, JSON.stringify(payload, null, 2) + '\n');
console.log(`已写出 ${path.relative(ROOT, OUT)}（${fs.statSync(OUT).size} 字节）`);

fs.rmSync(dir, { recursive: true, force: true });

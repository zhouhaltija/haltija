#!/usr/bin/env node
/**
 * 用 SillyTavern 自己的解析器校验 fixtures/ 是否合法。
 *
 * 这个脚本是 fixture 生成器的「自证」：如果 ST 自己都读不出预期结果，
 * 那就说明 fixture 是错的，Kotlin 实现的对照测试也就没有意义。
 *
 * 用法：  node tools/verify-fixtures.mjs
 * 退出码：0 = 全部通过
 */
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..');
const ST = path.join(ROOT, 'SillyTavern');
const FIX = path.join(ROOT, 'fixtures');

const stRequire = createRequire(path.join(ST, 'package.json'));
const fflate = stRequire('fflate');

const loadSt = (rel) => import(pathToFileURL(path.join(ST, rel)).href);
const { read: readCard } = await loadSt('src/character-card-parser.js');
const { CharXParser } = await loadSt('src/charx.js');
const { readWorldInfoFile } = await loadSt('src/endpoints/worldinfo.js');

let pass = 0;
let fail = 0;

async function check(name, fn) {
    try {
        const detail = await fn();
        pass++;
        console.log(`  ✅ ${name}${detail ? `  — ${detail}` : ''}`);
    } catch (e) {
        fail++;
        console.log(`  ❌ ${name}\n       ${e.message}`);
    }
}

function assert(cond, msg) {
    if (!cond) throw new Error(msg);
}

function deepEqual(a, b, p = '$') {
    if (a === b) return;
    if (typeof a !== typeof b) throw new Error(`${p}: 类型不同 ${typeof a} vs ${typeof b}`);
    if (a === null || b === null) throw new Error(`${p}: ${JSON.stringify(a)} !== ${JSON.stringify(b)}`);
    if (Array.isArray(a) !== Array.isArray(b)) throw new Error(`${p}: 数组/对象不匹配`);
    if (typeof a !== 'object') throw new Error(`${p}: ${JSON.stringify(a)} !== ${JSON.stringify(b)}`);
    const ka = Object.keys(a), kb = Object.keys(b);
    for (const k of new Set([...ka, ...kb])) deepEqual(a[k], b[k], `${p}.${k}`);
}

const golden = (rel) => JSON.parse(fs.readFileSync(path.join(FIX, 'expected', rel), 'utf8'));

// ---------------------------------------------------------------- 角色卡

console.log('\n【角色卡】');

await check('seraphina-v3.png → 与 golden 完全一致', () => {
    const got = JSON.parse(readCard(fs.readFileSync(path.join(FIX, 'characters/seraphina-v3.png'))));
    deepEqual(got, golden('seraphina-v3.json'));
    return `spec=${got.spec} name=${got.data.name}`;
});

await check('seraphina-v2.png → 与 golden 完全一致', () => {
    const got = JSON.parse(readCard(fs.readFileSync(path.join(FIX, 'characters/seraphina-v2.png'))));
    deepEqual(got, golden('seraphina-v2.json'));
    return `spec=${got.spec}`;
});

await check('precedence-test.png → 必须选 ccv3 而非 chara', () => {
    const got = JSON.parse(readCard(fs.readFileSync(path.join(FIX, 'characters/precedence-test.png'))));
    deepEqual(got, golden('precedence-ccv3-wins.json'));
    assert(got.data.name === 'RIGHT_ccv3_chunk', `选错了 chunk，读到 ${got.data.name}`);
    return `name=${got.data.name}`;
});

await check('legacy-v1.json → 扁平格式可解析', () => {
    const got = JSON.parse(fs.readFileSync(path.join(FIX, 'characters/legacy-v1.json'), 'utf8'));
    deepEqual(got, golden('legacy-v1.json'));
    assert(got.spec === undefined && got.data === undefined, 'V1 卡不应有 spec/data');
    return '无 spec/data 字段';
});

await check('charx-test.charx → CharXParser 能解出 card.json', async () => {
    const parser = new CharXParser(fs.readFileSync(path.join(FIX, 'characters/charx-test.charx')));
    const result = await parser.parse();
    deepEqual(result.card, golden('charx-test.json'));
    assert(Buffer.isBuffer(result.avatar), 'icon 资源未解出为 Buffer');
    return `card.json name=${result.card.data.name}, avatar=${result.avatar.length}B`;
});

// ---------------------------------------------------------------- 聊天

console.log('\n【聊天记录】');

const chatDir = path.join(FIX, 'chats');
const chatFiles = fs.existsSync(chatDir)
    ? fs.readdirSync(chatDir).flatMap((d) => fs.readdirSync(path.join(chatDir, d)).map((f) => path.join(chatDir, d, f)))
    : [];

await check('存在至少一个 .jsonl 聊天样本', () => {
    assert(chatFiles.length > 0, '没有找到聊天样本');
    return `${chatFiles.length} 个文件`;
});

await check('每行都是合法 JSON，首行是 metadata', () => {
    for (const f of chatFiles) {
        const lines = fs.readFileSync(f, 'utf8').split('\n').filter((l) => l.trim());
        assert(lines.length >= 1, `${path.basename(f)} 是空的`);
        lines.forEach((l, i) => {
            try { JSON.parse(l); } catch { throw new Error(`${path.basename(f)} 第 ${i + 1} 行不是合法 JSON`); }
        });
        const meta = JSON.parse(lines[0]);
        assert(typeof meta.user_name === 'string', `${path.basename(f)} 首行缺少 user_name`);
        assert(typeof meta.character_name === 'string', `${path.basename(f)} 首行缺少 character_name`);
    }
    return `${chatFiles.length} 个文件全部合法`;
});

await await check('现代头部使用字面量 unused（与 ST saveChat 一致）', () => {
    const f = chatFiles.find((x) => x.endsWith('fixture-basic.jsonl'));
    assert(f, '缺少 fixture-basic.jsonl');
    const meta = JSON.parse(fs.readFileSync(f, 'utf8').split('\n')[0]);
    assert(meta.user_name === 'unused', `user_name 应为 'unused'，实际 ${JSON.stringify(meta.user_name)}`);
    assert(meta.character_name === 'unused', `character_name 应为 'unused'，实际 ${JSON.stringify(meta.character_name)}`);
    return 'user_name/character_name = unused';
});

await check('导入式头部仍被接受', () => {
    const f = chatFiles.find((x) => x.endsWith('fixture-imported.jsonl'));
    assert(f, '缺少 fixture-imported.jsonl');
    const meta = JSON.parse(fs.readFileSync(f, 'utf8').split('\n')[0]);
    assert(meta.user_name === 'User', '导入式头部应保留真实 user_name');
    assert(typeof meta.create_date === 'string', '导入式头部应带 create_date');
    return `create_date=${meta.create_date}`;
});

await check('integrity 与自定义 metadata 被原样保留', () => {
    const f = chatFiles.find((x) => x.endsWith('fixture-integrity.jsonl'));
    assert(f, '缺少 fixture-integrity.jsonl');
    const meta = JSON.parse(fs.readFileSync(f, 'utf8').split('\n')[0]);
    assert(meta.chat_metadata.integrity === '9f1c2b7a-4d3e-4f5a-8b6c-1d2e3f4a5b6c', 'integrity 丢失或被改写');
    assert(meta.chat_metadata.custom_flag === true, '自定义 metadata 字段丢失');
    assert(Array.isArray(meta.chat_metadata.attachments), 'attachments 字段丢失');
    return 'integrity + custom_flag + attachments 均在';
});

check('消息字段符合 ST 规范', () => {
    const required = ['name', 'is_user', 'is_system', 'send_date', 'mes', 'extra'];
    for (const f of chatFiles) {
        const lines = fs.readFileSync(f, 'utf8').split('\n').filter((l) => l.trim()).slice(1);
        for (const l of lines) {
            const m = JSON.parse(l);
            for (const k of required) assert(k in m, `${path.basename(f)} 消息缺少字段 ${k}`);
            assert(typeof m.mes === 'string', 'mes 必须是 string');
            assert(typeof m.is_user === 'boolean', 'is_user 必须是 boolean');
        }
    }
    return `必填字段 ${required.join(', ')}`;
});

await check('swipes / swipe_info / swipe_id 三者长度一致', () => {
    let found = 0;
    for (const f of chatFiles) {
        const lines = fs.readFileSync(f, 'utf8').split('\n').filter((l) => l.trim()).slice(1);
        for (const l of lines) {
            const m = JSON.parse(l);
            if (!m.swipes) continue;
            found++;
            assert(m.swipes.length === m.swipe_info.length, 'swipes 与 swipe_info 长度不一致');
            assert(m.swipe_id >= 0 && m.swipe_id < m.swipes.length, 'swipe_id 越界');
            assert(m.mes === m.swipes[m.swipe_id], 'mes 必须等于 swipes[swipe_id]');
        }
    }
    assert(found > 0, '样本里没有 swipe 数据，无法覆盖');
    return `覆盖 ${found} 条带 swipe 的消息`;
});

// ---------------------------------------------------------------- 世界书

console.log('\n【世界书】');

await check('worlds/fixture-book.json 结构合法', () => {
    const book = JSON.parse(fs.readFileSync(path.join(FIX, 'worlds/fixture-book.json'), 'utf8'));
    assert(book.entries && typeof book.entries === 'object', '缺少 entries');
    const uids = Object.keys(book.entries);
    assert(uids.length === 4, `预期 4 条，实际 ${uids.length}`);
    for (const uid of uids) {
        const e = book.entries[uid];
        assert(Array.isArray(e.key), `uid ${uid} 的 key 必须是数组`);
        assert(typeof e.content === 'string', `uid ${uid} 的 content 必须是 string`);
        assert([0, 1, 2, 3].includes(e.selectiveLogic), `uid ${uid} selectiveLogic 非法`);
        assert(e.position >= 0 && e.position <= 7, `uid ${uid} position 非法`);
    }
    return `${uids.length} 条，覆盖 constant / selective / regex / disabled`;
});

await check('ST 自己的 readWorldInfoFile 能读出这本世界书', () => {
    const book = readWorldInfoFile({ worlds: path.join(FIX, 'worlds') }, 'fixture-book', false);
    assert(book, 'ST 返回了 null，说明文件读不出来');
    assert(book.entries, '缺少 entries');
    const uids = Object.keys(book.entries);
    assert(uids.length === 4, `ST 读到 ${uids.length} 条，预期 4 条`);
    assert(book.entries[1].selectiveLogic === 0, 'selectiveLogic 被读错');
    assert(book.entries[2].position === 4, 'position 被读错');
    assert(book.entries[3].disable === true, 'disable 被读错');
    return `ST 读到 ${uids.length} 条，字段值一致`;
});

await check('ST 的 /list 能拿到书名', () => {
    const book = readWorldInfoFile({ worlds: path.join(FIX, 'worlds') }, 'fixture-book', false);
    const name = book?.name || 'fixture-book';
    return `name='${name}'（无 name 字段时退回文件名）`;
});

// ---------------------------------------------------------------- 预设

console.log('\n【预设】');

await check('预设 JSON 均可解析且非空', () => {
    const pdir = path.join(FIX, 'presets');
    const files = fs.readdirSync(pdir).flatMap((d) => fs.readdirSync(path.join(pdir, d)).map((f) => path.join(pdir, d, f)));
    assert(files.length > 0, '没有预设样本');
    for (const f of files) {
        const o = JSON.parse(fs.readFileSync(f, 'utf8'));
        assert(Object.keys(o).length > 0, `${path.basename(f)} 是空对象`);
    }
    return `${files.length} 个预设`;
});

// ---------------------------------------------------------------- 汇总

console.log(`\n${'─'.repeat(50)}`);
console.log(`通过 ${pass} 项，失败 ${fail} 项`);
process.exit(fail === 0 ? 0 : 1);

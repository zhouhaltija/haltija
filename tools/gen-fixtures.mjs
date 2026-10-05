#!/usr/bin/env node
/**
 * 生成 fixtures/ —— 用于验证 Kotlin 数据层的「验收基准」。
 *
 * 设计原则：所有产物都由 SillyTavern 自己的模块或 API 生成，
 * 而不是手写「我以为的格式」。这样基准才是权威的。
 *
 * 用法：  node tools/gen-fixtures.mjs
 * 需要：  SillyTavern/node_modules 已安装
 */
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { spawn } from 'node:child_process';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..');
const ST = path.join(ROOT, 'SillyTavern');
const OUT = path.join(ROOT, 'fixtures');
const SCRATCH = path.join(ROOT, '.fixture-scratch');

const PORT = 8231;

// ST 的依赖从 ST 自己的 node_modules 解析
const stRequire = createRequire(path.join(ST, 'package.json'));
const fflate = stRequire('fflate');
const extractChunks = stRequire('png-chunks-extract');
const PNGtext = stRequire('png-chunk-text');

const loadSt = (rel) => import(pathToFileURL(path.join(ST, rel)).href);
const { read: readCard } = await loadSt('src/character-card-parser.js');
const { default: encodePng } = await loadSt('src/png/encode.js');

// ---------------------------------------------------------------- helpers

function mkdirp(p) { fs.mkdirSync(p, { recursive: true }); }

function freshDir(p) {
    fs.rmSync(p, { recursive: true, force: true });
    mkdirp(p);
}

function writeJson(p, obj) {
    mkdirp(path.dirname(p));
    fs.writeFileSync(p, JSON.stringify(obj, null, 2) + '\n');
}

function writeBin(p, buf) {
    mkdirp(path.dirname(p));
    fs.writeFileSync(p, buf);
}

/** 用 base64 编码的 tEXt 块组装 PNG。keyword 为 chara / ccv3。 */
function buildPngWithTextChunks(sourcePng, chunks) {
    const parsed = extractChunks(new Uint8Array(sourcePng));
    const cleaned = parsed.filter((c) => {
        if (c.name !== 'tEXt') return true;
        const d = PNGtext.decode(c.data);
        const kw = d.keyword.toLowerCase();
        return kw !== 'chara' && kw !== 'ccv3';
    });
    const newChunks = chunks.map(({ keyword, json }) =>
        PNGtext.encode(keyword, Buffer.from(JSON.stringify(json), 'utf8').toString('base64')));
    cleaned.splice(-1, 0, ...newChunks);
    return Buffer.from(encodePng(cleaned));
}

async function waitForServer(timeoutMs = 90_000) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
        try {
            const r = await fetch(`http://127.0.0.1:${PORT}/`, { signal: AbortSignal.timeout(2000) });
            if (r.ok) return true;
        } catch { /* not ready */ }
        await new Promise((r) => setTimeout(r, 500));
    }
    return false;
}

function startST() {
    const proc = spawn(process.execPath, [
        'server.js',
        '--port', String(PORT),
        '--browserLaunchEnabled=false',
        '--disableCsrf',
        '--dataRoot', SCRATCH,
    ], { cwd: ST, stdio: ['ignore', 'pipe', 'pipe'] });

    let log = '';
    proc.stdout.on('data', (d) => { log += d; });
    proc.stderr.on('data', (d) => { log += d; });

    proc.on('exit', (code) => {
        if (code !== 0 && code !== null) {
            console.error('ST server exited early, code=', code);
            console.error(log.slice(-2000));
        }
    });
    return { proc, getLog: () => log };
}

async function api(pathname, body) {
    const r = await fetch(`http://127.0.0.1:${PORT}${pathname}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    });
    if (!r.ok) throw new Error(`${pathname} -> ${r.status} ${await r.text()}`);
    return r.json();
}

// ---------------------------------------------------------------- cards

const log = [];
const note = (s) => { console.log(s); log.push(s); };

mkdirp(OUT);
freshDir(SCRATCH);

const sourcePng = fs.readFileSync(path.join(ST, 'default/content/default_Seraphina.png'));
const sourceCard = JSON.parse(readCard(sourcePng));

note(`源卡: ${sourceCard.data?.name ?? sourceCard.name}  spec=${sourceCard.spec} v${sourceCard.spec_version}`);
note(`源 PNG 体积: ${(sourcePng.length / 1024).toFixed(0)} KB`);

// --- V3 卡：chara + ccv3 内容一致
const v3Card = structuredClone(sourceCard);
v3Card.spec = 'chara_card_v3';
v3Card.spec_version = '3.0';
writeJson(path.join(OUT, 'expected/seraphina-v3.json'), v3Card);
writeBin(path.join(OUT, "characters/seraphina-v3.png"),
    buildPngWithTextChunks(sourcePng, [
        { keyword: 'chara', json: v3Card },
        { keyword: 'ccv3', json: v3Card },
    ]));

// --- V2 卡：只有 chara，spec 降级为 v2
const v2Card = { spec: 'chara_card_v2', spec_version: '2.0', data: structuredClone(sourceCard.data) };
writeJson(path.join(OUT, 'expected/seraphina-v2.json'), v2Card);
writeBin(path.join(OUT, "characters/seraphina-v2.png"),
    buildPngWithTextChunks(sourcePng, [{ keyword: 'chara', json: v2Card }]));

// --- 优先级测试：chara 和 ccv3 故意不同，解析器必须选 ccv3
const wrongV2 = { spec: 'chara_card_v2', spec_version: '2.0', data: { ...structuredClone(sourceCard.data), name: 'WRONG_chara_chunk' } };
const rightV3 = structuredClone(v3Card);
rightV3.data = { ...structuredClone(v3Card.data), name: 'RIGHT_ccv3_chunk' };
writeJson(path.join(OUT, 'expected/precedence-ccv3-wins.json'), rightV3);
writeBin(path.join(OUT, "characters/precedence-test.png"),
    buildPngWithTextChunks(sourcePng, [
        { keyword: 'chara', json: wrongV2 },
        { keyword: 'ccv3', json: rightV3 },
    ]));

// --- 老式 V1 JSON（扁平，无 spec / data）
const v1Card = {
    name: 'Legacy V1 Card',
    description: 'A card in the original flat format.',
    personality: 'Terse, old-fashioned.',
    scenario: 'A dusty archive.',
    first_mes: 'You found me in an old backup.',
    mes_example: '<START>\n{{user}}: hi\n{{char}}: hello.',
};
writeJson(path.join(OUT, 'expected/legacy-v1.json'), v1Card);
writeJson(path.join(OUT, 'characters/legacy-v1.json'), v1Card);

// --- CHARX（zip: card.json + 内嵌 icon 资源）
const charxCard = structuredClone(v3Card);
charxCard.data = {
    ...structuredClone(v3Card.data),
    name: 'CharX Test',
    assets: [{
        type: 'icon',
        uri: 'embeded://assets/icon/main.png',
        name: 'main',
        ext: 'png',
    }],
};
writeJson(path.join(OUT, 'expected/charx-test.json'), charxCard);
const zipEntries = {
    'card.json': new Uint8Array(Buffer.from(JSON.stringify(charxCard), 'utf8')),
    'assets/icon/main.png': new Uint8Array(sourcePng),
};
writeBin(path.join(OUT, "characters/charx-test.charx"), Buffer.from(fflate.zipSync(zipEntries, { level: 6 })));

note('卡片产物: seraphina-v3.png / seraphina-v2.png / precedence-test.png / legacy-v1.json / charx-test.charx');

// ---------------------------------------------------------------- world book

const worldBook = {
    entries: {
        0: {
            uid: 0, key: ['dragon', 'wyrm'], keysecondary: [], comment: 'Constant lore',
            content: 'Dragons are ancient and hoard memories, not gold.', constant: true,
            selective: false, selectiveLogic: 0, addMemo: false, order: 100, position: 0,
            disable: false, excludeRecursion: false, preventRecursion: false, delayUntilRecursion: 0,
            probability: 100, useProbability: true, depth: 4, group: '', groupOverride: false,
            groupWeight: 100, scanDepth: null, caseSensitive: null, matchWholeWords: null,
            useGroupScoring: null, automationId: '', role: 0, sticky: null, cooldown: null, delay: null,
            displayIndex: 0, vectorized: false, matchPersonaDescription: false,
            matchCharacterDescription: false, matchCharacterPersonality: false,
            matchCharacterDepthPrompt: false, matchScenario: false, matchCreatorNotes: false,
            ignoreBudget: false, triggers: [],
        },
        1: {
            uid: 1, key: ['sword'], keysecondary: ['silver', 'enchanted'], comment: 'Selective + AND_ANY',
            content: 'The blade hums when silver is near.', constant: false,
            selective: true, selectiveLogic: 0, addMemo: false, order: 200, position: 1,
            disable: false, excludeRecursion: false, preventRecursion: false, delayUntilRecursion: 0,
            probability: 100, useProbability: true, depth: 4, group: '', groupOverride: false,
            groupWeight: 100, scanDepth: 2, caseSensitive: false, matchWholeWords: true,
            useGroupScoring: null, automationId: '', role: 0, sticky: null, cooldown: null, delay: null,
            displayIndex: 1, vectorized: false, matchPersonaDescription: false,
            matchCharacterDescription: false, matchCharacterPersonality: false,
            matchCharacterDepthPrompt: false, matchScenario: false, matchCreatorNotes: false,
            ignoreBudget: false, triggers: [],
        },
        2: {
            uid: 2, key: ['/whisper(s)?/i'], keysecondary: [], comment: 'Regex key + at depth',
            content: 'A whisper carries the recursion seed.', constant: false,
            selective: true, selectiveLogic: 0, addMemo: false, order: 300, position: 4,
            disable: false, excludeRecursion: false, preventRecursion: false, delayUntilRecursion: 0,
            probability: 100, useProbability: true, depth: 2, group: '', groupOverride: false,
            groupWeight: 100, scanDepth: null, caseSensitive: null, matchWholeWords: null,
            useGroupScoring: null, automationId: '', role: 0, sticky: 3, cooldown: 2, delay: 0,
            displayIndex: 2, vectorized: false, matchPersonaDescription: false,
            matchCharacterDescription: false, matchCharacterPersonality: false,
            matchCharacterDepthPrompt: false, matchScenario: false, matchCreatorNotes: false,
            ignoreBudget: false, triggers: [],
        },
        3: {
            uid: 3, key: ['disabled-entry'], keysecondary: [], comment: 'Must never activate',
            content: 'This entry is disabled.', constant: true, selective: false, selectiveLogic: 0,
            addMemo: false, order: 400, position: 0, disable: true, excludeRecursion: false,
            preventRecursion: false, delayUntilRecursion: 0, probability: 100, useProbability: true,
            depth: 4, group: '', groupOverride: false, groupWeight: 100, scanDepth: null,
            caseSensitive: null, matchWholeWords: null, useGroupScoring: null, automationId: '',
            role: 0, sticky: null, cooldown: null, delay: null, displayIndex: 3, vectorized: false,
            matchPersonaDescription: false, matchCharacterDescription: false,
            matchCharacterPersonality: false, matchCharacterDepthPrompt: false, matchScenario: false,
            matchCreatorNotes: false, ignoreBudget: false, triggers: [],
        },
    },
};
// 世界书在 ST 里就是「名字 = 文件名」的一本 JSON
mkdirp(path.join(OUT, 'worlds'));
writeJson(path.join(OUT, 'worlds/fixture-book.json'), worldBook);

// ---------------------------------------------------------------- presets

const presetDirs = ['context', 'instruct', 'sysprompt', 'reasoning'];
for (const dir of presetDirs) {
    const src = path.join(ST, 'default/content/presets', dir);
    if (!fs.existsSync(src)) continue;
    const files = fs.readdirSync(src).filter((f) => f.endsWith('.json')).slice(0, 2);
    for (const f of files) {
        mkdirp(path.join(OUT, 'presets', dir));
        fs.copyFileSync(path.join(src, f), path.join(OUT, 'presets', dir, f));
    }
}
note('预设产物: fixtures/presets/{context,instruct,sysprompt,reasoning}/*.json（ST 自带）');

// --- 全字段预设（合成，但 schema 完整）-----------------------------------
// 为什么需要它：如果 fixture 里某个键压根不存在，Kotlin 的访问器即使把键名拼错，
// 测试也会因为「退回默认值」而通过。把每个字段都设成与默认值不同的可辨识取值，
// 才能保证访问器真的读到了那个键。
const allFields = {
    context: {
        name: 'AllFields Context',
        story_string: 'SYS={{system}}|WI={{wiBefore}}|DESC={{description}}|T={{trim}}',
        example_separator: '<<EX>>',
        chat_start: '<<START>>',
        use_stop_strings: false,
        names_as_stop_strings: false,
        story_string_position: 1,
        story_string_depth: 7,
        story_string_role: 2,
        always_force_name2: true,
        trim_sentences: true,
        single_line: true,
    },
    instruct: {
        name: 'AllFields Instruct',
        input_sequence: '<IN>',
        output_sequence: '<OUT>',
        last_output_sequence: '<LASTOUT>',
        system_sequence: '<SYS>',
        stop_sequence: '<STOP>',
        wrap: false,
        macro: false,
        names_behavior: 'always',
        activation_regex: 'thinking',
        first_output_sequence: '<FIRSTOUT>',
        skip_examples: true,
        output_suffix: '<OUTSUF>',
        input_suffix: '<INSUF>',
        system_suffix: '<SYSSUF>',
        user_alignment_message: '<ALIGN>',
        system_same_as_user: true,
        last_system_sequence: '<LASTSYS>',
        first_input_sequence: '<FIRSTIN>',
        last_input_sequence: '<LASTIN>',
        sequences_as_stop_strings: false,
        story_string_prefix: '<PREFIX>',
        story_string_suffix: '<SUFFIX>',
    },
    sysprompt: {
        name: 'AllFields SysPrompt',
        content: '<CONTENT>',
        post_history: '<POSTHISTORY>',
    },
    reasoning: {
        name: 'AllFields Reasoning',
        prefix: '<PRE>',
        suffix: '<SUF>',
        separator: '<SEP>',
    },
};

for (const [kind, preset] of Object.entries(allFields)) {
    writeJson(path.join(OUT, 'presets', kind, 'AllFields.json'), preset);
}
note('预设产物: AllFields.json × 4（每个字段都设为非默认值，用于 zod 式字段覆盖）');


// ---------------------------------------------------------------- chats（走 ST API）

const { proc, getLog } = startST();
note(`启动 ST (port ${PORT}) 生成聊天样本 ...`);

if (!await waitForServer()) {
    console.error(getLog().slice(-2000));
    proc.kill('SIGKILL');
    throw new Error('ST 未能在超时内就绪');
}

const ready = await api('/api/characters/all', {}).catch(() => null);
const avatarUrl = (ready && ready[0]?.avatar) || 'Seraphina.png';
note(`数据目录中的角色头像: ${avatarUrl}`);

function msg(o) {
    return { extra: {}, is_system: false, send_date: '2026-10-05T07:00:00.000Z', ...o };
}

// ST 保存聊天时的真实头部（public/script.js:7428）：
//   { chat_metadata, user_name: 'unused', character_name: 'unused' }
// 注意 user_name / character_name 就是字面量 'unused'，不是角色的真实名字。
// 头部没有 create_date —— 那是角色字段，不是聊天字段。
const stHeader = (metadata) => ({
    chat_metadata: metadata,
    user_name: 'unused',
    character_name: 'unused',
});

// ① 现代 ST 写出的标准聊天
const basicChat = [
    stHeader({}),
    msg({ name: 'Seraphina', is_user: false, mes: 'Good morning. The kettle is already on.', send_date: '2026-10-05T07:00:05.000Z' }),
    msg({ name: 'User', is_user: true, mes: 'You remembered.', send_date: '2026-10-05T07:00:20.000Z' }),
    msg({
        // ST 的不变量：mes === swipes[swipe_id]（script.js:6790 每次生成都会回写 swipes[id] = mes）
        name: 'Seraphina', is_user: false, mes: 'Of course I did.',
        send_date: '2026-10-05T07:00:31.000Z',
        swipes: ['I remember most things about you.', 'Of course I did.', 'Some things are worth remembering.'],
        swipe_id: 1,
        swipe_info: [
            { send_date: '2026-10-05T07:00:30.000Z', gen_started: '2026-10-05T07:00:25.000Z', gen_finished: '2026-10-05T07:00:30.000Z', extra: {} },
            { send_date: '2026-10-05T07:00:31.000Z', gen_started: '2026-10-05T07:00:26.000Z', gen_finished: '2026-10-05T07:00:31.000Z', extra: {} },
            { send_date: '2026-10-05T07:00:32.000Z', gen_started: '2026-10-05T07:00:27.000Z', gen_finished: '2026-10-05T07:00:32.000Z', extra: {} },
        ],
    }),
];
await api('/api/chats/save', { avatar_url: avatarUrl, file_name: 'fixture-basic', chat: basicChat });

// ② 老式 / 第三方导入的聊天：头部带真实名字与 create_date
//    （ST 的导入校验认 user_name / name / chat_metadata 三者之一，见 src/endpoints/chats.js:839）
const importedChat = [
    { user_name: 'User', character_name: 'Seraphina', create_date: '2026-10-05T08:00:00.000Z', chat_metadata: {} },
    msg({ name: 'Seraphina', is_user: false, mes: 'Welcome back.', send_date: '2026-10-05T08:00:05.000Z' }),
    msg({ name: '', is_user: false, mes: '[A hidden system note]', send_date: '2026-10-05T08:00:10.000Z', is_system: true }),
    msg({ name: 'User', is_user: true, mes: 'Let us continue.', send_date: '2026-10-05T08:00:20.000Z', force_avatar: '/img/user-default.png' }),
];
await api('/api/chats/save', { avatar_url: avatarUrl, file_name: 'fixture-imported', chat: importedChat });

// ③ 带 integrity 与自定义 metadata 的聊天。
//    ST 加载时若无 integrity 会补一个 uuidv4（script.js:7666），保存前校验它；
//    客户端写回时必须原样保留 chat_metadata，否则 ST 会判定「完整性校验失败」而拒绝覆盖。
const integrityChat = [
    stHeader({
        integrity: '9f1c2b7a-4d3e-4f5a-8b6c-1d2e3f4a5b6c',
        custom_flag: true,
        attachments: [],
        note_prompt: 'keep it short',
    }),
    msg({ name: 'Seraphina', is_user: false, mes: 'The archive remembers.', send_date: '2026-10-05T09:00:05.000Z' }),
    msg({ name: 'User', is_user: true, mes: 'Does it remember me?', send_date: '2026-10-05T09:00:20.000Z' }),
];
await api('/api/chats/save', { avatar_url: avatarUrl, file_name: 'fixture-integrity', chat: integrityChat });

// 世界书能被 ST 读回，说明格式正确
const wiList = await api('/api/worldinfo/list', {}).catch(() => null);
note(`ST /api/worldinfo/list 可用: ${wiList ? 'yes' : 'no'}`);

proc.kill('SIGTERM');
await new Promise((r) => setTimeout(r, 1500));
proc.kill('SIGKILL');

// 把 API 生成的 jsonl 原样搬进 fixtures
const chatsSrc = path.join(SCRATCH, 'default-user', 'chats');
freshDir(path.join(OUT, 'chats'));
if (fs.existsSync(chatsSrc)) {
    for (const cardDir of fs.readdirSync(chatsSrc)) {
        const dst = path.join(OUT, 'chats', cardDir);
        mkdirp(dst);
        for (const f of fs.readdirSync(path.join(chatsSrc, cardDir))) {
            fs.copyFileSync(path.join(chatsSrc, cardDir, f), path.join(dst, f));
            note(`聊天样本: chats/${cardDir}/${f}`);
        }
    }
} else {
    note('⚠️ 未找到 chats 目录');
}

// ---------------------------------------------------------------- Prompt 管理器的默认顺序

// 从 PromptManager.js 里把默认顺序数组原文抽出来。
// 手抄一份到 Kotlin 里很容易抄错顺序（worldInfoAfter 排在 nsfw 之后这种），
// 所以这里做一份 fixture，让 Kotlin 侧能对着它断言。
{
    const pmSrc = fs.readFileSync(path.join(ST, 'public/scripts/PromptManager.js'), 'utf8');
    const MARKER = 'const promptManagerDefaultPromptOrder = [';
    const start = pmSrc.indexOf(MARKER);
    if (start < 0) throw new Error('找不到 promptManagerDefaultPromptOrder');
    const end = pmSrc.indexOf('];', start);
    const body = pmSrc.slice(start + MARKER.length, end);

    const entries = [];
    const re = /\{\s*'identifier':\s*'([^']+)'\s*,\s*'enabled':\s*(true|false)\s*,?\s*\}/g;
    let m;
    while ((m = re.exec(body)) !== null) {
        entries.push({ identifier: m[1], enabled: m[2] === 'true' });
    }
    if (entries.length === 0) throw new Error('默认顺序解析为空');

    writeJson(path.join(OUT, 'prompt-order/default.json'), {
        _comment: '由 tools/gen-fixtures.mjs 从 SillyTavern/public/scripts/PromptManager.js 抽取',
        source: 'promptManagerDefaultPromptOrder',
        entries,
    });
    note(`Prompt 默认顺序: ${entries.length} 项（${entries.filter((e) => e.enabled).length} 项启用）`);
}

// ---------------------------------------------------------------- 汇总

const inventory = [];
(function walk(dir, base = '') {
    if (!fs.existsSync(dir)) return;
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
        const rel = base ? `${base}/${e.name}` : e.name;
        const full = path.join(dir, e.name);
        if (e.isDirectory()) walk(full, rel);
        else inventory.push({ path: rel, bytes: fs.statSync(full).size });
    }
})(OUT);

writeJson(path.join(OUT, 'MANIFEST.json'), { generatedAt: new Date().toISOString(), files: inventory });

console.log('\n=== fixtures 清单 ===');
for (const f of inventory) console.log(`  ${String(f.bytes).padStart(9)}  ${f.path}`);
console.log(`\n共 ${inventory.length} 个文件，${(inventory.reduce((a, b) => a + b.bytes, 0) / 1024).toFixed(0)} KB`);

fs.rmSync(SCRATCH, { recursive: true, force: true });
console.log('临时数据目录已清理');

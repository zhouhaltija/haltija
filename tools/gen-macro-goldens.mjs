#!/usr/bin/env node
/**
 * 把 SillyTavern 的**真实宏引擎**抽出来跑，生成跨语言对照表。
 *
 * 为什么可行：宏引擎那几个模块（MacroLexer / MacroParser / MacroEngine /
 * MacroCstWalker / MacroRegistry / MacroFlags）只依赖 `chevrotain`，
 * 外加两个纯函数（isTrueBoolean / isFalseBoolean）和一个常量（ELSE_MARKER）。
 * 剩下会拖进 DOM 的只有 MacroDiagnostics，把它换成一个收集消息的桩即可。
 *
 * 于是在一个临时的模块树里改掉 import 路径，就能原样运行 ST 的词法/语法/求值器。
 * 这不是「抄一份」，是**生成时现场抽取**：ST 升级后重跑就能发现行为漂移。
 *
 * 用法：node tools/gen-macro-goldens.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..');
const ST = path.join(ROOT, 'SillyTavern');
const SRC = path.join(ST, 'public/scripts/macros/engine');
const OUT = path.join(ROOT, 'fixtures/macro-goldens.json');

// 必须放在 ST 目录内，否则裸模块名 `chevrotain` 解析不到
const HARNESS = fs.mkdtempSync(path.join(ST, '.macro-harness-'));
const ENGINE = path.join(HARNESS, 'engine');
const SHIMS = path.join(HARNESS, 'shims');

// ---------------------------------------------------------------- 搭模块树

const IMPORT_REWRITES = [
    [/'\.\.\/\.\.\/\.\.\/lib\.js'/g, "'../shims/lib.js'"],
    [/'\.\.\/\.\.\/utils\.js'/g, "'../shims/utils.js'"],
    [/'\/scripts\/utils\.js'/g, "'../shims/utils.js'"],
    [/'\.\.\/definitions\/core-macros\.js'/g, "'../shims/core-macros.js'"],
    [/'\.\/MacroDiagnostics\.js'/g, "'../shims/MacroDiagnostics.js'"],
];

fs.mkdirSync(ENGINE, { recursive: true });
fs.mkdirSync(SHIMS, { recursive: true });

const copied = [];
for (const name of fs.readdirSync(SRC)) {
    if (!name.endsWith('.js')) continue;
    if (name === 'MacroDiagnostics.js') continue; // 用桩替换
    let code = fs.readFileSync(path.join(SRC, name), 'utf8');
    for (const [pattern, replacement] of IMPORT_REWRITES) {
        code = code.replace(pattern, replacement);
    }
    fs.writeFileSync(path.join(ENGINE, name), code);
    copied.push(name);
}
console.log(`抽取 ${copied.length} 个模块: ${copied.join(', ')}`);

// —— 桩 ——
fs.writeFileSync(path.join(SHIMS, 'lib.js'), `import * as chevrotain from 'chevrotain';\nexport { chevrotain };\n`);

fs.writeFileSync(path.join(SHIMS, 'utils.js'), `
export function isTrueBoolean(arg) { return ['on', 'true', '1'].includes(arg?.trim()?.toLowerCase()); }
export function isFalseBoolean(arg) { return ['off', 'false', '0'].includes(arg?.trim()?.toLowerCase()); }
`);

fs.writeFileSync(path.join(SHIMS, 'core-macros.js'), `
// core-macros.js:24 —— {{else}} 用的内部分隔标记
export const ELSE_MARKER = '\\u0000\\u001FELSE\\u001F\\u0000';
`);

fs.writeFileSync(path.join(SHIMS, 'MacroDiagnostics.js'), `
export const diagnostics = [];
const push = (level) => (payload) => { diagnostics.push({ level, ...payload }); };
export const logMacroGeneralError = push('error');
export const logMacroInternalError = push('internal');
export const logMacroRuntimeWarning = push('runtime-warning');
export const logMacroSyntaxWarning = push('syntax-warning');
export const logMacroRegisterError = push('register-error');
export const logMacroRegisterWarning = push('register-warning');
export function createMacroRuntimeError(message) { return new Error(message); }
`);

// ---------------------------------------------------------------- 运行

const { MacroEngine } = await import(path.join(ENGINE, 'MacroEngine.js'));
const { MacroRegistry, MacroValueType } = await import(path.join(ENGINE, 'MacroRegistry.js'));

// 注册一批**行为可预测**的测试宏，用来验证语法与求值
MacroRegistry.registerMacro('up', {
    unnamedArgs: [{ name: 'text', type: MacroValueType.STRING }],
    handler: ({ unnamedArgs: [text] }) => String(text).toUpperCase(),
});
MacroRegistry.registerMacro('echo', {
    unnamedArgs: [{ name: 'text', type: MacroValueType.STRING }],
    handler: ({ unnamedArgs: [text] }) => String(text),
});
MacroRegistry.registerMacro('twice', {
    unnamedArgs: [{ name: 'text', type: MacroValueType.STRING }],
    handler: ({ unnamedArgs: [text] }) => String(text) + String(text),
});
MacroRegistry.registerMacro('pad', {
    // 注意 defaultValue 是**字符串** '1'：写成数字 1 会让注册直接失败
    // （core-macros.js 的 {{space}} 也是这么写的）
    unnamedArgs: [
        { name: 'count', type: MacroValueType.INTEGER, optional: true, defaultValue: '1' },
    ],
    handler: ({ unnamedArgs: [count] }) => '.'.repeat(Number(count)),
});
MacroRegistry.registerMacro('opt', {
    unnamedArgs: [
        { name: 'a', type: MacroValueType.STRING, optional: true },
        { name: 'b', type: MacroValueType.STRING, optional: true, defaultValue: 'D' },
    ],
    handler: ({ unnamedArgs: [a, b] }) => `[${a ?? 'null'}|${b ?? 'null'}]`,
});
MacroRegistry.registerMacro('noop', { handler: () => '' });
// 读环境变量的宏，模拟 ST 的 {{char}} / {{user}}
// 与 {{space}} 完全同构：可选 + 默认值，但不写 optional
MacroRegistry.registerMacro('pad2', {
    unnamedArgs: [{ name: 'count', type: MacroValueType.INTEGER, defaultValue: '1' }],
    handler: ({ unnamedArgs: [count] }) => '.'.repeat(Number(count)),
});

MacroRegistry.registerMacro('char', { handler: ({ env }) => env.names.char });
MacroRegistry.registerMacro('user', { handler: ({ env }) => env.names.user });
MacroRegistry.registerMacro('description', { handler: ({ env }) => env.character.description });
MacroRegistry.registerMacro('group', { handler: ({ env }) => env.names.group ?? '' });
MacroRegistry.registerMacro('charIfNotGroup', { handler: ({ env }) => env.names.group ?? '' });

MacroRegistry.registerMacro('bang', {
    unnamedArgs: [{ name: 'text', type: MacroValueType.STRING }],
    handler: ({ unnamedArgs: [text] }) => `!${text}!`,
});

// 环境：提供几个变量，模拟 {{char}} 这类
const env = {
    names: { char: 'Seraphina', user: 'Traveler', group: 'The Trio' },
    character: { name: 'Seraphina', description: 'A scholar.', personality: 'Curious.' },
    system: { maxPrompt: 2048 },
    functions: {},
    // 引擎在解析宏时会读 env.dynamicMacros；缺了它 evaluate 会抛错并**静默回退**成原文
    dynamicMacros: {},
};

const inputs = [
    // 空 / 无宏
    '', 'plain text', 'a {b} c',
    // 简单变量
    '{{char}}', '{{user}}', 'x{{char}}y', '{{char}}/{{user}}',
    // 未知宏
    '{{unknownMacro}}', 'a{{unknownMacro}}b',
    // 单冒号 vs 双冒号（吃空格规则）
    '{{up:abc}}', '{{up::abc}}', 'A {{up:: abc }} B',
    // 嵌套
    '{{up:{{char}}}}', '{{twice:{{char}}}}',
    // 多个参数
    '{{opt}}', '{{opt:a}}', '{{opt:a,b}}',
    // 整数参数
    '{{pad}}', '{{pad:3}}', '{{pad::3}}',
    // 空参数
    '{{echo:}}', '{{echo::}}',
    // 未闭合
    '{{up:abc', '{{', 'a{{b',
    // 空的宏调用
    '{{}}', '{{ }}',
    // 连续
    '{{up:a}}{{up:b}}', '{{noop}}X{{noop}}',
    // 转义相关
    '\\{\\{char\\}\\}', '{{{{char}}}}',
    // 带特殊字符的参数
    '{{echo:a-b_c}}', '{{echo:a b c}}',
    // env 变量
    '{{description}}', '{{personality}}',
    // 单冒号 vs 双冒号 + 空白：这才是两者真正的差别
    '{{up: abc }}', '{{up:: abc }}',
    '{{up:a b}}', '{{up::a b}}',
    'X{{up:ab}}Y', 'X{{up::ab}}Y',
    // 前导/尾随空格是否被吃掉
    '{{echo:  padded  }}',
    // 连续冒号
    '{{echo:::a}}', '{{echo:a:b}}',
    // :: 的真正含义：多参数分隔
    '{{opt::a::b}}', '{{opt::a}}', '{{opt::a::b::c}}',
    '{{echo::a::b}}', '{{echo:a::b}}',
    // 缺省值是否会生效
    '{{pad2}}', '{{pad2:5}}',
    // 空白分隔参数（也是一种「单参数」写法）
    '{{echo abc}}', '{{echo  a b  c  }}', '{{up hello}}',
    // 宏名大小写不敏感
    '{{UP:abc}}', '{{Up:abc}}', '{{ECHO:x}}',
    // INTEGER 类型校验
    '{{pad:abc}}', '{{pad:-1}}', '{{pad:0}}', '{{pad2:abc}}', '{{pad2:2}}',
    // 空尾参数
    '{{opt::a::}}', '{{opt::}}',
    // 老式尖括号标记（预处理阶段改写）
    '<USER>', '<BOT>', '<CHAR>', '<GROUP>', '<CHARIFNOTGROUP>',
    'x<BOT>y', '<user>小写',
    // {{time_UTC±N}} 预处理
    '{{time_UTC+2}}', '{{time_UTC-10}}',
    // 单冒号后的冒号
    '{{opt:a:b}}',
];

const results = [];
for (const input of inputs) {
    let output = null;
    let error = null;
    try {
        output = await MacroEngine.evaluate(input, env);
    } catch (e) {
        error = String(e.message ?? e).split('\n')[0];
    }
    results.push({ input, output, error });
    console.log(`  ${JSON.stringify(input).padEnd(28)} -> ${error ? 'ERROR: ' + error : JSON.stringify(output)}`);
}

// 打印诊断：注册失败 / 语法警告都藏在这里，不看的话会以为是「引擎没做事」
const { diagnostics } = await import(path.join(SHIMS, 'MacroDiagnostics.js'));
if (diagnostics.length > 0) {
    console.log(`\n诊断消息 ${diagnostics.length} 条：`);
    for (const d of diagnostics.slice(0, 15)) {
        console.log(`  [${d.level}] ${d.message ?? JSON.stringify(d).slice(0, 160)}`);
    }
} else {
    console.log('\n诊断消息：无');
}

const payload = {
    _comment: '由 tools/gen-macro-goldens.mjs 生成；引擎源码在生成时从 SillyTavern 逐字抽取，仅重写了 import 路径',
    extractedFrom: 'SillyTavern/public/scripts/macros/engine/',
    registeredTestMacros: {
        up: '无名参数 → 转大写',
        echo: '无名参数 → 原样返回',
        twice: '无名参数 → 重复两次',
        pad: '可选整数参数（默认 1）→ 若干个点',
        opt: '两个可选参数（b 默认 "D"）→ "[a|b]"',
        noop: '返回空串',
        bang: '参数包在感叹号里',
    },
    envSummary: 'names.char/user/group, character.*, system.maxPrompt',
    cases: results,
};

fs.writeFileSync(OUT, JSON.stringify(payload, null, 2) + '\n');
console.log(`\n已写出 ${path.relative(ROOT, OUT)}（${fs.statSync(OUT).size} 字节）`);

fs.rmSync(HARNESS, { recursive: true, force: true });
console.log('临时模块树已清理');

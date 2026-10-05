#!/usr/bin/env node
/**
 * 用**真实的 Handlebars** 生成 story_string 渲染的对照表。
 *
 * 关键点：必须把 SillyTavern 自己注册的两个 helper 一起挂上
 * （`public/scripts/macros.js:18-25`），否则 `{{trim}}` / `{{unknown}}`
 * 的行为会和 ST 不一致 —— 那两个 helper 正是「未定义变量原样留给宏引擎」的实现。
 *
 * `helperMissing` 里 ST 调的是 `substituteParams('{{name}}')`；
 * 这里用一个恒等函数代替（把 `{{name}}` 原样返回），因为我们要测的是
 * **Handlebars 阶段**的产物，宏替换是下一阶段的事。
 *
 * 用法：node tools/gen-handlebars-goldens.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..');
const ST = path.join(ROOT, 'SillyTavern');
const OUT = path.join(ROOT, 'fixtures/handlebars-goldens.json');

const stRequire = createRequire(path.join(ST, 'package.json'));
const Handlebars = stRequire('handlebars');

// —— 与 ST 完全相同的两个 helper（macros.js:18-25） ——
Handlebars.registerHelper('trim', () => '{{trim}}');
Handlebars.registerHelper('helperMissing', function () {
    const options = arguments[arguments.length - 1];
    // ST 这里是 substituteParams(`{{${options.name}}}`)；用恒等函数隔离出 Handlebars 阶段
    return `{{${options.name}}}`;
});

// —— 参数表：与 script.js:4702-4718 的 storyStringParams 一致 ——
const params = {
    description: 'A wandering scholar.',
    personality: 'Curious, patient.',
    persona: 'The user is a traveler.',
    scenario: 'A rain-soaked library.',
    system: 'Stay in character.',
    char: 'Seraphina',
    user: 'Traveler',
    wiBefore: 'WI-BEFORE',
    wiAfter: 'WI-AFTER',
    loreBefore: 'WI-BEFORE',
    loreAfter: 'WI-AFTER',
    anchorBefore: 'ANCHOR-BEFORE',
    anchorAfter: 'ANCHOR-AFTER',
    mesExamples: '<START>\n{{user}}: hi',
    mesExamplesRaw: '<START>\n{{user}}: hi',
};

const templates = [
    // 基本变量
    '{{char}}', '{{user}}', 'plain text', '',
    '{{char}} and {{user}}',
    '{{#if description}}{{description}}{{/if}}',
    // ST 自带的默认 story_string（真实数据）
    "{{#if system}}{{system}}\n{{/if}}{{#if description}}{{description}}\n{{/if}}" +
        "{{#if personality}}{{char}}'s personality: {{personality}}\n{{/if}}" +
        "{{#if scenario}}Scenario: {{scenario}}\n{{/if}}{{#if persona}}{{persona}}\n{{/if}}",
    // Adventure 预设的 story_string（真实数据）
    '{{#if anchorBefore}}{{anchorBefore}}\n{{/if}}{{#if system}}{{system}}\n{{/if}}' +
        '{{#if wiBefore}}{{wiBefore}}\n{{/if}}{{#if description}}{{description}}\n{{/if}}' +
        '{{#if personality}}{{personality}}\n{{/if}}{{#if scenario}}{{scenario}}\n{{/if}}' +
        '{{#if wiAfter}}{{wiAfter}}\n{{/if}}{{#if persona}}{{persona}}\n{{/if}}' +
        '{{#if anchorAfter}}{{anchorAfter}}\n{{/if}}{{trim}}',
    // 桥接 helper
    '{{trim}}', 'x{{trim}}y',
    '{{unknownMacro}}', 'a{{unknownMacro}}b',
    '{{char}}{{unknownMacro}}{{persona}}',
    // else 分支
    '{{#if missing}}A{{else}}B{{/if}}',
    '{{#if char}}A{{else}}B{{/if}}',
    '{{#if emptyValue}}A{{else}}B{{/if}}',
    // unless
    '{{#unless missing}}A{{/unless}}',
    '{{#unless char}}A{{else}}B{{/unless}}',
    // 嵌套
    '{{#if char}}{{#if user}}both{{/if}}{{/if}}',
    '{{#if char}}outer-{{#if description}}inner{{/if}}{{/if}}',
    // 三花括号
    '{{{char}}}', '{{{char}}} vs {{char}}',
    // 块内的桥接
    '{{#if char}}{{trim}}{{/if}}',
    // 变量紧邻
    '{{char}}{{user}}', '{{char}}-{{user}}',
    // 字面量：Handlebars 把 true / false / 数字当成值，不是变量查找
    '{{#if true}}Y{{else}}N{{/if}}',
    '{{#if false}}Y{{else}}N{{/if}}',
    '{{#if 1}}Y{{else}}N{{/if}}',
    '{{#if 0}}Y{{else}}N{{/if}}',
    '{{true}}', '{{false}}', '{{123}}',
    // 未闭合（Handlebars 会抛错，这里只记录行为）
];

// emptyValue 用空串参与真值测试
const paramVariants = [
    { name: 'full', params: { ...params } },
    { name: 'emptyValue-empty', params: { ...params, emptyValue: '' } },
    { name: 'emptyValue-undefined', params: { ...params } },
    { name: 'minimal', params: { char: 'Seraphina', user: 'Traveler' } },
    { name: 'nochar', params: { ...params, char: undefined } },
];

const results = [];
for (const t of templates) {
    for (const v of paramVariants) {
        // 真值测试只关心 emptyValue 相关的模板，减少无关组合
        const needsEmpty = t.includes('emptyValue');
        if (needsEmpty && !v.name.startsWith('emptyValue')) continue;
        if (!needsEmpty && v.name.startsWith('emptyValue')) continue;

        let output = null;
        let error = null;
        try {
            output = Handlebars.compile(t, { noEscape: true })(v.params);
        } catch (e) {
            error = e.message.split('\n')[0];
        }
        results.push({ template: t, variant: v.name, output, error, params: v.params });
    }
}

const payload = {
    _comment: '由 tools/gen-handlebars-goldens.mjs 生成；使用 ST 的 node_modules 里的真实 Handlebars，并注册了 ST 的 trim / helperMissing helper',
    helperRegistration: 'public/scripts/macros.js:18-25',
    cases: results,
};

fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, JSON.stringify(payload, null, 2) + '\n');

const ok = results.filter((r) => r.error === null).length;
console.log(`生成 ${results.length} 条用例（${ok} 条成功渲染，${results.length - ok} 条抛错）`);
console.log(`已写出 ${path.relative(ROOT, OUT)}（${fs.statSync(OUT).size} 字节）`);

// 顺手展示几条关键结果
console.log('\n关键用例：');
for (const t of ['{{trim}}', '{{unknownMacro}}', '{{char}}']) {
    const r = results.find((x) => x.template === t && x.variant === 'full');
    if (r) console.log(`  ${JSON.stringify(t)} -> ${JSON.stringify(r.output)}`);
}

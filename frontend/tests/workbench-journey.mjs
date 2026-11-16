import assert from 'node:assert/strict';

// One visible workflow is shared by the DOM transport driver and the actual Chromium driver.
// The driver adapts UI input only. Every body, revision, permission and score comes from HTTP.
const escape = (value) => String(value).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const names = {
  source: '验收专用自编泛型资料',
  baseConcept: '验收泛型基础',
  childConcept: '验收泛型读写',
  baseChunk: '验收泛型基础知识卡',
  childChunk: '验收泛型读写知识卡',
  question: '验收短答：List<? extends Number> 的读取结果是什么类型？只写简单类型名。',
  variant: '验收变式：List<? extends Integer> 的读取结果是什么类型？只写简单类型名。',
  document: '验收关联作品 · 我的类型总结',
  suite: '验收私有检索样例',
};
const crc32 = (bytes) => {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit += 1) crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0);
  }
  return (crc ^ 0xffffffff) >>> 0;
};
export function docxFixture() {
  const files = [
    [
      '[Content_Types].xml',
      '<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>',
    ],
    [
      'word/document.xml',
      '<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>泛型读取遵循已声明的类型边界。此段为自动化测试自编材料。</w:t></w:r></w:p></w:body></w:document>',
    ],
  ];
  const locals = [],
    directory = [];
  let offset = 0;
  for (const [filename, value] of files) {
    const name = Buffer.from(filename),
      bytes = Buffer.from(value),
      crc = crc32(bytes),
      local = Buffer.alloc(30),
      entry = Buffer.alloc(46);
    local.writeUInt32LE(0x04034b50);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0x800, 6);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(bytes.length, 18);
    local.writeUInt32LE(bytes.length, 22);
    local.writeUInt16LE(name.length, 26);
    entry.writeUInt32LE(0x02014b50);
    entry.writeUInt16LE(20, 4);
    entry.writeUInt16LE(20, 6);
    entry.writeUInt16LE(0x800, 8);
    entry.writeUInt32LE(crc, 16);
    entry.writeUInt32LE(bytes.length, 20);
    entry.writeUInt32LE(bytes.length, 24);
    entry.writeUInt16LE(name.length, 28);
    entry.writeUInt32LE(offset, 42);
    locals.push(local, name, bytes);
    directory.push(entry, name);
    offset += local.length + name.length + bytes.length;
  }
  const central = Buffer.concat(directory),
    end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50);
  end.writeUInt16LE(files.length, 8);
  end.writeUInt16LE(files.length, 10);
  end.writeUInt32LE(central.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, central, end]);
}
export async function runWorkbenchJourney(d, check) {
  const author = process.env.TYPEFORGE_TEST_EDITOR_AUTHOR,
    reviewer = process.env.TYPEFORGE_TEST_EDITOR_REVIEWER,
    admin = process.env.TYPEFORGE_TEST_ADMIN_USERNAME,
    password = process.env.TYPEFORGE_TEST_EDITOR_PASSWORD;
  assert.ok(
    author && reviewer && admin && password,
    'Real pre-registered temporary editorial and admin accounts are required; no permission bypass fixture is accepted.',
  );
  const suffix = crypto.randomUUID().slice(0, 8),
    courseVersion = `verify-${suffix}`;
  let source,
    batch,
    baseConcept,
    childConcept,
    baseChunk,
    childChunk,
    question,
    variant,
    release,
    workspace,
    notebook,
    linkedArtifact,
    suite,
    firstRun,
    secondRun;
  const login = async (username, title) => {
    await d.fill('用户名', username);
    await d.fill('密码', password);
    await d.click('button', '进入工作台');
    await d.fill('工作空间名称', title);
    await d.click('button', '创建工作空间');
    await d.click('link', '知识与阅读');
    await d.heading('泛型与类型安全');
  };
  const logout = async () => {
    await d.click('button', '退出登录');
    await d.heading('回到你的工作台');
  };
  const content = async (tab) => {
    await d.click('link', '内容工作台');
    await d.click('tab', tab);
  };
  const selectedWorkspace = async () => d.readJson(`/workspaces/${await d.value('当前工作空间')}`);
  const saveKnowledge = async (conceptId, title, body, captureId) => {
    await d.select('所属概念', conceptId);
    await d.fill('知识卡标题', title);
    await d.fill('知识正文', body);
    await d.select('引用来源 1', source.id);
    await d.select('固定采集记录 1', captureId);
    await d.fill('引用章节 1', '自动化自编材料 · 正文');
    await d.click('button', '保存知识草稿');
    await d.text('知识草稿已保存，请由另一位编辑审核。');
    return (await d.readJson('/admin/chunks?limit=100')).find((item) => item.title === title);
  };
  await check(
    'editor signs in, parses real Markdown and DOCX, exposes a broken file and atomically retains selected captures',
    async () => {
      await login(author, '编辑作者验收空间');
      assert.ok((await d.readJson('/auth/session')).roles.includes('EDITOR'));
      await content('资料来源');
      await d.summary('登记新来源', '#content-sources');
      await d.fill('来源名称', names.source);
      await d.fill('来源网址', 'https://example.com/typeforge-verification');
      await d.fill('资料版本', '自动化自编 v1');
      await d.fill(
        '使用范围与许可说明',
        '仅用于隔离测试的自编内容，不代表实际抓取该网址或独立教学认证。',
      );
      await d.click('button', '登记来源');
      await d.text('资料来源已登记，可以选择来源后继续录入原稿。');
      source = (await d.readJson('/admin/sources?limit=100')).find(
        (item) => item.title === names.source,
      );
      assert.ok(source);
      await d.click('tab', '批量加工');
      await d.select('这批文件的资料来源', source.id);
      await d.fill('本批处理目的', '读取实际字节并核对原文与清洗稿；测试材料不进入生产课程。');
      await d.upload('选择 TXT、Markdown 或 DOCX 文件', [
        {
          name: '泛型原稿.md',
          mimeType: 'text/markdown',
          buffer: Buffer.from(
            '# 泛型基础\n泛型具有不变性。Number 与 Integer 不能随意替换容器参数。\n',
          ),
        },
        {
          name: '读取边界.docx',
          mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
          buffer: docxFixture(),
        },
        {
          name: '损坏材料.docx',
          mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
          buffer: Buffer.from('This is deliberately not a ZIP document.'),
        },
      ]);
      await d.click('button', '上传并预览');
      await d.text('服务端已生成实际解析预览，请检查每个文件的结果。');
      batch = await d.readJson(
        `/admin/import-batches/${(await d.readJson('/admin/import-batches?limit=20'))[0].id}`,
      );
      assert.equal(batch.items.filter((item) => item.status === 'READY').length, 2);
      assert.equal(batch.items.filter((item) => item.status === 'FAILED').length, 1);
      await d.disabled('checkbox', '保存 损坏材料.docx');
      await d.fill(
        '清洗后的 Markdown',
        '# 泛型基础\n泛型具有不变性。读取上界得到 Number，具体类型参数必须保持一致。',
      );
      await d.fill('这份清洗稿的处理说明', '保留泛型关键约束并清理标题空行。');
      await d.capture('v06-batch-preview-desktop.png');
      await d.click('button', '保存所选采集记录');
      await d.click('button', '确认保存采集记录', 'dialog[open]');
      await d.text('所选采集记录已由服务端完整保存。');
      batch = await d.readJson(`/admin/import-batches/${batch.id}`);
      assert.equal(batch.status, 'COMMITTED');
      assert.equal(batch.items.filter((item) => item.captureId).length, 2);
      assert.ok(
        batch.items.every((item) => item.originalMarkdown === '' && item.cleanedMarkdown === ''),
      );
      await d.absentLabel('清洗后的 Markdown');
      await d.captureResponsive('v06-batch-committed', [390]);
    },
  );
  await check(
    'editor constructs real prerequisite concepts and compares immutable knowledge revisions',
    async () => {
      await d.click('tab', '知识版本');
      await d.summary('新增概念', '#content-knowledge');
      await d.fill('概念代码', `BASE${suffix.slice(0, 6).toUpperCase()}`);
      await d.fill('概念名称', names.baseConcept);
      await d.click('button', '创建概念');
      await d.text('新概念已登记。');
      baseConcept = (await d.readJson('/admin/concepts?limit=100')).find(
        (item) => item.title === names.baseConcept,
      );
      assert.ok(baseConcept);
      baseChunk = await saveKnowledge(
        baseConcept.id,
        names.baseChunk,
        '泛型具有不变性；类型参数保留固定约束。验收材料只检验真实课程发布链。',
        batch.items.find((item) => item.fileName.endsWith('.md')).captureId,
      );
      await d.clickRow('#content-knowledge .content-row', names.baseChunk, '修订为新版本');
      await d.fill(
        '知识正文',
        '泛型具有不变性；类型参数保留固定约束。第二版补充：读取上界得到边界类型，不能任意缩窄。',
      );
      await d.click('button', '保存知识草稿');
      await d.text('知识草稿已保存，请由另一位编辑审核。');
      baseChunk = (await d.readJson('/admin/chunks?limit=100'))
        .filter((item) => item.title === names.baseChunk)
        .sort((a, b) => b.revision - a.revision)[0];
      assert.equal(baseChunk.revision, 2);
      await d.fill('概念代码', `USE${suffix.slice(0, 6).toUpperCase()}`);
      await d.fill('概念名称', names.childConcept);
      await d.check(names.baseConcept, '#content-knowledge .editor-disclosure');
      await d.click('button', '创建概念');
      await d.text('新概念已登记。');
      childConcept = (await d.readJson('/admin/concepts?limit=100')).find(
        (item) => item.title === names.childConcept,
      );
      assert.deepEqual(childConcept.prerequisiteIds, [baseConcept.id]);
      childChunk = await saveKnowledge(
        childConcept.id,
        names.childChunk,
        '泛型读取边界：List<? extends Number> 读取为 Number；List<? extends Integer> 读取为 Integer。这里只要求有限短答案。',
        batch.items.find((item) => item.fileName === '读取边界.docx').captureId,
      );
      const diff = await d.readJson(
        `/admin/content-diffs?kind=CHUNK&id=${baseChunk.id}&fromRevision=1&toRevision=2`,
      );
      assert.ok(diff.changes.some((item) => item.path === '/body'));
      assert.notEqual(diff.fromReviewHash, diff.toReviewHash);
    },
  );
  await check(
    'editor authors finite TEXT rules and derives an actual same-family variant while self-review stays disabled',
    async () => {
      await d.click('tab', '练习设计');
      await d.select('题型', 'READING');
      await d.select('作答与核验方式', 'TEXT');
      await d.fill('题目内容', names.question);
      await d.check(names.childConcept, '#content-exercises');
      await d.check(new RegExp(`${escape(names.baseChunk)}.*版本 2`), '#content-exercises');
      await d.check(new RegExp(`${escape(names.childChunk)}.*版本 1`), '#content-exercises');
      await d.fill(
        '向学习者说明核验范围',
        '回答完整类型名称；保留词序和标点。只核对有限答案，不作开放语义评分。',
      );
      await d.check('区分大小写', '#content-exercises');
      await d.fill('允许答案 1', 'Number');
      await d.fill('拒绝反例 1', 'Integer');
      await d.fill(
        '讲解与判分依据',
        '读取值的静态类型由泛型上界决定；实际规则进行规范化后的整段精确匹配。',
      );
      await d.fill(
        '递进提示',
        '先查看 extends 后面的类型。\n---\n读取结果需要能接收上界范围内的值。\n---\n请用题面声明的上界类型命名读取结果。',
      );
      await d.click('button', '保存练习草稿');
      await d.text('练习草稿已保存，标准答案只对编辑与独立审核者可见。');
      question = (await d.readJson('/admin/exercises?limit=100')).find(
        (item) => item.title === names.question,
      );
      assert.ok(question);
      await d.clickRow('#content-exercises .content-row', names.question, '派生同题族新练习');
      await d.fill('题目内容', names.variant);
      await d.fill('允许答案 1', 'Integer');
      await d.fill('拒绝反例 1', 'Number');
      await d.click('button', '保存练习草稿');
      await d.text('练习草稿已保存，标准答案只对编辑与独立审核者可见。');
      variant = (await d.readJson('/admin/exercises?limit=100')).find(
        (item) => item.title === names.variant,
      );
      const first = await d.readJson(`/admin/exercises/${question.id}/revisions/1`),
        second = await d.readJson(`/admin/exercises/${variant.id}/revisions/1`);
      assert.equal(first.familyId, second.familyId);
      assert.notEqual(first.id, second.id);
      await d.click('tab', '独立审核');
      await d.click('button', new RegExp(`${escape(names.question)}.*版本 1`), '#content-review');
      await d.fill('审核意见', '作者只能检查草稿，不能批准自己的版本。');
      await d.disabled('button', '通过审核', '#content-review');
      await d.captureResponsive('v06-editor-self-review', [390]);
      await logout();
    },
  );
  await check(
    'a distinct real editor reviews source captures and revision differences, then publishes the frozen course',
    async () => {
      await login(reviewer, '独立审核验收空间');
      await content('独立审核');
      for (const item of [baseChunk, childChunk, question, variant]) {
        await d.click(
          'button',
          new RegExp(`${escape(item.title)}.*版本 ${item.revision}`),
          '#content-review',
        );
        if (item.id === baseChunk.id) {
          await d.summary('比较已保存修订', '#content-review');
          await d.click('button', '读取实际差异', '#content-review');
          await d.text('第二版补充', '#content-review');
          await d.summary(new RegExp(escape(names.source)), '#content-review .review-source');
          await d.text('实际采集原稿', '#content-review');
          await d.capture('v06-editor-review-diff-desktop.png');
          await d.captureResponsive('v06-editor-review-diff', [320, 390]);
          await d.captureResponsive(
            'v06-editor-review-diff-dark',
            [1440, 390, 320],
            undefined,
            'dark',
          );
        }
        await d.fill(
          '审核意见',
          '第二个测试账户核对实际采集、固定知识、边界规则与哈希。此为自动化权限验收，不冒充人工教学审核。',
        );
        await d.click('button', '通过审核', '#content-review');
        await d.text('独立审核已记录，此版本现在可以加入发布清单。');
      }
      await d.click('tab', '课程发布');
      await d.fill('课程版本号', courseVersion);
      for (const item of [baseChunk, childChunk, question, variant])
        await d.check(`${item.title} · ${item.revision}`, '#content-releases');
      await d.click('button', '冻结发布清单');
      await d.text('发布清单已冻结，请核对后发布。');
      await d.click('button', '发布该课程版本');
      await d.text('课程版本已激活，历史学习记录保持可追溯。');
      release = (await d.readJson('/admin/releases?limit=100')).find(
        (item) => item.version === courseVersion,
      );
      assert.equal(release.status, 'PUBLISHED');
      assert.equal(release.active, true);
      assert.equal(release.indexStatus, 'READY');
      await d.capture('v06-editor-publication-desktop.png');
      await logout();
    },
  );
  await check(
    'learner sees the exact published prerequisite graph and server-graded TEXT answers without private rubric leakage',
    async () => {
      await d.click('button', '注册');
      await d.fill('用户名', `learning_${suffix}`);
      await d.fill('密码', password);
      await d.click('button', '注册并进入');
      await d.fill('工作空间名称', '全链学习验收空间');
      await d.click('button', '创建工作空间');
      await d.click('link', '知识与阅读');
      await d.heading('泛型与类型安全');
      workspace = await selectedWorkspace();
      assert.equal(workspace.releaseId, release.id);
      const published = await d.readJson(
        `/exercises/${question.id}?releaseId=${release.id}&revision=1`,
      );
      assert.equal(published.answerKind, 'TEXT');
      assert.equal(published.textPolicy.scope.length > 0, true);
      assert.equal('answerSpec' in published, false);
      assert.equal(JSON.stringify(published).includes('acceptedAnswers'), false);
      await d.click('button', '概念路径');
      await d.click(
        'button',
        new RegExp(`${escape(childConcept.code)}.*${escape(names.childConcept)}`),
        '.concept-path-board',
      );
      await d.text(names.baseConcept, '#concept-path-detail');
      await d.capture('v06-concept-map-desktop.png');
      await d.captureResponsive('v06-concept-map', [320, 390]);
      await d.click('button', '层次列表');
      await d.click('button', '阅读这一概念', '#concept-path-detail');
      await d.click('button', '记录已读');
      await d.text('服务端已记录本次阅读。');
      await d.click('link', '练习与反馈');
      await d.click('button', new RegExp(escape(names.question)), '.practice-list');
      await d.fill('写下你的文本答案', 'Integer');
      await d.click('button', '提交文本作答');
      await d.heading('服务端反馈');
      const wrong = (await d.readJson(`/workspaces/${workspace.id}/attempts`)).find(
        (item) => item.exerciseId === question.id,
      );
      assert.equal(wrong.status, 'GRADED');
      assert.equal(Number(wrong.score), 0);
      assert.equal(wrong.answer.kind, 'TEXT');
      await d.captureResponsive('v06-text-feedback', [390]);
      await d.click('button', '加入可复练错题集');
      await d.heading('重新检查你的判断');
      notebook = (await d.readJson(`/workspaces/${workspace.id}/artifacts`)).find(
        (item) => item.kind === 'NOTEBOOK',
      );
      assert.ok(notebook);
      await d.fill('重新写下文本答案', '  Ｎｕｍｂｅｒ  ', '.notebook-error_context');
      await d.click('button', '提交复练', '.notebook-error_context');
      await d.heading(/复练反馈/, '.notebook-error_context');
      const correct = (await d.readJson(`/workspaces/${workspace.id}/attempts`)).find(
        (item) => item.answer.value === '  Ｎｕｍｂｅｒ  ',
      );
      assert.equal(Number(correct.score), 100);
      assert.equal(correct.hintLevel, 4);
    },
  );
  const append = async (kindTitle, configure = async () => {}) => {
    await d.click('button', '添加学习活动');
    await d.click('button', new RegExp(`^${escape(kindTitle)}`), 'dialog[open]');
    await configure();
    await d.click('button', '保存并添加活动', 'dialog[open]');
    await d.heading(
      kindTitle,
      `.notebook-${{ 类型关系推演: 'type_flow', 同题族对照: 'counterfactual', 逐层引导: 'expert_help', 复习与作品关联: 'review_links' }[kindTitle]}`,
    );
  };
  await check(
    'notebook persists real type evaluation, admitted same-family variants and deterministic guidance as separate source-backed activities',
    async () => {
      await append('类型关系推演');
      await d.select('推演目标类型', 'List<? extends Number>', '.notebook-type_flow');
      await d.click('button', '检验选定的类型关系', '.notebook-type_flow');
      await d.heading('已确认的求值结果', '.notebook-type_flow');
      let currentNotebook = await d.readJson(`/artifacts/${notebook.id}`);
      let state = await d.readJson(
        `/notebooks/${notebook.id}/state?artifactRevision=${currentNotebook.currentRevision}`,
      );
      assert.equal(
        state.blocks.find((item) => item.lastResult?.labSession)?.lastResult.labSession.verdict,
        'ALLOWED',
      );
      await d.select('推演源类型', 'List<? super Integer>', '.notebook-type_flow');
      await d.select('推演目标类型', 'Integer', '.notebook-type_flow');
      await d.select('推演操作', 'WRITE', '.notebook-type_flow');
      await d.click('button', '检验选定的类型关系', '.notebook-type_flow');
      await d.eventually(async () => {
        state = await d.readJson(
          `/notebooks/${notebook.id}/state?artifactRevision=${currentNotebook.currentRevision}`,
        );
        const item = state.blocks.find((item) => item.typeInput?.operation === 'WRITE');
        return item?.lastResult?.labSession?.verdict === 'ALLOWED';
      }, 'Actual lower-bound write must be allowed with container in sourceType');
      await d.click('button', '查看关联证据', '.notebook-type_flow');
      await d.text(names.childChunk, '.notebook-type_flow .notebook-evidence');
      await d.captureResponsive('v06-notebook-type-flow', [390], '.notebook-type_flow');
      await append('同题族对照', async () =>
        d.check(new RegExp(escape(names.variant)), 'dialog[open]'),
      );
      await d.select('选择这个区块允许的对照题', `${variant.id}:1`, '.notebook-counterfactual');
      await d.click('button', '确认载入这道对照题', '.notebook-counterfactual');
      await d.fill('重新写下文本答案', 'Integer', '.notebook-counterfactual');
      await d.click('button', '提交复练', '.notebook-counterfactual');
      await d.heading(/复练反馈/, '.notebook-counterfactual');
      const attempt = (await d.readJson(`/workspaces/${workspace.id}/attempts`)).find(
        (item) => item.exerciseId === variant.id,
      );
      assert.equal(Number(attempt.score), 100);
      assert.equal(attempt.hintLevel, 4);
      await append('逐层引导');
      await d.click('button', '查看第 1 级提示', '.notebook-expert_help');
      await d.text('先查看 extends 后面的类型。', '.notebook-expert_help');
      await d.captureResponsive(
        'v06-notebook-counterfactual',
        [320, 390],
        '.notebook-counterfactual',
      );
      await d.click('link', '知识与阅读');
      await d.click('link', '作品与错题集');
      await d.clickArtifact(notebook.id);
      currentNotebook = await d.readJson(`/artifacts/${notebook.id}`);
      state = await d.readJson(
        `/notebooks/${notebook.id}/state?artifactRevision=${currentNotebook.currentRevision}`,
      );
      // Adding a source revision resets its interaction states by contract; then refresh must retain newly confirmed guidance.
      assert.equal(state.blocks.filter((item) => item.hintLevel === 1).length, 1);
      await d.enabled('button', '查看第 2 级提示', '.notebook-expert_help');
    },
  );
  await check(
    'notebook links only explicitly admitted current private artifact revisions and opens the confirmed source',
    async () => {
      await d.click('button', '新建作品');
      await d.fill('作品名称', names.document);
      await d.select('源稿类型', 'DOCUMENT');
      await d.click('button', '创建源稿');
      await d.fill('段落 1 内容', '这是我从泛型边界练习整理的私人总结。');
      await d.click('button', '保存版本');
      await d.text('服务端已保存版本 1。');
      linkedArtifact = (await d.readJson(`/workspaces/${workspace.id}/artifacts`)).find(
        (item) => item.title === names.document,
      );
      assert.equal(linkedArtifact.currentRevision, 1);
      await d.clickArtifact(notebook.id);
      await append('复习与作品关联', async () =>
        d.check(new RegExp(escape(names.document)), 'dialog[open]'),
      );
      await d.captureResponsive('v06-notebook-review-links', [390], '.notebook-review_links');
      await d.click('button', '核验并打开作品', '.notebook-review_links');
      await d.heading(names.document);
      const currentNotebook = await d.readJson(`/artifacts/${notebook.id}`),
        notebookVersion = await d.readJson(
          `/artifacts/${notebook.id}/revisions?revision=${currentNotebook.currentRevision}`,
        );
      assert.ok(
        notebookVersion.inputRefs.some(
          (ref) => ref.kind === 'ARTIFACT' && ref.id === linkedArtifact.id && ref.revision === 1,
        ),
      );
    },
  );
  await check(
    'private evaluation runs measure real Recall and retain downloadable reports for fixed annotations and K values',
    async () => {
      await d.click('link', '检索评测');
      await d.fill('评测集标题', names.suite);
      await d.fill(
        '标注目的与范围',
        '个人标注；核对两条已发布知识的实际检索，不冒充独立教学质量分。',
      );
      await d.fill('实际检索查询', '泛型');
      await d.check(new RegExp(escape(names.baseChunk)), '.expected-evidence');
      await d.check(new RegExp(escape(names.childChunk)), '.expected-evidence');
      await d.click('button', '添加样例');
      await d.fill('实际检索查询', `qzzvvx${suffix}unmatched`);
      await d.select('期望结果', 'NO_RESULTS');
      await d.fill('这条标注的理由', '该唯一词项未出现在当前固定课程。');
      await d.click('button', '保存评测集');
      await d.heading(names.suite);
      suite = (
        await d.readJson(`/workspaces/${workspace.id}/evaluation-suites?limit=30`)
      ).items.find((item) => item.title === names.suite);
      assert.equal(suite.caseCount, 2);
      await d.captureResponsive('v06-evaluation-cases', [320, 390]);
      await d.click('button', '运行与对照');
      await d.select('检索前 K 条', '1');
      await d.click('button', '开始检索评测');
      await d.link('下载真实 JSON 报告');
      firstRun = (await d.readJson(`/evaluation-suites/${suite.id}/runs?limit=30`)).items[0];
      const firstReport = await d.report('下载真实 JSON 报告');
      assert.equal(firstReport.runId, firstRun.id);
      assert.equal(firstReport.suiteRevision, 1);
      firstRun = await d.readJson(`/evaluation-runs/${firstRun.id}`);
      assert.equal(firstRun.status, 'COMPLETED');
      assert.equal(firstRun.metrics.meanRecallAtK, 0.5);
      assert.equal(firstRun.metrics.noResultAccuracy, 1);
      assert.equal(firstRun.results.length, 2);
      await d.select('检索前 K 条', '2');
      await d.click('button', '开始检索评测');
      await d.eventually(async () => {
        const items = (await d.readJson(`/evaluation-suites/${suite.id}/runs?limit=30`)).items;
        secondRun = items.find((item) => item.id !== firstRun.id);
        return secondRun?.status === 'COMPLETED';
      }, 'Second real evaluation did not complete');
      await d.select('查看运行记录', secondRun.id);
      await d.link('下载真实 JSON 报告');
      secondRun = await d.readJson(`/evaluation-runs/${secondRun.id}`);
      assert.equal(secondRun.metrics.meanRecallAtK, 1);
      assert.equal(secondRun.metrics.passedCases, 2);
      assert.equal(secondRun.suiteHash, firstRun.suiteHash);
      await d.select('与另一份已完成运行对照', firstRun.id);
      await d.text('两次使用相同标注与语料');
      await d.capture('v06-evaluation-results-desktop.png');
      await d.captureResponsive('v06-evaluation-results', [320, 390]);
      const report = await d.report('下载真实 JSON 报告');
      assert.equal(report.runId, secondRun.id);
      assert.equal(report.suiteId, suite.id);
      assert.equal(report.metrics.meanRecallAtK, 1);
      assert.equal(report.results.length, 2);
    },
  );
  await check(
    'evaluation keeps edited cases while a separately saved server revision is deliberately reconciled',
    async () => {
      await d.click('button', '样例与版本');
      await d.fill('标注目的与范围', '本页面仍保留的并发编辑说明');
      const saved = await d.readJson(`/evaluation-suites/${suite.id}`);
      await d.mutate(`/evaluation-suites/${suite.id}/revisions`, 'POST', {
        expectedRevision: saved.revision,
        title: saved.title,
        description: '另一个客户端完成的独立修改',
        cases: saved.cases.map((item) => ({
          key: item.key,
          query: item.query,
          conceptId: item.conceptId,
          expectation: item.expectation,
          expectedChunkIds: item.expectedChunks.map((ref) => ref.chunkId),
          note: item.note,
        })),
      });
      await d.click('button', '刷新检索评测');
      await d.text('服务端已有版本 2，你的草稿仍基于版本 1');
      assert.equal(await d.value('标注目的与范围'), '本页面仍保留的并发编辑说明');
      await d.disabled('button', '保存评测集');
      await d.click('button', '保留草稿，更新保存基线');
      await d.click('button', '保留草稿并更新基线', 'dialog[open]');
      await d.click('button', '保存评测集');
      await d.text('服务端已保存评测集版本 3。');
      const latest = await d.readJson(`/evaluation-suites/${suite.id}`);
      assert.equal(latest.revision, 3);
      assert.equal(latest.description, '本页面仍保留的并发编辑说明');
      assert.equal(latest.cases.length, 2);
      const original = await d.readJson(`/evaluation-runs/${firstRun.id}`);
      assert.equal(original.suiteRevision, 1);
      assert.equal(original.suiteHash, firstRun.suiteHash);
      await logout();
    },
  );
  await check(
    'a real administrator reads aggregate evaluation and import capacity without exposing personal payloads',
    async () => {
      await login(admin, '运行观测验收空间');
      assert.ok((await d.readJson('/auth/session')).roles.includes('ADMIN'));
      await d.click('link', '运行状态');
      await d.heading('运行状态');
      const status = await d.readJson('/admin/operations');
      assert.ok(status.requestMetrics.completed > 0);
      assert.ok(status.queues.storedEvaluationBytes > 0);
      assert.ok(status.queues.pendingEvaluations >= 0);
      assert.ok(status.queues.importPreviewBytes >= 0);
      assert.equal(JSON.stringify(status).includes(names.question), false);
      await d.capture('v06-operations-desktop.png');
      await d.captureResponsive('v06-operations', [320, 390]);
      await logout();
    },
  );
  return {
    releaseId: release.id,
    suiteId: suite.id,
    evaluationRunIds: [firstRun.id, secondRun.id],
    notebookId: notebook.id,
  };
}

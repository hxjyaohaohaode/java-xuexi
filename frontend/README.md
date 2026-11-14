# TypeForge 学习工作台

本目录已经包含可构建的 React + TypeScript 客户端。页面使用真实会话、CSRF 和业务 API，学习记录、作答、错题块、作品版本、导师请求与后台导出均由服务端确认。当前实现范围与最终验收状态应结合仓库首页、`contracts/openapi.json` 和实际测试报告阅读。

## 运行

推荐 Node.js 24；最低要求为 Node.js 22.12。依赖已精确锁定，使用 `npm ci` 安装。

```bash
cd frontend
npm ci
npm run dev
```

开发服务器默认为 `http://127.0.0.1:5173`。Vite 将 `/api` 代理至 `http://127.0.0.1:8080`，会话与 CSRF 使用同源请求。另一个终端在仓库根目录启动后端：

```bash
mvn -f backend/pom.xml spring-boot:run -Dspring-boot.run.profiles=demo
```

`demo` 提供原创、代码复核的演示课程内容；它不代表正式双人内容审核。自行注册账号后创建工作空间。没有内置公开密码。

```bash
cd frontend
npm run check
npm run build
```

`check` 执行 TypeScript 检查与 Vitest 测试，`build` 生成 `dist/`。后端构建将该目录打包为静态资源；采用 `#learn`、`#practice`、`#studio/<id>` 等 hash 路由，不需要将未知 API 路径回退到 HTML。打包顺序是前端构建后再执行 Maven，具体 CI 与发布命令以仓库根目录配置为准。

## 已实现的真实路径

| 页面 | 实际操作与数据关系 |
|---|---|
| 知识与阅读 | 发布课程目录、前置概念、来源/版本/hash、真实 BM25 检索、显式记录已读、进入关联练习。无匹配证据时显示空结果。 |
| 练习与反馈 | 公开题目与选项、服务端选择题判分、误区与反馈、持久化近期作答、从错误或受助作答构建错题源稿。 |
| 进度与复习 | 独立样本与加权证据、服务端复习安排、移除安排；阅读、预测或重复刷题不会作为新的独立掌握证据。 |
| 类型实验 | 赋值与通配符读写的有限规则求值、真实实验历史、明确的 ALLOWED / DENIED / UNSUPPORTED；不是 Java 编译器。 |
| 作品与错题集 | 文档段落/代码/引用编辑，幻灯片文字/代码/备注编辑，安全预览，插入知识卡并保留来源，版本 CAS 冲突比较与显式重设基线。 |
| 错题块 | 记录本人预测、读取关联证据、将复练答案分派给真实练习服务，显示新 attempt 的反馈。来源失效后禁止复练。 |
| 导出 | 固定已保存版本，后台任务轮询，只有成功且来源有效的结果提供真实下载入口。支持当前已接通的 Markdown、HTML、SVG。 |
| 导师对话 | 持久化会话；本地证据整理与管理员配置的模型模式分开；固定快照、实际状态、取消、来源失效时隐藏旧回答；完成结果转带来源的学习文档。 |
| 证据工作流 | 创建真实目标与快照，执行本地证据工作流，显示持久化步骤，暂停/继续/取消，以服务端 planRevision 与 runEpoch 提交命令。 |
| 笔记、记忆与目标 | 保存明确陈述的记忆、撤回记忆、创建/编辑/归档目标。依赖旧来源的任务和成果会按服务端状态失效。 |
| 偏好与能力 | 带 quoted `If-Match` 的显示偏好更新、主题/减少动效/代码字号、空间名称与指导模式、真实 capabilities 清单。 |

模型模式未配置时禁用，页面不生成模拟回答。媒体生成、任意用户代码执行、PPTX/DOCX/PDF 二进制生成没有实现前端伪入口。正式内容编辑发布的浏览器控制台尚未实现；对应后端操作需按当前 OpenAPI 与管理员说明执行。

## 状态与安全边界

- 会话凭据由 HttpOnly cookie 承载。CSRF 从服务端取得，登录轮换会话后重新获取；密码不进入浏览器存储。
- 工作空间组件按 ID 重建；读取使用 `AbortController`，异步提交用 generation 校验，旧响应不能写入新工作空间界面。
- 工作空间事件按 `after` 游标轮询；重放去重、发现序号缺口时不推进游标。重新挂载从零分批重放，服务端 GET 仍是资源事实来源。当前没有 SSE。
- 幂等写操作将输入摘要与请求键保留到服务端确认，网络结果不明时重试复用原键。导师和证据工作流的中间快照也保留，防止重试创建另一项任务。
- 文档与幻灯片草稿按账号、工作空间和作品保存于本标签页存储。后台事件不覆盖脏稿；遇到新版本必须比较后明确选择。当前没有自动合并或“另存为副本”流程。
- 预览使用固定 React 元素，来源文本只作为文本。没有 `dangerouslySetInnerHTML`、`eval`、任意脚本、模型选择组件导入或任意 HTML 执行。
- 导出只有 `SUCCEEDED`、`sourceState=CURRENT` 且下载路径属于当前导出任务时显示下载。失败、失效、被替代或损坏的源不会被显示为可下载。
- 作答、暂停、取消、记忆撤回、保存和偏好设置都等待服务端确认；HTTP 202 表示任务已接受，不表示内容已完成或已审核。

详细状态边界见 [state-contract.json](state-contract.json)。OpenAPI 是网络字段的权威来源；本目录的 TypeScript 类型是针对页面实际消费字段的手工映射，不宣称自动生成。

## 验证分层

单元测试覆盖 CSRF 轮换、网络结果不明时复用幂等键、旧 workspace 响应隔离、迟到的旧会话 401/CSRF 隔离、事件去重与缺口、草稿冲突、中文输入法 Enter、可执行文本的安全预览。

真实 HTTP 的 DOM 联调（不是图形浏览器）在启动真实 demo jar 后，挂载实际 React 页面并执行注册、错答、错题复练、文档保存与真实导出、并发写冲突、导师转文档、撤回来源、空间隔离和退出清理。响应、判分、来源与文件字节均来自后端；仅用 cookie 传输适配器连接 Node fetch。

```bash
# 仓库根目录；先构建完整 jar
python3 scripts/browser_runtime.py \
  --jar backend/target/typeforge-backend-0.4.0-SNAPSHOT.jar --driver dom
```

真实浏览器测试使用 Playwright 自带 Chromium，默认不选择系统 Chromium：

```bash
cd frontend
npx playwright install --with-deps chromium
cd ..
python3 scripts/browser_runtime.py \
  --jar backend/target/typeforge-backend-0.4.0-SNAPSHOT.jar --driver browser
```

如已有服务，可设置 `TYPEFORGE_BASE_URL` 后执行 `npm run test:e2e` 或 `npm run test:live`。`CHROMIUM_EXECUTABLE_PATH` 只用于明确选择已有的兼容 Chromium。`browser_runtime.py` 使用隔离 H2、随机本地端口、就绪检查、有界超时和退出清理；失败保留非零退出码与日志，不转换为跳过。

报告输出到 `frontend/test-results/`：

- `browser-report.json`：真实浏览器步骤、页面异常与控制台异常；成功执行时产生桌面阅读、移动错题集与文档导出三张截图。
- `live-dom-report.json`：真实 HTTP 的 DOM 联调步骤与限制。
- `browser-runtime-report.json` / `dom-runtime-report.json` 与对应 server log：真实 jar 的启动和清理结果。

本地受管环境的 Chromium 曾在打开页面前因 `socket() failed: Operation not permitted` 被阻止；Browser 插件也没有可用的 originating connection。**这不是浏览器通过记录。** 图形布局、真实移动视口、原生下载和浏览器控制台以实际 CI 浏览器报告为准。DOM 验证与单元测试不能替代这项验收。

## 视觉与参考真实性

界面采用“纸面阅读＋代码实验”的方向：中性浅色表面、青绿色动作、安静导航、可读的规则与真实代码。暗色主题、键盘焦点、44px 控件目标、原生表单、减少动效与 IME 提交保护已写入实现，完整的视觉与辅助技术验收需以真实浏览器检查为准。

`reference-ledger.csv` 保留全部 209 项的研究与取舍记录。当前界面为业务导向的原创实现，**没有把 209 项演示全部运行、高保真复刻或授权审核的说法**；未复制 React Bits 源码，也没有安装整个动效库。具体落点见 [交互实现与参考边界](interaction-implementation.md)。

设计检查采用 unslop 方法（Damir Sokolovsky，@heydamir，CC BY 4.0），用于方向、间距、颜色、文字、布局与动效顺序审查；没有复制该技能的图像或组件资产。图标来自锁定版本的 Lucide React，依赖许可保留在各 npm 包中。

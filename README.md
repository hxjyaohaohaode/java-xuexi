# 泛型工坊 TypeForge · 联动学习工作台

围绕 **Java 泛型与类型安全**，把知识、实验、练习、学习证据、导师、作品和复练放在同一个有版本、有来源的学习工作空间里。

**当前版本 v0.4 已有真实 React 页面和持久 Java 后端。** 你可以注册自己的账户、创建固定课程版本的工作空间、阅读与检索、做题、进行有限类型实验、把真实错题变成可复练的交互源稿、保存文档和幻灯片、导出真实文件，并修改目标或撤回记忆。导师提供明确标识的证据整理模式；配置服务器模型后可以调用实际 HTTP 模型。内容工作台 API 提供来源登记、手工采集、草稿版本、独立编辑审核、发布及历史版本激活。

本版交付不等于整个终局产品完成。**自主多专家规划、不可信 Java 编译沙箱、图像/音频/视频生产、PPTX/DOCX/PDF 二进制导出、正式模型质量评测仍待开发。** 这些能力没有假成功实现。`businessReady=false` 保留完整产品验收边界；已实现功能独立可用。

## 同学拉取后先看这里

| 你想知道什么 | 唯一阅读入口 |
|---|---|
| 每个模块做了什么、没做什么、下一步改哪里 | [模块接手指南](docs/02-modules.md) |
| 哪些接口能调用、完整请求和响应是什么 | [当前 API 索引](contracts/README.md) · [OpenAPI](contracts/openapi.json) |
| 如何启动、配置模型和编辑角色 | [后端运行说明](backend/README.md) · [前端运行说明](frontend/README.md) |
| 数据表、字段、锁序、发布与撤回 | [当前字段字典](database/data-dictionary.md) · [数据一致性](docs/03-data.md) |
| 什么实际验证过、还有哪些限制 | [本轮验收记录](evidence/runtime-review.md) |
| 两人如何继续做及课程最终交付 | [团队计划](docs/09-team-plan.md) · [课程交付](docs/16-course-delivery.md) |
| 为什么这样设计、参考了什么 | [总体架构](docs/01-architecture.md) · [研究依据](docs/14-research-decisions.md) |

`contracts/modules.json` 的 `partial` 表示已有实际子能力、完整模块还有剩余范围；`planned` 表示当前没有可调用的业务实现。逐操作 `x-status` 与 Spring 路由核验一致。运行 `/api/v1/capabilities` 进一步反映当前模型配置与打包时是否包含前端。课程验收矩阵是最终成绩材料的门禁，与代码测试是否通过是两个不同记录。

## 五条已经连接的数据链

1. **内容到学习**：来源 → 手工原稿/清洗登记 → 知识与题目版本 → 作者之外的真实编辑审核 → 发布清单 → 知识阅读/BM25/客观判分。新发布不改旧工作空间的 release。
2. **作答到复练**：服务端判分 → 顺序事件/outbox → 独立题目家族证据与复习 → 错题源稿 → 当前块版本动作 → 同一个判分服务。重复题目家族不会刷高独立掌握样本。
3. **上下文到作品**：目标/记忆/本人作答 → 固定快照 → 证据导师或已配置模型 → 明确标识的导师回答 → 文档草稿 → 人工编辑 → 固定版本导出。
4. **修改到失效**：目标编辑、记忆撤回、作品换版 → 传递血缘标记过期 → 旧上下文/迟到 Agent 写入被拒绝 → 旧导出停止下载 → 用户重建当前结果。
5. **事件到页面**：持久游标轮询 → 有范围的查询刷新 → 进度/复习/运行/作品同步；切换空间取消旧请求，编辑草稿与服务端事实分开，冲突时保留用户输入。

## 从干净环境启动

需要 **JDK 17、Maven 3.9.x、Node.js 24、Python 3.12**。从仓库根目录运行：

```sh
npm ci --prefix frontend
npm run check --prefix frontend
npm run build --prefix frontend
python -m pip install -r requirements-dev.txt
python scripts/sync_contract_index.py --check
python scripts/verify.py
mvn -B -ntp -f backend/pom.xml clean verify
java -jar backend/target/typeforge-backend-0.4.0-SNAPSHOT.jar --spring.profiles.active=demo
```

打开 **http://127.0.0.1:8080/**。前端使用同源 `/api` 和 hash 导航，构建产物随 JAR 打包；先构建前端再打包后端。开发时可单独运行 `npm run dev --prefix frontend`，其 API 代理指向本地后端。没有默认登录账户，请自己注册。

默认使用持久 H2 文件，演示模式预置 16 张原创知识卡和 12 道客观题。演示作者不可登录，内容明确标记 `demo-authored-code-reviewed`，没有伪造人类审核、学习成绩或模型输出。标准 Profile 不自动预置课程；正式发布流程不能把 demo 的 REVIEWED 标签当成人工审核证据。

模型、MySQL 和编辑账户配置见后端说明。未配置模型也能使用证据整理、阅读、练习、类型实验、错题复练、手工作品及受支持导出。

## 当前文件导出范围

| 源作品 | 可下载格式 | 边界 |
|---|---|---|
| 文档 | Markdown、HTML | 保留文字/代码/知识引用；不宣称 DOCX/PDF |
| 幻灯片 | HTML | 文字与代码、固定布局、讲稿；不宣称 PPTX |
| 关系图 | SVG | 受控静态绘图；用户给定关系不自动成为已验证事实 |
| 错题集/交互作品 | 静态 HTML | 在线复练仍调用平台判分，离线文件不记录成绩 |

导出有持久队列、取消、归属检查、UTF-8 字节哈希和实际附件下载。下载前重新检查源版本与依赖；不公开私人文件 URL，不执行源稿中的 HTML/JavaScript。图片素材、音频与视频暂未接入。

## 修改后的检查与同步

```sh
python scripts/sync_contract_index.py
python scripts/verify.py
npm run check --prefix frontend
npm run build --prefix frontend
mvn -B -ntp -f backend/pom.xml clean verify
python scripts/smoke_runtime.py --profile h2
```

MySQL、浏览器、模型协议和重启检查的实际命令与环境见验收记录。测试脚本使用专用验证数据，不接入个人生产数据库。新增功能先更新真实实现和契约，再刷新索引、字典、消费者及验证；不要只修改能力标签。

已清理旧基线报告、过时研究副本和分版本字段字典，历史可从 Git 取回。**V001–V006 是升级链，必须保留**；删除或修改已应用的迁移会破坏同学现有数据库。AI 协助过程如实记录在 [ai-context.md](evidence/ai-context.md)，两位学生后续的审阅、修改和贡献另行记录。

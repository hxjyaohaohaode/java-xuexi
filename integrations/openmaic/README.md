# 在同一台机器上运行原版 OpenMAIC 并导入 Java 课堂

TypeForge 中的「我的课件」或「公共课件」已经使用官方渲染器。下面的步骤启动独立的原版工作台，可在其中打开 TypeForge 导出的单节课、编辑幻灯片并使用原版课堂工具。两个服务通过用户选中的课堂文件交换内容，各自管理账户、数据库和学习记录。

## 启动原版工作台

在 TypeForge 仓库**旁边**的新目录运行。固定版本为 OpenMAIC 1.5.0，Git 提交为 `240e8228de8228f949ca90ab10c270f42729f2de`。该提交要求 Node.js **22.19.0 或以上**，项目指定 **pnpm 10.28.0**，数据库使用 **PostgreSQL 16**。以下本地配方需要 Git、Docker Compose v2 和上述 Node/pnpm。OpenMAIC 使用回环地址的 3100 和 55432 端口，与 TypeForge 默认的 8080 独立。[官方版本及安装说明](https://github.com/THU-MAIC/OpenMAIC/blob/240e8228de8228f949ca90ab10c270f42729f2de/README-zh.md) · [依赖与脚本](https://github.com/THU-MAIC/OpenMAIC/blob/240e8228de8228f949ca90ab10c270f42729f2de/package.json)

```bash
git clone https://github.com/THU-MAIC/OpenMAIC.git OpenMAIC-typeforge
cd OpenMAIC-typeforge
git checkout --detach 240e8228de8228f949ca90ab10c270f42729f2de
git rev-parse HEAD
node --version
pnpm --version
pnpm install --frozen-lockfile

cp .env.example .env.local
export OPENMAIC_DB_PORT=55432
docker compose -p typeforge-openmaic -f docker-compose.db.yml up -d --wait postgres
export DATABASE_URL='postgres://openmaic:openmaic-dev@127.0.0.1:55432/openmaic'
pnpm run dev --hostname 127.0.0.1 --port 3100
```

`openmaic-dev` 是这份官方 Compose 的本机开发默认数据库口令。若自行设置了 `PERSISTENCE_POSTGRES_PASSWORD`，同步调整数据库连接字符串。打开 [本机原版工作台](http://127.0.0.1:3100)。换终端启动时重新设置 `DATABASE_URL`，也可将它写进此独立目录的 `.env.local`。若需要常驻运行，在相同目录、相同数据库环境下执行 `pnpm build`，然后运行 `pnpm start --hostname 127.0.0.1 --port 3100`。停止时在服务终端按 Ctrl+C，再运行 `docker compose -p typeforge-openmaic -f docker-compose.db.yml stop postgres`，可以保留课程数据。[官方数据库 Compose](https://github.com/THU-MAIC/OpenMAIC/blob/240e8228de8228f949ca90ab10c270f42729f2de/docker-compose.db.yml)

## 从当前课程导入一节课

1. 用自己的 TypeForge 学习空间打开「知识与阅读」，在「课程目录」选择 G06「泛型不变性」。展开「导出本节课堂」，点击「下载 OpenMAIC 课堂」，得到 `G06.maic.zip`。
2. 打开原版 OpenMAIC 工作台的「我的课程」，点击「导入课堂」，直接选择整个 ZIP。导入后从当前课程文件夹打开「泛型不变性」。每次导入都会创建新的课程与场景 ID。[官方工作台导入入口](https://github.com/THU-MAIC/OpenMAIC/blob/240e8228de8228f949ca90ab10c270f42729f2de/components/workbench/workspace/WorkspaceCoursesPage.tsx) · [原版课包读取与保存代码](https://github.com/THU-MAIC/OpenMAIC/blob/240e8228de8228f949ca90ab10c270f42729f2de/lib/import/use-import-classroom.ts)
3. 打开讲义页检查课程正文和页脚来源，翻到「不同的 List 类型实参不能直接替换」检查原始 Java 代码，再打开末页讨论。原版会为「课程导师」「讨论同学」分配新 ID，末页讨论动作应关联导入后的学生角色。若要运行 Java 和提交练习，可回到 TypeForge 的同一课「代码实验」和「随堂练习」。
4. `manifest.json` 是原版使用的入口。TypeForge 另在 `typeforge-sources.json` 保存课程发布 ID、原始材料全文、版本、哈希与 Java 示例出处。原版导入逻辑读取 manifest 与其中的场景，不会将这个附加来源文件写入它的文档模型。留存原 ZIP 可继续核对发布证据。包中没有 TypeForge 会话、导师私聊、作答、目标或成绩。

## 模型和可选媒体

阅读已经导出的文字和代码可以直接使用包内数据。要使用原版工作台的 AI 生成、教师解答或学生角色发言，需要接入上游支持的推理服务。在工作台「设置 → 模型服务」连接服务，或者在该独立目录复制 `openmaic.example.yml` 为 `openmaic.yml`，给 `slots.llm` 指定实际可用的模型，在 `.env.local` 配置相应凭据，再重启原版。语音合成、语音识别、图像、视频和网络搜索分别需要相应服务；本项目导出的 Java 课堂包含文本、结构化代码与讨论，没有预生成音视频。无需把 TypeForge 的 Cookie 或私人会话复制给原版。[上游配置与槽位](https://github.com/THU-MAIC/OpenMAIC/blob/240e8228de8228f949ca90ab10c270f42729f2de/packages/docs/content/docs/configuration.zh-cn.mdx) · [配置模板](https://github.com/THU-MAIC/OpenMAIC/blob/240e8228de8228f949ca90ab10c270f42729f2de/openmaic.example.yml)

## 已验证的范围

本仓库的 Chromium 验收从已登录的演示课堂实际下载全部 16 个课包，逐课核对 78 个场景中的讲义、Java、讨论角色、来源和官方 Draft-07 Schema。G06 还在 TypeForge 的官方播放器中实测了翻页、移动端代码文字稿和讨论转交导师。[浏览器课包断言](../../frontend/tests/classroom-archives.mjs) · [播放器断言](../../frontend/tests/maic-playback.mjs)

原版来源兼容性另以固定提交中实际的 `agentConfigFromManifest` 和 `rewriteAudioRefsToIds` 函数验证过浏览器导出的课包。已检验两位角色获得新 ID，讨论接收者被还原为学生，场景与动作能通过官方 Schema。原版 Next.js 服务、PostgreSQL 持久化、原版网页文件选择器以及外部媒体服务仍须在目标部署按上述步骤联验。[原版角色恢复](https://github.com/THU-MAIC/OpenMAIC/blob/240e8228de8228f949ca90ab10c270f42729f2de/lib/export/classroom-zip-types.ts) · [原版动作恢复](https://github.com/THU-MAIC/OpenMAIC/blob/240e8228de8228f949ca90ab10c270f42729f2de/lib/export/classroom-zip-utils.ts)

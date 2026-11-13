# 数据库迁移、运行数据与字典

服务使用 Spring JDBC 与 Flyway，目标数据库为 MySQL 8.4，字符集 UTF-8，业务时间为 UTC。本地开发默认使用文件型 H2 与专门的 H2 兼容迁移；H2 通过不等于真实 MySQL 行为已经验收。

## 迁移与字典

| 迁移 | 内容 | 逐字段字典 |
|---|---|---|
| [V001](V001__baseline.sql) | 课程、身份、来源、知识、题目、学习事实、发布、编译与导师等基础表 | [课程字典](data-dictionary.md) |
| [V002](V002__agent_workspace.sql) | 工作空间、目标、上下文、任务图、执行、作品、素材、记忆等表 | [Agent 工作空间字典](agent-data-dictionary.md) |
| [V003](V003__runtime_integrity.sql) | 作答归属、题目家族独立证据、工作空间投影、作品输入血缘、本地证据工作流 5 张表，以及 outbox 重试字段 | [运行时字典](runtime-data-dictionary.md) |

所有迁移按版本顺序前向执行，不改写已执行脚本。当前后端已真实读写部分业务表；其余表为后续能力预留。表已存在不代表业务适配器可用，模块状态以 [contracts/modules.json](../contracts/modules.json) 为准。关系与应用层不变量见 [数据设计](../docs/03-data.md)。

## 启动与迁移方式

推荐让 Flyway 管理**空的专用数据库**。正常 JDK 17 / Maven 环境中，按 [后端说明](../backend/README.md) 设置 `TYPEFORGE_DB_URL`、`TYPEFORGE_DB_USER`、`TYPEFORGE_DB_PASSWORD`，然后从仓库根目录运行：

```sh
mvn -f backend/pom.xml spring-boot:run -Dspring-boot.run.profiles=mysql
```

`mysql` Profile 选择原始 `V*.sql`；默认 Profile 选择 H2 Java 迁移适配。两者都由 Flyway 保存 schema history。不能先手工导入表，再把同一库当成空库启动 Flyway；已有库应先备份、确认迁移历史并制定正式接管方案。脚本没有 DROP/DELETE，但仍会新增表、列和约束，不能无评审地运行于任意已有数据库。

独立 SQL 验证时，可在临时空库按 V001→V002→V003 顺序导入，再选择是否加载 `seed-demo.sql`。SQL 文件不是可重复执行的 upsert，导入脚本与应用启动迁移是两种不同验证方式，不在同一库混用。

## 两种种子数据的不同用途

| 来源 | 实际内容 | 发布与审核状态 |
|---|---|---|
| `seed-demo.sql` | 禁用占位作者、来源索引、概念、草稿卡及草稿发布；用于独立 SQL 约束检查 | 没有 active release，没有可登录默认密码、学生成绩或人类审核 |
| `DemoContentInitializer`，只在 `demo` Profile | 16 个概念、16 张原创知识卡、12 道客观题和明确的演示发布 | `origin=demo-authored-code-reviewed`、`formalHumanReview=false`；禁用系统作者；不创建 `content_review`、学生提交或模型回答 |

本地演示启动用 `-Dspring-boot.run.profiles=demo`；专用 MySQL 演示库用 `-Dspring-boot.run.profiles=mysql,demo`。演示初始化在单个事务中执行，同版本重复启动幂等，内容清单变化则拒绝覆盖。它不是正式双人审核发布实现，16 卡/12 题也不等于完整课程内容目标。

## 独立验证

```sh
python scripts/verify_mysql.py
```

脚本使用本机 Docker 创建随机命名、无端口映射、禁外网、数据位于 tmpfs 的临时 MySQL 8.4.5 容器，执行当前迁移、草稿种子和约束案例，最后只清理自己创建的容器。首次可能需要拉取镜像。它不连接现有数据库；Docker 不可用时应如实报告未运行，不把静态解析或 H2 当作该项通过。

本轮是否运行 MySQL、应用集成测试及其结果见 [v0.3 验收记录](../evidence/runtime-review.md)。历史检查见 [v0.2 基线记录](../evidence/baseline-review.md)。部署前仍需完成真实数据库的锁竞争、备份恢复、保留/删除策略与生产容量验证。

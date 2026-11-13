# 数据库基线

MySQL 8.4，UTF-8，UTC。建表脚本没有 DROP/DELETE。只在一个新建的专用数据库按顺序运行 `V001__baseline.sql`、`V002__agent_workspace.sql`、`seed-demo.sql`；脚本不是重复运行的 seed upsert，不能直接投到已有库。

```sh
python scripts/verify_mysql.py
```

该脚本使用本机 Docker 新建随机命名、无开放端口、禁外网、数据位于 tmpfs 的临时 MySQL 8.4.5 容器，检查建表、种子、外键、版本成员关系和关键 CHECK/UNIQUE，最后只清理自己创建的容器。首次可能需要拉取该镜像。它不连接或清理现有服务。

如使用已有的独立测试 MySQL，可按课程环境手动导入两份迁移与一份种子 SQL，再按脚本中的约束案例验证；不得对生产库运行测试插入。

51 张表的完整字段见 [课程数据字典](data-dictionary.md)与[Agent工作空间数据字典](agent-data-dictionary.md)，关系和应用层不变量见 [数据库设计](../docs/03-data.md)。当前后端没有数据库适配器，种子都是草稿，后续必须先实现审核/发布再公开展示。

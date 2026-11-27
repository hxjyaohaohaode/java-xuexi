# 同一门课如何给不同学生授课

学生进入「课堂」并选择章节后会看到自己的课件。首次进入由服务器准备第一版。打开「为什么这样安排」可以查看决定分镜的依据。学生可以更改教学方式，也可以按照当前安排读知识卡、先做预测、载入同学提供的原始代码、做题、查看真实判分，再回到同一章。课内的「讲解课件」会围绕选中的个人版本生成可编辑幻灯片。

## 需求、实现与验收

| 要完成的学习活动 | 实现及取舍 | 如何核查 |
|---|---|---|
| 从同学提交的课内材料生成个人课件 | 固定当前 release 的概念、知识卡修订、源码、公开题干。由工作空间内纳入的本人作答、前置阅读、学习目标与学生主动填写的偏好决定教学方式 | 持久化 HTTP 验收对同一发布的 16 个章节逐一生成基础与实例偏好两种课堂；浏览器另用两个独立学生账号。确认没有记录时的基础版、先看代码的实例版以及出现错答后的订正版，比较第一项实际内容、关联题目及场景顺序 |
| 决定应该教什么 | 有错答或用过提示时优先重建判断；遇到明确的分步目标时增加前置回顾与判断步骤；实例偏好先看程序；独立答对、已阅读或选择挑战时先做迁移题。题库允许时避开已做过的题目家族。显式选择可覆盖自动安排 | 用同一个课程概念切换输入，验证 FOUNDATION、EXAMPLE_FIRST、REPAIR、TRANSFER 各自的分镜及引用。只有真实判分才能更新作答事实 |
| 关联具体错因、进入原题、实验和复练 | 订正页链接原题修订和作答；借用同学课程包中可追查的核对判据及勘误，没有配套判据时给出已发布知识卡原文与修订。学生可打开原判分；新练习仍由原判分服务处理。代码场景从同学原稿打开实验，编译和运行经隔离 worker | 检查原作答回执与新题的来源。Java/Python 分别在浏览器提交带输入的程序，由实际容器返回输出 |
| 让教师团队真正使用个人版本 | 生成任务绑定一份不可变的个人课件、该节概念、执行模式和授权学习快照。教学任务简报保留顺序、实际问题和样例片段；分工检索限于当前概念。规划、专家、审阅与排版均有持久状态 | 查看运行详情和最终 SLIDES。开头各页保留当前学生分镜、正文及代码；后续讲解独立标明生成方式，正文完全相同的专家讲解仅展示一次，各角色原始草稿与用量分别保留。检查课件来源、草稿修订和恢复的 runId。H2 和 MySQL 的持久化验收在停止应用 JVM 前提交个人任务，重新登录后核对同一任务的成品。在真实 MySQL 另用两条连接：一条先读取尚未完成的步骤，另一条提交成品，再由原连接读取终态，核对完成状态和作品引用同时可见 |
| 区分实际推理与本地整理 | MODEL 调用显式配置的兼容模型，停机或无配置时按真实状态返回；EVIDENCE_ONLY 使用固定课程证据编写讲解。两种模式各自有运行记录、输出和预算 | 无模型时尝试 MODEL 应返回 503。部署有模型时用服务端计量与请求回执核对实际调用；归属、引用和结构通过后，还要逐句审核学科结论 |
| 让角色带着正确的上下文交接 | 每一步冻结授权来源与前驱草稿。持久记录原文与保留的 Unicode 字符数、被略去的来源数、父检查点、计划修订、运行代次。角色从原快照按来源取回被略去的事实；发出前保存准备好的请求，未知外部结果按未知处理 | 测试发送前中断与新 worker 恢复、已发送但响应未知、过时来源及重规划。检查专家接收的材料、审阅收到的草稿、本人空间以外的请求 |
| 保持长期记忆和新一轮任务的边界 | 私人偏好可撤回。派生的教学任务目标只用于对应任务。学生在课件冻结之后新增的记忆不会追加入旧任务 | 生成后再增加记忆；查看原任务和普通目标列表；撤回后重开课件、重跑旧请求、恢复旧结果，验证拒绝或失效 |
| 保留课件、练习、讲解和作品的连续位置 | 课堂以选中课节及个人版本打开官方 OpenMAIC 渲染器。讲解完成后在课堂翻页，也可进入作品编辑。按学生、空间、课节、版本和模式从服务端恢复任务 | 刷新浏览器并核对运行 ID，检查另一账号 404；退出登录发出前取消旧空间轮询，并核对切换账号后只读取新空间；检查小屏横向溢出、键盘操作及标准课件和个人课件分别导出 |
| 保持公共课程可分享 | 标准课程包只取已发布素材。下载个人包前重新检查冻结版本，个人 ZIP 保留教学安排、选用的公开内容和讨论动作；导出不包含原始记忆、本人作答标识及私有空间 ID。已下载的离线文件由下载者保管 | 用两名学生逐项比较公开场景；检查个人文件差异，扫描人工设置的私人标记及实际内部标识 |
| 合并与复验 | 运行静态契约、H2、MySQL 8.4、Chromium、DeepSeek Harness 工具注册、实际 Java/Python 容器用例，再将验收摘要绑定源码 | 检查 GitHub Actions 对 main 提交的三项结果。运行记录、模型实测与人工课程复审各自举证 |

## 教学设计的来源

OpenMAIC 的大纲生成器将用户资料作为显式输入，场景生成器和构造器把大纲逐项变成教学材料。本项目沿用这个拆分，在服务器根据本课已授权的学习证据选材，并通过官方 DSL 与渲染器呈现。结构化的第一版允许在没有外部模型时上课，也方便精确回放两个学生为什么读到不同的材料。另行启动的 MODEL 工作流可以让实际模型规划和讲解。[OpenMAIC 大纲实现](https://github.com/THU-MAIC/OpenMAIC/blob/240e8228de8228f949ca90ab10c270f42729f2de/packages/%40openmaic/generation/src/outline-generator.ts) · [场景构造](https://github.com/THU-MAIC/OpenMAIC/blob/240e8228de8228f949ca90ab10c270f42729f2de/packages/%40openmaic/generation/src/scene-builder.ts)

DeepSeek Harness 提供会话和上下文压缩子系统、子智能体及显式能力注册。Codex 的公开实现展示了上下文压缩时保留近期输入和恢复工作上下文的机制。Claude Code 的公开说明定义了子代理的独立上下文、工具范围和持久记忆。课堂采用来源可查的确定性摘录、固定证据、角色权限与任务代次。这里的压缩计量采用字符数；模型提供的 token 用量另行保存。[DeepSeek Harness 压缩机制](https://github.com/deepseek-ai/deepseek-harness/blob/4b399c39a6d1479cd84ca591b4d7c88e24d38a96/docs/subsystems/compaction.md) · [Codex 压缩实现](https://github.com/openai/codex/blob/main/codex-rs/core/src/compact.rs) · [Claude Code 子代理](https://code.claude.com/docs/en/sub-agents) · [Claude Code 记忆](https://code.claude.com/docs/en/memory)

Apple 的内容层次、适应不同尺寸的布局和辅助功能用于组织同一个课堂舞台、上下文工具和次要操作。设计在 Web 环境中以实际字体缩放、键盘焦点、小屏布局和减少动效检查。[Apple HIG](https://developer.apple.com/design/human-interface-guidelines/) · [布局](https://developer.apple.com/design/human-interface-guidelines/layout)

## 实现入口

| 对象 | 文件 |
|---|---|
| 分镜输入、教学方式与冻结修订 | [PersonalCourseware](../backend/src/main/java/io/typeforge/learning/PersonalCourseware.java) · [PersonalLessonOutline](../backend/src/main/java/io/typeforge/learning/PersonalLessonOutline.java) |
| 个人课件与教学工作流 | [CoursewareStudyService](../backend/src/main/java/io/typeforge/learning/CoursewareStudyService.java) · [StudyWorkflowService](../backend/src/main/java/io/typeforge/tutor/StudyWorkflowService.java) · [CoursewareSlides](../backend/src/main/java/io/typeforge/tutor/CoursewareSlides.java) |
| 记忆、角色检查点及恢复 | [LearningContextJournal](../backend/src/main/java/io/typeforge/workspace/LearningContextJournal.java) · [StudyContextJournal](../backend/src/main/java/io/typeforge/tutor/StudyContextJournal.java) |
| 学生课堂、课件与作品 | [PersonalLesson](../frontend/src/components/PersonalLesson.tsx) · [CoursewareStudy](../frontend/src/components/CoursewareStudy.tsx) |
| 面向学生的场景测试 | [双账户浏览器验收](../frontend/tests/personal-courseware.mjs) · [个性化后端验收](../backend/src/test/java/io/typeforge/PersonalCoursewareTest.java) · [生成和恢复验收](../backend/src/test/java/io/typeforge/CoursewareStudyIntegrationTest.java) |

真实模型的输入、输出、错误和修复记录见[个性化课件模型实测](../evidence/personal-courseware-model.md)。课程评审仍以实际学生、教师、同学提交的材料及 [课程验收矩阵](../evidence/course-acceptance-matrix.csv) 为准。[课堂总清单](25-integrated-classroom.md)保留了实时多人课堂、媒体生成、课程扩容、独立复审及学习效果评测的条件。

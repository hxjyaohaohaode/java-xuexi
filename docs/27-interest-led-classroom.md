# 按学生兴趣上课，在课内完成练习

本章以同学提交的「泛型与类型安全」为教学边界，覆盖 G01–G16。打开一个知识点，在「调整兴趣与要求」里填写想研究的情境和希望怎样学习。例如，两位学生都学 G06。一人希望分析篮球队名单，先预测再动手；另一人希望从天文观测星表出发，解释编译反例并设计变式。两人仍需掌握同一条 Java 类型规则，可以通过相同课程版本的正式题比较学习结果。

## 功能、实现和验收

| 学生或教师的要求 | 实现位置与行为 | 可复现的检查 |
|---|---|---|
| 兴趣和本节要求真正影响课程材料 | 本课的兴趣、要求、既有学习证据进入冻结的个人课件。课程规则提供已核对的合法与非法 Java 行；应用情境决定类型、任务、需要维护或读取的数据，要求影响是否先预测、补机理或做迁移。自由填写的其他兴趣可用自己的项目举例，也可在模型模式展开。 | 两套 H2/MySQL HTTP 回归逐点生成不同兴趣的课件，比对同一发布下的实际代码和讲解；真实模型检查用两名合成学生生成同一个知识点。 |
| 既保留同学原课，又深化学科内容 | 原始 16 个 Java 案例、哈希、出处和订正保留；新增 16 组学科判例，每组给出适用条件、可编译行、编译器拒绝的行、原因和迁移题。把课内预测与正式课程判分分别记录。 | Java 17 重编译原案例并核对警告／输出，逐组编译课件所用的 32 个正反程序，检查拒绝行，并以 Java 进程运行 16 个固定正例。 |
| 学、练、订正、问导师连在一起 | 在课件内读例子、选预测、看解释、作答正式题、拿到真实判分，错误反馈带有该次作答的来源。需要讨论可以把当前课堂上下文带到导师，再回到触发位置。 | Chromium 通过真实页面和 API 比对两种兴趣、作答收据、订正、导师交接、回到课件、刷新恢复和账号隔离。 |
| 小屏、键盘和等待时都能继续使用 | 小屏按内容重排为可阅读的课件，代码块可独立滚动；面板的显示、收起、禁用、加载、错误和焦点随当前任务变化，支持减少动效。 | 同一浏览器流程覆盖桌面和手机视口，检查无页面级横向溢出、课件自由作答、控件可操作与返回焦点。 |
| 深度解释获得足够预算 | 提示、规划、专家讲解和复核各有输出目标。请求还受输入长度、服务端配置的模型上下文窗口和本次最大输出约束。实际输入、输出与请求状态分别结算。 | 用真实模型核对发出的 max_tokens、供应商返回用量和终止原因。协议测试覆盖窗口边界、截断和含用量的异常。 |
| 受控的多代理课堂 | 规划依据当前课件目标选择受支持的下游任务，专家结合原始课程证据、已有作答和本节要求生成讲解，复核者能给指定专家提交结构化替换。有已核对判例时，复核者逐个选择推导、预测、反例或迁移，学生版讲解由所选动作与本课已验事实组成；原始专家草稿留存。最终作品保留草稿状态和版本引用。 | 真实模型核对角色、每次请求回执和最终作品。集成测试检查指定修订确实用于合成，并覆盖未知专家、越权、重启恢复。 |
| 代码实验支持追问与对照 | 当前兴趣预测题能将完整 Main.java 送入同课编辑器，学生可以修改、执行、回到原题。工作进程中可运行 Java/Python；按实际公开结果解释所在阶段。可选同空间同语言同模式的另一任务比较源码是否变化、可见输出和公开诊断数，再带着回执摘要向导师提问。 | 浏览器经真实 Docker 工作进程运行 Java、原始类型警告及 Python 输入输出，再验证两次 Java 对照和 Python 导师草稿。后端验证处理中任务、跨账号和跨空间访问。 |
| 学生能控制自己的输入 | 明确保存在工作区记忆中的受支持兴趣可跨课复用，本节输入优先。学生可以为本节更新或清除要求。版本保留实际采用的来源；公开导出转成公共应用文本，撤回后重新校验依赖。其他学生无法打开相关私人资源。 | API 和浏览器检验冻结、重新进入、撤回、账号隔离和下载中是否含私人标记。 |
| 交付到可复核的 main | 每次合并后 GitHub 在 main 自身上重新检查，应用包和各门禁报告来自该次工作流。 | 查看同一个提交的契约及真实 MySQL、隔离编译器、Java/HTTP/浏览器和本地真实模型四项任务。 |

## 设计依据及项目取舍

OpenMAIC 的大纲及场景生成把学习者输入、教学目标和选定资料逐步转成课程场景。本项目以本章的可复验 Java 规则和同学原例作为基础，把原话显式传给教学工作流，并在任何模型调用之前提供可完成的个人版课件。预置应用情境覆盖有限，陌生领域由学生给出领域实例。模型生成的额外说明保留为草稿。[OpenMAIC 大纲生成](https://github.com/THU-MAIC/OpenMAIC/blob/240e8228de8228f949ca90ab10c270f42729f2de/packages/%40openmaic/generation/src/outline-generator.ts) · [场景构造](https://github.com/THU-MAIC/OpenMAIC/blob/240e8228de8228f949ca90ab10c270f42729f2de/packages/%40openmaic/generation/src/scene-builder.ts)

对照 Claude Code 的观察、行动和验证循环、Codex 的上下文压缩，以及 DeepSeek Harness 对工具和会话的管理，课堂保留能复查的目标、输入快照、父步骤、工具回执和最终版本。复核修改要指向本轮真实专家输出。DeepSeek Coder 的公开编程任务覆盖代码补全与填充；本课用静态类型预测、改一行、执行并对照作为学生可亲自检验的编程任务。运行时接受显式配置的兼容模型。[Claude Code 工作方式](https://code.claude.com/docs/en/how-claude-code-works) · [Codex 上下文压缩](https://github.com/openai/codex/blob/main/codex-rs/core/src/compact.rs) · [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) · [DeepSeek Coder](https://github.com/deepseek-ai/DeepSeek-Coder)

Apple HIG 的布局建议强调不同尺寸上保持操作关系，动效应传达状态，按钮应清楚表达动作，无法预估时长的工作适合不定进度提示。这里对应的是课件内作答、手机流式排版、按钮与上下文紧邻，以及长任务的真实状态与恢复。网页圆角和动画时长属于本项目的样式选择。[布局](https://developer.apple.com/design/human-interface-guidelines/layout) · [动效](https://developer.apple.com/design/human-interface-guidelines/motion) · [按钮](https://developer.apple.com/design/human-interface-guidelines/buttons) · [进度](https://developer.apple.com/design/human-interface-guidelines/progress-indicators) · [辅助功能](https://developer.apple.com/design/human-interface-guidelines/accessibility)

## 如何复核证据

- 在 [GitHub Actions](https://github.com/hxjyaohaohaode/java-xuexi/actions/workflows/baseline.yml) 选择 main 对应的运行。下载 TypeForge-classroom 可取得已通过 HTTP 和浏览器检查的应用。real-model-personal-classroom 记录两位合成学生的编程题、解释、角色、分配上限、实际 token、调用终止原因、成品哈希和模型权重哈希。
- 模型门禁使用本地 Qwen3-0.6B Q8_0，固定权重、推理引擎和随机种子。报告证明所测模型、课程、环境和任务组合确实执行。课堂准确性仍由实际源码、编译器和教师复审逐项确认。研究参考的供应商与实际执行的模型分别记录。[模型门禁](../scripts/verify_live_classroom.py) · [课内浏览器验收](../frontend/tests/personal-courseware.mjs) · [编程实测](../frontend/tests/program-runtime.mjs)

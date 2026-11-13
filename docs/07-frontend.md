# 前端样式、页面与交互规范

## 1. 研究范围与证据边界

已逐条阅读用户《前端组件交互参考.md》的 1–209 条，完整取舍见 [209 条研究台账](../frontend/reference-ledger.csv)。四个核心候选已读取官方 TS + Tailwind 源文件：#68 LineSidebar、#69 AnimatedList、#92 SpotlightCard、#102 Stepper。其余候选仅完成目录与业务适配研究，**没有宣称已运行 209 个演示、完成触屏验收或复制组件**。

源码参考快照：React Bits commit `4e0d091d3b1ea8b7aadef33716a8d99d164ae4ce`。本次不做前端页面；实际安装前必须再核对当前版本、读完整实现并运行真实演示。台账中“候选”不等于“已采用上线”。

## 2. 视觉方向

采用“精密的学习工作台”：温暖灰白阅读背景、深墨色代码区、青绿色操作强调、少量橙色诊断强调。首页可有一块类型关系视觉，学习正文保持安静。排版用清晰的章节编号、类型标签、代码位置和证据侧栏表达秩序。不同类型的状态同时用图标、文字和颜色表示。

字体：界面采用系统中文无衬线（`PingFang SC/Microsoft YaHei` 等），代码采用系统等宽（`Cascadia Code/Consolas` 等）；当前不下载第三方字体。Word/PDF 正式文档仍遵从用户独立的统一模板要求，网页字体不与文档模板混用。

| Token | 浅色 | 深色 | 用途 |
|---|---|---|---|
| canvas | #F5F4F0 | #111719 | 页面背景 |
| surface | #FFFFFF | #1B2528 | 内容区 |
| text | #172B2E | #EDF5F2 | 主文字 |
| muted | #526568 | #B0C1BF | 次文字，仍需测对比度 |
| accent | #006B5E | #68D7BE | 主动作/选中 |
| warning | #8A4B00 | #FFCB82 | 警告，附明确标签 |
| danger | #B42318 | #FFB4AB | 错误 |
| border | #CAD5D1 | #445653 | 分隔线；输入边界需复测 ≥3:1 |
| inputBorder | #708885 | #78918B | 输入控件边界，分别对 surface 对比约 3.78/4.64 |

规格保存在 [design-tokens.json](../frontend/design-tokens.json)。间距 4/8/12/16/24/32/48；按钮高至少 44px；代码字号 14–18 可调；正文 16–18、行高 1.65；卡片圆角 12，输入 8，大面板 16。细边框为主，阴影只用于浮层。不把整个系统堆成玻璃卡片，不用模糊正文制造层次。

## 3. 桌面与移动布局

桌面 ≥1280：左导航 224px；主要内容弹性；导师/证据侧栏 340px，可收起。学习页正文宽 720–840px，代码可横向滚动。实验页为“题意 28% / 编辑器 44% / 类型与诊断 28%”，分栏可调整并保存；任何面板隐藏后有明确恢复入口。

768–1279：侧导航收为 72px 图标栏且有文字提示；导师在抽屉打开；实验器两栏。<768：单栏、明确顶部导航，编辑/运行结果/导师使用标签页切换；不挤成三列，不用 hover 作为唯一功能入口。320px 下页面正文无水平溢出，代码块允许自身滚动。

## 4. 页面与业务状态

| 路由 | 核心布局与操作 | 数据/联动 | 必须设计的状态 |
|---|---|---|---|
| `/` | 项目介绍、继续学习、当前能力入口 | framework 能力清单 | 未登录/加载/模块未上线 |
| `/learn` | 概念路径、前置关系、进度与章节列表 | concepts + progress | 空记录/前置不足/历史版本 |
| `/learn/:conceptId` | 阅读、正反例对照、证据抽屉、去实验 | concept/releaseId | 404/退役/引用失效/阅读已保存 |
| `/labs/:labType` | 预测→类型操作→核验→解释 | lab session + compile job | 不支持/排队/超时/警告/取消 |
| `/practice/:exerciseId` | 题干、回答区、提示层级、反馈 | exercise + attempt | 未答/提交中/重复请求/基础设施失败 |
| `/reviews` | 复练清单、原因、修改前后 | review_item + attempts | 证据不足/无复练/更新延迟 |
| `/knowledge` | 术语搜索、筛选、证据列表 | retrieval + source | 无命中/失败/旧版本/加载更多 |
| `/tutor/:conversationId` | 对话、上下文卡、证据、建议 | tutor request + own attempt | 断开/无证据/模型不可用/取消 |
| `/progress` | 独立正确率、样本量、错因、时间线 | learner_concept | 未练习/部分数据/投影重建 |
| `/admin/content` | 来源、卡片、题目、审核与版本 diff | admin APIs | 无权限/编辑冲突/审核未通过 |
| `/admin/releases` | 校验清单、发布/回退、索引状态 | release/index_snapshot | 发布中/失败/并发冲突/回退完成 |
| `/settings` | 主题、动效、字号、隐私与会话 | preferences/session | 保存中/失败恢复/退出 |

每页加载失败都有重试与保留输入；不能用空态遮掩服务器错误。表单失败焦点落到错误概要，字段保留原值。未实现能力不呈现可点击主操作。

## 5. 核心交互拆解

### #68 LineSidebar → 学习路径导航

源码：[LineSidebar.tsx](https://github.com/DavidHDev/react-bits/blob/4e0d091d3b1ea8b7aadef33716a8d99d164ae4ce/src/ts-tailwind/Components/LineSidebar/LineSidebar.tsx)。依赖 React/Tailwind，无额外动画包。原逻辑通过距离衰减和 rAF 指数平滑同时控制标记长度、颜色和文字横移，离开后回位，当前项维持高亮。

迁移保留邻近感应与回位规律；maxShift 从演示 30px 调到本产品 6px，避免路径文字跳动。必须把源码中点击的 `li` 改成真实链接并用 `aria-current="page"`，焦点有相同反馈；路由作为唯一选中状态来源，避免 defaultActive 与浏览器前进/后退不同步。减少动效时直接切换状态。此无障碍缺口来自源码检查，尚待实际实现验证。

### #69 AnimatedList → 诊断/证据选择列表

源码：[AnimatedList.tsx](https://github.com/DavidHDev/react-bits/blob/4e0d091d3b1ea8b7aadef33716a8d99d164ae4ce/src/ts-tailwind/Components/AnimatedList/AnimatedList.tsx)。实际 TSX 引入 `@hugeicons/react` 与 `@hugeicons/core-free-icons`，不应凭旧教程安装 motion。提供 pop/slide/fade、可视区域状态、列表高亮与方向键，使用弹性 `linear()` 曲线及 CSS 降级。

源码过渡含 620ms transform 与 300ms opacity；迁移先观察运动再适配为 220–320ms 的诊断反馈，保留轻微回弹但不影响快速点击。源码有 window keydown 注册，必须限制到列表拥有焦点时响应，避免拦截 Monaco/textarea 的方向键。证据列表使用稳定 evidenceId，新增结果不能抢走正在编辑的焦点；超过 100 项再采用虚拟列表，不让动画破坏行测量。

### #92 SpotlightCard → 首页的三个实验入口

源码：[SpotlightCard.tsx](https://github.com/DavidHDev/react-bits/blob/4e0d091d3b1ea8b7aadef33716a8d99d164ae4ce/src/ts-tailwind/Components/SpotlightCard/SpotlightCard.tsx)。React/Tailwind，无额外动画包；指针与 focus-visible 都能触发聚光，按下有 flare，rAF 到达稳定状态停止，observer/事件在卸载时清理。

保留随指针渐进跟随、移出淡出；本产品不启用 ambient 常驻循环，最多三个入口使用。正文、代码与表格不加光斑；触屏/减少动效退化成清晰焦点框。浏览器不支持 color-mix 或 mask 时退化为纯边框，文字和链接一直可用。

### #102 Stepper → 实验四步流程

源码：[Stepper.tsx](https://github.com/DavidHDev/react-bits/blob/4e0d091d3b1ea8b7aadef33716a8d99d164ae4ce/src/ts-tailwind/Components/Stepper/Stepper.tsx)。实际依赖为 React/Tailwind 与两个 Hugeicons 包；支持受控 step、linear、禁用步骤、横/纵方向与切换动画。源码主要滑块/尺寸过渡 560ms，含 reduced-motion 路径。

用受控 step 对接服务端实验状态：预测提交后才进入核验，作业未完成不能冒进“已掌握”。返回上一步允许修改，修改后旧编译结果标记过期。失败不跳过步骤，显示原位置的错误和重试。动画不是计时器，不使用固定 3 秒后假装成功。减少动效设 transition none；所有按钮保留原生 disabled。

## 6. 其他候选与取舍

#107 翻卡适用于复习正反例，但不能让完整解答在 DOM 中提前泄露；#114 提示栏可借鉴输入区组织，只显示实际具备的文本和代码上下文入口；#119 状态标记表达检索/生成/校验真实阶段；#129 提示要支持聚焦；#131 分段切换用于编辑/结果/证据，不承担路由副作用；#133 勾选对应真实表单。

Uiverse #173 焦点输入、#197 文件树、#200 主题开关、#207 发送、#209 错误页可作为行为候选。它们当前仍未读取原源码或跑真实演示，实施前必须逐项核对许可和真实行为，不能把参考文档描述当已确认实现。

全部 137–165 动态背景首版不选：学习与诊断是信息密集场景，成本不能替代教学价值。各类图库/金融卡/票券/角色加载也不迁入核心流程；不使用替代系统鼠标的光标效果。未来如增加首屏动效，单独预算与验证。

## 7. 状态、无障碍与性能

- 加载/排队：真实状态文案和可取消操作，不虚构百分比；未知进度用 indeterminate。
- 成功/错误：由响应决定；错误提示保留源位置并可跳到对应代码行；toast 不作为唯一错误容器。
- 键盘：Tab/Shift+Tab 连贯；Esc 关闭抽屉并恢复焦点；Ctrl+Enter 运行仅在编辑区域激活；中文输入法合成时 Enter 不发送。
- 屏幕阅读：装饰 aria-hidden，图形关系同时有文本表格；状态只在改变时适度 announce，不逐 token 播报。
- 动效：一般反馈 120–220ms，面板 220–320ms，必要实验步骤最长 560ms；同屏最多一个连续动画，离屏暂停，所有 listener/observer/rAF 清理。
- 性能目标：首屏 JS gzip <250 KiB（编辑器另行懒加载）、CLS<0.1、实验无输入阻塞；实际指标待实现后在固定设备上测量。
- 验收：鼠标/键盘/触屏，320/768/1440 宽度，浅/深主题，减少动效，离线/慢网，重复挂载卸载，保存/取消/重试/历史后退均覆盖。

## 8. 许可与落地门槛

React Bits 当前为 [MIT + Commons Clause](https://github.com/DavidHDev/react-bits/blob/4e0d091d3b1ea8b7aadef33716a8d99d164ae4ce/LICENSE.md)，应用内使用与重新分发组件包有区别。本次只写研究和链接，没有把源码复制进组件库。真正采用时保留版权许可，逐项登记源文件/commit/依赖锁文件/改造/交互测试/限制；Uiverse 的每个作品分别核对。没有实现和实测的条目不能标成完成。

## 9. 以Agent工作空间为主界面

新增主路由 `/workspaces`、`/w/:workspaceId`、`/w/:workspaceId/artifacts/:artifactId`、`/w/:workspaceId/runs/:runId`。前面的阅读/实验/练习页作为可独立访问、也可嵌入主画布的学习视图。所有视图保留深链接、浏览器前进后退和局部编辑状态。

桌面左栏224px为目标/知识/作品树，中心弹性画布，右侧320–380px上下文指导，底部48px任务条；画布上方为作品标签、当前版本/保存状态和全局命令输入。大媒体编辑时可收起两侧；任务详情按需展开DAG。移动端一次显示一个画布或指导面板，底部提供明确切换，作品树进入抽屉。拖拽布局必须有键盘/按钮等价操作。

| 场景 | 样式与交互 | 真正的数据动作 |
|---|---|---|
| 提出目标 | #114输入结构，选中材料引用chip，产物多选和可展开预算 | 固定contextSnapshot→createAgentRun；输入法合成不发送 |
| 运行中 | #111状态线、#102阶段、可用产物逐个出现；无虚构百分比 | 由run_event序号更新；暂停/取消/改目标有实际command |
| PPT/文档 | 低装饰白色画布、页缩略列表、右侧属性；#91只用于手动缩略浏览 | 对象选择、改文案/排版、保存revision、局部重新生成、导出 |
| 图片/图解 | 原图/结构图与说明双栏，键盘可选择节点 | 关系编辑走Diagram DSL；事实错误回专家核验 |
| 音视频 | 稳定时间尺、分段轨道、字幕与脚本联动；#127数值输入可键入 | 单句/单镜头重做、拖动后保存时间线、预览和导出job |
| 动态错题集 | 六类块可折叠，错误现场和验证结果同时可见 | 预测→编译/判分→提示→变式，结果回到同一学习事实链 |
| Agent指导 | 当前选区上下文卡、下一步建议、证据抽屉；不堆头像聊天 | 明确scope，允许撤回选区/关闭建议/切换安静模式 |
| 版本冲突 | 保留本地草稿+服务器版本，按片段比较 | 409后重新基于当前revision提交，不能自动覆盖 |

新增候选#84作品文件夹、#91缩略浏览、#110媒体处理中边框、#111状态线、#114输入、#127数值拖动、#197素材树、#202属性小面板已同步到209条台账。其中#110/#111/#114/#127已补读固定提交的关键props、事件、状态和清理源码，发现的迁移问题如下；其他新增候选仍待源码研究。所有组件的真实浏览器行为均待前端实施后验收。

色彩继续使用本章tokens：清晰的纸面/墨色/深青主动作，状态附文字与图标。工作空间不铺满发光卡片；动效用于指示任务与对象状态的真实变化。除首次介绍外不展示“14个专家启动”等内部口号，学生看到的是正在解决的具体问题、可用产物和下一步选择。

## 10. Agent相关组件的源码迁移要求

以下均固定到与前面相同的React Bits提交；这是关键路径源码研究，尚无浏览器验收，也未复制组件实现。

**#114 PromptBar**：[源码](https://github.com/DavidHDev/react-bits/blob/4e0d091d3b1ea8b7aadef33716a8d99d164ae4ce/src/ts-tailwind/Micro/PromptBar/PromptBar.tsx)。依赖React、motion/react和两个Hugeicons包。可借鉴@材料、/命令、模型/深度选择和发送/停止形变；`onSend/onStop/onAttach/onDictate`只是回调接口，实际上传/识别/取消必须接业务。源码send调用回调后立即清空草稿；迁移须保留发送快照、失败恢复和幂等key，避免请求失败丢字。普通Enter已有isComposing判断，但菜单Enter/Tab分支在前；本产品应在整个keydown入口先排除输入法合成。附件Promise补错误处理；附件名改为已扫描assetId映射，不能凭显示字符串授权。取消按钮直到服务端确认前保持“正在停止”。

**#111 ThoughtLine**：[源码](https://github.com/DavidHDev/react-bits/blob/4e0d091d3b1ea8b7aadef33716a8d99d164ae4ce/src/ts-tailwind/Micro/ThoughtLine/ThoughtLine.tsx)。依赖React、motion/react、Hugeicons。保留可折叠状态线、350ms收束和简短announce；固定settleAfter=0，working只由运行状态控制，elapsed从服务端时间计算。源码将“最后一项以前都完成”用于顺序步骤，这不能表达并行DAG；本产品改用每个step的真实状态，不从数组位置打勾。reduce分支仍有弱呼吸，本项目减少动效时要关闭持续循环。状态文案用“正在核对反例/合成第2镜头”等事实，不展示所谓内部思维。

**#110 RefineFrame**：[源码](https://github.com/DavidHDev/react-bits/blob/4e0d091d3b1ea8b7aadef33716a8d99d164ae4ce/src/ts-tailwind/Micro/RefineFrame/RefineFrame.tsx)。React/Hugeicons，无motion依赖；queued/generating/refining/complete/error映射模糊、饱和度、尺寸与Canvas渐进预览，rAF在适当状态停止，并清理监听。只用于媒体预览，不模糊学生正在阅读的代码。外层role=img及子内容aria-hidden不适合放整套可交互编辑器；将预览、文字状态和真实重试按钮拆成可访问结构。跨域素材须走获授权资源，Canvas可读性与失败行为单独验证。

**#127 ScrubField**：[源码](https://github.com/DavidHDev/react-bits/blob/4e0d091d3b1ea8b7aadef33716a8d99d164ae4ce/src/ts-tailwind/Micro/ScrubField/ScrubField.tsx)。React+motion/react；保留越界阻力、松手回弹、Shift粗调/Alt细调、键盘上下/Home/End和Esc回退，使用lg尺寸达到44px。onChange只更新本地预览，onCommit才提交时间线版本，不能拖动每像素调用模型。源码blur用字符过滤再parseFloat，可能把`1e3`变为`13`；本产品采用明确数值语法和Number.isFinite校验，并显示非法输入。与外部revision冲突时保留草稿供比较；不能把受控值同步当作并发处理。

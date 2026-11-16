# 账户生命周期、真实学习统计与私有素材

本文描述当前代码实现的账户、阅读历史、统计、运维聚合与素材接口。接口字段以 `contracts/openapi.json` 为准；前端须先读取 `/api/v1/capabilities` 的 `enabled`，再显示对应能力入口。本文不把格式结构检查写成防病毒认证，也不把本地测试写成外部模型、媒体转码服务或部署容量验收。

## 1. 能力与责任

| 能力标识 | 已实现行为 | 主要入口 |
| --- | --- | --- |
| `ACCOUNT_LIFECYCLE` | 个人显示名、修订冲突处理、改密、会话列表与撤销、关闭账号 | `/me/profile`、`/me/password`、`/me/sessions`、`/me/close-account` |
| `READING_HISTORY` | 按工作空间持久化真实阅读开始及完成记录，页面重开可恢复 | `/workspaces/{id}/readings` |
| `LEARNING_ANALYTICS` | 本人真实记录的累计数、UTC 每日趋势、相邻等长时间窗比较 | `/me/analytics` |
| `OPERATIONS_METRICS` | 当前进程请求延时直方图与服务端真实队列积压 | `/admin/operations` |
| `PRIVATE_ASSETS` | 私有 PNG、JPEG、PCM WAV、MP3、MP4 上传、列表、读取、范围下载、撤回与作品失效传播 | `/workspaces/{id}/assets`、`/assets/{id}` |

所有路径均以 `/api/v1` 为前缀。私人资源的归属来自真实登录会话；客户端提交的用户名、UUID、文件名与 MIME 均不能替代服务端校验。当前服务不会提供未配置的音视频合成或通用转码能力。素材管理与作品源稿保存可用，不代表所有媒体导出格式都已可用。

## 2. 账户与身份安全

### 2.1 显示资料与并发修改

`GET /me/profile` 返回 `userId`、登录 `username`、`displayName`、当前角色、`revision` 与创建时间，并提供对应 `ETag`。显示名最初回退为用户名。更新使用：

```http
PUT /api/v1/me/profile
If-Match: "1"
X-CSRF-TOKEN: <当前令牌>
Content-Type: application/json

{"displayName":"学习者 小黄","revision":1}
```

服务端锁住当前账号，以数据库修订号进行比较后更新。冲突返回 `412 PRECONDITION_FAILED`，前端应保留编辑草稿，让用户重新读取并合并。显示名限制为 1～80 个 Java 字符单位，拒绝控制字符及方向覆盖字符；修改显示名不改变登录用户名。角色不属于可编辑资料，额外字段会被拒绝。

### 2.2 密码与会话

注册和新密码要求至少 12 个 Unicode 字符、最多 72 个 UTF-8 字节。后者对应当前密码散列实现的输入边界，不能仅按浏览器输入框的字符数判断。服务端不保存明文密码，也不在资料、会话、审计或异常响应中回传密码与散列。

`PUT /me/password` 接收 `currentPassword` 和 `newPassword`。当前密码错误返回 `400 CURRENT_PASSWORD_INVALID`，当前登录仍然保留。成功后返回 `204`，递增账号 `auth_epoch` 并撤销**全部会话，包括当前会话**；客户端须清除身份及私人工作空间状态并回到登录页。密码验证与更新之间再次核对旧散列，避免并发改密覆盖另一操作。

真实登录使用 `AccountPrincipal` 的固定账号 UUID 和 `auth_epoch`。成功登录会轮换 Servlet 会话 ID，并在 `account_session` 保存该 ID 的 SHA-256。认证后再入库前重新核对 epoch，防止已通过旧密码校验的迟到登录产生新会话。当前认证上下文按用户名定位身份时，也必须同时匹配固定 UUID 和 epoch，防止旧请求在关闭账号及同名再注册后被映射到新账号。

| 会话边界 | 当前策略 |
| --- | --- |
| Servlet 空闲超时 | 30 分钟 |
| 持久会话最长寿命 | 登录后 12 小时，访问不延长绝对期限 |
| 每人有效会话数 | 最多 10 个，新增时撤销较旧记录 |
| 最近活跃写入 | 最多每分钟更新一次，列表时间不是逐请求实时值 |
| 私人 API 校验 | 每次检查账号状态、会话撤销状态、epoch、空闲及绝对期限 |
| 失效或不可校验 | 失效返回 401；数据库不可用时返回 503，均不放行私人请求 |

`GET /me/sessions` 返回 `{items:[...]}`，每项仅包含公开会话 UUID、粗粒度浏览器/平台标签、创建时间、最近活跃时间、到期时间及 `current`。不返回 Cookie、原始 User-Agent 或 IP。

- `DELETE /me/sessions/{id}` 撤销本人指定会话。撤销当前项会退出当前登录；撤销别人的会话返回 404。
- `DELETE /me/sessions` 撤销其他会话，保留当前会话。
- `DELETE /auth/session` 同时撤销持久记录并销毁当前 Servlet 会话。

敏感变更沿用现有 CSRF 校验，不能只凭 Cookie 发起。持久会话表提供跨实例撤销判定，并未把完整 Servlet 登录上下文改为共享存储；多实例部署仍需明确会话黏性或会话复制方案。生产 HTTPS 环境应设置 `TYPEFORGE_SECURE_COOKIE=true`。

### 2.3 账号关闭与证据保留

`POST /me/close-account` 接收当前密码与**准确的当前用户名**：

```json
{"currentPassword":"当前密码","confirmationUsername":"当前用户名"}
```

服务端先验证密码，再在同一事务内锁住账号以及其全部工作空间。`AccountClosureParticipant` 由各领域实现撤回行为；身份模块只调用端口，不反向依赖导师、编排、编译或作品实现。任何参与者失败都会回滚整个关闭操作，不能出现账号关闭而部分归属验证未执行的中间状态。

关闭执行以下已授权动作：

1. 按已有导师和任务图取消语义撤销未完成工作，保护 epoch、lease 与用量核算；已提交给外部服务的请求仍按实际核算状态处理，不虚构零成本结果。
2. 撤回本人所有工作空间的素材，包含已归档空间；清除实际上传字节。清理可重新生成的导出文件缓存，并阻止未完成导出迟到写回。
3. 撤回私人记忆、归档活动工作空间、取消未完成编译；未完成源码和请求清单不再留作可执行工作。已完成的学习成绩、源稿及审计证据不会被改写。
4. 撤销所有会话、替换为不可知随机密码散列、递增认证 epoch，并将账号标记为停用。

返回 `{status:"CLOSED",closedAt,dataRetention}`。前端应显示服务端返回的 `dataRetention`，并结束当前登录。此操作是关闭和撤回私人访问，**不是全部历史数据物理擦除**。

普通学习账号释放原用户名，同名新注册得到全新 UUID，不继承旧数据。曾具有 `EDITOR` 或 `ADMIN` 权限的用户名保持保留，即使当前角色已被撤销；历史授权审计同样参与判定。这避免启动时的旧 operator bootstrap 配置把权限授予同名新用户。旧配置遇到已关闭的保留账号会跳过授权并记录明确日志；未知或其他不可用账号仍属于配置错误。

### 2.4 数据库限流

认证限流使用数据库原子计数，多个 API 实例共享同一额度；记录保存策略加身份摘要，不保存明文密码或原始请求体。窗口到期记录定期清理。

| 操作 | 固定窗口 | 当前限额 |
| --- | --- | --- |
| 登录，按 Servlet 网络对端 | 5 分钟 | 100 次 |
| 登录，按对端与规范化用户名组合 | 5 分钟 | 12 次 |
| 注册，按网络对端 | 1 小时 | 20 次 |
| 改密及关闭账号，按账号 | 5 分钟 | 合计 8 次 |

超额返回 429，并提供 `Retry-After`。服务端不信任任意客户端传来的 `X-Forwarded-For`；代理部署必须规划可信网络边界，避免所有用户意外共用一个代理对端额度。此策略不是全站流量或 DDoS 防护的容量承诺。

## 3. 阅读历史和真实统计

### 3.1 阅读历史

阅读提交保留真实 `learning_event`，并在同一事务写入 `workspace_reading` 索引。`GET /workspaces/{id}/readings` 的每个项目包含：

| 字段 | 含义 |
| --- | --- |
| `conceptId` | 当前工作空间发布版本内的知识点 |
| `lastReadAt` | 最近一次真实阅读开始或完成时间 |
| `lastCompletedAt` | 最近完成时间；尚未完成时为 `null` |
| `readEvents` | 该知识点在此空间的真实阅读事件数 |
| `completed` | 是否存在实际完成事件 |

阅读完成是行为记录，不自动生成独立掌握证据。跨工作空间不共享完成标记；越权查询返回 404。旧实例启动时，以每批 200 条的方式从既有事件回填索引，使用原始事件时间并验证空间归属和 release；不会补造事件或为无法关联的历史数据推测完成状态。

### 3.2 学习统计的口径

`GET /me/analytics?days=30` 支持 7～90 天。响应固定 `timezone:"UTC"`，按显式 UTC 时间边界分组；数据库或 JVM 的本地日期不会被冒充为 UTC。无记录的日期补零，无提交时正确率为 `null`。

- `totals` 是当前账号的累计真实数量：工作空间、已判分提交、完全正确提交、独立题族、待复习项、作品、阅读完成事件、实验和待投影事件。
- `daily` 是所选天数内每天的已判分提交、完全正确提交、阅读完成、实验及工作空间活动事件数。
- `comparison.current` 与 `comparison.previous` 比较连续、等长的时间窗。`includesPartialToday:true` 明确说明当前时间窗含未结束的今天。
- `correctRatePercent` 以全部已判分提交为分母，包含重复和辅助练习，不能当作独立掌握度。`activeDays` 表示至少出现一项受统计活动的天数，不表示学习持续时间。

统计不估计“学习了多少分钟”，也不会用随机趋势或固定演示数据填充空账号。多条聚合查询反映请求期间的真实记录，并不保证所有查询处于同一数据库快照；客户端刷新时可能看到投影短暂追赶，`pendingProjectionEvents` 用于表达该状态。

## 4. 私有素材

### 4.1 上传与容量

`POST /workspaces/{id}/assets` 直接接收 `multipart/form-data`，唯一文件字段为 `file`，同时要求会话、CSRF 和 `Idempotency-Key`。成功返回 201 和素材元数据。没有前端直传公共存储 URL，也不需要后续 `/assets/{id}/complete` 步骤。

| 约束 | 当前值 |
| --- | --- |
| 每个文件 | 8,388,608 字节，8 MiB |
| 每个账号总有效素材 | 默认 104,857,600 字节，100 MiB，跨全部工作空间计数 |
| 每个账号有效文件数 | 1,000 |
| 每个账号滚动 24 小时成功上传 | 500 次 |
| 每个 API 实例同时解析上传 | 2 个，额外请求返回 429 |
| Servlet multipart 整体请求 | 9 MiB |
| 图像/视频尺寸 | 每边最多 8,192 像素，合计最多 16,777,216 像素 |
| 音视频时长 | 1 毫秒～10 分钟 |

总额度可通过 `typeforge.assets.max-owner-bytes` 配置，允许 1 字节～1 GiB；每项 8 MiB 仍由固定字节上限约束。配额计算和写入持有账号、空间锁，避免并发或多实例绕过总量。撤回会释放原始素材字节额度，但不退回滚动上传次数。

原始文件名仅作为显示名，经路径与控制字符清理后按实际格式重设扩展名。存储定位符由服务器生成。`contentHash` 是实际字节 SHA-256；同一幂等键重试必须匹配空间、清理后的文件名、字节散列和 MIME，参数不同返回 409。若已经撤回该键对应的素材，重试仍返回其撤回元数据；重新上传须使用新的键。

### 4.2 格式检查的真实范围

| 格式 | 检查内容 |
| --- | --- |
| PNG | 签名、分块边界及 CRC、完整结束、受限尺寸、实际像素解码；拒绝 APNG |
| JPEG | 签名和结束、受限尺寸、实际像素解码 |
| PCM WAV | RIFF/WAVE 长度及分块、PCM 声道/位深/采样率一致性、数据对齐及推导时长 |
| MP3 | ID3 长度、连续 MPEG Layer III 帧边界和码率/采样率、帧数及推导时长 |
| MP4 | 受限 ISO-BMFF 分块层级、品牌、movie/data/video 轨迹、时基、时长及尺寸 |

浏览器声明的 MIME 与真实格式不符会被拒绝。SVG、HTML、可执行格式不接受为上传素材；生成的受控关系图 SVG 由独立作品渲染器生成，不能与任意上传 SVG 混用。

每个素材明确返回 `validation:"STRUCTURE_VERIFIED_NO_ANTIVIRUS"`。MP4 结构检查不等于对所有编解码器逐帧解码验收；MP3 时长来自帧数，可能包含编码填充；当前没有防病毒扫描服务、自动转码或外部媒体提供商验收。前端应显示“格式结构已检查”等准确说明。

### 4.3 列表、读取与撤回

素材元数据包含 `id`、`workspaceId`、`filename`、`mimeType`、`byteSize`、`contentHash`、`revision`、`status`、`createdAt`、可用时的 `width`/`height`/`durationMillis`、`downloadPath` 和 `validation`。

`GET /workspaces/{id}/assets` 默认每页 50 条，`limit` 支持 1～100，按创建时间和 UUID 降序。`nextCursor` 为服务端返回的素材 UUID；只能用于同一账号、同一工作空间。列表只包含有效素材，并返回账号级 `usage`：

```json
{"usedBytes":0,"limitBytes":104857600,"maxFileBytes":8388608,"activeFiles":0,"maxFiles":1000}
```

`GET /assets/{id}/download` 校验归属、状态、实际字节长度与 SHA-256，之后才返回文件。默认支持浏览器媒体预览；`?attachment=true` 请求下载。响应使用真实 MIME、`private, no-store`、`nosniff`、受限 CSP 与内容散列 ETag。支持一个标准字节范围，包括首尾区间、从位置到结尾和后缀区间；不支持多范围，非法范围返回 416 及实际总长度。

`DELETE /assets/{id}` 幂等返回 204。撤回后原始 BLOB 置空，`revision` 递增，状态为 `REVOKED`，下载返回 410，`downloadPath` 变为 `null`。别人的素材对查询、下载、上传引用与撤回均不可用。

### 4.4 作品来源与传播

嵌入素材的作品必须在 `inputRefs` 中携带当前 `ASSET` 引用，包括 UUID、修订号和散列。作品保存同时核对空间归属、有效状态、媒体类型与元素用途，图像元素还需有替代说明。不能把其他人的资源、已撤回版本或任意 URL 塞入 DSL。

`artifact_input_reference` 保存完整的直接和间接来源闭包。素材撤回会在同一事务内使其全部受影响作品修订变为 `STALE`，包括通过另一个作品继承的来源；不是等后台投影再撤回。当前来源被撤回的导出不得继续下载，正在渲染的旧任务也不能越过最终来源复检而写入成功状态。已由用户下载到本地的文件不会被服务端追回。

## 5. 运维观察范围

`GET /admin/operations` 每次使用数据库中的当前 `ADMIN` 角色验证权限；旧会话曾有角色不能代替现时授权。响应只包含聚合数，不包含用户名、请求正文、密码、私人源码或导师上下文。

`requestMetrics` 为当前进程启动以来的固定桶直方图，包含完成请求数、正在执行数、5xx 数、429 数、P50/P95 桶上界和最大延时。它不按任意 URL 创建标签，不因攻击者制造路径而无限增加维度。进程重启会重新计数；多实例数据需要运维侧汇总，不能把单进程直方图当集群 SLO 或负载测试结论。

`queues` 从真实数据库记录统计待投影事件、重试过多的事件、待处理导师请求、导出和编译。API 响应提供 `X-Request-ID`，异常 Problem 使用对应 `traceId`；服务端未知错误只记录跟踪号和异常类别，不输出私人请求体。

## 6. 迁移与验证

- `V007__identity_operations.sql` 为账号字段、持久会话、认证限流和阅读索引增加表与索引。
- `V011__private_assets.sql` 增加私有素材内容表，并扩展既有来源类型 CHECK 支持 `ASSET`。MySQL 通过实际元数据发现旧约束名；H2 使用独立适配器查找并替换真实约束，不假定两个数据库的自动生成名称相同。
- 既有 `V001`～`V006` 不回写。升级须完整执行当前迁移序列，不能单独跳过编译、导出或任务图依赖的表。

针对性回归入口是 `AccountLifecycleIntegrationTest` 与 `PrivateAssetsIntegrationTest`。测试覆盖真实 HTTP 会话、CSRF、资料 CAS、旧认证 epoch、关闭账号隔离、角色名称复用、并发限流、UTC 日期边界、阅读回填、真实媒体字节、错误格式、配额、范围请求、来源传播及关闭后字节清理。MP3/MP4 使用仓库内真实小型媒体样本。

测试命令与最终结果应记录在运行审查报告中。源代码检查、H2 集成测试、独立 MySQL 迁移和真实浏览器演练是不同证据，任何一项未执行都不能由其他项替代。

默认 Maven 执行使用独立 H2 测试库。真实 MySQL 回归通过 `scripts/verify_private_modules_mysql.py` 显式启用，配置加载复用既有数据库门禁，要求 `MYSQL_TEST_CONNECTION` 指向标明 `purpose=typeforge-isolated-verification` 的任务专用连接。只接受回环地址和保留的测试库名；不会把开发者任意环境变量中的数据库当作可清理测试库。JUnit 本身还会验证实际 JDBC 引擎，不能通过改报告标签伪装 MySQL。

```bash
MYSQL_TEST_CONNECTION=/path/to/private/connection.json python scripts/verify_private_modules_mysql.py --maven mvn
```

v0.5 历史第三轮数据库检查在真实 MySQL 8.4.13 上执行：当时11个迁移、79张表、123项模式检查通过；账户 14 例与素材 8 例全部通过，无失败、错误或跳过。脱敏结果由 `.work/mysql-verification.json` 和 `.work/mysql-account-assets-verification.json` 记录；最终交付使用最后一次全量运行审查汇总，不用本段替代后续代码变更的复测。

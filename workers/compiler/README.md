# Java 17 与 Python 3 隔离代码服务

在学习页进入「代码工作台」，选择 Java 或 Python，填写源码和标准输入，然后点「运行代码」。工作台显示程序输出、编译诊断或运行异常，并将记录保存在当前学习空间。Java 接受默认包的 `Main` 入口，也支持同学讲义中的非 public `Demo` 入口。Python 使用镜像内的 CPython 3 标准库，解释器版本随结果返回。

## 在本机启动完整课堂

在具备 Docker Engine、seccomp、CPU/内存/PID cgroup 限额、Python 3 和 Java 17 的 Linux 主机上，先按根 README 构建含前端的 JAR，再从仓库根目录运行：

```sh
python3 workers/compiler/start_workbench.py backend/target/typeforge-backend-0.6.0-SNAPSHOT.jar
```

打开 `http://127.0.0.1:8080`。启动器构建含 JDK 和 Python 的镜像，生成本次启动的认证令牌，确认隔离环境就绪后启动 demo 学习平台。默认使用当前目录下的 `.work/typeforge` 本地数据库。端口可用 `--port` 和 `--worker-port` 调整。Ctrl+C 停止应用和 worker。没有 Docker 时启动失败，学生源码始终不进入宿主解释器。面向其他用户提供服务时，将 broker 置于专用 Linux worker 主机，按下节单独配置。

## 部署

在专用 Linux worker 主机安装 Python 3.12+ 与 Docker Engine，然后从仓库根运行：

```sh
docker build -t typeforge-compiler:jdk17 workers/compiler
export TYPEFORGE_COMPILER_TOKEN="$(python -c 'import secrets; print(secrets.token_urlsafe(48))')"
export TYPEFORGE_COMPILER_IMAGE=typeforge-compiler:jdk17
export TYPEFORGE_EXECUTION_ENABLED=true
python workers/compiler/server.py
```

broker 默认只监听 `127.0.0.1:8091`。启动时把本地镜像标签解析为不可变 `sha256` image ID，后续任务固定使用此 ID，并用同一组隔离参数检查 `javac 17` 是否可启动。正式部署应固定和记录基础镜像 digest、定期更新镜像与宿主补丁。镜像下载/构建由运维显式执行，任务请求不能拉取镜像或改变版本。

API 服务端显式配置：

```properties
typeforge.compiler.enabled=true
typeforge.compiler.execution-enabled=true
typeforge.compiler.worker-url=http://127.0.0.1:8091/v1/compile
typeforge.compiler.worker-token=<与broker相同的随机token>
```

跨主机使用 HTTPS 受控反向代理，设置 `TYPEFORGE_COMPILER_BIND` 为受保护的内网监听地址；不要公开 broker 或 Docker socket。broker 的 Docker 权限只属于这个专用信任边界，不能授予 API 服务、用户源码或生成作品。所有容器均不包含 API 密钥、数据库配置或用户会话。

隔离运行开关在 broker 与 API 两侧均需启用。保留原有仅编译的部署时，两侧执行开关保持关闭。只有 API 的环境令牌使用 `TYPEFORGE_COMPILER_WORKER_TOKEN`，应与 broker 的 `TYPEFORGE_COMPILER_TOKEN` 相同。浏览器不能选择 worker 地址、镜像、工具链路径、shell 或自定义启动参数。

## 固定执行限制

| 项目 | 实现 |
|---|---|
| 模式 | `COMPILE_ONLY` 执行严格 Java 编译和题目检查；`COMPILE_AND_RUN` 执行一个 Java/Python 学习程序 |
| 容器 | 每次检查或运行使用新容器，非 root UID 65534、无网络、只读根文件系统、全部 capability 丢弃、no-new-privileges |
| 工作目录 | 原始源码只读挂载到 `/input`；输出在 64 MiB tmpfs `/tmp`；没有宿主用户目录挂载 |
| 限额 | 192 MiB内存且无额外swap、0.5 CPU、64 PIDs、128文件描述符、16 MiB单文件 |
| 编译 | `-proc:none --release 17 -Xlint:all -Werror -implicit:none`；固定空类路径，禁用注解处理 |
| 时间 | 编译检查每项6秒、整组38秒；自由运行最多12秒（含启动和编译），容器内另设18秒硬截止；API默认45秒HTTP期限及额外10秒持久租约 |
| 容量 | broker至多2任务、16连接；API默认每用户4个等待/执行作业、全局256、每日200 |
| 输入/输出 | 源码32768 UTF-8字节、最多8个隐藏检查每个8192字节；编译输出64 KiB、诊断100条。自由运行标准输入8 KiB、stdout 与 stderr 合并16 KiB，达到输出上限终止 |
| 清理 | 超时/取消杀掉Docker客户端进程组并强制移除容器；临时目录作用域退出清理 |

原有只编译链的[历史 Docker 验收](https://github.com/hxjyaohaohaode/java-xuexi/actions/runs/43743602623/job/132901926094)覆盖八项。新增 Java/Python 执行的证据需查看当前提交的 Actions 中 `Isolated Java and Python code worker` 结果和浏览器产物 `program-runtime.json`。

## 学习链路

编译/运行共用的队列：`POST /api/v1/compile-jobs` 固定工作空间/课程、源码hash与幂等键 → 持久队列 → 独立worker → 当前租约核验 → 公共诊断。状态可轮询、取消；取消后迟到结果不能恢复。进程中断的过期租约显式变为 `INFRA_ERROR`，等待超过10分钟变为 `TIMED_OUT`；不会把基础设施故障当作学生答错。

代码题：`POST /api/v1/attempts` 的 `answer.kind=CODE` → `PENDING/GRADING` → 固定正向/反向编译检查 → 事务outbox → `GRADED` 或 `FAILED`。只有完整验证题目所规定类型契约后才评分。公开响应不含隐藏源码或隐藏诊断；公共编译成功本身不代表通过题目。该判分器不检验运行时行为，题目不得声称它能验证算法输出或性能。

编辑工作台的 `SIGNATURE/REFACTOR/FILL` 题使用以下 `answerSpec`（例子说明结构，不是自动发布内容）：

```json
{
  "options": [],
  "optionIds": [],
  "explanation": "解释具体约束与正反例。",
  "misconceptions": ["GENERIC_RETURN_TYPE"],
  "hints": ["先考虑类型参数关联。", "调用前后T应一致。", "检查一个应通过与一个应失败的赋值。"],
  "compileChecks": [
    {"source": "static void verify() { Integer x = Main.first(java.util.List.of(1)); }", "expectedCompiled": true, "expectedDiagnosticCodes": []},
    {"source": "static void verify() { String x = Main.first(java.util.List.of(1)); }", "expectedCompiled": false, "expectedDiagnosticCodes": ["compiler.err.prob.found.req"]}
  ]
}
```

检查 `source` 是由固定 `Probe_checkN` 类包装的类体声明，不能指定文件路径、参数或shell命令。负例只有在错误来自对应检查文件、且实际错误码全部落入审核允许集合时才通过；重复类、恶意缺省编译失败不应被当作有效负例。题目作者须与第二位审核者核对正确解、错解、边界解，知识来源/发布仍走原本双人流程。

提示使用有独立持久回执。领取提示后，同题家族的后续作答标记为辅助练习；不会把提示后的正确结果刷成独立掌握证据。类型实验和错题中的编译动作只形成探索事实。

## 检查命令

```sh
python -m unittest discover -s workers/compiler -p 'test_*.py'
mvn -f backend/pom.xml -Dtest=CompileQueueTest,CompileHttpProtocolTest,CodeRubricSemanticsTest test
```

Python测试检查协议、字节限制、路径拒绝和Docker命令限制；Java协议测试使用本地HTTP夹具，不声称夹具具有真实沙箱隔离；语义测试只编译仓库内固定源码。容器验收另外运行固定任务与故障案例，并记录实际镜像digest与宿主环境。

## 真实 Docker CI 验收

在具备 Docker Engine 的临时 Linux CI runner（例如 GitHub Actions 的 Ubuntu runner）执行：

```sh
docker build -t typeforge-compiler:jdk17 workers/compiler
python3 workers/compiler/verify_docker.py \
  --image typeforge-compiler:jdk17 \
  --report .work/compiler-docker-verification.json
```

镜像构建放在单独步骤；验收脚本本身不拉取镜像。脚本启动原始 `server.py`，产生临时随机凭据并把源码目录限定到本次独占目录，随后验证：合法与非法泛型、隐藏正反编译契约、raw 类型严格 lint、UTF-8/请求容量与路径拒绝、运行中容器配置和取消、截止时间、输出截断，以及课程原始 16 个 Demo 的编译、警告和输出、无 main 入口、Java Demo 与 Python 的标准输入/输出、Java 编译错误、Python 语法错误、运行异常、超时和输出容量限制。截止时间通过暂停正在编译的固定容器触发，避免把某台机器上编译得慢当作稳定的超时测试。输出检查使用固定长类型名产生超过 64 KiB 的诊断，必须观测到 `RESOURCE_LIMIT` 和 `outputTruncated=true`。

脚本读取真实 Docker 容器配置，核验非 root、只读根、无网络、capability、no-new-privileges、源文件只读挂载、内存/CPU/PIDs 限制以及禁用注解处理；同时要求 Docker 报告宿主支持对应资源限制。执行场景采用仓库内固定教学样例与固定输入，在受限容器内启动真正的 JDK 和 Python。配置检查与真实编译/故障回收不等同于通用容器逃逸认证。

总执行预算为 **210 秒**，其中最多 195 秒用于检查，保留最多 15 秒停止 broker 并强制清理本次容器。清理只识别挂载到本次独占源码目录的容器，不能删除同宿主的其他任务。每组检查后和退出前都核对容器数量为零；清理无法确认、Docker 缺失、镜像缺失或宿主资源限制不支持都会以非零退出并写 `FAILED`，不会跳过后标成成功。CI 应保留失败退出码，并在 `always()` 的报告上传步骤保存 JSON；使用临时 runner，让整个作业被平台终止时也能回收宿主资源。

JSON 仅包含测试名称、结果、耗时、实际镜像 ID、JDK/Docker 版本和隔离配置摘要，不包含随机凭据、源码、HTTP 正文或宿主临时路径。缺少 Docker 时门禁明确失败；只有对应 CI 报告的 `status=PASSED` 且 `cleanup.status=COMPLETED` 才能作为那次容器环境的通过证据。

## 参数依据

- [Oracle Java 17 javac](https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html)：编译版本、注解处理、lint及隐式类编译参数。
- [Docker container run](https://docs.docker.com/reference/cli/docker/container/run/)：只读文件系统、用户、能力、进程及资源参数。
- [Docker资源限制](https://docs.docker.com/engine/containers/resource_constraints/)：实际限额受宿主内核和cgroup支持影响，需部署实测。
- [Docker none network](https://docs.docker.com/engine/network/drivers/none/)：容器仅保留本地loopback，不连接应用网络。
- [Docker pause](https://docs.docker.com/reference/cli/docker/container/pause/)：冻结容器中的进程，用于固定的截止时间故障注入。
- [Docker Engine API](https://docs.docker.com/reference/api/engine/version/v1.44/)：真实容器的资源限额和宿主能力元数据。

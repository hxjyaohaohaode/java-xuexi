# 在 DeepSeek Harness 里继续这一节课

学生从 TypeForge 当前课堂的「导出本节课堂」中选择「导出 Harness 课堂资料」。浏览器生成 `typeforge-classroom.json`，包含这一节的标题、最多四段带来源和版本的课程原文，以及本空间当时的前三项学习建议。建议可能透露该知识点需要复习。打开文件可以查看整个分享范围。

## 首次安装与使用

从本仓库根目录安装到自己的 Harness Web 配置。下面以 PowerShell 为例，先安装本地插件的锁定依赖，再把导出文件的绝对路径给当前进程。插件读取的是当前用户选择的那一个文件。

```powershell
npm ci --prefix integrations/dsh-classroom
dsh plugin --profile web add ./integrations/dsh-classroom
$env:TYPEFORGE_CLASSROOM_EXPORT = (Resolve-Path "$HOME/Downloads/typeforge-classroom.json").Path
dsh --profile web
```

macOS 或 Linux 的对应环境设置为 `export TYPEFORGE_CLASSROOM_EXPORT="$HOME/Downloads/typeforge-classroom.json"`。在 Harness 中询问「读取我的 TypeForge 课堂资料，先问我一个关于本节规则的问题」，或「根据我的课堂导出解释下一步建议」。模型工具 `typeforge_lesson` 返回本节课程证据，`typeforge_next_step` 返回建议、理由及导出时间。两个工具都没有模型可设置的路径或网络参数。覆盖原导出文件后，下一次调用会重新读取。更改所选路径后重启 Harness。

导出后的副本独立于站内草稿；新学习记录需要再次导出。工具经过当前 Harness 宿主的可见性和模型配置发送内容。它们没有 TypeForge 凭据，也没有写入成绩、发起运行、上传课堂或改写文件的入口。

## 运行核验

`npm test --prefix integrations/dsh-classroom` 使用真实的 Cordis Context、SessionProjection、SystemPrompt 和 Tools 服务，注册、调用、渲染并卸载这两个工具，另测无效、超大及非普通文件。`@deepseek-ai/dsh-tools` 的开发测试版本固定在锁文件。插件导出 `name`、`inject` 和 `apply`，用 `ctx.effect` 托管 `ctx.tools.register(defineTool(...))` 的生命周期。包里的 `cordis.patch.yml` 是可直接安装的 DSH bundle。

[DeepSeek Harness 工具契约](https://github.com/deepseek-ai/deepseek-harness/blob/4b399c39a6d1479cd84ca591b4d7c88e24d38a96/packages/core/tools/src/schema.ts) · [官方本地 bundle 安装文档](https://github.com/deepseek-ai/deepseek-harness/blob/4b399c39a6d1479cd84ca591b4d7c88e24d38a96/docs/user/develop/basic/publish.zh.md) · [OpenMAIC 的 Harness 插件](https://github.com/THU-MAIC/dsh-openmaic)

本次用 `@deepseek-ai/dsh-tools` 的 `0.2.2-rc.5` 和 `0.2.3-alpha.2` 分别完成了上述注册、调用和卸载测试。官方 CLI `0.2.3-alpha.2` 在新的本地 profile 安装成功。另从真实登录的 Chromium 课堂下载了 G03「泛型接口」资料，通过宿主工具读回 1 段课程依据和 3 条路径建议（练习、阅读、阅读），并核对两个工具返回的课程版本和导出时间一致。

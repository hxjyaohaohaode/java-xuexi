# 前端设计资产
本阶段没有实现页面、安装 React Bits 或生成前端锁文件。详见 [前端规范](../docs/07-frontend.md)。
`reference-ledger.csv` 逐条覆盖 209 条参考，记录业务匹配、取舍和证据边界。
`design-tokens.json` 为视觉规格，接入时生成 CSS variables；必须测试对比度、键盘/触屏与减少动效。
后续建议目录：`src/app`（路由/Providers）、`src/features/{learn,labs,practice,tutor,progress,admin}`、`src/shared/{ui,api,theme}`。
组件状态由业务 DTO 驱动；未实现能力不放可点击假入口。OpenAPI 契约先生成 TypeScript DTO，再接真实 API。

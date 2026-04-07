---
name: spec-commit
description: 提交信息规范, 用于将要生成 commit message 时使用
---

使用中文生成符合 [Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/) 规范的提交信息

**格式模板**
```
<类型>: <简短描述: 不超过 50 字符>

<详细说明:可选 每行不超过 72 字符, 最多不超过 7 行>
```

**类型说明**
- feat：新功能（feature）
- fix：修补bug
- docs：文档（documentation）
- style： 格式（不影响代码运行的变动）
- refactor：重构（即不是新增功能，也不是修改bug的代码变动）
- test：增加测试
- build：构建过程或辅助工具的变动
- other：其他

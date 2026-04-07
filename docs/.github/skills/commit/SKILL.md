---
name: commit
description: 执行`git commit`操作; 当用户需要提交文件变更时使用
---

## 执行流程
1. 如果暂存区没有文件, 将所有变更文件都放入暂存区, 如果暂存区已经有问题, 则跳过这一步
2. 使用Task工具启动子代理执行'pre-commit-review'技能审查暂存区的文件
3. 展示审查报告
   - 如果没有问题直接跳到第4步, 生成提交信息, 并展示
   - 如果存在问题, 则使用 `question` 工具询问用户处理意见, 等待用户答复后继续 
4. 根据'提交信息规范'自动生成提交信息 (commit message)
   - 如果存在用户确定不处理的审查问题, 需要追加到提交信息结尾
5. **展示提交信息**
6. 使用 `question` 工具等待用户确认
   - 选项 "确认提交"
   - 选项 "确认并推送"
7. 执行提交

## 注意事项

- 不用参考历史提交信息
- 如未使用 `question` 工具, 需为每个选项编号, 用户回复数字即可操作

## 参考清单文件

| 描述 | 位置 |
|------|---------|
| 提交信息规范 | [@.claude/skills/commit/references/commit-message.md](references/commit-message.md) |
| pre-commit-review | [@.claude/skills/pre-commit-review/SKILL.md](../pre-commit-review/SKILL.md) |
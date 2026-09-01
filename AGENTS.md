# 代理须知

Cursor 侧的保证机制是 `.cursor/rules/upstream-review.mdc`（`alwaysApply: true`）。本文件给其他工具看，不作为 Cursor 的注入保证。

## 上游增量检查

检查内核 / Android / 桌面的上游安全修复、崩溃、内存泄漏或重要补丁时：

1. MUST 先读 [`scripts/upstream-review.json`](scripts/upstream-review.json)。
2. MUST 从各组件的 `reviewedThrough` 继续：`git log <reviewedThrough>..<upstreamRemote>/<upstreamRef>`。
3. MUST NOT 从 fork 起点或 `HEAD..upstream` 的全部历史重扫。
4. 扫完后 MUST 更新该 JSON：`reviewedThrough` 改为当时的上游 tip；摘入写入 `ported`，明确跳过写入 `skipped`。

本仓不跟上游整树 rebase。只摘冲突面小的补丁。内核改动提交到自有 mihomo 并发布 `Prerelease-Alpha`，再更新父仓 gitlink。桌面 sidecar 跟 `version.txt`，不要再钉 `mihomo.pin.json`。

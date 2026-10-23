# 验证维护手册

> **本文件属于项目维护。** 旧 `04-三-验证.md`、`08-七-门禁与关账命令.md`、`10-九-实测失败形态.md`
> 保留为历史事故与详细样本；当前命令与证据规则以本文件为准。

## 一、第一入口

```bash
tools/gate.sh pre
tools/gate.sh focused <module> [args...]
tools/gate.sh guards
tools/gate.sh full
tools/gate.sh docs
tools/gate.sh selftest
```

- `pre` 会在服务/锁/工作树检查之后顺带跑 `check-docs.py` 与 `skills-lint.py`；文档索引坏了会在开工前拦住。
- `full` = 全仓 `clean verify`（Spotless → Checkstyle → SpotBugs → Surefire + 前端门禁 + shaded jar）。只在阶段边界/关账或任务明确要求时跑。
- `focused` 默认带 `-am`；结论只能写 `FOCUSED TEST PASSED (NOT a full gate)`。
- 禁止用 `-Dskip*`、`-q`、`-N`、`-pl` 不带 `-am` 等方式制造假绿；`gate.sh` 会拒绝削弱门禁的参数/环境。
- 服务在跑时只跑 `test/compile`，不要 `package`/`verify` 覆盖运行中 jar；需要打包先停服务或打快照。

## 二、Maven 并发与工作树

- worktree 规范落点：`<仓库根>/worktree/<name>/`；不用 `.claude/worktrees/` 作为新树目录。
- 同一条工作树一次只允许一个 Maven；`tools/mvn-lock.sh` 负责本树串行。
- 每棵工作树使用自己的 `<树根>/.m2-repo`；跨树可并行，跨树生效的脚本/纪律必须先提交。
- 本仓门禁只检查落在本仓的 Maven/服务进程；隔壁项目不参与本仓门禁（用户 2026-10-23）。
- 不要在 Maven 运行窗口里改 Java；怀疑增量产物陈旧时删对应 `target/classes` / `target/test-classes` 重编。

## 三、证据规则：不许把“没跑”写成“通过”

- PASS = 命令实际执行 + 退出码符合预期 + 关键输出/报告核对。
- `rc=0` 不充分；`-q` 会吞 `Tests run:`，必须看 Surefire 报告或 Maven 汇总。
- 管道命令取 `${PIPESTATUS[0]}`，不能拿 `tail` 的退出码。
- 每轮门禁尽量留 run-bound 证据：source SHA、开始时间、命令、输出、报告路径；旧报告先移走/用 run 专属目录。
- mtime 只辅助；以本轮 Surefire XML + Maven `Tests run:` 交叉核对为准；源码测试类数 ≠ 用例数。
- SpotBugs 结论前先确证编译产物是新的；`spotless` 一红时，该项在该模块可能根本没跑，不能把“没输出”当 0 违规。
- 报告必须有「未做 / 未验证 / blocker」一节；没跑写 `NOT_RUN`，证据不足写 `EVIDENCE_INCOMPLETE`。

## 四、测试节奏

- 用户 2026-09-26/27 裁定：写代码阶段只做编译；测试与变异自证留到最后统一做。
- 测试按验收判据写，必须含负向用例：权限不能放大、拒绝语义成立、数据往返成立、绕过路径确实被挡住。
- 关键项变异自证：改坏 ⇒ 当场红 ⇒ 还原 ⇒ md5/字节自证；做不到如实写“等价存活”。
- 变异窗口独占 checkout 或副本；不得与全量 verify 并发。
- 旧架构旧测试；被改架构相关的旧测试可删/迁移，但新测试必须按新判据补，不能为变绿弱化断言。

## 五、多 tick 与真实世界

- 用户 2026-10-15 裁定：多 tick 世界模拟不进 JUnit；用独立 `tools/multitick-sim.sh` 或真实 world。
- 门禁内只留短窗口 smoke；禁止用 `@Disabled`/`@Ignore` 藏违规多 tick 用例。
- 用户 2026-10-23 裁定：真实世界长跑测试移出程序化门禁；行为验证走真实 world + 日志。
- 真模型优先：功能验收默认真进程 + 真模型 + HTTP/日志/事件库互证；假 LLM 只用于真模型造不出的确定性场景。

## 六、现有门禁基线（历史，不冒充本轮）

最近一次功能全量门禁见 `STATUS.md` 与
`docs/superpowers/status/2026-10-23-agentlib-side-integration-result.md`（历史快照）：
3751 tests / 0 failures / 0 errors / 7 skipped；前端 412/412；shaded jar 29,451,438 B。

文档系统重写只跑 `tools/gate.sh docs` 和脚本 selftest，不跑 Maven；不要在本 STATUS 记录里冒充 full gate。

## 七、旧长文入口

- 验证事故/假绿样本：`04-三-验证.md`
- 全仓门禁历史命令：`08-七-门禁与关账命令.md`
- 判别力假货/工具假阴性：`10-九-实测失败形态.md`
- 产物与 jar：`03-二-产物与jar.md`

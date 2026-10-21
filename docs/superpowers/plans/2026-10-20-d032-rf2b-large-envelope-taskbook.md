# D-032 RF2b 任务书：大状态快照信封读取上限

> **父计划：** `docs/superpowers/plans/2026-10-20-d032-rural-360-fix-plan.md`
> **状态：** 可派实现；本批只写生产代码到编译过，不写/不跑测试。
> **工作树：** `/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`
> **性质：** 360 tick 复跑暴露的 Core 大规模状态阻塞；不是玩法/数值规则变更。

## 1. 现场

用 RF1+RF2 当前字节码复跑 360 tick，在 tick296 失败：

```text
java.lang.IllegalArgumentException: 信封不是合法 JSON
Caused by: com.fasterxml.jackson.core.exc.StreamConstraintsException:
String value length (20013205) exceeds the maximum allowed
(20000000, from StreamReadConstraints.getMaxStringLength())
at io.mosire.simos.core.store.Envelope.decode(Envelope.java:102)
at io.mosire.simos.core.store.Replay.decodeCheckpoint(Replay.java:235)
```

状态本身是合法 JSON；问题只是 `Envelope` 的默认 Jackson `StreamReadConstraints` 对单个模块字符串
上限 20,000,000 字节。实测 `checkpoints/main/296.json` 为 22,446,986 字节，economy 模块载荷字符串
已约 20,013,205 字节；tick360 预计约 27MB。当前 32hex/290 户世界在 360 tick 内就会超过默认上限。

## 2. 目标

让 Core 能读取合法的、更大的模块载荷字符串，同时保持：

- 仍由 `Envelope.decode` fail-closed：非法 JSON / 顶层不是对象 / 字段类型错误仍拒绝；
- 不做任何模块反序列化（Core 仍只把模块载荷当文本）；
- 不改变存档格式、域状态、ChangeSet、Codec；
- 不静默截断字符串，不把超限当“空状态”；
- 保留明确的有限上限，避免无界内存读取（建议 64 MiB / 可解释常量）。

## 3. 约束

- 只改 `simos-core/src/main/java/io/mosire/simos/core/store/Envelope.java`（或 Core 内同一职责的最小文件）。
- 不新增持久组件，不改 `WorldChangeSet`/模块 codec。
- 不修改测试文件；不跑 test/verify/门禁/GUI；不 commit。
- 不把“资产占用 grant 历史增长”与“信封读取上限”混成一个修复：本批只解决读取上限。
  资产占用历史为何增长、是否该 pruning，另开责任区记录；本批不得顺手改业务状态。

## 4. 建议实现

- 在 `Envelope` 内用显式 `JsonFactory`/`ObjectMapper` 配置
  `StreamReadConstraints.builder().maxStringLength(64L * 1024L * 1024L).build()`；
- 常量具名，例如 `MAX_MODULE_PAYLOAD_STRING_BYTES = 64L * 1024L * 1024L`；
- 注释解释：模块载荷是完整状态文本，32hex/360 已经 >20MB；64MiB 是有限安全上限，不是无界；
- 不改变 `encode` 的“原样内嵌字符串”语义；
- 失败路径仍抛 `IllegalArgumentException("信封不是合法 JSON", e)`，不得降级成空表。

## 5. 编译与交账

```bash
tools/mvn-lock.sh -q -DskipTests compile -pl simos-core -am
```

真实 rc 必须为 0。报告：改动文件、常量/上限值、编译 rc、行为变化、未验证项、是否发现其它超限点。
本批不跑 360；360 复跑由控制方在下一轮执行。

## 6. 用户原话

```text
需求被允许挂价了吗？家户提的需求，面对市场不管多贵都会直接接受的；其他没啥问题，记录一下你的修复方案，开始吧
```

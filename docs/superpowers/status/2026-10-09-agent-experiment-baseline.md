# 2026-10-09 子 Agent 新架构实验 · 基线

> 本文件是 **用户 2026-10-09 裁定“试新派单原则”** 的回退点与实验台账。
> 规则见 `AGENTS.md §一.10`；实验期覆盖 §一.5/§一.8 的派单细节，但不取消五条铁律、模块边界与用户设计权威。
> 本文件随 `AGENTS.md §一.10` 同一提交落盘；随后打 tag `agent-experiment-baseline-20261009` 并推送。

## 0. 基线状态

- 仓库：`/home/cna/SimulatorMosire`
- 分支：`main`
- 实验前 HEAD：`ebb139c4`（运费口径更正报告）
- 工作树：实验前干净；本文件与 `AGENTS.md` 是本实验的第一笔提交
- 项目版本：`0.1.0-SNAPSHOT`
- 运行时版本（只记录，不在本实验改动）：
  - economy production-runtime = `seven-hex-v2`
  - class-first = `aggregate-v1`
  - actor = `actor-v1`

## 1. 实验前的项目现实（只记录，不重新跑测试）

- S1~S4 / S3a：Social API、家户状态、Unit/Gov 家户容纳、实时人口读口、SocialLog/UnitLog 已落地。
- 已知经济红字（handoff 2026-10-09 §2）：
  1. `AssetShareBook` 质押越界（12hex / 3650 / trace）；
  2. `SevenHexFullChain3650Test` 的 `merchantFee=0`；
  3. `SevenHexNatural3650Test` 的 `ExpectedProfit` 负读数。
- 测试基线（历史记录，非本实验实测）：social 228、unit 472、app 842 通过 / 4 errors / 5 skipped；前端 412/412。
- 门禁债：部分 spotless；`simos-social-api` 4 个 `EI_EXPOSE_REP`。
- 经济路线：用户已裁定 `production-runtime` 与 `production-runtime-government` 是同一体系，政府为固定组成；
  `class-first` 目标状态不存在，需删干净。
- Log 覆盖：仅 economy/social/unit 有门面；其余模块多数为零命中。

## 2. 实验派单原则（摘要，完整版在 AGENTS §一.10）

- 子 Agent 只分两类：**调查子 Agent**（只读，必须有方向与问题清单）与 **办事子 Agent**（目标 + 边界 + 大致文件范围）。
- 办事 Agent：只给目标框架、当前瑕疵对照、本 Agent 负责的部分、不可碰机制与大致文件范围；
  具体改法由其自主决定；`-DskipTests compile` 过即继续派下一部分；不做逐段验收，最终按 AGENTS 总验收。
- 测试：老架构老测试、新架构新测试；被改架构相关的旧测试直接删/迁移；新测试由后续测试 Agent 按新架构补。
- 一次只允许一个写代码办事 Agent；多 Agent 只在只读调查且互不共享写目标时并行。
- 办事 Agent 不 commit；控制方审、总验收、提交。

## 3. 本实验的第一个目标

按最新优先级，第一个办事目标是：

> **唯一经济路线：把 `class-first` 从生产路径中删干净，使 `production-runtime`（政府内置）成为唯一路线。**

本目标允许 Agent 自主判断删除顺序与实现方式；不可碰 `simos-util`；不得破坏五条铁律与模块边界；
测试/前端/文档迁移由后续部分处理，本部分只要求生产代码 `-DskipTests compile` 通过。

## 4. 回退点

实验前状态可用 tag `agent-experiment-baseline-20261009` 取回；若实验造成不可接受破坏，直接回退到该 tag。

## 5. 实验期如实记录

- 未跑测试/未验收的事项，一律不写成“通过”；
- 每个办事 Agent 的报告要保留：改动文件、编译结果、会改变数值行为的清单、受影响硬编码字面量、
  与目标框架不一致处及原因、未验证项。

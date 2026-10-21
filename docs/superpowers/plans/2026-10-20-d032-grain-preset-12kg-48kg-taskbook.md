# D-032 粮食生产预设 12 kg/亩种子 · 48 kg/亩毛产 · 实现任务书（2026-10-20）

> **状态：可派单。** 用户 2026-10-20 给出的历史农业基准，本轮只做**生产方式数值预设的最小改动**：
> 每亩播种 **12 kg**、每亩年毛产 **48 kg**。不改 120 天生产周期、不改 Social 口粮、不改休耕/损耗/地租、不改结构。
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`；只动本树。

## 1. 父设计

- 用户历史设计：种子 12 kg/亩、年毛产 48 kg/亩、成年重体力劳动者 0.8 kg/天；本轮只取前两项。
- 当前代码：
  - `simos-app/src/main/java/io/mosire/simos/app/world/WorldBalanceSeeder.java` 的 `GRAIN_OUTPUT_PER_MU = 34L`
  - 同文件 `SEED_MILLI_PER_MU = 8_000L`
- 当前单位：`1 粮 = 1000 毫粮`；所以：
  - 新种子 = **12,000 毫粮/亩**
  - 新毛产 = **48 粮/亩/周期**（本轮仍按现有生产周期算，不把 120 天改成 360 天）

## 2. 唯一改动

只改 `WorldBalanceSeeder` 的两个生产预设常量：

```text
GRAIN_OUTPUT_PER_MU: 34L -> 48L
SEED_MILLI_PER_MU: 8_000L -> 12_000L
```

同步更新这两个常量附近的中文注释，说明这是 2026-10-20 用户历史农业基准的“简单预设改动”；不要改其它任何生产/社会/周期/休耕/损耗/地租参数。

允许修改：

```text
simos-app/src/main/java/io/mosire/simos/app/world/WorldBalanceSeeder.java
.superpowers/sdd/2026-10-20-d032-grain-preset-12-48/
```

禁止：其它生产代码、Social、Seeder 结构、POM/XML、测试、tools、docs；当前未提交的 RA4a 半成品不要接手/还原/格式化。

## 3. 编译

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
tools/mvn-lock.sh -q -DskipTests compile -pl simos-app -am
```

只写生产代码；不写/不改/不跑测试；不 commit/push；不启动 GUI/服务。

## 4. 交账

写 `.superpowers/sdd/2026-10-20-d032-grain-preset-12-48/impl-ledger.md`，报告：

- 实际改动；
- 新旧值/单位；
- 真实编译 rc；
- 会改变数值行为的清单（应仅这两项）；
- 未做/未验证；
- blocker。

## 5. 用户原话/设计依据（逐字）

```text
普通小麦地按每亩播种12公斤、年收获48公斤，成年农业劳动者每日消费0.8公斤干谷物作为基准；再独立模拟休耕、损耗和地租。
简单修改生产方式的数值预设，再跑一轮360tick
```

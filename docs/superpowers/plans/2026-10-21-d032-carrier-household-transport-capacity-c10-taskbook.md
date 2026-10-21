# D-032 C10 责任区任务书：统一 test owner（2026-10-21）

> **状态：可派单的测试/验证任务书；测试 owner 可改/写测试与 fixture，不改 main 生产代码。**
> **工作树：** `/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`
> **父设计/计划：** `2026-10-21-d032-carrier-household-transport-capacity-design.md` / `...-plan.md`
> **上游：** C1–C9 已完成且各自 compile 绿；本批没有 commit/push，主树未动。
> **本任务的目标是把“编译绿”升级为真实行为证据；不得把没跑写成通过。**

---

## 0. 验收纪律

- 测试 owner 只改 `src/test/**`、测试 fixture、测试资源；不改 main 生产代码。
- 发现生产代码缺陷 ⇒ 记录 `file:line`、复现命令/期望/实际，交控制方决定是否另派 writer；不得为迁就实现改断言。
- 先按用户原话/设计验收判据写测试，不按代码反推。
- 旧架构测试直接删/迁移；新架构新测试。
- 所有命令必须记真实 rc 与证据路径；`-q` 不吞 surefire 报告，必须核对报告 mtime/数字。
- 不 commit/push；不碰主树。

---

## 1. 先做基线

```bash
git status --short
tools/mvn-lock.sh -q -DskipTests compile -pl simos-app -am
tools/mvn-lock.sh -q test -pl simos-app -am
```

记录：
- 编译 rc；
- 测试总数/失败/错误/跳过；
- 第一轮失败清单（按测试类归组）。

若测试无法编译，先修测试源码，不碰 main。

---

## 2. 必须迁移/新增的测试面

按 C1–C9 账本失效清单，至少覆盖：

### C1/C2
- ownershipClass 派生、位置访问门；
- merge key `(hex,cultureKey,ownershipClass)`；
- 无 mode/currentPosition target；
- 0 人口 OWNER/PROLETARIAN 壳；
- 同 homeHex 合户批次相加。

### C3/C4/C8
- S7-B LAND 向上、0 人仍转地、OWNER 壳；
- HouseholdFlowBook 只迁人口，钱/货/债/LAND/资产/经验不变；
- 马匹取得读口、LAND+马匹最大余数拆分、先 LAND 后马匹；
- 0 share 仍转对应资产；不建 AssetKind.HORSE/OwnershipStake。

### C5/C6
- transport_horse 词表/OwnershipCapitalVocabulary；
- durable commodity capacity/occupancy；
- 服务产出 non-inventory，不进 HouseholdGoods/收入/角色分配；
- `TransportCapacityBatch` 每 tick 累加、S7-END 清当前轮、上一轮不可见；
- 合户批次相加。

### C7
- `TransportRoundPlan` 派生跨 hex 需求、同 hex 零需求、LAND 零需求；
- shippingHex 来自卖方发货 hex；
- 不同 homeHex 不调拨；批次多 lane 共享不超卖；
- 区级 D/S 汇总、本地 observation；
- 锚价 0 合法、D>S 涨、D<=S 延迟降、floor 0；
- matcher lane 硬约束、无容量不成债、运费普通债、服务不扣 haul；
- `MarketRoundStats.lastTransportByHex` 往返。

### C9
- 每 hex OWNER 大库存马匹/可售余量；
- 每 hex caravan_transport plan/unit/roster；
- 不再 seed 旧 trade/haul；
- 32hex 单市场区；
- ApiViews ownershipClass/马匹/批次/锚价/D-S/observation 读口；
- 32hex fixture 与 main 同一马匹合同口径。

### 往返/Codec/组件
- `EconomyData` 组件数/记录组件数按实际 C6/C7 后更新；
- `EconomyChangeSet` diff/apply 往返；
- Codec 新字段读写；
- `MarketRoundStats` 新旧构造器/`withRound`。

---

## 3. 负向用例（必须）

- 权限不能放大：PROLETARIAN 不能组织生产；
- 无本地运力不成交、不成债；
- 跨 hex 调拨/汇总被拒；
- 0 价不被 `max(1,…)` 强改；
- LAND 绕过 LandBook 被拒；
- 服务进入普通库存/订单簿被拒；
- 向下 flow 搬库存/债/资产被拒；
- 合户丢批次/人口/资产被拒；
- 失败不产生半状态；
- 缺失读口不伪造 0。

---

## 4. 验证命令

按项目纪律执行，真实记录 rc：

```bash
tools/mvn-lock.sh -q test -pl simos-app -am
tools/mvn-lock.sh -q -DskipTests verify -pl simos-app -am
tools/gate.sh full
```

- `verify` 成功不能替代 `gate full`；
- `gate full` 如受工具超时/环境限制，分段跑并如实记录，不能写成 PASS；
- 32hex 360 tick：按现有 test-world/脚本入口运行；记录 OK/REJECTED/ADVANCE_EXCEPTION 与关键日志计数。
- 若服务/jar 在跑，按项目纪律不要覆盖运行中 jar。

---

## 5. 变异自证（关键项）

至少四类关键项，改坏生产代码 → 测试当场红 → 还原 → md5 对照：

1. 批次跨轮清空；
2. 0 价合法；
3. 向下 flow 不搬库存/债；
4. 合户批次同 homeHex 相加/不同分键。

做不到“等价存活”的，如实写明；不许编红点。

---

## 6. 交账

写 `.superpowers/sdd/2026-10-21-d032-carrier-c10/impl-ledger.md`（或 test-owner-ledger），必须包含：

- 基线测试总数/失败；
- 迁移/新增测试清单；
- 正例/负例覆盖表；
- verify/gate/360 真实 rc 与证据路径；
- 变异自证结果/等价存活项；
- **我没做/没验证的**；
- 生产代码缺陷清单（file:line、复现、期望/实际）；
- 未完成/blocker。

---

## 7. 非目标

- 不改 main 生产代码（缺陷只报告）。
- 不 commit/push、不碰主树。
- 不把 compile 绿或部分测试绿写成总验收 PASS。

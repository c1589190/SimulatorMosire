# D-032 C8 责任区任务书：运输马匹向上跃迁与混合购买拆分（2026-10-21）

> **状态：可派单的实现任务书；只写生产代码到 `compile` 绿，不写/不跑测试，不 commit/push。**
> **工作树：** `/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`
> **父设计：** `docs/superpowers/specs/2026-10-21-d032-carrier-household-transport-capacity-design.md` §8.1/§8.2/§11/§13
> **父计划：** `docs/superpowers/plans/2026-10-21-d032-carrier-household-transport-capacity-plan.md` §6 C8
> **上游：** C1–C7 已完成且 compile 绿；C3 `LandUpwardMobility` 已有 LAND 目标选择/0 人口 OWNER 壳；
> C5 已有 `transport_horse` 商品/durable capital；C6/C7 已有批次/本地 lane。

---

## 0. 用户原话与冻结口径

```text
如果是混合情况，那么拍个先后顺序，先成为地主，为对应hex的有地农民家户增加对应土地和人数，不增加同时买入的其他生产资料
```

```text
一次资产获得若按吸收能力得到 0 个迁移人口，生产资料仍完整转入目标 OWNER 户
```

```text
马匹：买方“当前 hex”= S7-M2 债务清偿完成后、市场交易开始前的 homeHex 的 OWNER 家户
```

冻结：

- 马匹目标 hex = S7-M2 后、S7-A 前的 buyer homeHex；
- 同 hex + 同 culture 优先；无目标 ⇒ 0 人口 OWNER 壳；
- 不要求 mode/currentPosition/classStanding；
- LAND 与马匹可命中同一个 OWNER 户；
- 先 LAND，后马匹；
- 混合总迁移人数 = landCapacity + horseCapacity 上限，再按最大余数拆分；
- 任一步 0 人，对应生产资料仍完整转移；
- 马匹是普通商品库存，不建 AssetKind.HORSE/OwnershipStake；
- 不扣运输马匹（本批不折旧）。

---

## 1. 目标运行序列

```text
S7-M2 后、S7-A 前
  ownershipClassAtMarketTradeStart 快照已存在
  buyer.currentHex = 当刻 household homeHex（不是市场格/卖方格/土地 hex）

S7-A/MATCH
  市场成交产生：
    - LAND 取得：既有 marketRound.landAcquiredQuantityThisRound
    - transport_horse 取得：本轮 fill 中 commodity=transport_horse 且 buyer=PROLETARIAN 的
      实际到货/成交数量（按当刻交付状态读取，不猜未来 shipment）

S7-B 向上（本责任区）
  1. 只处理开盘前快照 = PROLETARIAN 的 buyer
  2. 读取 LAND 取得量与 transport_horse 取得量
  3. 计算：
       landCapacity  = min(land/minLandPerPerson, speedCap, P-1)
       horseCapacity = min(horseQty/horsePerDriver, speedCap, P-1)
       totalMigrants  = min(P-1, speedCap, landCapacity + horseCapacity)
     以 landCapacity:horseCapacity 为权重，最大余数法拆 totalMigrants
  4. 先执行 LAND 步：
       - 目标 = 土地 hex 的 OWNER（同 culture 优先）
       - LAND 全量转目标
       - 该步 migrants = landShare；0 人仍转 LAND
  5. 再执行马匹步：
       - 目标 = buyer 当前 hex 的 OWNER（同 culture 优先）
       - 马匹全量从 buyer 库存转目标库存
       - 该步 migrants = horseShare；0 人仍转马匹
  6. 每步的人口/钱/货/债/经验按该步 migrants 比例分离，禁止双搬
  7. 同一 revision；失败整次 advance 丢弃
```

---

## 2. 责任区六要素

### 2.1 问题现场（C7 后）

- C3 `LandUpwardMobility` 只处理 LAND 取得，并自行计算 migrants；
- C5 `transport_horse` 已是商品词表/durable capital；
- C7 已能完成 transport_horse 的市场成交和本地 lane；
- 尚无“马匹取得”读口、马匹目标户/0 人口壳、混合最大余数拆分；
- 无 `AssetKind.HORSE`/马匹 `OwnershipStake`。

### 2.2 责任归属

C8 writer 负责：
- 从市场事实读取 PROLETARIAN 买家的马匹取得量；
- buyer 当前 hex 目标选择与 OWNER 壳；
- 马匹库存从 buyer 到 target 的完整转移；
- 与 C3 LAND 步混合的最大余数拆分和两步顺序；
- 不重复搬人口/钱/货/债/经验；
- 不写 C9 seeder/32hex，不改 C7 matcher/价格。

### 2.3 最终能力

1. PROLETARIAN 市场买 transport_horse ⇒ 目标 OWNER 户取得马匹；
2. 目标 hex = S7-M2 后 buyer homeHex；
3. 无目标 ⇒ 确定性 0 人口 OWNER 壳；
4. 0 马匹吸收人数仍完整转马；
5. 混合 LAND+马匹按最大余数拆分总迁移人数；
6. 先 LAND 后马匹；两目标可相同；
7. 人口/钱/货/债/经验不丢不双搬；
8. compile 绿。

### 2.4 不可破坏机制

- 马匹只走普通商品库存，不建 AssetKind.HORSE/OwnershipStake；
- 马匹不扣、不折旧；
- LAND 只走 LandBook；
- 人口只走 outbox/Social；
- class 由资产派生，不持久；
- 不改 C7 lane/锚价/旧 haul；
- 不写/跑测试、不改 pom/tools/world/docs（账本除外）、不碰主树。

### 2.5 可动范围

见 §6；只改 main 代码到 compile 绿。

### 2.6 交账

写 `.superpowers/sdd/2026-10-21-d032-carrier-c8/impl-ledger.md`，先写目标运行段落+影响表；
报告编译、改动文件、马匹取得读口、目标/壳规则、混合拆分、C9 接口、测试失效面、未完成/blocker。

---

## 3. 冻结契约

### 3.1 马匹取得读口

新增（或等价）瞬态读数：

```text
TransportHorseAcquisition {
  buyer household,
  commodity = transport_horse,
  quantityMilli,
  marketRoundId
}
```

- 只从本轮市场事实读取；
- 不把未来在途 shipment 当已取得；若本轮到货未完成，本轮不触发；
- 数量口径与 `householdGoods[transport_horse]` 一致（毫商品）；
- 只处理 S7-M2 后快照为 PROLETARIAN 的 buyer；
- 不得从卖方/目标户反推。

### 3.2 马匹步

- 目标 = buyer 当前 hex 的 OWNER 候选；
- 排序/壳规则复用 C3 `LandUpwardMobility.resolveOwnerTarget`（同 hex、同 culture 优先、0 人口壳、稳定序）；
- 马匹从 buyer 的 `householdGoods[transport_horse]` 全量转目标户；
- 只允许库存转移，不新建 `AssetKind.HORSE`/OwnershipStake；
- 目标 shell 空账户、无 mode/position/standing；
- 目标 0 人 + migrants=0 仍完成马匹转移；
- 马匹转移失败 ⇒ 整次 advance 丢弃。

### 3.3 混合拆分

```text
landCapacity  = min(land/minLandPerPerson, speedCap, P-1)
horseCapacity = min(horse/horsePerDriver, speedCap, P-1)
total = min(P-1, speedCap, landCapacity + horseCapacity)
weights = (landCapacity, horseCapacity)
split total by largest remainder, deterministic tie-break (LAND first)
```

- `total=0` ⇒ 两步都只转资产、不搬人/钱/货/债/经验；
- `landShare=0` ⇒ LAND 仍全转；`horseShare=0` ⇒ 马仍全转；
- 两步人口/钱/货/债/经验按各自 share 从同一 source 递减；
- 不允许把 `total` 同时按两次完整比例搬；
- 两步目标可相同；若相同，第二次转移使用第一次后的 target 人口投影。

### 3.4 目标/快照

- buyer 当前 hex：S7-M2 后、S7-A 前 homeHex 快照；
- 不取市场成交格、卖方格、土地 hex；
- 无目标 ⇒ 0 人口 OWNER 壳；
- 目标已有正人口时优先（C3 同序）；
- 目标 0 人壳允许；
- 不要求 mode/currentPosition/classStanding；
- 不改变 C3 的 LAND 目标规则。

### 3.5 C9 接口

C9 seeder/32hex 必须只读本责任区的马匹取得/转移/拆分入口；
不得复制目标选择、最大余数、马匹库存转移逻辑。

---

## 4. 不变量与失败语义

1. 马匹只走普通库存；不建 AssetKind.HORSE/OwnershipStake。
2. 马匹不扣、不折旧。
3. 目标 hex = buyer 当前 homeHex。
4. 0 人迁移仍全量转资产。
5. 混合拆分总数不超过 P-1/speedCap。
6. 两步钱货债经验不双搬。
7. LAND 只走 LandBook。
8. 人口只走 outbox。
9. 失败整次回滚。
10. 所有排序确定性。

负例：

- 用市场格/卖方格当 buyer 当前 hex ⇒ 红；
- 马匹建 AssetKind.HORSE/OwnershipStake ⇒ 红；
- 0 人迁移丢马 ⇒ 红；
- 混合 total 双搬钱货债 ⇒ 红；
- LAND 绕过 LandBook ⇒ 红；
- 两步目标错配/马匹进 LAND 目标 ⇒ 红。

---

## 5. 验收锚点（供 test owner，不在本任务运行）

- A-C8-01：PROLETARIAN 买马 ⇒ 目标 OWNER 取得全部马匹，0 人仍转。
- A-C8-02：目标 hex = S7-M2 后 buyer homeHex。
- A-C8-03：无 OWNER ⇒ 0 人口 OWNER 壳。
- A-C8-04：LAND+马匹混合按 landCapacity:horseCapacity 最大余数拆分。
- A-C8-05：先 LAND 后马匹；两目标可相同。
- A-C8-06：人口/钱/货/债/经验不双搬。
- A-C8-07：同 culture 优先，无同 culture 确定性 fallback。
- A-C8-08：马匹不建 AssetKind.HORSE/OwnershipStake。
- A-C8-09：C9 只读同一入口。
- A-C8-10：失败无半状态。

---

## 6. 文件所有权

### 允许写

```text
simos-economy-api/src/main/java/io/mosire/simos/economy/api/**
simos-economy/src/main/java/io/mosire/simos/economy/**
simos-app/src/main/java/io/mosire/simos/app/**
```

### 禁止写

```text
**/src/test/**
**/pom.xml
tools/**
test-world/**
主树 /home/cna/SimulatorMosire/.claude/worktrees 之外
docs/**（除 `.superpowers` 账本）
```

- 约 316 项前序未提交改动；只改本责任区 main 文件，不回滚/格式化。
- C8 不做 C9 seeder/读口/32hex；不改 C7 matcher/价格。
- 不新增测试/不跑测试。

---

## 7. 编译与交账

```bash
tools/mvn-lock.sh -q -DskipTests compile -pl simos-app -am
```

只到 compile 绿；不跑 test / test-compile / verify / gate / 360；不 commit/push。

报告：改动文件、真实 rc/输出、马匹取得读口、目标/壳规则、混合拆分、C9 接口、测试失效清单、未完成/blocker。

---

## 8. 非目标

- 不做 C9 seeder/读口/32hex。
- 不做运输马匹折旧/损耗。
- 不做 C7 lane/锚价/旧 haul 改动。
- 不写测试/不跑测试/不 commit/push/不碰主树。

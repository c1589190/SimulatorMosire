# run5 实测：单 tick 单产业产出数量公式 vs run4（同种子 / 同参数 / 同 360 tick）

> 目的（设计书 §11.7）：同种子、同创世参数、同 19 hex / 2 GOV / 2 军队 / 12×30 tick 的真实运行下，
> 新公式（Z1–Z4a）与旧公式的可观测差异逐段列出，并判定是否在 §1.2 预期界内。
> 结论先行：**12/12 段读数逐字节完全一致**；机制确实在跑（事件计数见 §3），差异为 0 的原因可证（§3）；
> 另在真服务上端到端验证了 GM 产出数量覆盖（§4）。

## 1. 两个 run 的口径

| 项 | run4（旧） | run5（新） |
|---|---|---|
| 公式 | 旧 `scaleOf`：`min(capacity×planned, labor/cycleDays/lpu, input/drawn)` 后乘配方数量 | Z2 `ProductionEfficiencyBook`：劳动链带余数结转 + 显式满足率 + 修正参数乘算（本 run 无注入 ⇒ 1000‰） |
| 创世参数 | 34 粮/亩、初始粮 ×15（工作树值） | 同左（已提交 `e59e4aa8`） |
| 世界 | SmallWorld 19 hex，tick0 pop 4800 | 同左（同一 bootstrap 代码） |
| tick0 建场 | 2 GOV（CENTRAL/PROVINCE，各 SCRIBE 2）+ 2 军队 + 省税 100‰ + 两条军俸 | 同一 `setup_tick0.py`（仅端口/路径不同） |
| 分段 | 12×30 tick（tick 0→360） | 同左 |
| 日志 | 全 DEBUG | 全 DEBUG |
| 目录 | `/home/cna/simos-runs/2026-10-23-sw19-run4/` | `/home/cna/simos-runs/2026-10-23-sw19-run5/` |

## 2. 逐段读数与逐字节对照

`dumps/seg-XXX.json` 的 md5 前 12 位逐段相同（12/12）：

| tick | pop | grainStock（毫粮） | debtPrincipal（毫粮） | md5(run4=run5) |
|---|---|---|---|---|
| 30 | 4970 | 78,271,727 | 530,406 | `cee62ceda7e9` |
| 60 | 4982 | 68,193,205 | 538,186 | `2c41e4857f4e` |
| 90 | 4992 | 58,101,020 | 545,976 | `a0b9931e3e46` |
| 120 | 4995 | 1,290,106,869 | 12,514 | （同左，逐段同 md5） |
| 150 | 5004 | 842,224,692 | 32,248 | |
| 180 | 5014 | 832,140,647 | 33,874 | |
| 210 | 5024 | 822,048,284 | 35,574 | |
| 240 | 5047 | 2,542,095,612 | 4,609 | |
| 270 | 5056 | 2,094,156,797 | 30,567 | |
| 300 | 5072 | 2,084,012,373 | 32,064 | |
| 330 | 5077 | 2,073,855,034 | 33,722 | |
| 360 | 5085 | 3,868,556,871 | 6,312 | `f470ab571a6e` |

（`grainStock`/`debtPrincipal` 为 19 格求和；`cmp` 对 12 个文件全部 byte-identical。）

## 3. 机制确证与"差异为 0"的原因（不是没跑到）

run5 `service.log` 事件计数：

| 事件 | 条数 |
|---|---|
| `PRODUCTION_EFFICIENCY_TICK` | 57,240 |
| `PRODUCTION_EFFICIENCY_HARVEST` | 477 |
| `PRODUCTION_MODIFIER_INJECTED`（每日一条） | 360 |
| `PRODUCTION_EFFICIENCY_CONTRACT`（契约故障） | **0** |

477 次收获的逐条字段统计：

- 四个余数（`modifierRemainder` / `laborDayRemainder` / `laborScaleRemainder` / `scaleRemainder`）**全部为 0**；
- `scale == scaleBase`：**477/477**；
- `avgModifier == 1000`：477/477（无任何修正注入）；
- `satisfaction ∈ {1000, 0}`：1000 = 426 条（劳动是最紧约束），0 = 51 条（`laborScale == 0`，无劳动投入 ⇒ 产出 0）；
  没有中间值（本世界资产/投入要么富余到不约束，要么直接为 0）。

因此本世界里新公式与旧公式**逐值同义**：劳动链的每一个除法恰好整除（余数为 0，无处可结转），
修正参数恒为 1000‰（`scaleRemainder` 恒 0），`scaleBase` 的 min 与旧 `scaleOf` 同解。
⇒ 12/12 段逐字节一致是**该世界数值结构的必然结果**，不是新代码未生效。
余数结转路径由 Z4a 的 `ProductionEfficiencyBookTest` 黄金用例（含"两周期 3×500‰ 合计 3"）与 `/tmp`
360 周期冒烟覆盖；修正参数路径由 `ProductionEfficiencySettlementTest`（500→35,000、1500→105,000 毫）覆盖。

★ 副产品：中性运行全部 477 次收获都判定"四余数全零且无非中性注入证据"⇒ `productionEfficiency`
**不物化任何行**（设计书 §3.2/§14.1 的"不给全部 unit 写零行"生效，本 run 无状态膨胀）。

## 4. 真档端到端 GM 覆盖验证（在 run5 服务上，360 tick 对照之后追加）

用 GM 工具 `simos.economy.adjust`（app 侧 Z3 接线）对 hex(1,0) 的 `farm@1_0`：

1. `setOutputQuantity` 预览：`{component=outputQuantityOverrides, keyId=farm@1_0/grain, before=null, after=200}`（只算不写）；
2. 落盘（`preview=false`）：`submitted=true`，rev 20→21，`changedIds=["farm@1_0/grain"]`；
3. 服务重启后 head 仍为 21 ⇒ **覆盖跨进程持久**；
4. 推进 tick 360→480（rev 21→22，一个完整农业周期）后各格粮储：

| hex | 360（毫） | 480（毫） | Δ |
|---|---|---|---|
| **(1,0) 被覆盖 grain=200** | 212,554,067 | **787,178,255** | **+574,624,188** |
| (0,0) 控制 | 224,410,091 | 293,369,331 | +68,959,240 |
| (1,-1) 控制 | 212,523,804 | 288,256,847 | +75,733,043 |
| (0,1) 控制 | — | 288,302,147 | — |
| (-1,0) 控制 | — | 288,155,561 | — |

被覆盖格比控制格多 ≈ **+5.06 亿毫粮**，与预期 `(200−34) × 3,100 亩 = 514,600 粮 = 514,600,000 毫`
同量级（差值为贸易/消费/市场再分配，未逐笔剥离）。

5. `clearOutputQuantity`：`submitted=true`，`before=200, after=null` ⇒ 回落配方默认 34（rev 23）。

## 5. 本批**未改变**的既有问题（run5 与 run4 逐值相同，另行排期）

- 债务仍在每个关账日被清空：峰值 530,406→545,976，残余 12,514 / 4,609 / 6,312 毫粮；人口 4800→5085。
  （用户要的"债务必然累积"仍未达成；下一杠杆待裁定，见 34/15 参数提交 `e59e4aa8` 的说明。）
- F1 税收 100% 行政损耗（`securitySupply`=YAMEN=0）、F2 GOV 国库空/军俸 `no-payable-leg`、
  F3 铸币政府与运行期 GOV 无财政连接、F4 GovRules 人均编制需求 —— 全部原样保留（对照 dump 逐字节相同）。

## 6. 诚实边界

1. 本 run **没有注入任何非中性修正参数**（app 侧尚无消费者）：真档只证明"新公式中性等价 + GM 覆盖生效"；
   "修正参数影响产出"的证据是模块级测试，不是真档。若要真档注入，需要一个真实机制（文化/天气等，
   本批明确不做）或测试专用桥。
2. 修正参数只作用于生产路径；劳动/市场/债务/预期利润等规划读数仍走旧口径（设计书 §13.7/§14.2）。
3. run5 的 GM 覆盖实验在 12 段对照**之后**做，且只动 tick≥360 的后续状态；对照用 `dumps/` 已在实验前落盘。
4. 过程中服务曾被我方 shell 重置连带停止一次（日志 `SHELL_STOP_SIGNAL`，0 条 ERROR/异常）；重启后
   从 rev 21 继续，属运行环境操作，不是代码缺陷。

## 7. 证据路径

- run4（旧公式）：`/home/cna/simos-runs/2026-10-23-sw19-run4/{dumps,service.log,setup_tick0.py,run_segments.py}`
- run5（新公式）：`/home/cna/simos-runs/2026-10-23-sw19-run5/{dumps,seg.log,service.log,service-restart.log,setup_tick0.py,run_segments.py}`
- 日志关键计数：`grep -c 'event=PRODUCTION_EFFICIENCY_TICK' run5/service.log` 等（本报告 §3 的数字可逐条复算）。

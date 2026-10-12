package io.mosire.simos.util.spi;

/**
 * ★★ <b>DM 可用命令标记</b>（2026-10-12 权限统一 / 反默认）。一条已注册的领域命令，<b>允许</b>被嵌进决策令 （{@code ga.IssueDirective}
 * / {@code ga.AdjudicateTick} 的 directive 载荷），且<b>允许</b>入效果队列 （{@code ga.RegisterEffect} 的 {@code
 * Action.EnqueueUnitCommand}）。
 *
 * <p>★★ <b>语义是"允许被嵌进决策令 / 允许入效果队列"，不是"已授权"</b>：标了它只说明这条命令<b>可以被写进令</b>；至于
 * "这条令能不能被执行"，仍由既有的权限面现算（{@code DecisionScopeFunctions} 未注册类型 = deny-all、{@code NationScope}
 * 类型不符即抛、资源目标按出令决策人可达面预检）。★ 本标记<b>不放松任何一条</b>授权判据，它只决定"令里能不能出现这个 type"。
 *
 * <p>★★ <b>默认是仅 GM（"反默认"）</b>：<b>不打这个标记 = 不给 DM</b>。这是用户 2026-10-12 第三轮的原话所指 「理论上不应是 GM
 * 命令优先实现，然后额外套 DMOnly 标签管理吗？」——命令面此前是"默认放行、少数打反向标记 排除"，本标记把它翻成"默认仅 GM、显式声明才给 DM"。★ 反向标记 {@code
 * GmOnlyCommand} 已于 2026-10-12 批次 1 收尾（甲组第 2 条）<b>整体退役</b>：反默认之后"不打本标记"本身就是 GM-only 的声明，留着它就是
 * 两个标记说同一件事、读者要查两处才知道一条命令的面。⇒ <b>本仓现在只有这一个权限标记</b>。
 *
 * <p>★ <b>为什么需要它（用户原话，2026-10-12 第五轮，逐字）</b>：
 *
 * <blockquote>
 *
 * 「哦牛逼，那不能直接让 ga 统一鉴权来解决吗？这本来就是规范问题」<br>
 * 「不是，那不能让各个模块自己决定自己工具的权限范围吗？为啥还有 APP 额外批准？」
 *
 * </blockquote>
 *
 * ⇒ <b>各模块在自己的 handler 上声明</b>（本接口就长在那个类上），<b>ga 负责统一规则与装配期审计</b> （{@code
 * io.mosire.simos.ga.command.CommandPermissionRules}：四个面从同一份声明派生 + 三条 fail-closed 审计），
 * <b>组合根只注册</b>（{@code Shell} 里不再有任何权限判断）。
 *
 * <p>★★ <b>2026-10-13 权限收紧（批次 4 + 批次 4b）：声明面 66 → 17</b>。依据 = 派单规划 §4「批次 4（收紧）」＋用户原话 「{@code map}
 * 层级的命令、凭空造钱造人建军肯定不该给 DM 用」/「单个 Army 决策人只有权提交自然文本战术和移动到哪， 其他工具都是 GM 专有」；批次 4b = 用户 2026-10-13
 * 追加裁定「<b>关闭：整条摘标记（可嵌令面 18 → 17）</b>」。 <b>逐条判据三选一</b>：
 *
 * <ol>
 *   <li><b>① 属于 GM 的推演 / 世界构造权</b>：单位位置与状态（{@code unit.PlaceAt} / {@code SetStatus} / {@code
 *       SetVisionRadius} / {@code SetStateDescription}）、单位编制与指挥链（改名 / 改编 / 挂靠 / 拆分 / 合并 / 指挥链 /
 *       单位家户表）、聚落（{@code social.CreateCity} / {@code UpdateCity}）与经济世界参数 / 产权结构 （{@code
 *       economy.RegisterCandidate} / {@code TransferAssetShare} / {@code SetMarketPrice} / {@code
 *       MigrateHousehold}）；
 *   <li><b>② 能绕过工单直接创造 / 毁灭资源或人口（钱、人口、军队）</b>：{@code actor.Seed} / {@code economy.Seed}
 *       （凭空造钱造货）、{@code social.SetPopulation} / {@code SeedGroups} / {@code CreateHousehold} /
 *       {@code AddHouseholdMembers} / {@code RemoveHouseholdMembers} / {@code
 *       TransferHouseholdMembers} / {@code SetHouseholdVitalRates} / {@code
 *       AdjustHouseholdPopulation}（造人 · 灭人 · 人口布局）、 {@code unit.CreateUnit} / {@code DisbandUnit} /
 *       {@code SetComposition} / {@code ApplyCasualties} / {@code RecruitStaff} / {@code
 *       DismissStaff}（建军 · 兵力）、经济需求的注入 / 改写 / 取消 （{@code economy.AddDemand} / {@code UpdateDemand}
 *       / {@code CancelDemand}）；
 *   <li><b>③ 跨域的世界编辑（地图）</b>：{@code map.*} 七条（地形 / 区划增删改 / 连通性 / 通路词表 / 随机化）。
 * </ol>
 *
 * <p>★ <b>摘除面 49 条</b>（map 7 · {@code actor.Seed} · economy 8 · social 12 · unit 21；其中批次 4 摘 48、批次
 * 4b 再摘 {@code social.SubmitHouseholdWorkOrder} 1 条）；★ <b>保留 DM 的 17 条</b> = 移动三件套（{@code
 * unit.PlanRoute} / {@code PlanSparseRoute} / {@code CancelRoute}，派单规划 §4 明令保留）＋ {@code
 * unit.SetRejoinTarget}（移动意图：只设引用、不设 hex、不建路线）＋ {@code actor.RemitGovTreasury}（零和上缴 / 两 GOV 间转移）＋
 * economy 家户经济配置 3 条（{@code SetHouseholdClass} / {@code SetHouseholdParticipation} / {@code
 * SetHouseholdLabor}）＋ GOV 行政 / 政策 9 条（辖区 / 税率 / 编制 / 政策 / 上级 / 岗位 / 军俸）。
 *
 * <p>★★ <b>批次 4b：{@code social.SubmitHouseholdWorkOrder} 整条摘标记</b>（用户 2026-10-13 裁定：它虽带 {@code
 * reason} + {@code source} 的审计形状，但 plan 词表含 {@code CREATE_HOUSEHOLD} / {@code ADD_MEMBERS} / {@code
 * REMOVE_MEMBERS} / {@code TRANSFER_MEMBERS} / {@code ADJUST_POPULATION} ⇒ <b>整族写人口</b>，与「凭空造人肯定不该给
 * DM 用」直接冲突）。★ <b>为什么是"整条摘"而不是"按 plan 种类分别放行"</b>：后者要在本接口之外新增第二处权限声明 / 判定点，违反本仓「 {@link
 * DmUsableCommand} 是唯一声明、{@code CommandPermissionRules} 是唯一判定点」⇒ 不采纳（这是选整条摘的唯一理由，不是取舍）。 该命令仍留在
 * catalog（141 不变）与 GM 提交面，只是不再进令白名单 / {@code ga.RegisterEffect} / 决策人 catalog。
 *
 * <p>★★ <b>权限只收紧、不放松</b>：批次 4 逐条对称差 = <b>新增 0 条</b>，批次 4b 再摘 1 条 / 新增 0 条；catalog（141）与决策落点面（2 =
 * {@code ga.IssueDirective} / {@code ga.SubmitVerdict}）<b>逐条不变</b> —— 摘标记不改目录、不改 handler、不改落点。 ★
 * <b>批次 4 如实记的残留通道（工单写人口）已由批次 4b 关闭</b>：DM 经命令面改人口的通道现在为零（逐操作直接命令在批次 4 摘除，工单在批次 4b 摘除）。
 *
 * <p>★ <b>为什么住 {@code util.spi}</b>：声明方是五个域模块（map / unit / social / economy / actor），而 util 是它们
 * <b>全部显式声明</b>的唯一下层——与 {@link CommandHandler} 同址同形制。★ 不放 {@code ga-spi}：{@code simos-actor} 的
 * enforcer 明确禁 {@code simos-ga-spi}（改它要动 pom，且会给"依赖传递不是契约" 开口子），而移居当时那 2 条 actor 命令确实 在 DM 面上 ⇒ 放
 * ga-spi 会让 DM 面静默从 66 掉到 64。（★ 2026-10-13 批次 4 后 actor 只剩 {@code RemitGovTreasury} 在 DM 面上、{@code
 * actor.Seed} 已摘；此处的"2 条 / 66"是批次 1 的历史事实，留痕不改。）
 *
 * <p>★ <b>唯一标记</b>：本仓现在只有这一个权限标记（反向标记已退役）⇒ "同一条命令同时标两个互斥标记"在类型上
 * 已不可表达，相应那条审计（装配期当场抛）随之废止。剩下<b>三条</b>审计（声明了未注册类型 / <b>落点不得是 DM 可用命令</b> / 四个面不得为空） 仍在 {@code
 * CommandPermissionRules} 里，一个不放松；★ 该层另有一条**装配顺序守卫**（声明收集之后装配面又混进带标记的 handler ⇒ 当场抛），
 * 它不是"审计声明"而是"审计装配次序"，故不计入上面三条。
 */
public interface DmUsableCommand {}

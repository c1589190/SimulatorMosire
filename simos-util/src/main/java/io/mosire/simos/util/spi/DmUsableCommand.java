package io.mosire.simos.util.spi;

/**
 * ★★ <b>DM 可用命令标记</b>（2026-10-12 权限统一 / 反默认）。一条已注册的领域命令，<b>允许</b>被嵌进决策令
 * （{@code sd.IssueDirective} / {@code sd.AdjudicateTick} 的 directive 载荷），且<b>允许</b>入效果队列
 * （{@code sd.RegisterEffect} 的 {@code Action.EnqueueUnitCommand}）。
 *
 * <p>★★ <b>语义是"允许被嵌进决策令 / 允许入效果队列"，不是"已授权"</b>：标了它只说明这条命令<b>可以被写进令</b>；至于
 * "这条令能不能被执行"，仍由既有的权限面现算（{@code DecisionScopeFunctions} 未注册类型 = deny-all、{@code NationScope}
 * 类型不符即抛、资源目标按出令决策人可达面预检）。★ 本标记<b>不放松任何一条</b>授权判据，它只决定"令里能不能出现这个 type"。
 *
 * <p>★★ <b>默认是仅 GM（"反默认"）</b>：<b>不打这个标记 = 不给 DM</b>。这是用户 2026-10-12 第三轮的原话所指
 * 「理论上不应是 GM 命令优先实现，然后额外套 DMOnly 标签管理吗？」——命令面此前是"默认放行、少数打
 * {@code GmOnlyCommand} 排除"，本标记把它翻成"默认仅 GM、显式声明才给 DM"。
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
 * ⇒ <b>各模块在自己的 handler 上声明</b>（本接口就长在那个类上），<b>ga 负责统一规则与装配期审计</b>
 * （{@code io.mosire.simos.ga.command.CommandPermissionRules}：四个面从同一份声明派生 + 三条 fail-closed 审计），
 * <b>组合根只注册</b>（{@code Shell} 里不再有任何权限判断）。
 *
 * <p>★ <b>为什么住 {@code util.spi}</b>：声明方是五个域模块（map / unit / social / economy / actor），而 util 是它们
 * <b>全部显式声明</b>的唯一下层——与 {@link GmOnlyCommand} / {@link CommandHandler} 同址同形制。★ 不放
 * {@code ga-spi}：{@code simos-actor} 的 enforcer 明确禁 {@code simos-ga-spi}（改它要动 pom，且会给"依赖传递不是契约"
 * 开口子），而那 2 条 actor 命令今天确实在 DM 面上 ⇒ 放 ga-spi 会让 DM 面静默从 66 掉到 64。
 *
 * <p>★ <b>两个标记互斥</b>：同一条命令同时标 {@link DmUsableCommand} 与 {@link GmOnlyCommand} 是自相矛盾，
 * 装配期<b>当场抛</b>（{@code CommandPermissionRules} 的审计第 ① 条），不许静默按其中一条处理。
 */
public interface DmUsableCommand {}

package io.mosire.simos.util.spi;

/**
 * ★★ <b>GM-only 命令标记</b>（E6a）：一条已注册的领域命令，<b>可以</b>由 GM 的 {@code simos.command.submit} 直接提交，
 * 但<b>不得</b>作为决策人令（{@code sd.IssueDirective} / {@code sd.AdjudicateTick} 的 directive 载荷）嵌入执行。
 *
 * <p>★★ <b>为什么需要它</b>：命令是否注册（执行面）与命令是否可以由普通 GOV Agent 通过决策渠道触发（授权面）是两件事。 后者此前只由 {@code sd} 的 {@code
 * DirectiveWhitelist}（"全量注册面 − sd.* − 通用写"）一揽子决定，表达不了"某条领域命令只许 GM
 * 直接调、不许进令"。本接口把这一维作为**标记**放在契约层（{@code simos-util/spi}）—— 领域模块（如 economy）实现它不依赖 app，组合根（Shell）在构造
 * {@code DirectiveWhitelist} 的输入集时把标记者排除。
 *
 * <p>★ <b>语义是"只禁嵌入"不是"未注册"</b>：标记命令仍照常注册到 Core、仍进 {@code CommandTargets} 表（GM 直接提交照走
 * handler）；被排除的只是"决策人把它写进 directive"这一条路。★ 禁止的是一条**绕过政治能力的入口**，不是命令本身。
 *
 * <p>★ 组合根侧的使用口径（唯一）：{@code Shell} 构造决策白名单与工具目录时，用 {@code !(handler instanceof GmOnlyCommand)}
 * 过滤；不得在别处另写一份"哪些命令是 GM-only"的清单（那会与标记漂移）。
 */
public interface GmOnlyCommand {}

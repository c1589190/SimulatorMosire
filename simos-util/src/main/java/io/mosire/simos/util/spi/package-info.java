/**
 * Core 与模块之间的契约面（ADR-1 C9）。位置冻结，**既有契约原地不动**。
 *
 * <p>★ **契约面一旦成型即稳定**（U13，2026-09-18 用户裁定）：本包的类型一旦落地，后续**要动就是大版本更新**。
 * 加字段、改签名、改语义都算"动"。**新增契约放本包，既有契约原地不动**（ADR-1 C9）。
 *
 * <p>三个接口各自的稳定性承诺：
 *
 * <ul>
 *   <li>{@link io.mosire.simos.util.spi.CommandHandler}——{@code type()} 的字符串形态是**跨进程协议**（M5 的 MCP
 *       会照抄它），改它 = 改协议
 *   <li>{@link io.mosire.simos.util.spi.ModuleCodec}——{@code namespace()} 与 {@code
 *       Snapshot.namespace()} 绑定；JSON 形态**不在承诺内**（那是各模块自己的事）
 *   <li>{@link io.mosire.simos.util.spi.TimeParticipant}——{@code simulate} 的**纯函数性**是承诺：实现不得就地改传入的
 *       state
 * </ul>
 *
 * <p>为什么不拆独立的 {@code simos-spi} 模块（U12）：**没有独立消费者**。三个实现模块（map/social/unit） 都依赖 {@code
 * simos-util}，拆出去只会多一个模块、多一次版本同步，买到的是零。包分离 + 本文件即是书面约定； 真正的强制来自 {@code bannedDependencies}（模块级），而那是
 * **artifact 级**的——拆包买不到任何它给不了的 强制（ADR-1 §二）。
 */
package io.mosire.simos.util.spi;

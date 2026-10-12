package io.mosire.simos.ga.command;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>命令权限的四个面</b>（2026-10-12 批次 1「权限统一到 ga 规范层」）：把"谁能用哪条命令"从三套分散鉴权收成 <b>一份声明 ⇒ 四个面</b>。
 *
 * <ul>
 *   <li>{@code catalogTypes} = <b>注册面</b>（全部已注册 handler 的 type，含 GM-only 与装配自身新增的那几条）—— {@code
 *       simos.command.catalog} 的 GM 面与 `simos.command.submit` 的目标表看它；
 *   <li>{@code directiveTypes} = <b>决策令可嵌面</b> —— {@code sd.IssueDirective} 的白名单、{@code
 *       sd.AdjudicateTick} 重建的白名单、决策人 catalog 的"令里可嵌"那一支看它；
 *   <li>{@code drainableTypes} = <b>效果可入队面</b> —— {@code sd.RegisterEffect} 的可入队白名单看它；
 *   <li>{@code landingPoints} = <b>决策落点面</b>（{@code sd.IssueDirective} / {@code
 *       sd.SubmitVerdict}）—— {@code ChannelAdmission.requireLandingPoint} 的落点集合。
 * </ul>
 *
 * <p>★★ <b>后三个面是同一份声明的三种用法</b>：{@code directiveTypes} 与 {@code drainableTypes} 在本批口径下
 * <b>内容逐条相同</b>（{@code DmUsableCommand} 的语义同时覆盖"可嵌令"与"可入队"），仍作为两个具名字段保留—— 将来若两者要分家，调用点不必改；{@code
 * landingPoints} 是<b>输入 + 审计</b>（由组合根把 {@code ChannelAdmission.LANDING_POINTS} 送进来，本层审计"它是注册面成员且不是
 * DM 可用命令"）。
 *
 * <p>★ <b>四个面都不得为空</b>：空集 = 装配故障（比如所有 handler 都漏标 ⇒ 决策人一条命令都用不了）， 由 {@link CommandPermissionRules}
 * 当场抛，不许静默放行。
 *
 * @param catalogTypes 注册面（保序去重，构造顺序 = 注册顺序）
 * @param directiveTypes 可嵌进决策令的命令类型（保序）
 * @param drainableTypes 可入效果队列的命令类型（保序）
 * @param landingPoints 决策落点（{@code ChannelAdmission.LANDING_POINTS}，保序）
 */
public record CommandFaces(
    Set<String> catalogTypes,
    Set<String> directiveTypes,
    Set<String> drainableTypes,
    Set<String> landingPoints) {

  /** 四个面一律冻在赋值处（保序、不可变）——调用方拿到的是只读视图。 */
  public CommandFaces {
    catalogTypes = frozen(catalogTypes, "catalogTypes");
    directiveTypes = frozen(directiveTypes, "directiveTypes");
    drainableTypes = frozen(drainableTypes, "drainableTypes");
    landingPoints = frozen(landingPoints, "landingPoints");
  }

  private static Set<String> frozen(Set<String> types, String name) {
    Objects.requireNonNull(types, name);
    return Collections.unmodifiableSet(new LinkedHashSet<>(types));
  }
}

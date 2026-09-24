package io.mosire.simos.sd.model;

import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.DirectiveId;
import io.mosire.simos.sd.id.EffectId;
import io.mosire.simos.sd.id.VerdictId;
import io.mosire.simos.util.address.Address;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 决策（spec §三.5，R3/R4）：一个决策人在一个**世界日**（{@code tick}，单位：日，2026-09-24 日制裁定）的决策——含执行原文（INFO）+ 结构化命令 +
 * 效果引用。
 *
 * <p>★★ **R4 的形态是"末位生效"，不是"唯一"**（2026-09-23 用户裁定）：同一 ({@code decisionMakerId}, {@code tick})
 * 允许**出多条**（重写 = 产生新的一版），但**只有最新一条生效**——旧的在出令那一刻转 {@link DirectiveStatus#SUPERSEDED}。
 * 不变量由命令期（{@code IssueDirectiveHandler} 顶掉旧令）+ {@code SdState} 构造期（**至多一条生效**，见 {@code
 * requireAtMostOneActiveDirective}）**两处**校验。 本 record 只守形状。
 *
 * <p>★ **效果与执行原文无关**（spec §三.6）：{@code intentInfoKey} 与 {@code effects} **分开存**，不互相推导。
 *
 * <p>★ {@code commands} / {@code effects} 保序不可变（{@code List.copyOf} / {@code LinkedHashSet} +
 * 冻在赋值处）。
 */
public record Directive(
    DirectiveId id,
    DecisionMakerId decisionMakerId,
    long tick,
    Optional<Address> target,
    String intentInfoKey,
    List<DirectiveCommand> commands,
    Set<EffectId> effects,
    Optional<VerdictId> verdict,
    DirectiveStatus status) {

  public Directive {
    if (id == null) {
      throw new IllegalArgumentException("id 不得为 null");
    }
    if (decisionMakerId == null) {
      throw new IllegalArgumentException("decisionMakerId 不得为 null");
    }
    if (tick < 0) {
      throw new IllegalArgumentException("tick 必须 ≥ 0: " + tick);
    }
    if (target == null) {
      throw new IllegalArgumentException("target 不得为 null（无目标用 Optional.empty()）");
    }
    if (intentInfoKey == null || intentInfoKey.isBlank()) {
      throw new IllegalArgumentException("intentInfoKey 不得为空白");
    }
    if (commands == null) {
      throw new IllegalArgumentException("commands 不得为 null");
    }
    if (effects == null) {
      throw new IllegalArgumentException("effects 不得为 null");
    }
    if (verdict == null) {
      throw new IllegalArgumentException("verdict 不得为 null（未有判决用 Optional.empty()）");
    }
    if (status == null) {
      throw new IllegalArgumentException("status 不得为 null");
    }
    commands = List.copyOf(commands);
    Set<EffectId> effectIds = new LinkedHashSet<>();
    for (EffectId effect : effects) {
      if (effect == null) {
        throw new IllegalArgumentException("effects 不得含 null");
      }
      effectIds.add(effect);
    }
    effects = Collections.unmodifiableSet(effectIds); // ★ 冻在赋值处
  }

  /**
   * **仅换状态**的那一版（record 没有 wither）。
   *
   * <p>★ 两处调用者共用它，是为了让"除 {@code status} 外逐字段照抄"这条**只有一份**——各写一份拷贝代码时，将来 {@code Directive}
   * 加字段必然漏掉一处，而漏掉的那处**不会报错**（拷贝出的旧构造器参数默认值），属于本仓最贵的教训那一族（铁律 5 的由来）。
   *
   * @param newStatus 新状态（非 null；语义合法性的判定不在这里——见 {@code DirectiveStatus} 的类注）
   */
  public Directive withStatus(DirectiveStatus newStatus) {
    return new Directive(
        id, decisionMakerId, tick, target, intentInfoKey, commands, effects, verdict, newStatus);
  }
}

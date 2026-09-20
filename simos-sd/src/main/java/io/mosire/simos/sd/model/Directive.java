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
 * 决策（spec §三.5，R3/R4）：一个决策人在一个 tick 的**唯一**决策——含执行原文（INFO）+ 结构化命令 + 效果引用。
 *
 * <p>★ **R4 硬不变量**：({@code decisionMakerId}, {@code tick}) 唯一——由命令期 + {@code SdState}
 * 构造期**两处**校验（spec §十一.2）。 本 record 只守形状。
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
}

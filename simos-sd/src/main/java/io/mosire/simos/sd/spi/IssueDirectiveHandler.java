package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.DirectiveId;
import io.mosire.simos.sd.id.EffectId;
import io.mosire.simos.sd.model.Directive;
import io.mosire.simos.sd.model.DirectiveCommand;
import io.mosire.simos.sd.model.DirectiveStatus;
import io.mosire.simos.sd.model.SdInfoEntry;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@code sd.IssueDirective} 命令的处理器（spec §四，D1）：落一条 {@link Directive}，并把**执行原文**写成 sd 侧 INFO。
 *
 * <pre>{@code
 * {"directiveId":"d1","decisionMakerId":"dm1","tick":3,"target":"sd:combat.c1",
 *  "intentInfo":"向北推进","commands":[{"type":"unit.PlanRoute","payloadJson":"{...}"}],
 *  "effects":[]}
 * }</pre>
 *
 * <p>★ **R4 硬不变量**（spec §十一.2）：({@code decisionMakerId}, {@code tick}) 唯一——本处理器在**命令期**显式校验； {@link
 * SdState} 的构造期不变量是同一规则的**状态期**后备（第二层）。
 *
 * <p>★ **白名单**（spec §四）：{@code commands[].type} 必须落在 {@link DirectiveWhitelist}；{@code sd.*}
 * 自指与通用写被明确拒绝。
 *
 * <p>★ **执行原文与效果分开存**（spec §三.6）：{@code intentInfo} 写进地址 {@code sd:directive.<id>} 下的 INFO 条目（key
 * 固定为 {@value #INTENT_INFO_KEY}），{@code Directive.intentInfoKey} 只记该 key；二者不互相推导。
 *
 * <p>拒绝：{@code decisionMakerId} 不存在；**载荷 {@code tick} 记在未来**（{@code tick > 世界当前 tick}，见 {@link
 * #handle}）；指令 id 已存在；**R4 违反**；{@code commands[].type} 不在白名单（或自指）； {@code target} 非法；{@code
 * effects[]} 引用的效果不存在。
 */
public final class IssueDirectiveHandler implements CommandHandler {

  /** 执行原文在 sd INFO 覆盖层里的 key（spec §二.3："单条决策 = 单条 Command，Command 必须自带 INFO 作为执行原文"）。 */
  public static final String INTENT_INFO_KEY = "intent";

  private final DirectiveWhitelist whitelist;

  public IssueDirectiveHandler(DirectiveWhitelist whitelist) {
    this.whitelist = Objects.requireNonNull(whitelist, "whitelist");
  }

  @Override
  public String type() {
    return "sd.IssueDirective";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SdState base = SdSnapshots.of(state).state();
    try {
      JsonNode payload = SdPayloads.parse(payloadJson);
      DirectiveId id = DirectiveId.parse(SdPayloads.requireText(payload, "directiveId"));
      DecisionMakerId decisionMakerId =
          DecisionMakerId.parse(SdPayloads.requireText(payload, "decisionMakerId"));
      long tick = SdPayloads.requireLong(payload, "tick");
      Optional<Address> target = SdPayloads.optionalAddress(payload, "target");
      String intentInfo = SdPayloads.requireText(payload, "intentInfo");
      List<DirectiveCommand> commands = SdPayloads.optionalDirectiveCommands(payload, "commands");
      Set<EffectId> effects = parseEffectIds(payload);

      if (!base.decisionMakers().containsKey(decisionMakerId)) {
        return new HandlerOutcome.Rejected("决策人不存在: " + decisionMakerId.value());
      }
      long worldTick = state.meta().timestamp().tick();
      if (tick > worldTick) {
        // ★★ **令不得记在未来**（2026-09-23，用户实测撞到的真缺陷）：`tick` 是**调用方（模型）给的**，
        //   而记录一旦落盘就**无法回改**（时间线只追加）。真实现场：模型填了 6、而世界当时是 0（后来 3）
        //   ⇒ 记录里"上次出令在 tick 6"比世界**还晚** ⇒ `ticksSinceLast` 算出负数 ⇒ `due` 永久算错
        //   （症状是"点开始决策没反应"，且**没有任何报错**）。
        //   ★ **只拒"未来"，不要求"恰好等于世界 tick"**：过去的 tick 合法（补记 / 滞后一拍都说得通），
        //     且**系统不改写调用方给的值**（不静默兜底——改了值等于让调用方以为自己写对了）。
        return new HandlerOutcome.Rejected("令不得记在未来：载荷 tick " + tick + " > 世界 tick " + worldTick);
      }
      if (base.directives().containsKey(id)) {
        return new HandlerOutcome.Rejected("决策已存在: " + id.value());
      }
      Optional<String> violation = firstR4Violation(base, decisionMakerId, tick);
      if (violation.isPresent()) {
        return new HandlerOutcome.Rejected(violation.get());
      }
      Optional<String> whiteListViolation = firstWhitelistViolation(commands);
      if (whiteListViolation.isPresent()) {
        return new HandlerOutcome.Rejected(whiteListViolation.get());
      }
      for (EffectId effectId : effects) {
        if (!base.effects().containsKey(effectId)) {
          return new HandlerOutcome.Rejected("效果不存在: " + effectId.value());
        }
      }

      RevisionId at = state.meta().ref().revision();
      String infoAddress = "sd:directive." + id.value();
      SdInfoEntry entry =
          new SdInfoEntry(INTENT_INFO_KEY, intentInfo, Optional.empty(), at, Optional.of(id));
      Map<String, List<SdInfoEntry>> nextInfo = new LinkedHashMap<>(base.info());
      List<SdInfoEntry> entries = new ArrayList<>(nextInfo.getOrDefault(infoAddress, List.of()));
      entries.add(entry);
      nextInfo.put(infoAddress, List.copyOf(entries));

      Directive directive =
          new Directive(
              id,
              decisionMakerId,
              tick,
              target,
              INTENT_INFO_KEY,
              commands,
              effects,
              Optional.empty(),
              DirectiveStatus.ISSUED);
      Map<DirectiveId, Directive> nextDirectives = new LinkedHashMap<>(base.directives());
      nextDirectives.put(id, directive);
      SdState target0 = base.withDirectives(nextDirectives).withInfo(nextInfo);
      return new HandlerOutcome.Applied(SdChangeSet.between(base, target0));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  private static Optional<String> firstR4Violation(
      SdState base, DecisionMakerId decisionMakerId, long tick) {
    for (Directive existing : base.directives().values()) {
      if (existing.decisionMakerId().equals(decisionMakerId) && existing.tick() == tick) {
        return Optional.of(
            "R4 违反：决策人 "
                + decisionMakerId.value()
                + " 在 tick "
                + tick
                + " 已有决策 "
                + existing.id().value());
      }
    }
    return Optional.empty();
  }

  private Optional<String> firstWhitelistViolation(List<DirectiveCommand> commands) {
    for (DirectiveCommand command : commands) {
      if (DirectiveWhitelist.isSdSelfReference(command.type())) {
        return Optional.of("决策命令不得自指 sd.*（防无限递归）: " + command.type());
      }
      if (!whitelist.allows(command.type())) {
        return Optional.of("决策命令不在白名单: " + command.type());
      }
    }
    return Optional.empty();
  }

  private static Set<EffectId> parseEffectIds(JsonNode payload) {
    Set<EffectId> out = new LinkedHashSet<>();
    for (String value : SdPayloads.optionalTextSet(payload, "effects")) {
      out.add(EffectId.parse(value));
    }
    return out;
  }
}

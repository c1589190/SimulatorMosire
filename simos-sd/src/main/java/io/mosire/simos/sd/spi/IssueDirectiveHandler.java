package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.sd.SdLog;
import io.mosire.simos.sd.SdLogSource;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.DirectiveId;
import io.mosire.simos.sd.id.EffectId;
import io.mosire.simos.sd.model.Directive;
import io.mosire.simos.sd.model.DirectiveCommand;
import io.mosire.simos.sd.model.DirectiveStatus;
import io.mosire.simos.sd.model.SdInfoEntry;
import io.mosire.simos.sd.model.SdInfoIds;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
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
 * <p>★★ **R4 = 末位生效，不是"唯一"**（2026-09-23 用户裁定）：同一 ({@code decisionMakerId}, {@code tick}) 允许在 **同一
 * tick 内再出令**（"打回重写"要的正是这一条——不必先推 tick）——但**只有最新一条生效**：出令时把同 (决策人, tick) 下**仍生效** 的旧令翻成 {@link
 * DirectiveStatus#SUPERSEDED}（终态、留痕、不再参与裁决）。 {@link SdState} 的构造期不变量是同一规则的**状态期**后备 （第二层："至多一条生效"）。
 *
 * <p>★ **重写 = 新的 {@link DirectiveId}**（不是改写旧令）：旧令的形状与执行原文一条不动 ⇒ 时间线可回退、可审计。
 *
 * <p>★ **白名单**（spec §四）：{@code commands[].type} 必须落在 {@link DirectiveWhitelist}；{@code sd.*}
 * 自指与通用写被明确拒绝。
 *
 * <p>★ **执行原文与效果分开存**（spec §三.6）：{@code intentInfo} 写进地址 {@code sd:directive.<id>} 下的 INFO 条目（key
 * 固定为 {@value #INTENT_INFO_KEY}），{@code Directive.intentInfoKey} 只记该 key；二者不互相推导。
 *
 * <p>拒绝：{@code decisionMakerId} 不存在；**载荷 {@code tick} 记在未来**（{@code tick > 世界当前 tick}，见 {@link
 * #handle}）；指令 id 已存在；{@code commands[].type} 不在白名单（或自指）； {@code target} 非法；{@code effects[]}
 * 引用的效果不存在。
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
    String directiveForLog = null;
    String dmForLog = null;
    try {
      JsonNode payload = SdPayloads.parse(payloadJson);
      DirectiveId id = DirectiveId.parse(SdPayloads.requireText(payload, "directiveId"));
      directiveForLog = id.value();
      DecisionMakerId decisionMakerId =
          DecisionMakerId.parse(SdPayloads.requireText(payload, "decisionMakerId"));
      dmForLog = decisionMakerId.value();
      long tick = SdPayloads.requireLong(payload, "tick");
      Optional<Address> target = SdPayloads.optionalAddress(payload, "target");
      String intentInfo = SdPayloads.requireText(payload, "intentInfo");
      List<DirectiveCommand> commands = SdPayloads.optionalDirectiveCommands(payload, "commands");
      Set<EffectId> effects = parseEffectIds(payload);

      if (!base.decisionMakers().containsKey(decisionMakerId)) {
        return rejected(
            "决策人不存在: " + decisionMakerId.value(), "directive", directiveForLog, "dm", dmForLog);
      }
      long worldTick = state.meta().timestamp().tick();
      if (tick > worldTick) {
        // ★★ **令不得记在未来**（2026-09-23，用户实测撞到的真缺陷）：`tick` 是**调用方（模型）给的**，
        //   而记录一旦落盘就**无法回改**（时间线只追加）。真实现场：模型填了 6、而世界当时是 0（后来 3）
        //   ⇒ 记录里"上次出令在 tick 6"比世界**还晚** ⇒ `ticksSinceLast` 算出负数 ⇒ `due` 永久算错
        //   （症状是"点开始决策没反应"，且**没有任何报错**）。
        //   ★ **只拒"未来"，不要求"恰好等于世界 tick"**：过去的 tick 合法（补记 / 滞后一拍都说得通），
        //     且**系统不改写调用方给的值**（不静默兜底——改了值等于让调用方以为自己写对了）。
        return rejected(
            "令不得记在未来：载荷 tick " + tick + " > 世界 tick " + worldTick,
            "directive",
            directiveForLog,
            "dm",
            dmForLog,
            "tick",
            tick);
      }
      if (base.directives().containsKey(id)) {
        return rejected("决策已存在: " + id.value(), "directive", directiveForLog, "dm", dmForLog);
      }
      Optional<String> whiteListViolation = firstWhitelistViolation(commands);
      if (whiteListViolation.isPresent()) {
        return rejected(
            whiteListViolation.get(),
            "directive",
            directiveForLog,
            "dm",
            dmForLog,
            "commands",
            commands.size());
      }
      for (EffectId effectId : effects) {
        if (!base.effects().containsKey(effectId)) {
          return rejected(
              "效果不存在: " + effectId.value(),
              "directive",
              directiveForLog,
              "dm",
              dmForLog,
              "effect",
              effectId.value());
        }
      }

      RevisionId at = state.meta().ref().revision();
      String infoAddress = "sd:directive." + id.value();
      Map<String, List<SdInfoEntry>> nextInfo = new LinkedHashMap<>(base.info());
      List<SdInfoEntry> entries = new ArrayList<>(nextInfo.getOrDefault(infoAddress, List.of()));
      // ★ 决策结果三件套（第 3 波第 1 步）：执行原文就是这条决策的**结果**，故 tick 取**令自带的 tick**（不是世界 tick——
      //   令允许补记/滞后，见上面"令不得记在未来"的裁定；按世界 tick 归档会让同一条令随处理时刻漂移）；tags 挂**出令决策人**。
      SdInfoEntry entry =
          new SdInfoEntry(
              SdInfoIds.synthesize(infoAddress, entries.size()),
              tick,
              Set.of(decisionMakerId),
              Set.of(),
              INTENT_INFO_KEY,
              intentInfo,
              Optional.empty(),
              at,
              Optional.of(id),
              Optional.empty());
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
      supersedeOldVersions(nextDirectives, decisionMakerId, tick);
      nextDirectives.put(id, directive);
      SdState target0 = base.withDirectives(nextDirectives).withInfo(nextInfo);
      EventLog.channel(SdLog.decision())
          .info(
              LogEvent.of(
                  "SD_DIRECTIVE_ISSUED",
                  SdLogSource.SD_DECISION,
                  "id",
                  id.value(),
                  "decisionMaker",
                  decisionMakerId.value(),
                  "tick",
                  tick,
                  "commands",
                  commands.size(),
                  "effects",
                  effects.size(),
                  "target",
                  target.map(Address::canonical).orElse("-"),
                  "directives",
                  nextDirectives.size()));
      return new HandlerOutcome.Applied(SdChangeSet.between(base, target0));
    } catch (IllegalArgumentException e) {
      return rejected(e.getMessage(), "directive", directiveForLog, "dm", dmForLog);
    }
  }

  /**
   * 具名拒绝的唯一发射点（用户 2026-10-23：被拒绝一律 INFO，必须明显记录）：{@code reason} + 关键 id（取不到 {@code -}）。 理由先过 {@link
   * SdPayloads#logReason}，载荷原文不进日志；返回 {@code Rejected} 保持原有控制流。
   */
  private static HandlerOutcome rejected(String reason, Object... idKeyValues) {
    SdPayloads.logRejected(
        SdLog.decision(),
        SdLogSource.SD_DECISION,
        "SD_ISSUE_DIRECTIVE_REJECTED",
        reason,
        idKeyValues);
    return new HandlerOutcome.Rejected(reason);
  }

  /**
   * **末位生效**（R4 的新形态）：把同 (决策人, tick) 下**仍生效**（{@code PLANNED}/{@code ISSUED}）的旧令翻成 {@link
   * DirectiveStatus#SUPERSEDED}。
   *
   * <p>★ **只顶"生效中"的**：已终态的旧令（{@code EXECUTED}/{@code CANCELLED}/{@code SUPERSEDED}）**一条都不动**—— 尤其
   * {@code CANCELLED}（被 GM 打回的令）必须保住自己的状态与留痕，不能被后来的重写抹掉。
   *
   * <p>★ **不删旧令**：只换状态。旧令的执行原文（INFO）与其命令清单留在原地 ⇒ 时间线可回退、AAR 看得到"第 1 版被第 2 版顶掉"。
   */
  private static void supersedeOldVersions(
      Map<DirectiveId, Directive> nextDirectives, DecisionMakerId decisionMakerId, long tick) {
    for (Directive existing : List.copyOf(nextDirectives.values())) {
      if (!existing.decisionMakerId().equals(decisionMakerId) || existing.tick() != tick) {
        continue;
      }
      if (existing.status() == DirectiveStatus.PLANNED
          || existing.status() == DirectiveStatus.ISSUED) {
        nextDirectives.put(existing.id(), existing.withStatus(DirectiveStatus.SUPERSEDED));
      }
    }
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

package io.mosire.simos.ga.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.ga.GaLog;
import io.mosire.simos.ga.GaLogSource;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.change.GaChangeSet;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.DirectiveId;
import io.mosire.simos.sd.id.EffectId;
import io.mosire.simos.sd.model.Directive;
import io.mosire.simos.sd.model.DirectiveCommand;
import io.mosire.simos.sd.model.DirectiveStatus;
import io.mosire.simos.sd.spi.DirectiveIntentInfo;
import io.mosire.simos.sd.spi.DirectiveWhitelist;
import io.mosire.simos.sd.spi.SdPayloads;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@code ga.IssueDirective} 命令的处理器（spec §四，D1；<b>★ 2026-10-12 批次 3b′ 自 {@code simos-sd/sd.spi}
 * 迁入并改名 {@code sd.* → ga.*}，语义一字未改</b>）：落一条 {@link Directive}，按 R4 顶掉同 (决策人, tick) 的旧版。
 *
 * <pre>{@code
 * {"directiveId":"d1","decisionMakerId":"dm1","tick":3,"target":"sd:combat.c1",
 *  "intentInfo":"向北推进","commands":[{"type":"unit.PlanRoute","payloadJson":"{...}"}],
 *  "effects":[]}
 * }</pre>
 *
 * <p>★★ **R4 = 末位生效，不是"唯一"**（2026-09-23 用户裁定）：同一 ({@code decisionMakerId}, {@code tick}) 允许在 **同一
 * tick 内再出令**（"打回重写"要的正是这一条——不必先推 tick）——但**只有最新一条生效**：出令时把同 (决策人, tick) 下**仍生效** 的旧令翻成 {@link
 * DirectiveStatus#SUPERSEDED}（终态、留痕、不再参与裁决）。 {@link GaState} 的构造期不变量是同一规则的**状态期**后备 （第二层："至多一条生效"）。
 *
 * <p>★ **重写 = 新的 {@link DirectiveId}**（不是改写旧令）：旧令的形状与执行原文一条不动 ⇒ 时间线可回退、可审计。
 *
 * <p>★ **白名单**（spec §四）：{@code commands[].type} 必须落在 {@link DirectiveWhitelist}；{@code sd.*}
 * 自指与通用写被明确拒绝。
 *
 * <p>★★ <b>执行原文（{@code intentInfo}）不再由本命令写入</b>（2026-10-12 批次 3b′，本批的**唯一**行为收窄，如实记）： 一条命令 =
 * 一个命名空间（{@code CommandBus.namespaceOf(type)} 取类型前缀）⇒ 本命令改名 {@code ga.*} 后**只能**写 {@code
 * GaChangeSet}，而执行原文的落点（sd INFO 覆盖层的 {@code sd:directive.<id>} 条目）本批仍住 {@code SdState.info} （{@code
 * info} 表搬 ga 属批次 3b）。⇒ 该条目改由**同批的第二条命令 {@code sd.PutInfo}** 落（仓内既有形制：{@code RejectDirectiveTool} /
 * {@code AdjudicateTickTool} / {@code DecisionEffectExecutor} 等十余处审计条目都这么做）： 载荷由 {@code
 * io.mosire.simos.sd.spi.DirectiveIntentInfo} 从**同一份出令载荷**派生（键/地址的**唯一拼写点**在那边），
 * 提交方是真正持有载荷的两个生产写路径（{@code IssueDirectiveTool} 与三条决策渠道）——它们把两条命令装进**同一批**，故 令与执行原文仍落在**同一条
 * revision**。{@code Directive.intentInfoKey} 照旧记那个 key，二者的对应关系（ {@code sourceDirective} 字段）由那条
 * {@code sd.PutInfo} 写上。
 *
 * <p>★ <b>本命令自己仍然要求</b> {@code intentInfo} 非空白（载荷契约不变）：缺了它，那条执行原文的写入就无从谈起。
 *
 * <p>拒绝：{@code decisionMakerId} 不存在；**载荷 {@code tick} 记在未来**（{@code tick > 世界当前 tick}，见 {@link
 * #handle}）；指令 id 已存在；{@code commands[].type} 不在白名单（或自指）； {@code target} 非法；{@code effects[]}
 * 引用的效果不存在。
 */
public final class IssueDirectiveHandler implements CommandHandler {

  /**
   * 命令类型。
   *
   * <p>★ {@link #type()} 里仍写**字面量**而不是引用本常量：{@code SimosToolsTest} 从源码抽 {@code type()} 的返回串做"注册面 ==
   * 实现面"的强判据，扫描器只认 {@code return "…"} 形态（引用常量会被漏掉）。两处同值由下方注释钉住。 决策渠道据此判"这条落点要不要配一条 {@code
   * sd.PutInfo} 记执行原文"（见 {@code AbstractCoreDecisionChannel}）。
   */
  public static final String TYPE = "ga.IssueDirective";

  /**
   * 执行原文在 sd INFO 覆盖层里的 key（spec §二.3："单条决策 = 单条 Command，Command 必须自带 INFO 作为执行原文"）。
   *
   * <p>★ <b>拼写点只有一处</b>：本常量是 {@link DirectiveIntentInfo#INFO_KEY} 的别名（那条配套 {@code sd.PutInfo} 的载荷
   * 也用它），不在这里再写一遍字面量。{@code Directive.intentInfoKey} 记的就是它。
   */
  public static final String INTENT_INFO_KEY = DirectiveIntentInfo.INFO_KEY;

  private final DirectiveWhitelist whitelist;

  public IssueDirectiveHandler(DirectiveWhitelist whitelist) {
    this.whitelist = Objects.requireNonNull(whitelist, "whitelist");
  }

  @Override
  public String type() {
    return "ga.IssueDirective"; // ★ 必须与 TYPE 同值：扫描器要求字面量（见 TYPE 的注）
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    GaState base = GaSnapshots.state(state);
    // ★ 决策人 / 效果两张表仍住 sd（本批不搬，见批次 3b）⇒ 引用完整性判据在命令期读 sd 切片（既有的 ga → sd 方向）。
    SdState sd = GaSnapshots.sd(state);
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
      // ★ 载荷契约不变：intentInfo 必须是字符串（本命令只校验形状；它的落点是同批的第二条 sd.PutInfo，见类注）。
      SdPayloads.requireText(payload, "intentInfo");
      List<DirectiveCommand> commands = SdPayloads.optionalDirectiveCommands(payload, "commands");
      Set<EffectId> effects = parseEffectIds(payload);

      if (!sd.decisionMakers().containsKey(decisionMakerId)) {
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
        if (!sd.effects().containsKey(effectId)) {
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
      GaState target0 = base.withDirectives(nextDirectives);
      EventLog.channel(GaLog.decision())
          .info(
              LogEvent.of(
                  "SD_DIRECTIVE_ISSUED",
                  GaLogSource.GA_DECISION,
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
      return new HandlerOutcome.Applied(GaChangeSet.between(base, target0));
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
        GaLog.decision(),
        GaLogSource.GA_DECISION,
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

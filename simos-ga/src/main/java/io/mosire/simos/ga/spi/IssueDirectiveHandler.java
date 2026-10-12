package io.mosire.simos.ga.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.ga.GaLog;
import io.mosire.simos.ga.GaLogSource;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.change.GaChangeSet;
import io.mosire.simos.sd.id.DirectiveId;
import io.mosire.simos.sd.id.EffectId;
import io.mosire.simos.sd.model.Directive;
import io.mosire.simos.sd.model.DirectiveCommand;
import io.mosire.simos.sd.model.DirectiveStatus;
import io.mosire.simos.sd.model.SdInfoEntry;
import io.mosire.simos.sd.model.SdInfoIds;
import io.mosire.simos.sd.spi.DirectiveWhitelist;
import io.mosire.simos.sd.spi.SdPayloads;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.DecisionMakerId;
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
 * {@code ga.IssueDirective} 命令的处理器（spec §四，D1；<b>★ 2026-10-12 批次 3b-1 自 {@code simos-sd/sd.spi}
 * 迁入并改名 {@code sd.* → ga.*}；3b-2 起<b>执行原文回归本命令</b>，见下</b>）：落一条 {@link Directive}，按 R4 顶掉同 (决策人,
 * tick) 的旧版。
 *
 * <pre>{@code
 * {"directiveId":"d1","decisionMakerId":"dm1","tick":3,"target":"army:combat.c1",
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
 * <p>★★ <b>执行原文（{@code intentInfo}）由本命令<b>自己</b>写入（2026-10-12 批次 3b-2 <b>回归</b>）：一条命令 = 一个命名空间</b>
 * ——{@code info} 覆盖层（地址 {@code sd:directive.<id>}、key {@value #INTENT_INFO_KEY}）与 {@code
 * directives} 表<b>同住 {@code GaState}</b>（第 3/6 个组件）⇒ 令与执行原文落在<b>同一条 revision、同一条命令</b>里，二者不可分割。
 *
 * <p>★★ <b>批次 3b-1 的过渡态及其中止（如实记）</b>：3b-1 把令搬进 ga 而 {@code info} 仍住 sd ⇒ 当时不得不改用"**同批第二条命令 {@code
 * ga.PutInfo}**"补执行原文（{@link io.mosire.simos.sd.spi.DecisionTablesLookup 所在模块}的 {@code
 * DirectiveIntentInfo} + {@code AbstractNarrowWriteTool.companionRequests} + {@code
 * CommandSubmitTool} 的配套分支）。那条缺口（控制方 §16.4：绕过工具/渠道直接提交 {@code ga.IssueDirective} ⇒ 只落令、不落执行原文） 随本批
 * {@code info} 搬进 ga 而<b>关闭</b>：配套机制整条删除，"单条命令自带执行原文"的原形恢复。
 *
 * <p>★ <b>本命令自己要求</b> {@code intentInfo} 非空白（载荷契约不变）：它现在是这条条目<b>唯一</b>的来源。
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
   * 实现面"的强判据，扫描器只认 {@code return "…"} 形态（引用常量会被漏掉）。两处同值由下方注释钉住。
   */
  public static final String TYPE = "ga.IssueDirective";

  /**
   * 执行原文在 INFO 覆盖层里的 key（spec §二.3："单条决策 = 单条 Command，Command 必须自带 INFO 作为执行原文"）。
   *
   * <p>★ <b>拼写点只有一处</b>：{@code Directive.intentInfoKey} 记的就是它；批次 3b-1 曾把它别名到 sd 侧的 {@code
   * DirectiveIntentInfo.INFO_KEY}（那条配套命令的载荷生产者），该机制已随 3b-2 删除 ⇒ 常量回到本类自持（与搬迁前逐字同形）。
   */
  public static final String INTENT_INFO_KEY = "intent";

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
    // ★ 决策人表已在 ga（3b-2 搬入）⇒ 上面那条外键在状态期也判（GaState 构造期）；此处仍逐条判是为了给具名拒因。
    //   ★★ 2026-10-12 批次 3b-4：效果表也搬进本模块（GaState 第 7 组件）⇒ 下面那条判据从"读 sd 切片"改成
    //      读同一棵树（GaSnapshots.sd 已随本批删除：它失去了唯一调用者）。
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
      String infoAddress = intentInfoAddressOf(id);
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
      GaState next = base.withDirectives(nextDirectives).withInfo(nextInfo);
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
                  nextDirectives.size(),
                  "infoAddresses",
                  nextInfo.size()));
      return new HandlerOutcome.Applied(GaChangeSet.between(base, next));
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

  /**
   * 出令执行原文条目的 canonical 地址前缀（{@code sd:directive.<id>}）——<b>唯一拼写点</b>（搬迁前它住在 {@code
   * DirectiveIntentInfo.ADDRESS_PREFIX}，那个类随 companion 机制一起删了）。
   *
   * <p>★ <b>地址族串一字未改</b>（{@code sd:directive.<id>} 的裁量属批次 3e）：变的只是"表住哪个切片"。
   */
  static final String INTENT_INFO_ADDRESS_PREFIX = "sd:directive.";

  /** 执行原文条目的 canonical 地址（{@code sd:directive.<id>}）。 */
  static String intentInfoAddressOf(DirectiveId id) {
    return INTENT_INFO_ADDRESS_PREFIX + id.value();
  }
}

package io.mosire.simos.app.sd;

import io.mosire.simos.app.llm.LlmProviderResolver;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.sd.adjudication.Breakpoints;
import io.mosire.simos.sd.adjudication.DecisionAdjudicator;
import io.mosire.simos.sd.adjudication.Judgement;
import io.mosire.simos.sd.id.CombatId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.Combat;
import io.mosire.simos.sd.model.CombatStage;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.OutcomeOption;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateRef;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 「开始决策」编排（T3，把 D7 的 {@link AdjudicatorRunner} **接进壳**）：一次调用 = 一个决策人的一次判决。
 *
 * <p>★ **它解决的真实缺口**：D 阶段交付了 {@link AdjudicatorRunner}，但**全仓只有测试调用它** ⇒ 用户点「开始决策」 只写一条 INFO
 * 覆盖层记录（{@code sd.StartDecision}），**判决从不真跑**。本类是那个"接线"。
 *
 * <p>★ **形状（为什么是这么一条窄路，而不是"推进时自动跑全部断点"）**：
 *
 * <ol>
 *   <li>调用方先经 {@code CoreSimos.submit} 落一条 {@code sd.StartDecision}（**世界事实**：谁在哪个 tick 发起了决策）；
 *   <li>本服务从**新的 head** 读 sd 切片，取该决策人**当前**绑定的 provider（{@link DecisionMaker#providerId()}）， 由
 *       {@link LlmProviderResolver#adjudicatorFor} 装配裁决器；
 *   <li>按**世界状态选断点**跑判决（见下"战斗不是前提"）；
 *   <li>判决经 {@link AdjudicatorRunner} 折成 {@code sd.SubmitVerdict} 落成**真 revision**（N7：回放不重跑 LLM）。
 * </ol>
 *
 * <p>★ **战斗不是决策的前提**（2026-09-22 用户裁定，逐字）：「**战斗是一个状态**，**决策是随时可以做的**」⇒ 断点选择为：
 *
 * <ul>
 *   <li>**有战斗** ⇒ 跑战斗判决断点 D1/D3（合并一次调用）+ D6，主体 {@code sd:combat.<id>}（现状不变）；
 *   <li>**无战斗** ⇒ **不跳过判决**，跑 D2（决策人自己的决策记录），主体 {@code sd:decision-maker.<id>}——LLM 照常被调。
 * </ul>
 *
 * 战斗/情报/世界状态一律是判决的**输入**（进简报 / {@link AdjudicationRequest}），**不是 gate**。★ **哪些断点天然需要战斗上下文**：
 * D1/D3（阶段 exit）/ D6（战斗收尾）——本实现仅在**存在战斗**时跑它们；D2 无战斗前提。D4/D5/D7/D8 的产出是 草案，本服务**不替它们编草稿**。
 *
 * <p>★ **失败语义（N13 / B3）**：单条判决失败且**可降级** ⇒ 该断点本 tick 无判决、后续断点继续（{@link AdjudicatorRunner}
 * 保证）；**不可降级**的失败（配置错等）⇒ **冒泡**，由调用方如实报错，绝不静默降级、绝不换 provider 顶上。
 *
 * <p>★ **不静默**：未绑定 provider / 绑定悬空 / 路由坏掉都会抛（{@link LlmProviderResolver} 的 fail-closed），
 * 本类**不吞**——调用方看得见"为什么这次没有判决"。
 */
public final class DecisionAdjudicationService {

  private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
      SimosObjectMapper.create();

  private final CoreSimos core;
  private final LlmProviderResolver resolver;

  public DecisionAdjudicationService(CoreSimos core, LlmProviderResolver resolver) {
    this.core = Objects.requireNonNull(core, "core");
    this.resolver = Objects.requireNonNull(resolver, "resolver");
  }

  /**
   * 跑一次决策：按**世界状态选**产判决的断点逐个裁决，判决落成 revision。
   *
   * <p>★ **无战斗不跳过判决**：有战斗 ⇒ D1/D3/D6（主体 {@code sd:combat.<id>}）；无战斗 ⇒ D2（主体 {@code
   * sd:decision-maker.<dmId>}）——LLM 一律被调。
   *
   * @param branch 分支（应为 {@code sd.StartDecision} 刚落盘的那个分支）
   * @param baseRevision 起始 base（新 head；乐观并发，落点成功后前移）
   * @param maker 决策人（调用方从**新 head** 读出的那个——绑定的 provider 以它为准）
   * @return 各断点的判决（保序）
   * @throws IllegalStateException 未绑定 provider / 绑定悬空（fail-closed，不兜底）
   */
  public List<Judgement> adjudicate(BranchId branch, RevisionId baseRevision, DecisionMaker maker) {
    Objects.requireNonNull(branch, "branch");
    Objects.requireNonNull(baseRevision, "baseRevision");
    Objects.requireNonNull(maker, "maker");
    SdState sd = sdOf(core.replay(new StateRef(branch, baseRevision)));
    DecisionAdjudicator adjudicator = resolver.adjudicatorFor(maker);
    AdjudicatorRunner runner = new AdjudicatorRunner(core, adjudicator);
    Optional<Combat> subject = firstCombat(sd);
    if (subject.isEmpty()) {
      // ★ 无战斗 ⇒ 不跳过判决，跑决策人自己的决策断点 D2（战斗只是输入，不是前提）。
      String decisionSubject = "sd:decision-maker." + maker.id().value();
      return runner.run(
          branch,
          baseRevision,
          List.of(Breakpoints.D2),
          Map.of(Breakpoints.D2, decisionSubject),
          Map.of(Breakpoints.D2, decisionBrief(sd, maker)));
    }
    Combat combat = subject.get();
    String canonical = "sd:combat." + combat.id().value();
    String brief = briefJson(sd, maker, combat);
    return runner.run(
        branch,
        baseRevision,
        List.of(Breakpoints.D1, Breakpoints.D3, Breakpoints.D6),
        Map.of(Breakpoints.D1, canonical, Breakpoints.D3, canonical, Breakpoints.D6, canonical),
        Map.of(Breakpoints.D1, brief, Breakpoints.D6, brief));
  }

  /**
   * 决策人的**事实性**简报（D2，无战斗时用）：只摆该决策人自己与世界**已有**的字段（归属 / 允许工具 / 决策周期 / 已存在的战斗与国家
   * 清单），战斗/情报作**输入**摆出来，**不发明候选**。guidance 只复述 schema 的字段要求。
   */
  private static String decisionBrief(SdState sd, DecisionMaker maker) {
    Map<String, Object> brief = new LinkedHashMap<>();
    brief.put("decisionMakerId", maker.id().value());
    brief.put("affiliation", affiliationView(maker));
    brief.put("allowedTools", new ArrayList<>(maker.allowedTools()));
    brief.put("decisionCadenceTicks", maker.decisionCadenceTicks());
    brief.put("combats", sortedCombatIds(sd));
    brief.put("nations", sortedNationIds(sd));
    brief.put(
        "guidance",
        "本 tick 无战斗对象；战斗/情报只是判决的输入，不是前提。按 schema 出令：directiveId 自拟、intentText 写决心、commands 是数组（可为空）、rationaleText 写理由；无法裁决则弃权。");
    try {
      return MAPPER.writeValueAsString(brief);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("决策简报序列化失败: " + e.getOriginalMessage(), e);
    }
  }

  private static Map<String, Object> affiliationView(DecisionMaker maker) {
    Map<String, Object> view = new LinkedHashMap<>();
    switch (maker.affiliation()) {
      case Affiliation.Nation nation -> {
        view.put("kind", "nation");
        view.put("id", nation.nationId().value());
      }
      case Affiliation.Army army -> {
        view.put("kind", "army");
        view.put("id", army.armyId().value());
      }
      case Affiliation.Gov gov -> {
        view.put("kind", "gov");
        view.put("id", gov.govUnit().value());
      }
    }
    return view;
  }

  private static List<String> sortedCombatIds(SdState sd) {
    List<String> ids = new ArrayList<>();
    for (CombatId combatId : sd.combats().keySet()) {
      ids.add(combatId.value());
    }
    Collections.sort(ids);
    return ids;
  }

  private static List<String> sortedNationIds(SdState sd) {
    List<String> ids = new ArrayList<>();
    for (NationId nationId : sd.nations().keySet()) {
      ids.add(nationId.value());
    }
    Collections.sort(ids);
    return ids;
  }

  /**
   * 事实性脱敏简报（取代说明，2026-09-22）：D 阶段 {@code AdjudicatorRunner} 把简报写死 {@code "{}"}，真模型因此拿不到任何 上下文（D1/D3
   * 的 schema 要 {@code stageId}/{@code selectedOutcomeId}，却无从知道可选值）⇒ 只能弃权。本方法只把**这场战斗 自己已有的**数据（id /
   * 名 / 参战单位 / 阶段 / 结局候选）如实摆出来，**不发明候选**；无阶段时 {@code stages} 为空数组。
   */
  private static String briefJson(SdState sd, DecisionMaker maker, Combat combat) {
    List<Map<String, Object>> stages = new ArrayList<>();
    for (CombatStage stage : combat.stages()) {
      List<Map<String, Object>> outcomes = new ArrayList<>();
      for (OutcomeOption option : stage.outcomes().options()) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("outcomeId", option.id().value());
        row.put("label", option.label());
        outcomes.add(row);
      }
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("stageId", stage.id().value());
      row.put("name", stage.name());
      row.put("outcomes", outcomes);
      stages.add(row);
    }
    List<String> participants = new ArrayList<>();
    for (var unit : combat.participants()) {
      participants.add(unit.value());
    }
    Map<String, Object> brief = new LinkedHashMap<>();
    brief.put("combatId", combat.id().value());
    brief.put("combatName", combat.name());
    brief.put("participants", participants);
    brief.put("stages", stages);
    brief.put("decisionMakerId", maker.id().value());
    brief.put(
        "guidance",
        "stageId 必须取自 stages[].stageId，selectedOutcomeId 必须取自对应的 outcomes[].outcomeId；无可选值则弃权。");
    try {
      return MAPPER.writeValueAsString(brief);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("简报序列化失败: " + e.getOriginalMessage(), e);
    }
  }

  private Optional<Combat> firstCombat(SdState sd) {
    List<String> ids = new ArrayList<>();
    for (CombatId combatId : sd.combats().keySet()) {
      ids.add(combatId.value());
    }
    Collections.sort(ids);
    if (ids.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(sd.combats().get(new CombatId(ids.get(0))));
  }

  private static SdState sdOf(SimulationState state) {
    Snapshot slice = state.module("sd").orElse(null);
    if (!(slice instanceof SdSnapshot sd)) {
      throw new IllegalStateException("sd 切片缺失：世界未装配 SDSimos");
    }
    return sd.state();
  }
}

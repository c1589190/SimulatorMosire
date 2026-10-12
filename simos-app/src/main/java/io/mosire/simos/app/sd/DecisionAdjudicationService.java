package io.mosire.simos.app.sd;

import io.mosire.simos.app.llm.LlmProviderResolver;
import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.army.CombatOutcome;
import io.mosire.simos.army.CombatRecord;
import io.mosire.simos.army.CombatRecordId;
import io.mosire.simos.army.CombatStage;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.gov.GovSnapshot;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.gov.nation.NationId;
import io.mosire.simos.sd.adjudication.Breakpoints;
import io.mosire.simos.sd.adjudication.DecisionAdjudicator;
import io.mosire.simos.sd.adjudication.Judgement;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
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
 * 覆盖层记录（{@code ga.StartDecision}），**判决从不真跑**。本类是那个"接线"。
 *
 * <p>★ **形状（为什么是这么一条窄路，而不是"推进时自动跑全部断点"）**：
 *
 * <ol>
 *   <li>调用方先经 {@code CoreSimos.submit} 落一条 {@code ga.StartDecision}（**世界事实**：谁在哪个 tick 发起了决策）；
 *   <li>本服务从**新的 head** 读世界，取该决策人**当前**绑定的 provider（{@link DecisionMaker#providerId()}）， 由 {@link
 *       LlmProviderResolver#adjudicatorFor} 装配裁决器；
 *   <li>按**世界状态选断点**跑判决（见下"战斗不是前提"）；
 *   <li>判决经 {@link AdjudicatorRunner} 折成 {@code ga.SubmitVerdict} 落成**真 revision**（N7：回放不重跑 LLM）。
 * </ol>
 *
 * <p>★ **战斗不是决策的前提**（2026-09-22 用户裁定，逐字）：「**战斗是一个状态**，**决策是随时可以做的**」⇒ 断点选择为：
 *
 * <ul>
 *   <li>**有交战记录** ⇒ 跑战斗判决断点 D1/D3（合并一次调用）+ D6，主体 {@code army:combat.<id>}；
 *   <li>**无交战记录** ⇒ **不跳过判决**，跑 D2（决策人自己的决策记录），主体 {@code sd:decision-maker.<id>}——LLM 照常被调。
 * </ul>
 *
 * 战斗/情报/世界状态一律是判决的**输入**（进简报 / {@link AdjudicationRequest}），**不是 gate**。★ **哪些断点天然需要战斗上下文**：
 * D1/D3（阶段 exit）/ D6（战斗收尾）——本实现仅在**存在交战记录**时跑它们；D2 无战斗前提。D4/D5/D7/D8 的产出是草案，本服务**不替它们编草稿**。
 *
 * <p>★★ <b>2026-10-12 批次 3d-2：交战记录的来源由 sd 换成 army（判决面重挂的编排侧）</b>——旧战斗族整族退役（用户裁定「战斗模块， 包括阶段之类的，归
 * Army 模块管」＋「sd 整个弃用！」），交战记录的<b>唯一</b>载体是 {@code ArmyData.combats}（{@code CombatRecord}）。本类的三处随之改：①
 * 取"第一条记录"改读 army 切片；② subject 串由 {@code sd:combat.<id>} 改为 {@code army:combat.<id>}（{@code
 * Breakpoints.acceptsVerdictSubject} 同步改挂，否则 {@code ga.SubmitVerdict} 当场拒）； ③ 简报的字段名（{@code
 * combatId} / {@code combatName} / {@code participants} / {@code stages[].stageId} / {@code
 * outcomes[].outcomeId}）<b>逐字保留</b>——D1/D3 的 schema 就是按这几个键写的，改键名等于改判决契约。
 *
 * <p>★ <b>失败语义（N13 / B3）</b>：单条判决失败且**可降级** ⇒ 该断点本 tick 无判决、后续断点继续（{@link AdjudicatorRunner}
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
   * <p>★ **无交战记录不跳过判决**：有记录 ⇒ D1/D3/D6（主体 {@code army:combat.<id>}）；无记录 ⇒ D2（主体 {@code
   * sd:decision-maker.<dmId>}）——LLM 一律被调。
   *
   * @param branch 分支（应为 {@code ga.StartDecision} 刚落盘的那个分支）
   * @param baseRevision 起始 base（新 head；乐观并发，落点成功后前移）
   * @param maker 决策人（调用方从**新 head** 读出的那个——绑定的 provider 以它为准）
   * @return 各断点的判决（保序）
   * @throws IllegalStateException 未绑定 provider / 绑定悬空（fail-closed，不兜底）
   */
  public List<Judgement> adjudicate(BranchId branch, RevisionId baseRevision, DecisionMaker maker) {
    Objects.requireNonNull(branch, "branch");
    Objects.requireNonNull(baseRevision, "baseRevision");
    Objects.requireNonNull(maker, "maker");
    // ★ 2026-10-12 批次 3c：这一份 state 要喂两片——army（交战记录，本批 3d-2 起）与 gov（国家表，见 decisionBrief）。
    SimulationState state = core.replay(new StateRef(branch, baseRevision));
    DecisionAdjudicator adjudicator = resolver.adjudicatorFor(maker);
    AdjudicatorRunner runner = new AdjudicatorRunner(core, adjudicator);
    ArmyData army = armyOf(state);
    Optional<CombatRecord> subject = firstCombat(army);
    if (subject.isEmpty()) {
      // ★ 无交战记录 ⇒ 不跳过判决，跑决策人自己的决策断点 D2（战斗只是输入，不是前提）。
      String decisionSubject = "sd:decision-maker." + maker.id().value();
      return runner.run(
          branch,
          baseRevision,
          List.of(Breakpoints.D2),
          Map.of(Breakpoints.D2, decisionSubject),
          Map.of(
              Breakpoints.D2,
              decisionBrief(sortedCombatIds(army.combats().keySet()), govOf(state), maker)));
    }
    CombatRecord record = subject.get();
    // ★★ 2026-10-12 批次 3d-2：subject 由 sd:combat.<id> 改为 army:combat.<id>（判决面重挂；
    //   字面量与 io.mosire.simos.army.ArmyAddresses 的 canonical 逐字一致——那边是 AST 产出，这里是最小改动）。
    String canonical = "army:combat." + record.id().value();
    String brief = briefJson(maker, record);
    return runner.run(
        branch,
        baseRevision,
        List.of(Breakpoints.D1, Breakpoints.D3, Breakpoints.D6),
        Map.of(Breakpoints.D1, canonical, Breakpoints.D3, canonical, Breakpoints.D6, canonical),
        Map.of(Breakpoints.D1, brief, Breakpoints.D6, brief));
  }

  /**
   * 决策人的**事实性**简报（D2，无交战记录时用）：只摆该决策人自己与世界**已有**的字段（归属 / 允许工具 / 决策周期 / 已存在的交战记录与国家
   * 清单），战斗/情报作**输入**摆出来，**不发明候选**。guidance 只复述 schema 的字段要求。
   */
  private static String decisionBrief(List<String> combatIds, GovState gov, DecisionMaker maker) {
    Map<String, Object> brief = new LinkedHashMap<>();
    brief.put("decisionMakerId", maker.id().value());
    brief.put("affiliation", affiliationView(maker));
    brief.put("allowedTools", new ArrayList<>(maker.allowedTools()));
    brief.put("decisionCadenceTicks", maker.decisionCadenceTicks());
    brief.put("combats", combatIds);
    brief.put("nations", sortedNationIds(gov));
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
        view.put("id", nation.nationId());
      }
      case Affiliation.Army army -> {
        view.put("kind", "army");
        view.put("id", army.armyId());
      }
      case Affiliation.Gov gov -> {
        view.put("kind", "gov");
        view.put("id", gov.govUnit().value());
      }
    }
    return view;
  }

  /**
   * 已存在的交战记录 id（字典序）。
   *
   * <p>★ <b>2026-10-12 批次 3d-2：改读 army 的 {@code CombatRecord} 表（如实记）</b>——它原来读 sd 切片的 {@code
   * combats}。★ 形参是 id 表而不是整个 state：切片取用与装配故障判定只有 {@link #armyOf} 一处，本方法只负责"排序 + 成列"。
   */
  private static List<String> sortedCombatIds(java.util.Collection<CombatRecordId> ids) {
    List<String> out = new ArrayList<>(ids.size());
    for (CombatRecordId id : ids) {
      out.add(id.value());
    }
    Collections.sort(out);
    return out;
  }

  /** ★ 2026-10-12 批次 3c：国家表搬进 gov 切片（外交是 gov 的事）⇒ 简报的国家清单从这里取。 */
  private static List<String> sortedNationIds(GovState gov) {
    List<String> ids = new ArrayList<>();
    for (NationId nationId : gov.nations().keySet()) {
      ids.add(nationId.value());
    }
    Collections.sort(ids);
    return ids;
  }

  /**
   * 事实性脱敏简报（取代说明，2026-09-22）：D 阶段 {@code AdjudicatorRunner} 把简报写死 {@code "{}"}，真模型因此拿不到任何 上下文（D1/D3
   * 的 schema 要 {@code stageId}/{@code selectedOutcomeId}，却无从知道可选值）⇒ 只能弃权。本方法只把**这场战斗 自己已有的**数据（id /
   * 名 / 参战单位 / 阶段 / 结局候选）如实摆出来，**不发明候选**；无阶段时 {@code stages} 为空数组。
   *
   * <p>★ <b>2026-10-12 批次 3d-2：数据源由 sd 的 {@code Combat} 换成 army 的 {@link CombatRecord}</b>——键名
   * （{@code combatId} / {@code combatName} / {@code participants} / {@code stages[].stageId} /
   * {@code stages[].name} / {@code stages[].outcomes[].outcomeId} / {@code
   * stages[].outcomes[].label}）<b>逐字保留</b>： D1/D3 的 schema 就是按这几个键写的。★ 取值语义有两处<b>如实</b>差异：① army 的
   * {@link CombatStage#participants()} 与 {@link CombatRecord#participants()} 都是<b>有序列表</b>（旧 sd
   * 是集合）⇒ 顺序原样透出（不再排序）；② army 的阶段 {@code outcomes} 是<b>列表</b>（旧 sd 是 {@code {options:[…]}} 包一层）⇒
   * 这里直取列表。
   */
  private static String briefJson(DecisionMaker maker, CombatRecord record) {
    List<Map<String, Object>> stages = new ArrayList<>();
    for (CombatStage stage : record.stages()) {
      List<Map<String, Object>> outcomes = new ArrayList<>();
      for (CombatOutcome outcome : stage.outcomes()) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("outcomeId", outcome.id().value());
        row.put("label", outcome.label());
        outcomes.add(row);
      }
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("stageId", stage.id().value());
      row.put("name", stage.name());
      row.put("outcomes", outcomes);
      stages.add(row);
    }
    List<String> participants = new ArrayList<>();
    for (var unit : record.participants()) {
      participants.add(unit.value());
    }
    Map<String, Object> brief = new LinkedHashMap<>();
    brief.put("combatId", record.id().value());
    brief.put("combatName", record.kind());
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

  /**
   * 世界里的第一条交战记录（按 id 字典序；无 ⇒ 空）。
   *
   * <p>★ <b>2026-10-12 批次 3d-2：改读 army 切片</b>（旧战斗族退役）。★ 取"第一条"的口径<b>逐字保留</b>（先按 id 字典序，再取首条）：
   * 判决面今天仍是"本 tick 对一条交战裁决"，多记录的选择策略不在本批范围内（与迁移前逐字同构）。
   */
  private static Optional<CombatRecord> firstCombat(ArmyData army) {
    List<String> ids = sortedCombatIds(army.combats().keySet());
    if (ids.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(army.combats().get(new CombatRecordId(ids.get(0))));
  }

  /** ★ 2026-10-12 批次 3c：gov 切片（国家表的新家）；缺切片/类型不对 = 装配故障，当场炸。 */
  private static GovState govOf(SimulationState state) {
    Snapshot snapshot =
        state.module("gov").orElseThrow(() -> new IllegalStateException("state 里没有 gov 切片（装配故障）"));
    if (!(snapshot instanceof GovSnapshot govSnapshot)) {
      throw new IllegalStateException(
          "state 的 gov 切片不是 GovSnapshot: " + snapshot.getClass().getName());
    }
    return govSnapshot.state();
  }

  /**
   * ★★ 2026-10-12 批次 3d-2：army 切片（交战记录的新家，D-012「交战的具体记录移到 Army 模块记录与处理」）。
   *
   * <p>★★ <b>缺切片 ⇒ 空表（不是抛，如实记）</b>：这与 {@link #govOf} / {@code ToolSupport.sdState} 的"装配故障当场炸"
   * <b>有意不同</b>，理由是判决面今天<b>不会</b>因为缺 army 切片而失效——无交战记录时它照常跑 D2（战斗只是输入不是前提），
   * 而"缺切片"与"没有交战记录"在这个判据上<b>后果完全相同</b>（都不跑 D1/D3/D6）。⇒ 方向是 <b>fail-closed</b>：绝不凭空跑战斗判决。 ★
   * 类型不对（切片在、但不是 {@code ArmySnapshot}）仍<b>当场炸</b>——那是装配写错，不是"没有记录"。
   */
  private static ArmyData armyOf(SimulationState state) {
    Snapshot snapshot = state.module("army").orElse(null);
    if (snapshot == null) {
      return ArmyData.empty();
    }
    if (!(snapshot instanceof ArmySnapshot armySnapshot)) {
      throw new IllegalStateException(
          "state 的 army 切片不是 ArmySnapshot: " + snapshot.getClass().getName());
    }
    return armySnapshot.data();
  }
}

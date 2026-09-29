package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ {@code economy.Seed} 命令的处理器（聚合式经济重设计 §十 的 R2a）：**一次把某国全部格的初始经济状态种进 economy 切片** ——一条命令、一条
 * revision（与 {@code social.SetPopulation} 同制）。
 *
 * <p>★★ **它只做 materialize，不做任何公式**：每格人口、阶层比例、土地、有效劳动、库存全在**载荷**里（由 {@code EconomySeeder} 按 §十
 * 的口径算好），本类把它翻成 §3 的领域类型。理由：§八 R1 行"模块化、无公式"是 economy 切片的既定边界，而且
 * "一切数字来自场景参数"这条纪律落在生成器一处即可（两条路各算一遍必然漂移）。
 *
 * <p>★★ **已激活（{@code meta} 非空）⇒ 按格追加**（user 2026-09-25 裁定，取代原"已激活即拒"）：世界有**多国**，各国先后各播一批
 * （三国的格互不相同）——按**库**判会播完第一国就把后两国的整批（人口/城市/军队）一起挡回滚。故判据降到**格**：
 *
 * <ul>
 *   <li>{@code meta} 空 ⇒ **首次播种**：整份载荷落盘并**打标**（现有逻辑保留）。
 *   <li>{@code meta} 非空 ⇒ **逐格**判：该格（{@code q,r}，经 {@link IndustryHexKeys#hexKeyOf} 认）若已有产业/阶层行 ⇒
 *       **拒绝并点名该格**； 否则把该格追加进现有切片。**{@code meta} 不覆盖**（保留首次的 {@code activatedDay}/{@code
 *       rulesVersion}）。
 * </ul>
 *
 * <p>★ **目标资源**（{@link CommandTargets}）：{@code entries[]} 里**每一个**格的 {@link
 * ResourcePaths#economy(int, int)} （{@code <q>_<r>}）——GM 代执行决策人令时据此逐条判越权（与 {@code
 * SetPopulationHandler} 同款）。
 *
 * <p>★ **校验分工**：形状/类型在本包 {@link EconomyPayloads} 判；数值语义（非负、槽位 ∈ 该产业 slots、{@code progressDays ≤
 * cycleDays}）由 §3 的领域类型与 {@link EconomyData} 构造期守卫判——**不重复实现**。两者的失败都以 {@code Rejected} 出面。
 */
public final class EconomySeedHandler implements CommandHandler, CommandTargets {

  @Override
  public String type() {
    return "economy.Seed";
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    return EconomyPayloads.entryHexKeys(EconomyPayloads.parse(payloadJson));
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    EconomyData base = EconomySnapshots.of(state).data(); // 装配故障当场炸，不走拒绝路径
    JsonNode payload;
    EconomyData seeded;
    try {
      payload = EconomyPayloads.parse(payloadJson);
      seeded = EconomyPayloads.toData(payload, state.meta().timestamp());
    } catch (IllegalArgumentException | IllegalStateException e) {
      // ★ 旧形状载荷（缺 household/memberships）会在 EconomyData 构造期的 LegacyHouseholdMigration 里
      //   以 IllegalStateException fail-closed（"无法定位产业格"等）—— 它同样是**载荷语义错误**，
      //   必须在命令边界成为 Rejected，不允许穿出去变成整条推进/revision 失败（类注的"失败都以 Rejected 出面"）。
      return new HandlerOutcome.Rejected(e.getMessage());
    }
    if (base.meta().isEmpty()) {
      return new HandlerOutcome.Applied(EconomyChangeSet.between(base, seeded)); // 首次播种：打标
    }
    // ★ 已激活 ⇒ 按格追加：先逐格判重（任一格已被占用 ⇒ 整份拒绝并点名该格），再并入现有切片。
    Set<String> occupied = occupiedHexKeys(base);
    for (String hex : EconomyPayloads.entryHexKeys(payload)) {
      if (occupied.contains(hex)) {
        return new HandlerOutcome.Rejected(
            "格 " + hex + " 已有经济状态（产业/阶层行），拒绝重复播种: mapId=" + base.meta().orElseThrow().mapId());
      }
    }
    EconomyData merged =
        new EconomyData(
            base.meta(), // ★ 不覆盖：保留首次的 activatedDay / rulesVersion
            merge(base.industries(), seeded.industries()),
            merge(base.classes(), seeded.classes()),
            merge(base.debtContracts(), seeded.debtContracts()),
            base.flows(),
            // ★ R2：劳动供给与配额**按格追加**（与产业/阶层行同一套判重口径：该格已被占用 ⇒ 上面就拒了），
            //   故这里按表合并即可 —— 各国的批次 id 互不相同（含格/城的 id 段）。
            merge(base.laborSupply(), seeded.laborSupply()),
            merge(base.allocations(), seeded.allocations()),
            // ★ T2：第 8 个组件按同一套判重口径追加（该格已被占用 ⇒ 上面就拒了）。
            merge(base.relations(), seeded.relations()),
            // ★ H4：第 9 个组件（每格的现货市场）按同一套判重口径追加 —— ★ 漏了它 = 播种时给的价格
            //   **静默消失**（世界照旧起得来，只是市场全无、城市缺口不收敛，而账面上看不出是谁弄丢的）。
            merge(base.markets(), seeded.markets()),
            // ★ M2.4：第 10 个组件（在途批次）—— 播种载荷没有在途，但**必须原样带过已有批次**：
            //   漏了它 = 一次按格追加播种会把全世界正在路上的货物静默抹掉（比"没播"更糟：货权凭据消失）。
            merge(base.shipments(), seeded.shipments()),
            // ★ S1：成员份额与 R3B.1 的实物资产份额按同一套"该格已被占用 ⇒ 上面就拒"的口径追加 —— 漏了它们 =
            //   第二批播种的家户成员份额/资产份额静默消失（世界照旧起得来，缺口却在账面上查无此人）。
            merge(base.memberships(), seeded.memberships()),
            merge(base.assetShares(), seeded.assetShares()),
            // ★ S3 预留的第 13 个组件：同一套"该格已被占用 ⇒ 上面就拒"的口径追加（空表播种 ⇒ 逐值带过已有状态）。
            merge(base.operatorConditions(), seeded.operatorConditions()),
            // ★★ R3B.2 第 14 个组件（生产单元）：同一套口径追加 —— 漏了它 = 新播的 unit 静默消失，
            //   而关系/条件指着这些 unit ⇒ 构造期守卫当场把整批种子拒掉（宁可当场拒，不静默半播）。
            merge(base.units(), seeded.units()),
            // ★★ R4-E2 第 15/16 个组件：播种载荷不含需求/候选预设 ⇒ 原样带过已有状态（漏了它 = 再播一国时
            //   把 GM 注入的需求/预设静默抹掉）。
            base.demands(),
            base.candidates(),
            // ★★ E1 第 17–20 个组件：播种载荷暂不声明新地基 ⇒ 逐值带过已有状态（漏了它 = 后续命令写入的
            //   mode/作业/归属静默消失；E1 不接线结算，但新状态必须能被后续阶段安全地跨命令保留）。
            merge(base.modes(), seeded.modes()),
            merge(base.classStructures(), seeded.classStructures()),
            merge(base.classPositions(), seeded.classPositions()),
            merge(base.classStandings(), seeded.classStandings()),
            // ★★ E2 第 21/22 个组件：同一套"该格已被占用 ⇒ 上面就拒"的口径追加（空表播种 ⇒ 逐值带过已有状态）。
            merge(base.productionOrganizations(), seeded.productionOrganizations()),
            merge(base.assetRules(), seeded.assetRules()),
            // ★★ E3 第 23/24 个组件：政府按 id 幂等合并（同一份世界级最小政府在三国的 seed 里逐值相同），
            //   发行记录按 id 追加（每个 seed 一条 INITIAL_ENDOWMENT 聚合记录；id 含该 seed 的格集指纹）。
            //   ★ 漏了这两项 = 已播国家的政府/发行记录在后续国家 seed 时静默消失（账面上看不出是谁弄丢的）。
            merge(base.governments(), seeded.governments()),
            merge(base.moneyIssuances(), seeded.moneyIssuances()),
            // ★★ E4a 的第 25 个组件：质押按同一套“该格已被占用 ⇒ 上面就拒”的口径追加（空表播种 ⇒ 逐值带过已有状态）。
            merge(base.pledges(), seeded.pledges()),
            // ★★ E5a 的第 26/27 个组件：清算政策/危机信号也按同一套 append 口径合并 —— 漏了它们 =
            //   已播国家的清算制度参数与 hex 危机信号在后续国家 seed 时静默消失（账面上看不出是谁弄丢的）。
            //   ★ 危机信号键 = (hex, kind)，同键以后播的载荷为准（覆盖即更新，见 HexCrisisSignal 类注）。
            merge(base.liquidationPolicies(), seeded.liquidationPolicies()),
            merge(base.crisisSignals(), seeded.crisisSignals()),
            // ★★ E6a 的第 28/29 个组件：同一套 append 口径合并 —— 漏了它们 = 已有模式变迁/保留份额在后续
            //   国家 seed 时静默消失（新播的 seed 载荷通常为空表，逐值带过已有状态）。
            merge(base.modeTransitions(), seeded.modeTransitions()),
            merge(base.classShares(), seeded.classShares()));
    return new HandlerOutcome.Applied(EconomyChangeSet.between(base, merged));
  }

  /** 现有切片里**已被占用的格键**（{@code <q>_<r>}）：从产业 id 与**家户行的格**里认（{@link IndustryHexKeys} 是唯一拼写点）。 */
  private static Set<String> occupiedHexKeys(EconomyData base) {
    Set<String> hexes = new LinkedHashSet<>();
    for (IndustryId id : base.industries().keySet()) {
      IndustryHexKeys.hexKeyOf(id).ifPresent(hexes::add);
    }
    for (var row : base.classes().values()) {
      hexes.add(IndustryHexKeys.hexKey(row.view().hex().q(), row.view().hex().r()));
    }
    return hexes;
  }

  /** 追加表：保留 {@code base} 的插入序，再把新增项接在后面（保序不可变的纯形态仍由 {@link EconomyData} 构造期冻结）。 */
  private static <K, V> Map<K, V> merge(Map<K, V> base, Map<K, V> added) {
    LinkedHashMap<K, V> merged = new LinkedHashMap<>(base);
    merged.putAll(added);
    return merged;
  }
}

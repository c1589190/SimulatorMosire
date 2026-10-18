package io.mosire.simos.app.world;

import static io.mosire.simos.app.world.IndustrySeeder.singleOperatorRoleNodes;
import static io.mosire.simos.app.world.LandOwnershipSeeder.ownershipStakeNode;
import static io.mosire.simos.app.world.RuralLaborSeeder.appendAllocation;
import static io.mosire.simos.app.world.RuralLaborSeeder.industryDailyLabor;
import static io.mosire.simos.app.world.SeederPayloadSupport.actorNode;
import static io.mosire.simos.app.world.SeederPayloadSupport.relationNode;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.relation.LaborSource;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.social.population.PopulationGroup;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** GOV 创世家户、铸币产业与创世审计政府。 */
class GovernmentSeeder extends WorldBalanceSeeder {

  // ── 2026-10-07 GOV 非生产家户试点：种子形状 ───────────────────────────────────────────────

  /**
   * ★★ <b>把 GOV 家户追加进生产运行时 seed</b>：在 Social 播种已建好的 GOV 家户（人口 0、{@code slot=official}， 见 {@link
   * PopulationSeeder}）所在格加一条行，并在 {@code householdLocations}/{@code householdStocks}/{@code
   * householdMoney} 三张表里给它一本空账。
   *
   * <p>★★ <b>P2-A：身份/落点由 Social 的 {@link PopulationSeeder.Seeding#governmentHousehold()}
   * 给出</b>（经济侧只读， 不再自己挑格、另拼 id）。它<b>不</b>进 laborSupply/allocations/memberships/units/assetShares ——
   * 不生产、不出劳动、不持资产。 没有 HouseholdClassMembership（{@code productionRuntimeClassMemberships} 对 official
   * 槽位显式跳过）：关账日 HouseholdClassRule 在无可观察证据时保留当前 view。
   */
  static HouseholdId seedGovernmentHousehold(
      List<Map<String, Object>> entries,
      List<Household> socialHouseholds,
      HouseholdId householdId,
      Map<HouseholdId, HexCoord> householdLocations,
      Map<HouseholdId, Map<CommodityId, Long>> householdStocks,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney) {
    HexCoord hex = null;
    for (Household household : socialHouseholds) {
      if (!household.id().equals(householdId)) {
        continue;
      }
      if (household.location() instanceof HouseholdLocation.Hex at) {
        hex = at.hex();
      }
      break;
    }
    if (hex == null) {
      throw new IllegalStateException("GOV 家户不在 Social 播种的家户集里（P2-A 起经济侧只读 Social）：" + householdId);
    }
    householdLocations.put(householdId, hex);
    householdStocks.put(householdId, Map.of());
    householdMoney.put(householdId, Map.of());

    Map<String, Object> row = governmentHouseholdEconomy(householdId);
    boolean added = false;
    for (Map<String, Object> entry : entries) {
      int q = ((Number) entry.get("q")).intValue();
      int r = ((Number) entry.get("r")).intValue();
      if (q != hex.q() || r != hex.r()) {
        continue;
      }
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> classes = (List<Map<String, Object>>) entry.get("classes");
      classes.add(row);
      added = true;
      break;
    }
    if (!added) {
      throw new IllegalStateException(
          "GOV 家户找不到落点 entry（seed 内部不一致）：hex=" + hex + " entries=" + entries.size());
    }
    return householdId;
  }

  /**
   * ★★★ <b>给政府家户播一个铸币生产方式 + 它的 unit + 它的投入库存</b>（W5 的创世落点）。
   *
   * <pre>
   * 产业    id = mint@&lt;q&gt;_&lt;r&gt;（落点 = 政府家户所在格）
   *         regime = government_mint；cycleDays = CYCLE_DAYS（与其余产业同一周期，关账日同一天）
   *         capacityPerUnit = {WORKSHOP: 1}                        ← "单位规模"的锚（1 座铸炉 = 1 单位规模）
   *         laborPerUnit    = MINT_LABOR_MILLI_PER_FURNACE          ← 吃劳动
   *         cycleInputPerUnit = {WORKSHOP: {tool: …}}               ← 吃工具（真实消耗，走既有周期投入管线）
   *         moneyOutputPerUnit = {<本格计价币>: MINT_MILLI_PER_FURNACE_CYCLE}  ← ★ 产出 = 货币
   * unit    operator = HOUSEHOLD:hh-gov-…（政府家户 = 组织者，用户原话）
   * 投入库存 政府家户账上加一份工具（够跑 MINT_SEED_CYCLES 个周期；吃完就得靠市场买 —— 而国库户只按授权下单）
   * </pre>
   *
   * <p>★ <b>不变量</b>：本方法只在"有政府家户"的世界（demo / small-world）被调用 ⇒ 别的世界的产业表一个字节不变。
   */
  static void seedGovernmentMint(
      List<Map<String, Object>> entries,
      HouseholdId govHousehold,
      HexCoord hex,
      Map<HouseholdId, Map<CommodityId, Long>> householdStocks,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      CurrencyId numeraire,
      List<Map<String, Object>> allocations,
      List<PopulationGroup> urbanPool,
      Map<PeopleLotId, Long> urbanSlackLabor) {
    if (hex == null) {
      throw new IllegalStateException(
          "铸币产业找不到政府家户的落点格（seed 内部不一致）：household=" + govHousehold.value());
    }
    if (numeraire == null) {
      throw new IllegalStateException("铸币产业说不出本格计价币（铸哪种钱必须有币种，拒绝静默落到银）：hex=" + hex);
    }
    long furnaces = Math.max(1L, MINT_FURNACES_PER_CAPITA);
    IndustryId mintId = IndustryHexKeys.id(MINT, hex.q(), hex.r());
    ActorRef operator = HouseholdActors.of(govHousehold);
    List<Map<String, Object>> slots = new ArrayList<>(CLASS_IDS.length);
    for (int i = 0; i < CLASS_IDS.length; i++) {
      Map<String, Object> slot = new LinkedHashMap<>();
      slot.put("id", CLASS_IDS[i]);
      slot.put("name", CLASS_NAMES[i]);
      slot.put("laborParticipationPerMille", CLASS_LABOR_PER_MILLE[i]);
      slots.add(slot);
    }
    Map<String, Object> allocation = new LinkedHashMap<>();
    allocation.put("@class", "split");
    allocation.put("meansWeightPerMille", 500);
    allocation.put("laborWeightPerMille", 500);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", mintId.value());
    payload.put("name", "铸币");
    payload.put("regime", REGIME_MINT);
    payload.put("cycleDays", CYCLE_DAYS);
    payload.put("capacityPerUnit", Map.of(AssetKind.WORKSHOP.name(), 1L));
    payload.put("laborPerUnit", MINT_LABOR_MILLI_PER_FURNACE);
    payload.put("dailyInputPerUnit", Map.of());
    payload.put("dailyLaborPerUnit", 0);
    payload.put("outputPerUnit", Map.of());
    // ★★★ 本批新增的那一行：产出 = 货币（毫/规模·周期；键是 CurrencyId）。
    payload.put("moneyOutputPerUnit", Map.of(numeraire.value(), MINT_MILLI_PER_FURNACE_CYCLE));
    Map<String, Object> mintInput = new LinkedHashMap<>();
    mintInput.put(COMMODITY_TOOL, MINT_TOOL_MILLI_PER_FURNACE);
    payload.put("cycleInputPerUnit", Map.of(AssetKind.WORKSHOP.name(), mintInput));
    payload.put("allocation", allocation);
    payload.put("slots", slots);
    // ★★★ D-032 C3：铸币同样是生产方案 ⇒ 显式单 operator 角色（source = production defaultOperator）。
    payload.put("roles", singleOperatorRoleNodes());
    // ★★★ D-032 W5-E：铸币的产能份额整额 OWNED 物化（见下方 ownershipStakeNode）⇒ 显式声明接受面。
    payload.put("acceptedRightKinds", SEED_ACCEPTED_RIGHT_KINDS_OWNED);
    ProductionUnitId mintUnitId = ProductionUnitId.idOf(mintId, operator);
    Map<String, Object> unit = new LinkedHashMap<>();
    unit.put("id", mintUnitId.value());
    unit.put("industry", mintId.value());
    unit.put("operator", actorNode(operator));
    unit.put("modeKey", mintId.value());
    unit.put("progressDays", 0);
    unit.put("cycleLaborMilli", 0);
    unit.put("cycleInputUsedMilli", Map.of());
    // ★★★ D-032 C3 / D2-I17：显式写成空规则关系（不再依赖 regime 模板的缺省推导）——
    //   名册方案的旧 rules 必须为空；铸币的产出（货币）走 MintSettlement，不参与角色产品份额。
    unit.put(
        "relation",
        relationNode(
            new ProductionRules(
                mintUnitId,
                operator,
                new Payee.ToActor(operator),
                List.of(),
                operator,
                LaborSource.SELF)));
    // ① 铸币的产能：整额 OWNED 给组织者（与其余产业同一条"capacity 物化成份额"的口径）
    Map<String, Object> share =
        ownershipStakeNode(
            mintId.value(),
            AssetKind.WORKSHOP.name(),
            operator,
            operator,
            AssetRightKind.OWNED,
            furnaces,
            IndustryHexKeys.hexKey(hex.q(), hex.r()));
    // ② 把产业 + unit + 份额挂进政府家户落点那一格的 entry（entry 一定存在：seedGovernmentHousehold 刚写过）
    boolean attached = false;
    for (Map<String, Object> entry : entries) {
      int q = ((Number) entry.get("q")).intValue();
      int r = ((Number) entry.get("r")).intValue();
      if (q != hex.q() || r != hex.r()) {
        continue;
      }
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> plans = (List<Map<String, Object>>) entry.get("plans");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> units = (List<Map<String, Object>>) entry.get("units");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> shares = (List<Map<String, Object>>) entry.get("assetShares");
      plans.add(payload);
      units.add(unit);
      shares.add(share);
      attached = true;
      break;
    }
    if (!attached) {
      throw new IllegalStateException(
          "铸币产业找不到落点 entry（seed 内部不一致）：hex=" + hex + " entries=" + entries.size());
    }
    // ②-b ★★★ 劳动从哪来（W5 的关键一步，别跳过这一节）：铸币**必须有劳动**才能产出 ——
    //   `IndustryDefinitionView.laborPerUnit` 参与规模公式（`evaluateHarvest` 的 ② 劳动链），
    //   而劳动的唯一来源是**劳动配额表**（`laborByUnit`）⇒ 没有配额的 unit 规模恒 0
    //   ⇒ 印不出钱（判据 ② 会以"0 毫"的形式静默通过，但那不是"政策关掉了铸币"，
    //   是"这个产业根本没干活"。）
    //   ★ 政府家户自己人口为 0（非生产家户）⇒ 劳动只能来自**这一格城镇池的剩余预算**
    //     （= 作坊配额发完之后真的没被用掉的那部分，见 `urbanSlackLaborByHex` 的声明）。
    //   ★★ 剩余不足一份铸币配额 ⇒ **具名跳过**（DEBUG 一行），不缩作坊的配额、不造第二份劳动：
    //     结果 = 这一格没有铸币（合法状态：新钱 0 毫），而不是"把别人的劳动偷过来"。
    //   ★★★ W5-fix3：真正决定"跑不跑得起来"的是 `grantedLaborMilli ÷ MINT_LABOR_MILLI_PER_FURNACE ≥ 1`
    //     （规模公式的劳动链取整）—— 这个比值 < 1 时上面这条跳过**不会**触发（配额明明 > 0），
    //     但规模仍然是 0、铸币仍然 0 毫：W5 在 10‰ 下就是这样空转的（3,147 ÷ 8,000 = 0）。
    //     ⇒ 现在两个分支都发具名 DEBUG（SEED_MINT_LABOR / SEED_MINT_SKIPPED），都带这个比值要用的四个数。
    long mintLabor = 0L;
    long mintShare = 0L;
    // ★★ W5-fix3：这两个数是"铸币为什么产/不产"的**判据读数**（§一.9；W6 标定直接读 SEED_MINT_LABOR 那一行）。
    long poolDailyLabor = 0L;
    long slackAvailable = 0L;
    if (urbanPool != null && !urbanPool.isEmpty() && urbanSlackLabor != null) {
      // ★★ 名字要说实话（W5-fix3）：`poolDailyLabor` = 本格城镇池的日劳动（参与率折扣后口径）；
      //   `requestedLabor` = 它 × 本份额（铸币要的配额）；`slackAvailable` = 作坊发完后的剩余预算（宽的那道网）。
      //   ⇒ 实际配额 = min(requestedLabor, slackAvailable)。改前这两个数没有分开记，标定时只能猜谁是瓶颈。
      poolDailyLabor = industryDailyLabor(urbanPool);
      long requestedLabor = poolDailyLabor * MINT_LABOR_SHARE_PER_MILLE / 1_000L;
      long available = 0L;
      for (long left : urbanSlackLabor.values()) {
        available = Math.addExact(available, left);
      }
      slackAvailable = available;
      mintShare = Math.min(requestedLabor, available);
      if (mintShare > 0L) {
        // ★ 与 appendAllocation 同一条"按性别权重 × 可用劳动切配额"的口径（不另写第二套切法）；
        //   预算表按切出的份额就地扣减 ⇒ 它天然不会超过剩余。
        Map<PeopleLotId, Long> budget = new LinkedHashMap<>(urbanSlackLabor);
        int before = allocations.size();
        appendAllocation(
            allocations,
            budget,
            urbanPool,
            hex,
            ProductionUnitId.idOf(mintId, operator),
            operator,
            MINT,
            mintShare);
        for (int i = before; i < allocations.size(); i++) {
          mintLabor =
              Math.addExact(mintLabor, ((Number) allocations.get(i).get("laborMilli")).longValue());
        }
      }
    }
    if (mintLabor <= 0L) {
      // ★ 仍然播产业与 unit：产业**存在但不产**（配额 0 ⇒ 规模 0 ⇒ 新钱 0 毫）是合法状态，
      //   而且它让 GM 之后能通过市场/调产把它喂起来（"有配额才有产出"这条口径唯一）。
      LOG.debug(
          io.mosire.simos.util.log.EventLog.kv(
              new String[] {
                "event",
                "SEED_MINT_SKIPPED",
                "household",
                govHousehold.value(),
                "hex",
                hex.q() + "," + hex.r(),
                "reason",
                "no-urban-slack-labor",
                "poolDailyLaborMilli",
                Long.toString(poolDailyLabor),
                "sharePerMille",
                Integer.toString(MINT_LABOR_SHARE_PER_MILLE),
                "mintShareRequested",
                Long.toString(mintShare),
                "slackAvailableMilli",
                Long.toString(slackAvailable),
                "perUnitLaborMilli",
                Long.toString(MINT_LABOR_MILLI_PER_FURNACE)
              }));
    } else {
      // ★★★ W5-fix3（§一.9）：**发出去多少劳动**这条判据路径的具名读数 —— 铸币能不能跑起来只由这一个比值决定：
      //   `unitScalesSupported = grantedLaborMilli ÷ perUnitLaborMilli`；它 < 1 ⇒ 规模 0 ⇒ 新钱恒 0 毫
      //   （W5-fix2 的实测现场就是 3,147 ÷ 8,000 = 0）。W6 改份额/产出率/铸炉数之后，先读这一行看有没有跑起来。
      LOG.debug(
          io.mosire.simos.util.log.EventLog.kv(
              new String[] {
                "event",
                "SEED_MINT_LABOR",
                "household",
                govHousehold.value(),
                "hex",
                hex.q() + "," + hex.r(),
                "poolDailyLaborMilli",
                Long.toString(poolDailyLabor),
                "sharePerMille",
                Integer.toString(MINT_LABOR_SHARE_PER_MILLE),
                "requestedLaborMilli",
                Long.toString(mintShare),
                "slackAvailableMilli",
                Long.toString(slackAvailable),
                "grantedLaborMilli",
                Long.toString(mintLabor),
                "perUnitLaborMilli",
                Long.toString(MINT_LABOR_MILLI_PER_FURNACE),
                "unitScalesSupported",
                Long.toString(mintLabor / MINT_LABOR_MILLI_PER_FURNACE)
              }));
    }

    // ③ 铸币要吃的投入：给政府家户一份工具库存（够跑 MINT_SEED_CYCLES 个周期）。
    //   ★ 这是**创世库存**（与作坊主家户的工具周转料同制）：吃完之后铸币要么靠市场买（而国库户只按授权下单）、
    //     要么停工 —— 后者是合法状态（产出 0 ⇒ 新钱 0 毫，判据 ② 的自然形态）。
    Map<CommodityId, Long> stock =
        new LinkedHashMap<>(householdStocks.getOrDefault(govHousehold, Map.of()));
    stock.merge(
        new CommodityId(COMMODITY_TOOL),
        Math.multiplyExact(furnaces * MINT_SEED_CYCLES, MINT_TOOL_MILLI_PER_FURNACE),
        Math::addExact);
    householdStocks.put(govHousehold, stock);
    if (!householdMoney.containsKey(govHousehold)) {
      householdMoney.put(govHousehold, Map.of());
    }
  }

  /** GOV 家户行：population/labor/participation 全 0、无需求；身份仍由 {@link HouseholdId} 稳定承载。 */
  private static Map<String, Object> governmentHouseholdEconomy(HouseholdId householdId) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("householdId", householdId.value());
    row.put("residence", ResidenceKind.URBAN.value());
    row.put("slot", SocialClassId.OFFICIAL.value());
    row.put("population", 0L);
    row.put("laborMilli", 0L);
    row.put("participationPerMille", 0);
    row.put("money", 0L);
    row.put("naturalNeeds", Map.of());
    row.put("effectiveDemand", Map.of());
    return row;
  }

  /**
   * GOV 家户政府的国库指向它的家户账户；★★ W5：铸币政策旋钮取本类的具名 GM 默认值 （{@link
   * #GOVERNMENT_MINT_SCALE_PER_CYCLE_MILLI}），发债旋钮<b>已随双轨合并删除</b>。 只在 demo 世界有政府家户时调用。
   */
  static Government governmentHouseholdGovernment(HouseholdId governmentHousehold) {
    Objects.requireNonNull(governmentHousehold, "governmentHousehold");
    return new Government(
        GENESIS_GOVERNMENT_ID,
        GENESIS_GOVERNMENT_NATION_REF,
        HouseholdActors.of(governmentHousehold),
        Set.of(MARKET_NUMERAIRE),
        GOVERNMENT_MINT_SCALE_PER_CYCLE_MILLI);
  }

  /**
   * ★★ <b>P2-C：没有政府家户的 seed 所用的"创世审计主体"</b>（多国/多省播种的普通路径）。
   *
   * <p>它<b>不持户、不持账、不发生额</b>：国库是 legacy 形状的 {@code GOVERNMENT} actor（不是家户）， {@code issuable}
   * 为空、两个财政旋钮恒 0。它存在的唯一理由是 {@code INITIAL_ENDOWMENT} 发行记录必须有归属 （{@code
   * MoneyIssuanceRecord.governmentId} 必须存在于 {@code governments}）；每次 seed 生成的这份记录逐值相同，
   * 因此多国重复播种是幂等叠加，而不是"第一份赢"。
   *
   * <p>真正的政府（有家户国库、可铸币/入市/生产）由 GOV 单位路径 {@code economy.RegisterGovernment} 按 GOV 单位稳定 id 建立。
   */
  static Government genesisAuditGovernment() {
    return new Government(
        GENESIS_GOVERNMENT_ID,
        GENESIS_GOVERNMENT_NATION_REF,
        new ActorRef(ActorKind.GOVERNMENT, GENESIS_GOVERNMENT_ID.value()),
        Set.of());
  }
}

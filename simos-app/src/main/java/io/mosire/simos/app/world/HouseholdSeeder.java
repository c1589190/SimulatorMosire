package io.mosire.simos.app.world;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.model.Actor;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.map.hex.HexCoord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>家户 actor 的播种器</b>（H1；裁定 D1-A / D3-C / K1 / S1）：把"每格两组四行"的家户主体（{@link ActorKind#HOUSEHOLD}）
 * 连同它那一本 {@link GoodsAccount} 播进 actor 切片 —— 于是"产出/分配落到谁头上"第一次有了<b>稳定的主体</b>。
 *
 * <p>★★ <b>为什么必须每个家户都有 actor</b>（哪怕它一本空账）：economy 侧的关系规则（地租那类）<b>按人口过滤</b>不了 —— 规则可以把实物付给<b>人口为
 * 0</b> 的家户。少一个 actor ⇒ 那条分配<b>没有地方落</b>（要么静默丢、要么当场炸）。 故本类的口径是：<b>行集（{@code EconomyData.classes}
 * 的键集）里每一个家户都要有一个 actor + 一本账</b>， 账本可以全 0（"这个家户在这一格有一本账"与"它现在有东西"是两件事，见 {@code GoodsAccount} 的"0
 * 余额保留"）。
 *
 * <p>★★ <b>真档判据</b>：799 格 × 8 行（2 种居住类型 × 4 个阶层）= <b>6392</b> 个家户 actor + 同样多的账户。
 *
 * <p>★★ <b>身份的唯一拼写点是 {@link HouseholdActors}</b>（{@code of(cohort)} / {@code cohortOf(actor)} 互逆）——
 * 本类 <b>不自己拼 id</b>；账户键 = {@code new GoodsAccountKey(该 actor, cohort.hex())}（"某人<b>在某一格</b>有多少商品"，
 * 键的第二段是格，见 {@code GoodsAccountKey}）。
 *
 * <p>★★ <b>它落在哪条创世路径上</b>（本类只有两个入口，各自服务一类调用方）：
 *
 * <ol>
 *   <li>{@link #payload(String, Map)} —— <b>真档创世</b>：{@code WorldgenInitializeTool} 在同一批命令里发一条
 *       {@code actor.Seed}（载荷由本方法装配）。★ 开缸库存**不在这里算**：它与 {@code economy.Seed} 的逐行人口/产能 同源，由 {@link
 *       EconomySeeder#plan} 一次算出（{@link EconomySeeder.Seed#householdStocks()}）——
 *       两处各算一遍必然漂开（"同一个事实两处拼写点"是本仓明令禁止的形态）；
 *   <li>{@link #books(Map)} —— <b>直接装配状态</b>：给"手搭 EconomyData 的夹具/GUI 诊断"用（{@link #payload} 与它读的是
 *       同一份库存表，故两条路的 id / 键 / 空账口径逐字相同）。
 * </ol>
 *
 * <p>★ <b>不写 holdings</b>：产权表（{@code AssetHolding} / {@code AssetClassKey}）已按裁定 S3 整块退役 ⇒ 本切片里家户的
 * {@code GoodsAccount} 是唯一的账（★ H4 起它是**两张余额表**：商品 + 货币，裁定 M2"两个独立身份"； {@code AssetKind}
 * 仍在，它是产业产能的键）。
 *
 * <p>★★ <b>H4：创世货币禀赋也在这里播</b>——{@code goods} 那条记录多一个 {@code money} 键（见 {@link #goodsNode}）， 值来自
 * {@link EconomySeeder.Seed#householdMoney()}（每人 1.2 个周期的口粮等价，见 {@code
 * EconomySeeder.genesisMoneyMilliPerCapita}）。★ 它是**初始条件、不是发行**（{@code MoneyAuthority} 无实现者）。
 *
 * <p>★ <b>键序是内容的纯函数</b>：逐格按 {@code (q,r)} 字典序、格内按 {@code (居住类型, 阶层)} 字典序 —— 同一份库存表两次调用逐字段产出同一份载荷 /
 * 同一个状态（可复现、可写进字面量断言）。
 */
public final class HouseholdSeeder {

  /**
   * 本切片规则的版本标签（进 {@code ActorMeta.rulesVersion}，与 {@code ActorPayloads} 的样例同字面）。
   *
   * <p>★ 它是**载荷字段**（{@code actor.Seed} 要求非空白）：本类只决定"新世界打哪个标"，不参与任何公式。
   */
  public static final String RULES_VERSION = "actor-v1";

  private HouseholdSeeder() {}

  /**
   * ★★ <b>{@code actor.Seed} 的载荷</b>：为库存表里的每个家户建一个 actor + 一本账（{@code balances} 可以全 0）， 外加 H4
   * 的**创世货币禀赋**（{@code money}，同样可以全 0）。
   *
   * <p>载荷形状见 {@code ActorPayloads}（本类**只组装**那个形状，不复述它的解析规则）：
   *
   * <pre>{@code
   * {"mapId":"Map1","rulesVersion":"actor-v1","entries":[
   *   {"q":0,"r":0,
   *    "actors":[{"kind":"HOUSEHOLD","id":"0_0:rural:poor_peasant","label":"农村 贫农 家户"}, …],
   *    "goods":[{"owner":{"kind":"HOUSEHOLD","id":"0_0:rural:poor_peasant"},
   *              "location":{"q":0,"r":0},"balances":{"grain":2241000,"fiber":…},
   *              "money":{"silver":12}}, …]}]}
   * }</pre>
   *
   * <p>★★ <b>{@code goods} 与 {@code actors} 逐条对齐</b>：一本账的 owner 必须<b>是已声明的主体</b>（{@code
   * ActorPayloads} 的"悬空 owner ⇒ 拒"），故两者由同一次遍历产出 —— 少写一条就是"账没有主人"，多写一条就是"主体凭空多出来"。
   *
   * <p>★★ <b>H4：钱与货在同一本账里</b>（同一本 {@code GoodsAccount} 的两个余额表）：商品余额来自 {@link
   * EconomySeeder.Seed#householdStocks()}、货币余额来自 {@link EconomySeeder.Seed#householdMoney()} ——
   * 两条都出自 **同一次** {@code EconomySeeder.plan}（"一次算出、同一份喂两条命令"）。★ 货币的出厂值与依据见 {@code
   * EconomySeeder.genesisMoneyMilliPerCapita}（每人 1.2 个周期的口粮等价，初始条件而非发行）。
   *
   * @param mapId 本世界的 map 称谓（非空白；进 {@code ActorMeta}）
   * @param householdStocks 逐家户的开缸库存（键 = 家户身份；值可以为空表 = 一本空账）；不得为 null
   * @param householdMoney 逐家户的创世货币（键 = 家户身份；值可以为空表 = 一文不名）；不得为 null ★ 键集应与 {@code householdStocks}
   *     一致（人口 0 的家户 ⇒ 一本空钱包），由 {@code EconomySeeder} 保证
   * @throws IllegalArgumentException {@code mapId} 为空白
   */
  public static String payload(
      String mapId,
      Map<CohortKey, Map<CommodityId, Long>> householdStocks,
      Map<CohortKey, Map<CurrencyId, Long>> householdMoney) {
    return payload(mapId, householdStocks, householdMoney, List.of());
  }

  /**
   * ★★ <b>{@code actor.Seed} 的载荷（H5 的完整口径：家户 + 经营者）</b>—— 见 {@link #payload(String, Map, Map)}。
   *
   * <p>★★ <b>H5 ⑤：经营者的账与家户的账**在同一份载荷里的同一个 {@code goods} 数组**</b>（形状一字不改： {@code entries[].actors[]}
   * 先声明主体、{@code entries[].goods[]} 再给账 —— "悬空 owner ⇒ 拒"那条守卫因此照旧）。 ★
   * 主体与账**逐条对齐**（同一次遍历产出）：少写一条就是"账没有主人"，多写一条就是"主体凭空多出来"。
   *
   * @param operators 经营主体的开缸账（来自 {@code EconomySeeder.plan} 的 {@code Seed.operators()}；键序 = 产业生成序）
   */
  public static String payload(
      String mapId,
      Map<CohortKey, Map<CommodityId, Long>> householdStocks,
      Map<CohortKey, Map<CurrencyId, Long>> householdMoney,
      List<EconomySeeder.OperatorSeed> operators) {
    if (mapId == null || mapId.isBlank()) {
      throw new IllegalArgumentException("mapId 不得为空白: " + mapId);
    }
    Objects.requireNonNull(householdStocks, "householdStocks");
    Objects.requireNonNull(householdMoney, "householdMoney");
    Objects.requireNonNull(operators, "operators（没有经营者就给空表）");
    Map<HexCoord, List<EconomySeeder.OperatorSeed>> operatorsByHex = operatorsByHex(operators);
    List<Map<String, Object>> entries = new ArrayList<>();
    for (Map.Entry<HexCoord, List<CohortKey>> atHex : byHex(householdStocks).entrySet()) {
      HexCoord hex = atHex.getKey();
      List<Map<String, Object>> actors = new ArrayList<>(atHex.getValue().size());
      List<Map<String, Object>> goods = new ArrayList<>(atHex.getValue().size());
      for (CohortKey cohort : atHex.getValue()) {
        ActorRef actor = HouseholdActors.of(cohort);
        actors.add(actorNode(actor, labelOf(cohort)));
        goods.add(
            goodsNode(
                actor,
                hex,
                householdStocks.getOrDefault(cohort, Map.of()),
                householdMoney.getOrDefault(cohort, Map.of())));
      }
      // ★★ H5：本格的经营主体（有产业才有它；见 EconomySeeder.operatorSeed）—— 与家户同一个 actors/goods 形状。
      for (EconomySeeder.OperatorSeed operator : operatorsByHex.getOrDefault(hex, List.of())) {
        actors.add(actorNode(operator.owner(), operator.label()));
        goods.add(goodsNode(operator.owner(), hex, operator.goods(), operator.money()));
      }
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("q", hex.q());
      entry.put("r", hex.r());
      entry.put("actors", actors);
      entry.put("goods", goods);
      entries.add(entry);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("mapId", mapId);
    payload.put("rulesVersion", RULES_VERSION);
    payload.put("entries", entries);
    return ToolSupport.json(payload);
  }

  /**
   * ★★ <b>直接装配 actor 切片的那两件</b>（{@code actors} + {@code accounts}）—— 服务"手搭状态"的调用方 （测试夹具、GUI
   * 诊断、将来的存档迁移）。
   *
   * <p>★ <b>元信息不在这里</b>（{@code meta} 有自己的激活语义：空 = 本世界尚未落 actor 切片）⇒ 调用方按 {@code
   * ActorData.empty().withMeta(...).withActors(...).withAccounts(...)} 自己拼（{@code ActorData} 是三件：
   * meta / actors / accounts）。
   *
   * <p>★★ 与 {@link #payload(String, Map, Map)} <b>读的是同一份库存表、同一份货币表、同一套 id</b>（{@link
   * HouseholdActors}）⇒ "命令播出来的世界"与"夹具手搭的世界"在这两张表上逐字段同形。
   *
   * <p>★★ <b>H4：一本账 = 货 + 钱</b>（{@code GoodsAccount} 的两个余额表）：手搭的世界同样要带上创世货币禀赋，
   * 否则"手搭世界"与"真播种世界"会在钱上漂开 —— 而那正是本仓最忌的"同一事实两处拼写点"。
   */
  public static ActorData books(
      Map<CohortKey, Map<CommodityId, Long>> householdStocks,
      Map<CohortKey, Map<CurrencyId, Long>> householdMoney) {
    return books(householdStocks, householdMoney, List.of());
  }

  /**
   * ★★ <b>直接装配 actor 切片的那两件（H5 的完整口径：家户 + 经营者）</b>—— 见 {@link #books(Map, Map)}。★ 与 {@link
   * #payload(String, Map, Map, List)} 读的是**同一份**库存/货币/经营者表 ⇒ "命令播出来的世界"与"夹具手搭的世界"逐字段同形（H1 的那条接缝，H5
   * 只是多了经营者这一族）。
   */
  public static ActorData books(
      Map<CohortKey, Map<CommodityId, Long>> householdStocks,
      Map<CohortKey, Map<CurrencyId, Long>> householdMoney,
      List<EconomySeeder.OperatorSeed> operators) {
    Objects.requireNonNull(householdStocks, "householdStocks");
    Objects.requireNonNull(householdMoney, "householdMoney");
    Objects.requireNonNull(operators, "operators（没有经营者就给空表）");
    Map<ActorRef, Actor> actors = new LinkedHashMap<>();
    Map<GoodsAccountKey, GoodsAccount> accounts = new LinkedHashMap<>();
    for (Map.Entry<HexCoord, List<CohortKey>> atHex : byHex(householdStocks).entrySet()) {
      for (CohortKey cohort : atHex.getValue()) {
        ActorRef actor = HouseholdActors.of(cohort);
        actors.put(actor, new Actor(actor, labelOf(cohort)));
        // ★ 账本**按绝对值**建（含空账）：0 余额保留是本仓既定口径（读口因此读得到"这个家户在这一格有一本账"）。
        GoodsAccountKey accountKey = new GoodsAccountKey(actor, atHex.getKey());
        accounts.put(
            accountKey,
            new GoodsAccount(
                accountKey,
                householdStocks.getOrDefault(cohort, Map.of()),
                householdMoney.getOrDefault(cohort, Map.of())));
      }
    }
    // ★★ H5：经营主体的主体 + 账（同一份 OperatorSeed 同时给出两者 ⇒ 不会出现"有账没主体"）。
    for (EconomySeeder.OperatorSeed operator : operators) {
      actors.put(operator.owner(), new Actor(operator.owner(), operator.label()));
      GoodsAccountKey accountKey = new GoodsAccountKey(operator.owner(), operator.location());
      accounts.put(accountKey, new GoodsAccount(accountKey, operator.goods(), operator.money()));
    }
    return ActorData.empty().withActors(actors).withAccounts(accounts);
  }

  /** 经营者按格分组（键序 = 传入序；格序 = 首次出现序）—— 载荷按格装 {@code actors}/{@code goods} 用。 */
  private static Map<HexCoord, List<EconomySeeder.OperatorSeed>> operatorsByHex(
      List<EconomySeeder.OperatorSeed> operators) {
    Map<HexCoord, List<EconomySeeder.OperatorSeed>> byHex = new LinkedHashMap<>();
    for (EconomySeeder.OperatorSeed operator : operators) {
      byHex.computeIfAbsent(operator.location(), ignored -> new ArrayList<>()).add(operator);
    }
    return byHex;
  }

  /** 家户的显示名（{@code <居住类型> <阶层> 家户}）—— ★ **只为读**，不参与任何身份判定（身份是 {@link ActorRef}）。 */
  private static String labelOf(CohortKey cohort) {
    return cohort.residence().value() + " " + cohort.stratum().value() + " 家户";
  }

  /**
   * 按格分组（{@code 键序 = (q,r) 字典序；格内序 = (居住类型, 阶层) 字典序}）—— 内容的纯函数。
   *
   * <p>★ <b>排序而不是沿用 Map 的插入序</b>：插入序是"谁先算出来"的函数，而调用方可能来自别处（夹具/迁移）； 排序后"同一份库存表 ⇒ 同一份载荷/状态"这条判据是构造性的。
   */
  static Map<HexCoord, List<CohortKey>> byHex(
      Map<CohortKey, Map<CommodityId, Long>> householdStocks) {
    Map<HexCoord, List<CohortKey>> byHex = new LinkedHashMap<>();
    for (CohortKey cohort : householdStocks.keySet()) {
      byHex.computeIfAbsent(cohort.hex(), ignored -> new ArrayList<>()).add(cohort);
    }
    for (List<CohortKey> atHex : byHex.values()) {
      atHex.sort(
          Comparator.comparing((CohortKey key) -> key.residence().value())
              .thenComparing(key -> key.stratum().value()));
    }
    List<HexCoord> hexes = new ArrayList<>(byHex.keySet());
    hexes.sort(Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r));
    Map<HexCoord, List<CohortKey>> sorted = new LinkedHashMap<>();
    for (HexCoord hex : hexes) {
      sorted.put(hex, byHex.get(hex));
    }
    return sorted;
  }

  /** 一条主体：{@code {kind, id, label}}（{@code kind} 的字面量取自 {@link ActorKind#HOUSEHOLD}，不手写）。 */
  private static Map<String, Object> actorNode(ActorRef actor, String label) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("kind", actor.kind().name());
    node.put("id", actor.id());
    node.put("label", label);
    return node;
  }

  /**
   * 一本账：{@code {owner, location, balances, money}}。
   *
   * <p>★ {@code location} 是 {@code {q,r}} 对象（载荷形状），而 {@code balances} 的键是 {@link
   * CommodityId#toString()} 的产物 —— ★ <b>本类不复述那个格式</b>（键序沿用库存表的插入序：粮在前）。
   *
   * <p>★★ <b>H4：{@code money} 与 {@code balances} 并列在同一本账里</b>（{@code GoodsAccount} 的两个余额表）—— 键是
   * {@link CurrencyId#value()}（币种名，例如 {@code silver}），值是**最小币值**。★ <b>钱不是商品</b>（裁定 M2：{@code
   * CurrencyId} 与 {@code CommodityId} 各守各的余额与守恒）⇒ 它是**同一条 {@code goods} 记录里的另一个键**， 不是 {@code
   * balances} 里的第六个商品。
   *
   * <p>★ <b>键序沿用表本身的插入序</b>（{@code EconomySeeder.genesisMoney} 只发本格计价货币那一种）⇒ 同一份货币表 两次调用产出同一份载荷。
   */
  private static Map<String, Object> goodsNode(
      ActorRef owner, HexCoord at, Map<CommodityId, Long> balances, Map<CurrencyId, Long> money) {
    Map<String, Object> node = new LinkedHashMap<>();
    Map<String, Object> ref = new LinkedHashMap<>();
    ref.put("kind", owner.kind().name());
    ref.put("id", owner.id());
    node.put("owner", ref);
    Map<String, Object> location = new LinkedHashMap<>();
    location.put("q", at.q());
    location.put("r", at.r());
    node.put("location", location);
    Map<String, Object> table = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : balances.entrySet()) {
      table.put(entry.getKey().toString(), entry.getValue());
    }
    node.put("balances", table);
    Map<String, Object> wallet = new LinkedHashMap<>();
    for (Map.Entry<CurrencyId, Long> entry : money.entrySet()) {
      wallet.put(entry.getKey().value(), entry.getValue());
    }
    node.put("money", wallet);
    return node;
  }
}

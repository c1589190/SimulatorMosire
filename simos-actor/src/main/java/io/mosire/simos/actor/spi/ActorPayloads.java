package io.mosire.simos.actor.spi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorMeta;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.model.Actor;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.stock.DeductionReason;
import io.mosire.simos.economy.api.stock.HouseholdStockDeduction;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * actor 切片的载荷解析助手（与 {@code EconomyPayloads} / {@code SocialPayloads} 同制）：目前三条命令 —— {@code
 * actor.Seed}（见下）、{@code actor.AdjustAccounts}（见 {@link #adjustments}）与 {@code
 * actor.DeductHouseholdStock}（见 {@link #deductions}）。
 *
 * <p>★ <b>载荷形态是本模块的私事</b>（C26）：Core 只转交 {@code payloadJson} 字节串。{@code actor.Seed} 的形态如下（<b>与三个
 * record 的字段一一对应</b>）：
 *
 * <pre>{@code
 * {"mapId":"Map1","rulesVersion":"actor-v1","entries":[
 *   {"q":0,"r":0,
 *    "actors":[{"kind":"ESTATE","id":"farm@0_0","label":"农业庄园"},
 *              {"kind":"HOUSEHOLD","id":"house@0_0","label":"农户"}],
 *    "goods":[{"owner":{"kind":"HOUSEHOLD","id":"house@0_0"},
 *              "location":{"q":0,"r":0},
 *              "balances":{"grain":2241000,"fiber":0},
 *              "money":{"silver":1200},          // ★ H4：可缺省（缺 = 空表）
 *              "frozenBalances":{},"frozenMoney":{}}]}]}   // ★ M1.2：可缺省（缺 = 空表，M1.3 显式带过）
 * }</pre>
 *
 * <p>★ <b>逐格声明</b>（{@code entries[]}）：<b>格是命令目标与权限的粒度</b> —— {@code CommandTargets.targetPaths}
 * 交出的就是这些格（见 {@link #entryHexKeys}），大而全的"整档一把播"不在本形态里。
 *
 * <p>★★ <b>每一行都挂在自己那一格下</b>：{@code goods} 的 {@code location} <b>必须等于所在 entry 的 (q,r)</b>。理由不是洁癖 ——
 * 载荷声明的目标就是那一格，若某一行能落在别的格上，GM 代执行时**逐条判越权的对象**与 **命令真正改到的资源**就不是同一件事（一条被授权的命令改到了没被授权的格）。
 *
 * <p>★ <b>坏载荷一律以 {@link IllegalArgumentException} 面世</b>（带可读中文原因）：形状/类型/词表在本层判， <b>数值语义</b>（余额 ≥
 * 0……）交给 {@link HouseholdInventory} 的构造期守卫 —— <b>不重复实现，一处真相</b>（同 {@code EconomyPayloads} 的分工）。
 *
 * <p>★★ <b>2026-09-27 裁定 S3</b>：产权行（{@code holdings[]}）连同 {@code AssetHolding} 整块退役 ⇒ 本类不再有 {@code
 * holdings} 的解析、{@code assetKey} 的词表校验与"产权重复"判据。★ <b>如实记</b>：载荷里多出来的 {@code holdings}
 * 键现在**既不解析也不报错**（本类是手写的 JsonNode 走法，不认的键一律不看）；真档从未写过它，故无兼容负担。
 *
 * <p>★★ <b>两条本切片特有的引用判据</b>（{@code EconomyData} 的 {@code requireSlotExists} 那一族；{@link ActorData}
 * 的类注把"存在性"明确交给<b>命令面</b>判，本类就是那个命令面）：
 *
 * <ul>
 *   <li><b>悬空 owner ⇒ 拒</b>：库存的 {@code owner} 必须是**已声明的主体** —— 载荷里声明的 {@code actors} ∪
 *       <b>现有状态里已有</b>的主体（后者是"先落主体、后落库存"那种合法写序）。拼错的 owner 若被收下，那本账就是**静默的幽灵**： 按 owner
 *       查它查不到、也没有任何一层会报错。
 *   <li><b>{@code location} 必须等于所在格 ⇒ 否则拒</b>（见上）。
 * </ul>
 *
 * <p>★ <b>同一份载荷里同键声明两次 ⇒ 拒</b>（照 economy 的"同一份载荷里产业 id 重复"）：主体按 {@code ref} 判、库存按 {@code (owner,
 * location)} 判 —— 收下等于让"后写覆盖前写"静默解决一个**冲突的意图**。
 */
final class ActorPayloads {

  /** 本类唯一的一台 mapper（共享基座出厂配置；状态类型零 Jackson 注解，故不需要 mixin）。 */
  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private ActorPayloads() {}

  /**
   * ★ <b>日志安全的拒绝理由</b>：本类的校验消息为方便调用方排查会回显字段值/整段载荷，但日志纪律禁止载荷明文与 JSON 原文（plan §4.3）。 这里只保留可读前缀——截到第一个
   * JSON 起始符/换行；{@code payload ...} 这一类原始文本消息再截到冒号，避免把调用方原文带进日志。 截断只影响日志文本，不影响异常本身，也不改 {@code
   * Rejected} 的理由。
   */
  static String logReason(String message) {
    if (message == null || message.isBlank()) {
      return "unknown";
    }
    String text = message.strip();
    int cut = text.length();
    for (char marker : new char[] {'{', '[', '\n', '\r'}) {
      int at = text.indexOf(marker);
      if (at >= 0 && at < cut) {
        cut = at;
      }
    }
    if (text.startsWith("payload ")) {
      int colon = text.indexOf(':');
      if (colon >= 0 && colon < cut) {
        cut = colon;
      }
    }
    String reason = text.substring(0, cut).strip();
    return reason.isEmpty() ? "unknown" : reason;
  }

  /** 解析载荷文本：非 JSON、或不是 JSON 对象 ⇒ 抛。 */
  static JsonNode parse(String payloadJson) {
    Objects.requireNonNull(payloadJson, "payloadJson");
    JsonNode payload;
    try {
      payload = MAPPER.readTree(payloadJson);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("payload 不是合法 JSON: " + e.getOriginalMessage(), e);
    }
    if (payload == null || !payload.isObject()) {
      throw new IllegalArgumentException("payload 必须是 JSON 对象: " + payloadJson);
    }
    return payload;
  }

  /**
   * 载荷里逐格的可寻址路径（{@code <q>_<r>}，{@link ResourcePaths#actor(int, int)} 的形态）—— {@code CommandTargets}
   * 用它，<b>不建完整记录</b>（目标声明只关心"动谁"）。
   *
   * <p>★ 路径**经那条助手**拼，不在此处内联：它是权限围栏的输入，围栏两侧（命令的目标声明 / app 的资源断言）必须来自**同一个来源**。
   */
  static List<String> entryHexKeys(JsonNode payload) {
    JsonNode entries = requireArray(payload, "entries");
    if (entries.isEmpty()) {
      throw new IllegalArgumentException("entries 不得为空");
    }
    List<String> keys = new ArrayList<>(entries.size());
    for (JsonNode entry : entries) {
      requireEntryObject(entry);
      keys.add(ResourcePaths.actor(requireInt(entry, "q"), requireInt(entry, "r")));
    }
    return List.copyOf(keys);
  }

  /**
   * 把载荷整份 materialize 成 {@link ActorData}（含激活元信息）。
   *
   * <p>★ <b>两趟走</b>：第一趟收齐<b>全部</b>主体（悬空 owner 的判据要看见整份载荷，而不只是同一格那几条）， 第二趟才建那张带 {@code location}
   * 的表。<b>一趟走会让"主体声明在别的格、库存落在这一格"变成假悬空</b> —— 那是合法的形态（载荷的格序不是依赖序）。
   *
   * @param existingActors 现有切片里**已有**的主体（追加播种时"先落主体、后落库存"的合法写序靠它成立）
   * @param at 世界当前时刻（{@code activatedDay} 取它的 {@link SimosTimestamp#tick()}）
   */
  static ActorData toData(
      JsonNode payload,
      Set<ActorRef> existingActors,
      Set<HouseholdId> existingHouseholds,
      SimosTimestamp at) {
    Objects.requireNonNull(existingActors, "existingActors");
    Objects.requireNonNull(existingHouseholds, "existingHouseholds");
    Objects.requireNonNull(at, "at");
    String mapId = requireText(payload, "mapId");
    String rulesVersion = requireText(payload, "rulesVersion");
    JsonNode entries = requireArray(payload, "entries");
    if (entries.isEmpty()) {
      throw new IllegalArgumentException("entries 不得为空");
    }
    Map<ActorRef, Actor> actors = new LinkedHashMap<>();
    for (JsonNode entry : entries) {
      requireEntryObject(entry);
      requireInt(entry, "q");
      requireInt(entry, "r");
      for (JsonNode node : optionalArray(entry, "actors")) {
        Actor actor = actor(node);
        if (actors.putIfAbsent(actor.ref(), actor) != null) {
          throw new IllegalArgumentException("同一份载荷里主体重复: " + actor.ref());
        }
      }
    }
    // ★★ P2-A：账户主体只有家户 ⇒ 悬空判据看家户集（载荷里出现的 ∪ 现有状态里的），不再看 ActorRef。
    Set<HouseholdId> declaredHouseholds = new LinkedHashSet<>(existingHouseholds);
    for (JsonNode entry : entries) {
      for (JsonNode node : optionalArray(entry, "goods")) {
        declaredHouseholds.add(AccountPayloads.household(node, "household"));
      }
    }
    Map<HouseholdAccountKey, HouseholdInventory> inventories = new LinkedHashMap<>();
    for (JsonNode entry : entries) {
      requireInt(entry, "q");
      requireInt(entry, "r");
      for (JsonNode node : optionalArray(entry, "goods")) {
        HouseholdInventory inventory = goods(node, declaredHouseholds);
        if (inventories.putIfAbsent(inventory.key(), inventory) != null) {
          throw new IllegalArgumentException("同一份载荷里库存重复: " + inventory.key());
        }
      }
    }
    ActorMeta meta = new ActorMeta(mapId, at.tick(), rulesVersion);
    // ★ 两张表 + 元信息**批量**装配（`ActorData` 的 bulk wither 的调用面就在这里，见裁定 R-ae / R-ah：Task 8 用不到就删，
    //   而本任务正是它们要等的那条路）："键从值派生"的校验由 ActorData 的构造期守卫统一把守，本类不自己拼键。
    return ActorData.empty()
        .withMeta(Optional.of(meta))
        .withActors(actors)
        .withInventories(inventories);
  }

  // ── actor.AdjustAccounts（净增量账，阶段 6 / 计划 §6.2）────────────────────────────

  /**
   * ★★ <b>{@code actor.AdjustAccounts} 的一条账目</b>（P2-A §13.3：账户主体只有家户）：{@code household} +
   * 两张<b>有符号净增量</b>表。
   *
   * <p>★ 两张表<b>至少一张非空</b>（否则这条账目没有任何动作，解析期已拒）；表的迭代序 = 载荷里的键序（{@code LinkedHashMap}）， 构造期冻成不可变。★
   * 增量是<b>净量</b>（不是存量），0 已在解析期拒（无操作条目请删）。
   */
  record AccountAdjustment(
      HouseholdId household, Map<CommodityId, Long> goods, Map<CurrencyId, Long> money) {

    AccountAdjustment {
      if (household == null || goods == null || money == null) {
        throw new IllegalArgumentException("AccountAdjustment 的组件都不得为 null");
      }
      // ★ 冻在赋值处（保序不可变：LinkedHashMap + Collections.unmodifiableMap；不用 Map.copyOf）。
      goods = Collections.unmodifiableMap(new LinkedHashMap<>(goods));
      money = Collections.unmodifiableMap(new LinkedHashMap<>(money));
    }
  }

  /**
   * 解析 {@code actor.AdjustAccounts} 的载荷（形状见 {@code AdjustAccountsHandler} 的类注）： {@code
   * entries[{household, goods?, money?}...]}。
   *
   * <p>★ <b>本层只判形状 / 类型 / 词表 / 0 增量 / 同一家户重复</b>；"负增量是否使余额 &lt; 0 / 侵占冻结额、 缺账能否新建"是<b>数值语义</b>，由
   * {@code AdjustAccountsHandler} 判（本层不重复实现）。
   *
   * @throws IllegalArgumentException 形状/类型/词表/0 增量/重复任一不合法（消息带家户、维度与数字）
   */
  static List<AccountAdjustment> adjustments(JsonNode payload) {
    JsonNode entries = requireArray(payload, "entries");
    if (entries.isEmpty()) {
      throw new IllegalArgumentException("entries 不得为空");
    }
    List<AccountAdjustment> parsed = new ArrayList<>(entries.size());
    Set<HouseholdId> seen = new LinkedHashSet<>();
    int index = 0;
    for (JsonNode entry : entries) {
      if (entry == null || !entry.isObject()) {
        throw new IllegalArgumentException(
            "字段 entries 的元素必须是 {household,goods?,money?} 对象: " + entry);
      }
      HouseholdId household;
      try {
        household = AccountPayloads.household(entry, "household");
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("entries[" + index + "] 的家户不合法: " + e.getMessage(), e);
      }
      if (!seen.add(household)) {
        throw new IllegalArgumentException("同一份载荷里账目重复：household=" + household + "（同一家户只能出现一次）");
      }
      Map<CommodityId, Long> goods = deltas(entry, index, household, "goods", CommodityId::parse);
      Map<CurrencyId, Long> money = deltas(entry, index, household, "money", CurrencyId::parse);
      if (goods.isEmpty() && money.isEmpty()) {
        throw new IllegalArgumentException(
            "entries[" + index + "] 的 goods/money 至少一个必须非空（household=" + household + "；无操作条目请删）");
      }
      parsed.add(new AccountAdjustment(household, goods, money));
      index++;
    }
    return List.copyOf(parsed);
  }

  /**
   * 一张有符号净增量表（{@code goods} / {@code money} 共用）：键交给 {@code idParser}（词表在 ID 类型里），值必须是整数。
   *
   * <ul>
   *   <li>缺键 / {@code null} ⇒ 空表（"这张表没有动作"）；不是对象 ⇒ 拒；
   *   <li><b>值为 0 ⇒ 拒</b>（"无操作条目请删"）——收下它只会让"这条载荷到底想干什么"多一个假动作；
   *   <li>错误消息带 entries 下标、家户与维度（哪张表、哪个键）。
   * </ul>
   */
  private static <A> Map<A, Long> deltas(
      JsonNode entry,
      int index,
      HouseholdId household,
      String dimension,
      Function<String, A> idParser) {
    Map<A, Long> parsed = new LinkedHashMap<>();
    JsonNode node = optionalObject(entry, dimension);
    if (node == null) {
      return parsed;
    }
    Iterator<Map.Entry<String, JsonNode>> it = node.fields();
    while (it.hasNext()) {
      Map.Entry<String, JsonNode> field = it.next();
      A id;
      long delta;
      try {
        id = idParser.apply(field.getKey());
        delta = requireIntegral(field.getValue(), dimension + "." + field.getKey());
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "entries["
                + index
                + "] 的 "
                + dimension
                + " 键/值不合法（household="
                + household
                + "）: "
                + e.getMessage(),
            e);
      }
      if (delta == 0L) {
        throw new IllegalArgumentException(
            dimension
                + " 的值不得为 0（无操作条目请删）：household="
                + household
                + "，"
                + dimension
                + "."
                + field.getKey()
                + "=0");
      }
      parsed.put(id, delta);
    }
    return parsed;
  }

  // ── actor.DeductHouseholdStock（通用家户库存扣除，P1.2）────────────────────────────

  /**
   * ★★ <b>解析 {@code actor.DeductHouseholdStock} 的载荷</b>（形状见 handler 类注）：
   *
   * <pre>{@code
   * {"entries":[
   *   {"household":"hh-1","goods":{"grain":120},"money":{"silver":30},
   *    "reason":"jurisdiction_tax","detail":"unit=u-1","toHousehold":"hh-gov-u-1"},
   *   {"household":"hh-2","money":{"silver":5},"reason":"admin_upkeep"}
   * ]}
   * }</pre>
   *
   * <p>★ <b>本层只判形状 / 类型 / 词表 / 正数 / 至少一维非空</b>；"家户/账户是否存在、余额是否够、是否侵占冻结"是数值语义， 由 {@link
   * io.mosire.simos.actor.ops.StockDeductionOperations} 判（本层不重复实现）。★ <b>同一家户可以出现多次</b>：
   * 条目按载荷序顺序应用（前一条的收款后一条看得见），不是"净增量表"那种必须去重的形状。
   *
   * @throws IllegalArgumentException 形状/类型/词表/正数/自转任一不合法（消息带 entries 下标与家户）
   */
  static List<HouseholdStockDeduction> deductions(JsonNode payload) {
    JsonNode entries = requireArray(payload, "entries");
    if (entries.isEmpty()) {
      throw new IllegalArgumentException("entries 不得为空");
    }
    List<HouseholdStockDeduction> parsed = new ArrayList<>(entries.size());
    int index = 0;
    for (JsonNode entry : entries) {
      if (entry == null || !entry.isObject()) {
        throw new IllegalArgumentException(
            "字段 entries 的元素必须是 {household,goods?,money?,reason,...} 对象: " + entry);
      }
      HouseholdId household;
      try {
        household = AccountPayloads.household(entry, "household");
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("entries[" + index + "] 的家户不合法: " + e.getMessage(), e);
      }
      Map<CommodityId, Long> goods =
          positiveAmounts(entry, index, household, "goods", CommodityId::parse);
      Map<CurrencyId, Long> money =
          positiveAmounts(entry, index, household, "money", CurrencyId::parse);
      if (goods.isEmpty() && money.isEmpty()) {
        throw new IllegalArgumentException(
            "entries[" + index + "] 的 goods/money 至少一个必须非空（household=" + household + "）");
      }
      DeductionReason reason;
      try {
        reason = DeductionReason.parse(requireText(entry, "reason"));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "entries[" + index + "] 的 reason 不合法（household=" + household + "）: " + e.getMessage(),
            e);
      }
      String detail = optionalText(entry, "detail");
      Optional<HouseholdId> toHousehold = Optional.empty();
      JsonNode toNode = entry.get("toHousehold");
      if (toNode != null && !toNode.isNull()) {
        if (!toNode.isTextual() || toNode.asText().isBlank()) {
          throw new IllegalArgumentException(
              "entries[" + index + "].toHousehold 必须是非空家户 id 字符串: " + toNode);
        }
        try {
          toHousehold = Optional.of(HouseholdId.parse(toNode.asText()));
        } catch (IllegalArgumentException e) {
          throw new IllegalArgumentException(
              "entries[" + index + "].toHousehold 的家户 id 不合法: " + e.getMessage(), e);
        }
      }
      try {
        parsed.add(
            new HouseholdStockDeduction(household, goods, money, reason, detail, toHousehold));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "entries[" + index + "] 的扣除不合法（household=" + household + "）: " + e.getMessage(), e);
      }
      index++;
    }
    return List.copyOf(parsed);
  }

  /**
   * 一张<b>正数</b>扣除量表（{@code goods} / {@code money} 共用）：缺键 / {@code null} ⇒ 空表（"这张表没有动作"）； 不是对象 ⇒
   * 拒；值为 0 或负 ⇒ 拒（扣除量是正数 —— 0 不是一条发生额，请在载荷里删键）。
   */
  private static <A> Map<A, Long> positiveAmounts(
      JsonNode entry,
      int index,
      HouseholdId household,
      String dimension,
      Function<String, A> idParser) {
    Map<A, Long> parsed = new LinkedHashMap<>();
    JsonNode node = optionalObject(entry, dimension);
    if (node == null) {
      return parsed;
    }
    Iterator<Map.Entry<String, JsonNode>> it = node.fields();
    while (it.hasNext()) {
      Map.Entry<String, JsonNode> field = it.next();
      A id;
      long amount;
      try {
        id = idParser.apply(field.getKey());
        amount = requireIntegral(field.getValue(), dimension + "." + field.getKey());
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "entries["
                + index
                + "] 的 "
                + dimension
                + " 键/值不合法（household="
                + household
                + "）: "
                + e.getMessage(),
            e);
      }
      if (amount <= 0L) {
        throw new IllegalArgumentException(
            dimension
                + " 的扣除量必须 > 0（0 不是一条发生额，请删键）：household="
                + household
                + "，"
                + dimension
                + "."
                + field.getKey()
                + "="
                + amount);
      }
      parsed.put(id, amount);
    }
    return parsed;
  }

  /** 可选文本：缺键 / {@code null} ⇒ 空串；给了但非文本 ⇒ 拒（空串合法 = 没有 detail）。 */
  private static String optionalText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return "";
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是字符串: " + value);
    }
    return value.asText();
  }

  // ── 主体 / 库存 ────────────────────────────────────────────────────────────────────

  /** 一条主体：{@code {kind, id, label}}（{@code kind} 走 {@link ActorKind#parse} 的词表，词表外即抛并列出合法值）。 */
  private static Actor actor(JsonNode node) {
    return new Actor(actorRef(node), requireText(node, "label"));
  }

  /**
   * 一本家户账（P2-A §13.3）：{@code {household, balances:{<commodityId>:<余额>}, money?:{<currencyId>:<余额>},
   * frozenBalances?:{<commodityId>:<冻结额>}, frozenMoney?:{<currencyId>:<冻结额>}}}（余额与冻结额都是**存量**：0
   * 保留；数值守卫 —— 余额非负、{@code 0 ≤ 冻结 ≤ 余额} —— 由 {@code HouseholdInventory} 拒，本层不重复实现）。
   *
   * <p>★ {@code money} / {@code frozenBalances} / {@code frozenMoney} 三键**可缺省**（缺 = 空表）。
   *
   * <p>★ <b>没有 {@code location}</b>：账户键不再带格（P2-A）—— 位置从 {@code Household.location} 派生。
   */
  private static HouseholdInventory goods(JsonNode node, Set<HouseholdId> declaredHouseholds) {
    HouseholdId household;
    try {
      household = AccountPayloads.household(node, "household");
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("库存行不合法: " + e.getMessage(), e);
    }
    if (!declaredHouseholds.contains(household)) {
      throw new IllegalArgumentException("库存的 household 既不在载荷的家户集里、也不在现有状态里（悬空家户）: " + household);
    }
    JsonNode balances = optionalObject(node, "balances");
    if (balances == null) {
      throw new IllegalArgumentException("字段 balances 必须是对象: " + node);
    }
    Map<CommodityId, Long> parsed = new LinkedHashMap<>();
    balances
        .fields()
        .forEachRemaining(
            entry ->
                parsed.put(
                    CommodityId.parse(entry.getKey()),
                    requireIntegral(entry.getValue(), "balances." + entry.getKey())));
    // ★ H4（裁定 K15）：同一本账里还可以带【货币】余额。缺键 ⇒ 空表（"这一格这个人账上没钱"）。
    Map<CurrencyId, Long> money = new LinkedHashMap<>();
    JsonNode moneyNode = node.get("money");
    if (moneyNode != null && !moneyNode.isNull()) {
      if (!moneyNode.isObject()) {
        throw new IllegalArgumentException(node + " 的 money 必须是对象（币种 → 最小币值）");
      }
      Iterator<Map.Entry<String, JsonNode>> it = moneyNode.fields();
      while (it.hasNext()) {
        Map.Entry<String, JsonNode> field = it.next();
        long amount = requireIntegral(field.getValue(), node + " 的 money." + field.getKey());
        if (amount < 0L) {
          throw new IllegalArgumentException(
              node + " 的 money." + field.getKey() + " 不得为负（余额是存量）: " + amount);
        }
        money.put(new CurrencyId(field.getKey()), amount);
      }
    }
    // ★★ M1.3：M1.2 新增的两张**冻结表**同样是这本账的一部分，本解析**显式带过**它们（不许经三参便捷构造器
    //   静默清零）：① 缺键（旧载荷 / 创世载荷）⇒ 空表（fail-closed 方向：没写就是没有冻结）；
    //   ② 写了就逐键解析，数值语义（0 ≤ 冻结 ≤ 余额）交给 HouseholdInventory 的构造期守卫 —— 本层不重复实现。
    Map<CommodityId, Long> frozenBalances = new LinkedHashMap<>();
    JsonNode frozenBalancesNode = optionalObject(node, "frozenBalances");
    if (frozenBalancesNode != null) {
      frozenBalancesNode
          .fields()
          .forEachRemaining(
              field ->
                  frozenBalances.put(
                      CommodityId.parse(field.getKey()),
                      requireIntegral(field.getValue(), "frozenBalances." + field.getKey())));
    }
    Map<CurrencyId, Long> frozenMoney = new LinkedHashMap<>();
    JsonNode frozenMoneyNode = optionalObject(node, "frozenMoney");
    if (frozenMoneyNode != null) {
      frozenMoneyNode
          .fields()
          .forEachRemaining(
              field ->
                  frozenMoney.put(
                      new CurrencyId(field.getKey()),
                      requireIntegral(field.getValue(), "frozenMoney." + field.getKey())));
    }
    return new HouseholdInventory(
        new HouseholdAccountKey(household), parsed, money, frozenBalances, frozenMoney);
  }

  /** {@code {"kind","id"}}：主体引用（载荷里主体行共用**同一个**形状与解析）。 */
  private static ActorRef actorRef(JsonNode node) {
    return new ActorRef(ActorKind.parse(requireText(node, "kind")), requireText(node, "id"));
  }

  // ── 形状助手（照 EconomyPayloads）─────────────────────────────────────────────────────

  private static void requireEntryObject(JsonNode entry) {
    if (entry == null || !entry.isObject()) {
      throw new IllegalArgumentException("字段 entries 的元素必须是 {q,r} 对象: " + entry);
    }
  }

  private static String requireText(JsonNode node, String field) {
    return requireTextValue(node.get(field), field);
  }

  private static String requireTextValue(JsonNode value, String field) {
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是非空字符串: " + value);
    }
    return value.asText();
  }

  private static int requireInt(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是整数: " + node);
    }
    return value.asInt();
  }

  private static long requireIntegral(JsonNode value, String field) {
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是整数");
    }
    return value.asLong();
  }

  private static JsonNode requireArray(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isArray()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是数组: " + node);
    }
    return value;
  }

  private static Iterable<JsonNode> optionalArray(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return List.of();
    }
    if (!value.isArray()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是数组: " + node);
    }
    return value;
  }

  private static JsonNode optionalObject(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isObject()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是对象: " + node);
    }
    return value;
  }
}

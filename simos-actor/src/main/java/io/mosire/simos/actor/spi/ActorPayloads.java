package io.mosire.simos.actor.spi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorMeta;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.model.Actor;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.map.hex.HexCoord;
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
 * actor 切片的载荷解析助手（与 {@code EconomyPayloads} / {@code SocialPayloads} 同制）：目前两条命令 —— {@code
 * actor.Seed}（见下）与 {@code actor.AdjustAccounts}（见 {@link #adjustments}）。
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
 * 0……）交给 {@link GoodsAccount} 的构造期守卫 —— <b>不重复实现，一处真相</b>（同 {@code EconomyPayloads} 的分工）。
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
  static ActorData toData(JsonNode payload, Set<ActorRef> existingActors, SimosTimestamp at) {
    Objects.requireNonNull(existingActors, "existingActors");
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
    Set<ActorRef> declared = new LinkedHashSet<>(existingActors);
    declared.addAll(actors.keySet());
    Map<GoodsAccountKey, GoodsAccount> accounts = new LinkedHashMap<>();
    for (JsonNode entry : entries) {
      HexCoord atHex = new HexCoord(requireInt(entry, "q"), requireInt(entry, "r"));
      for (JsonNode node : optionalArray(entry, "goods")) {
        GoodsAccount account = goods(node, atHex, declared);
        if (accounts.putIfAbsent(account.key(), account) != null) {
          throw new IllegalArgumentException("同一份载荷里库存重复: " + account.key());
        }
      }
    }
    ActorMeta meta = new ActorMeta(mapId, at.tick(), rulesVersion);
    // ★ 两张表 + 元信息**批量**装配（`ActorData` 的 bulk wither 的调用面就在这里，见裁定 R-ae / R-ah：Task 8 用不到就删，
    //   而本任务正是它们要等的那条路）："键从值派生"的校验由 ActorData 的构造期守卫统一把守，本类不自己拼键。
    return ActorData.empty().withMeta(Optional.of(meta)).withActors(actors).withAccounts(accounts);
  }

  // ── actor.AdjustAccounts（净增量账，阶段 6 / 计划 §6.2）────────────────────────────

  /**
   * ★★ <b>{@code actor.AdjustAccounts} 的一条账目</b>：{@code (owner, 格)} + 两张<b>有符号净增量</b>表。
   *
   * <p>★ 两张表<b>至少一张非空</b>（否则这条账目没有任何动作，解析期已拒）；表的迭代序 = 载荷里的键序（{@code LinkedHashMap}）， 构造期冻成不可变。★
   * 增量是<b>净量</b>（不是存量），0 已在解析期拒（无操作条目请删）。
   */
  record AccountAdjustment(
      ActorRef owner,
      HexCoord location,
      Map<CommodityId, Long> goods,
      Map<CurrencyId, Long> money) {

    AccountAdjustment {
      if (owner == null || location == null || goods == null || money == null) {
        throw new IllegalArgumentException("AccountAdjustment 的组件都不得为 null");
      }
      // ★ 冻在赋值处（保序不可变：LinkedHashMap + Collections.unmodifiableMap；不用 Map.copyOf）。
      goods = Collections.unmodifiableMap(new LinkedHashMap<>(goods));
      money = Collections.unmodifiableMap(new LinkedHashMap<>(money));
    }
  }

  /**
   * 解析 {@code actor.AdjustAccounts} 的载荷（形状见 {@code AdjustAccountsHandler} 的类注）： {@code
   * entries[{owner{kind,id}, q, r, goods?, money?}...]}。
   *
   * <p>★ <b>本层只判形状 / 类型 / 词表 / 0 增量 / 同一 {@code (owner, 格)} 重复</b>；"负增量是否使余额 &lt; 0 / 侵占冻结额、
   * 缺账能否新建"是<b>数值语义</b>，由 {@code AdjustAccountsHandler} 判（本层不重复实现）。
   *
   * @throws IllegalArgumentException 形状/类型/词表/0 增量/重复任一不合法（消息带 owner、位置、维度与数字）
   */
  static List<AccountAdjustment> adjustments(JsonNode payload) {
    JsonNode entries = requireArray(payload, "entries");
    if (entries.isEmpty()) {
      throw new IllegalArgumentException("entries 不得为空");
    }
    List<AccountAdjustment> parsed = new ArrayList<>(entries.size());
    Set<GoodsAccountKey> seen = new LinkedHashSet<>();
    int index = 0;
    for (JsonNode entry : entries) {
      if (entry == null || !entry.isObject()) {
        throw new IllegalArgumentException(
            "字段 entries 的元素必须是 {owner{kind,id},q,r,goods?,money?} 对象: " + entry);
      }
      int q = requireInt(entry, "q");
      int r = requireInt(entry, "r");
      HexCoord at = new HexCoord(q, r);
      ActorRef owner = adjustmentOwner(entry, index, at);
      GoodsAccountKey key = new GoodsAccountKey(owner, at);
      if (!seen.add(key)) {
        throw new IllegalArgumentException(
            "同一份载荷里账目重复：owner=" + owner + "，格 " + hex(at) + "（同一 (owner,格) 只能出现一次）");
      }
      Map<CommodityId, Long> goods = deltas(entry, index, owner, at, "goods", CommodityId::parse);
      Map<CurrencyId, Long> money = deltas(entry, index, owner, at, "money", CurrencyId::parse);
      if (goods.isEmpty() && money.isEmpty()) {
        throw new IllegalArgumentException(
            "entries["
                + index
                + "] 的 goods/money 至少一个必须非空（owner="
                + owner
                + "，格 "
                + hex(at)
                + "；无操作条目请删）");
      }
      parsed.add(new AccountAdjustment(owner, at, goods, money));
      index++;
    }
    return List.copyOf(parsed);
  }

  /** 一条账目的 {@code owner}：形状/词表错都带 entries 下标与位置（见 {@link #adjustments} 的类注）。 */
  private static ActorRef adjustmentOwner(JsonNode entry, int index, HexCoord at) {
    JsonNode ownerNode = optionalObject(entry, "owner");
    if (ownerNode == null) {
      throw new IllegalArgumentException(
          "entries[" + index + "] 的字段 owner 必须是 {kind,id} 对象: " + entry);
    }
    try {
      return actorRef(ownerNode);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "entries[" + index + "] 的 owner 不合法（格 " + hex(at) + "）: " + e.getMessage(), e);
    }
  }

  /**
   * 一张有符号净增量表（{@code goods} / {@code money} 共用）：键交给 {@code idParser}（词表在 ID 类型里），值必须是整数。
   *
   * <ul>
   *   <li>缺键 / {@code null} ⇒ 空表（"这张表没有动作"）；不是对象 ⇒ 拒；
   *   <li><b>值为 0 ⇒ 拒</b>（"无操作条目请删"）——收下它只会让"这条载荷到底想干什么"多一个假动作；
   *   <li>错误消息带 entries 下标、owner、位置与维度（哪张表、哪个键）。
   * </ul>
   */
  private static <A> Map<A, Long> deltas(
      JsonNode entry,
      int index,
      ActorRef owner,
      HexCoord at,
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
                + " 键/值不合法（owner="
                + owner
                + "，格 "
                + hex(at)
                + "）: "
                + e.getMessage(),
            e);
      }
      if (delta == 0L) {
        throw new IllegalArgumentException(
            dimension
                + " 的值不得为 0（无操作条目请删）：owner="
                + owner
                + "，格 "
                + hex(at)
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

  // ── 主体 / 库存 ────────────────────────────────────────────────────────────────────

  /** 一条主体：{@code {kind, id, label}}（{@code kind} 走 {@link ActorKind#parse} 的词表，词表外即抛并列出合法值）。 */
  private static Actor actor(JsonNode node) {
    return new Actor(actorRef(node), requireText(node, "label"));
  }

  /**
   * 一本账：{@code {owner, location, balances:{<commodityId>:<余额>}, money?:{<currencyId>:<余额>},
   * frozenBalances?:{<commodityId>:<冻结额>}, frozenMoney?:{<currencyId>:<冻结额>}}}（余额与冻结额都是**存量**：0
   * 保留；数值守卫 —— 余额非负、{@code 0 ≤ 冻结 ≤ 余额} —— 由 {@code GoodsAccount} 拒，本层不重复实现）。
   *
   * <p>★ {@code money} / {@code frozenBalances} / {@code frozenMoney} 三键**可缺省**（缺 = 空表）：前者是 H4
   * 的口径，后两者是 M1.2 新增的组件 ⇒ M1.2 之前写的载荷里根本没有它们（照 {@code GoodsAccount} 的旧档兼容口径）。
   */
  private static GoodsAccount goods(JsonNode node, HexCoord atHex, Set<ActorRef> declared) {
    ActorRef owner = owner(node);
    requireOwnerDeclared(owner, declared, atHex);
    HexCoord location = location(node);
    requireLocationAtEntry(location, atHex);
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
    //   ② 写了就逐键解析，数值语义（0 ≤ 冻结 ≤ 余额）交给 GoodsAccount 的构造期守卫 —— 本层不重复实现。
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
    return new GoodsAccount(
        new GoodsAccountKey(owner, location), parsed, money, frozenBalances, frozenMoney);
  }

  /** {@code {"kind","id"}}：主体引用（载荷里主体行与两张表的 {@code owner} 共用**同一个**形状与解析）。 */
  private static ActorRef actorRef(JsonNode node) {
    return new ActorRef(ActorKind.parse(requireText(node, "kind")), requireText(node, "id"));
  }

  /** 产权/库存行的 {@code owner}（必须是 {@code {kind,id}} 对象：形状判在本层，不落到 NPE）。 */
  private static ActorRef owner(JsonNode node) {
    JsonNode owner = optionalObject(node, "owner");
    if (owner == null) {
      throw new IllegalArgumentException("字段 owner 必须是 {kind,id} 对象: " + node);
    }
    return actorRef(owner);
  }

  /** ★★ 悬空 owner ⇒ 拒（见类注：既不在载荷里、也不在现有状态里的主体 = 静默的幽灵）。 */
  private static void requireOwnerDeclared(ActorRef owner, Set<ActorRef> declared, HexCoord atHex) {
    if (!declared.contains(owner)) {
      throw new IllegalArgumentException("库存的 owner 不是已声明的主体: " + owner + "（格 " + hex(atHex) + "）");
    }
  }

  /** ★★ 库存是"到格"的：行内的 {@code location} 必须等于所在格（见类注的权限理由）。 */
  private static void requireLocationAtEntry(HexCoord location, HexCoord atHex) {
    if (!location.equals(atHex)) {
      throw new IllegalArgumentException(
          "库存的 location 必须等于所在格: location=" + location + "，格=" + hex(atHex));
    }
  }

  /** 行的 {@code location}（{@code {q,r}} 对象；缺键/非对象/非整数一律抛）。 */
  private static HexCoord location(JsonNode node) {
    JsonNode location = optionalObject(node, "location");
    if (location == null) {
      throw new IllegalArgumentException("字段 location 必须是 {q,r} 对象: " + node);
    }
    return new HexCoord(requireInt(location, "q"), requireInt(location, "r"));
  }

  /** 格的键：{@code <q>_<r>}（**只经 {@link ResourcePaths#actor}**，本类不再有第二个拼写点）。 */
  private static String hex(HexCoord coord) {
    return ResourcePaths.actor(coord.q(), coord.r());
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

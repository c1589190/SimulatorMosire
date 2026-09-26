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
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@code actor.Seed} 命令的载荷解析助手（与 {@code EconomyPayloads} / {@code SocialPayloads} 同制）。
 *
 * <p>★ <b>载荷形态是本模块的私事</b>（C26）：Core 只转交 {@code payloadJson} 字节串。形态如下（<b>与三个 record 的字段一一对应</b>）：
 *
 * <pre>{@code
 * {"mapId":"Map1","rulesVersion":"actor-v1","entries":[
 *   {"q":0,"r":0,
 *    "actors":[{"kind":"ESTATE","id":"farm@0_0","label":"农业庄园"},
 *              {"kind":"HOUSEHOLD","id":"house@0_0","label":"农户"}],
 *    "goods":[{"owner":{"kind":"HOUSEHOLD","id":"house@0_0"},
 *              "location":{"q":0,"r":0},
 *              "balances":{"grain":2241000,"fiber":0}}]}]}
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

  // ── 主体 / 库存 ────────────────────────────────────────────────────────────────────

  /** 一条主体：{@code {kind, id, label}}（{@code kind} 走 {@link ActorKind#parse} 的词表，词表外即抛并列出合法值）。 */
  private static Actor actor(JsonNode node) {
    return new Actor(actorRef(node), requireText(node, "label"));
  }

  /**
   * 一本账：{@code {owner, location, balances:{<commodityId>:<余额>}}}（余额是**存量**：0 保留、负数由 {@code
   * GoodsAccount} 拒）。
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
    return new GoodsAccount(new GoodsAccountKey(owner, location), parsed);
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

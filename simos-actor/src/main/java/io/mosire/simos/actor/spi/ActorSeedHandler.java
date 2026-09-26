package io.mosire.simos.actor.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.change.ActorChangeSet;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.map.hex.HexCoord;
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
 * ★★ {@code actor.Seed} 命令的处理器：**一次把逐格的初始 actor 状态种进切片** —— 一条命令、一条 revision（与 {@code economy.Seed}
 * / {@code social.SetPopulation} 同制）。
 *
 * <p>★★ <b>它只做 materialize，不做任何公式</b>：哪一格上是谁、谁账上有多少，全在**载荷**里（由生成器按场景参数算好）， 本类把它翻成领域类型。理由同 {@code
 * EconomySeedHandler}：本切片的既定边界是"模块化、无公式"，而"一切数字来自场景参数" 这条纪律落在生成器一处即可（两条路各算一遍必然漂移）。
 *
 * <p>★★ <b>已激活（{@code meta} 非空）⇒ 按格追加</b>（照 economy 的 R2a 裁定）：世界有**多国**，各国先后各播一批 （各国的格互不相同）——
 * 按**库**判会播完第一国就把后两国的整批挡回滚。故判据降到**格**：
 *
 * <ul>
 *   <li>{@code meta} 空 ⇒ <b>首次播种</b>：整份载荷落盘并**打标**（{@code activatedDay} = 世界当前 tick）。
 *   <li>{@code meta} 非空 ⇒ <b>逐格</b>判：该格若<b>已有库存行</b>（actor 切片里"落在某格"的记录只有这一张表， 主体表没有位置字段——见 {@code
 *       Actor} 的"身份本体"禁令）⇒ <b>整份拒绝</b>并点名该格；否则把该格追加进现有切片。 ★ <b>整份拒绝而不是逐格跳过</b>：一条命令 = 一条
 *       revision，"部分生效"是另一种语义。 <b>{@code meta} 不覆盖</b>（保留首次的 {@code activatedDay} / {@code
 *       rulesVersion}）。
 * </ul>
 *
 * <p>★ <b>2026-09-27 裁定 S3</b>：产权（{@code holdings}）整块退役 ⇒ "某格是否已被播种"的判据只剩<b>库存表</b>一张。
 *
 * <p>★ <b>目标资源</b>（{@link CommandTargets}）：{@code entries[]} 里**每一个**格的{@link
 * ResourcePaths#actor(int, int)} （{@code <q>_<r>}）—— GM 代执行决策人令时据此逐条判越权（与 {@code
 * EconomySeedHandler} 同款）。
 *
 * <p>★ <b>校验分工</b>：形状/类型/词表在本包 {@link ActorPayloads} 判；数值语义（余额 ≥ 0）由领域类型与 {@link ActorData}
 * 构造期守卫判——**不重复实现**。两者的失败都以 {@code Rejected} 出面；**装配故障**（state 里没有 actor 切片）则当场炸， 不走拒绝路径（见 {@link
 * ActorSnapshots}）。
 */
public final class ActorSeedHandler implements CommandHandler, CommandTargets {

  @Override
  public String type() {
    return "actor.Seed";
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    return ActorPayloads.entryHexKeys(ActorPayloads.parse(payloadJson));
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    ActorData base = ActorSnapshots.of(state).data(); // 装配故障当场炸，不走拒绝路径
    JsonNode payload;
    ActorData seeded;
    try {
      payload = ActorPayloads.parse(payloadJson);
      // ★ 现有主体表一并交给载荷层：悬空 owner 的判据是"到这个命令为止该主体存不存在"，
      //   故"先落主体、后落库存"的写序照样合法。
      seeded = ActorPayloads.toData(payload, base.actors().keySet(), state.meta().timestamp());
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
    if (base.meta().isEmpty()) {
      return new HandlerOutcome.Applied(ActorChangeSet.between(base, seeded)); // 首次播种：打标
    }
    // ★ 已激活 ⇒ 按格追加：先逐格判重（任一格已被占用 ⇒ 整份拒绝并点名该格），再并入现有切片。
    Set<String> occupied = occupiedHexKeys(base);
    for (String hex : ActorPayloads.entryHexKeys(payload)) {
      if (occupied.contains(hex)) {
        return new HandlerOutcome.Rejected(
            "格 " + hex + " 已有 actor 状态（库存行），拒绝重复播种: mapId=" + base.meta().orElseThrow().mapId());
      }
    }
    // ★ 两张表**批量**并表（`ActorData` 的 bulk wither 的第二个调用面，见裁定 R-ae / R-ah）：
    //   merge 保留 base 的插入序、把新增项接在后面。
    //   ★ 为什么并表是安全的（不会静默覆盖）：那张带 location 的表里每一行的 location 都必须等于它所在载荷格
    //     （ActorPayloads 的判据），而这里每一格都已判过"没被占用" ⇒ 键不可能撞上。
    //   ★ meta 走 base 的：不覆盖首次播种的 activatedDay / rulesVersion。
    ActorData merged =
        base.withActors(merge(base.actors(), seeded.actors()))
            .withAccounts(merge(base.accounts(), seeded.accounts()));
    return new HandlerOutcome.Applied(ActorChangeSet.between(base, merged));
  }

  /**
   * 现有切片里**已被占用的格键**（{@code <q>_<r>}）。
   *
   * <p>★ 判据只能落在有位置的**库存表**上：{@code actors} 没有位置字段（{@code Actor} 恰两件 {ref, label}， spec §三 L283
   * 的禁令），故"某格是否已被播种"由"该格上有没有库存行"来回答（产权表已随裁定 S3 退役）。★ 路径经 {@link ResourcePaths#actor(int, int)} 拼——与
   * {@code entries[]} 那条**同一个来源**，否则判重与目标声明会各拼一份。
   */
  private static Set<String> occupiedHexKeys(ActorData base) {
    Set<String> hexes = new LinkedHashSet<>();
    for (GoodsAccountKey key : base.accounts().keySet()) {
      hexes.add(hexKeyOf(key.location()));
    }
    return hexes;
  }

  private static String hexKeyOf(HexCoord coord) {
    return ResourcePaths.actor(coord.q(), coord.r());
  }

  /** 追加表：保留 {@code base} 的插入序，再把新增项接在后面（保序不可变的纯形态仍由 {@link ActorData} 构造期冻结）。 */
  private static <K, V> Map<K, V> merge(Map<K, V> base, Map<K, V> added) {
    LinkedHashMap<K, V> merged = new LinkedHashMap<>(base);
    merged.putAll(added);
    return merged;
  }
}

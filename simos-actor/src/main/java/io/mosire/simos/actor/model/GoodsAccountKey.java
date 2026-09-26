package io.mosire.simos.actor.model;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.map.hex.HexCoord;

/**
 * ★★ <b>商品余额的聚合键</b>：{@code (owner, location)} —— 两段里<b>任一不同就是另一本账</b>（不合并）。
 *
 * <p>★★ <b>为什么键是这两段而不是别的</b>：库存是"某人<b>在某一格</b>有多少商品"的关系。少写 {@code location} ⇒「全境有多少粮」与
 * 「这一格有多少粮」混成一件事（阶段 4 产出落 operator、阶段 6 消费从 receipt 来，都要按格算）； 少写 {@code owner} ⇒
 * 同一格上两个主体的库存会挤进同一本账，而"这一格谁手上有多少粮"就答不出来了。
 *
 * <p>★★ <b>为什么不像 {@link AssetHoldingKey} 那样带 {@code assetKey}</b>：资产类键是<b>同质性条件</b>（影响生产的地力等）， 而商品
 * —— 粮就是粮 —— 的同质性条件已经由那一段里的 {@link io.mosire.simos.economy.api.id.CommodityId} 自身表达 （课税的粮与种子粮是两个
 * {@code CommodityId}）。本类只负责"谁的、在哪一格"，"哪一种"由值那张余额表按商品逐条记。
 *
 * <p>★★ <b>本类型自带"裸 {@code toString()} + 单参 {@code parse}"这一对</b>（裁定 R-48-f，照 {@link
 * AssetHoldingKey#parse} / {@link ActorRef#parseCanonical} 的先例）：{@code FieldDelta}（{@code
 * simos-util}）把状态表的键压成 {@code toString()} 的产物、重建时用 {@code parse} 还原 ⇒ 缺了这条配对，本切片就被迫自己写规范串的逆，
 * 于是<b>同一个格式有了两处拼写点</b>。★ <b>格式的拼写只许在一个文件之内</b>：分隔符常量、{@code toString()} 与 {@code parse} 同住本文件（照
 * {@code ClassKey} / {@code EdgeRef} 的先例）。
 *
 * <p>★ <b>规范串的形状</b>：{@code <owner>|<location>}，例如 {@code ESTATE:farm@0_0|0_0}。两段各自交给上游的逆（{@link
 * ActorRef#parseCanonical} / {@link HexCoord#parse}）—— <b>本类不知道</b>冒号怎么切、坐标怎么切，只认"第一个 {@code |}
 * 是接缝"。
 *
 * <p>★★ <b>为什么按"第一个 {@code |}"切</b>（与 {@link AssetHoldingKey} 同款）：地格段是 {@code <q>_<r>}（数字与 {@code
 * _}），<b>必然不含 {@code |}</b> ⇒ 第一个接缝之后整段都是地格，这个切法是唯一能还原的。★ 反面写法"按<b>最后一个</b>接缝切" 在 {@link #parse}
 * 的坏输入上会<b>静默</b>造出错的键（见下条前提），故明令不许。
 *
 * <p>★ <b>本逆成立的前提，如实写在这里</b>（与 {@link AssetHoldingKey} 同一条已声明前提）：{@link ActorRef#id()} 是任意非空白文本 ⇒
 * 若某个 id 里含 {@code |}，第一个接缝就会落进 id 内部。这一档<b>不会静默产出错的键</b>：切歪之后余下那段喂不进 {@link
 * HexCoord#parse}，当场抛（fail-closed）。全仓现行的 id 形状（{@code farm@0_0} / {@code rural:0_0:MALE:1} / {@code
 * craft@-3_2}）不含 {@code |}。
 *
 * <p>★ <b>宁抛不静默</b>（照 {@code ClassKey} / {@code EdgeRef} 的口径）：{@code null} / 空白 / 没有接缝 / 接缝在首 /
 * 接缝在尾，一律 {@link IllegalArgumentException} —— 静默造一个半截的库存身份，比当场炸难查得多。
 *
 * @param owner 持有者（{@code ActorRef} 是身份；改名不影响它）
 * @param location 持有位置（库存是"到格"的：同一 owner 在两格各是一本账 = 两条，不合并）
 */
public record GoodsAccountKey(ActorRef owner, HexCoord location) {

  /**
   * 规范串的段分隔符 —— <b>只在 {@link #toString()} 与 {@link #parse(String)} 两处被读</b>
   * （同处一个文件，故"分隔符长什么样"在本类型只有这一个拼写点）。
   */
  private static final String SEGMENT_SEPARATOR = "|";

  public GoodsAccountKey {
    if (owner == null) {
      throw new IllegalArgumentException("GoodsAccountKey.owner 不得为 null");
    }
    if (location == null) {
      throw new IllegalArgumentException("GoodsAccountKey.location 不得为 null");
    }
  }

  /** 规范串：{@code <owner>|<location>}（既是变更集的 key，也是 JSON Map 的键）。 */
  @Override
  public String toString() {
    return owner + SEGMENT_SEPARATOR + location;
  }

  /**
   * 解析 {@link #toString()} 的产物（见类注释：按<b>第一个</b>接缝切）。
   *
   * <p>★ 两段各自交给上游的逆（{@link ActorRef#parseCanonical} / {@link HexCoord#parse}）—— <b>本类不复述它们的格式</b>，
   * 故上游改了规范串，本类的往返当场跟着红。
   */
  public static GoodsAccountKey parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("非法库存键: " + text);
    }
    int ownerEnd = text.indexOf(SEGMENT_SEPARATOR);
    if (ownerEnd <= 0) {
      throw new IllegalArgumentException("非法库存键（所有者段缺失或在首）: " + text);
    }
    if (ownerEnd == text.length() - SEGMENT_SEPARATOR.length()) {
      throw new IllegalArgumentException("非法库存键（地格段缺失）: " + text);
    }
    return new GoodsAccountKey(
        ActorRef.parseCanonical(text.substring(0, ownerEnd)),
        HexCoord.parse(text.substring(ownerEnd + SEGMENT_SEPARATOR.length())));
  }
}

package io.mosire.simos.actor.model;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetClassKey;
import io.mosire.simos.map.hex.HexCoord;

/**
 * ★★ <b>产权的聚合键</b>（I2.2 前半句）：{@code (owner, location, assetKey)} —— 三段里<b>任一不同就是另一份</b>产权。
 *
 * <p>★★ <b>为什么键是这三段而不是别的</b>（spec §2.3）：产权是"某人<b>在某一格</b>持有<b>某一类</b>资产"的关系。 少写 {@code location}
 * ⇒「某人在全境有多少地」与「这块地归谁」混成一件事；少写 {@code assetKey} ⇒ 同一格上的 B 等地与 C 等地会挤进同一条持有，而 {@code AssetClassKey}
 * 的全部意义恰恰是"影响生产的同质性条件"； 少写 {@code owner} ⇒ 佃制（同一格上地主与佃农各持一份，见 {@link AssetHolding}）根本表达不出来。
 *
 * <p>★★ <b>本类型自带"裸 {@code toString()} + 单参 {@code parse}"这一对</b>（裁定 R-48-f，照 {@link
 * ActorRef#parseCanonical} 的先例）：{@code FieldDelta}（{@code simos-util}）把状态表的键压成 {@code toString()}
 * 的产物、重建时用 {@code parse} 还原 ⇒ 缺了这条配对，本切片就被迫自己写规范串的逆， 于是<b>同一个格式有了两处拼写点</b>。★
 * <b>格式的拼写只许在一个文件之内</b>：分隔符常量、{@code toString()} 与 {@code parse} 同住本文件（照 {@code ClassKey} / {@code
 * EdgeRef} 的先例）。
 *
 * <p>★ <b>规范串的形状</b>：{@code <owner>|<location>|<assetKey>}，例如 {@code
 * ESTATE:farm@0_0|0_0|LAND|arable=true|quality=B}。三段各自交给上游的逆（{@link ActorRef#parseCanonical} /
 * {@link HexCoord#parse} / {@link AssetClassKey#parse}）—— <b>本类不知道</b>冒号怎么切、坐标怎么切、qualities
 * 怎么排，只认"头两个 {@code |} 是接缝"。
 *
 * <p>★★ <b>为什么按"头两个 {@code |}"切，而不是按"段数恰为 3"</b>：{@link AssetClassKey#toString()} 自身就含 {@code
 * |}（{@code LAND|arable=true|quality=B}）⇒ 段数恒 &gt; 3。而地格段的形状是 {@code <q>_<r>} （数字与 {@code
 * _}），<b>必然不含 {@code |}</b>；资产类段永远在最后。故"第一个 {@code |} 结所有者、第二个 {@code |} 结地格、其余全是资产类"是唯一能还原的切法。
 *
 * <p>★ <b>本逆成立的前提，如实写在这里</b>：{@link ActorRef#id()} 是任意非空白文本（{@code ActorRef} 只拒空白、不拒字符）⇒ 若某个 id 里含
 * {@code |}，第一个接缝就会落进 id 内部。这一档<b>不会静默产出错的键</b>： 切歪之后 {@link ActorRef#parseCanonical} 或 {@link
 * HexCoord#parse} 当场抛（fail-closed）。全仓现行的 id 形状（{@code farm@0_0} / {@code rural:0_0:MALE:1} / {@code
 * craft@-3_2}）不含 {@code |}。★ 这与 {@code ClassKey} 的"分隔符不可能出现在自身分隔位置上"是同一族的、**已声明**的前提，不是本类独有。
 *
 * <p>★ <b>宁抛不静默</b>（照 {@code ClassKey} / {@code EdgeRef} 的口径）：{@code null} / 空白 / 没有接缝 / 接缝在首尾 /
 * 某段为空，一律 {@link IllegalArgumentException} —— 静默造一个半截的产权身份，比当场炸难查得多。
 *
 * @param owner 持有者（{@code ActorRef} 是身份；改名不影响它）
 * @param location 持有位置（资产是"到格"的：同一 owner 在两格各持一份 = 两条，不合并）
 * @param assetKey 资产同质性键（粗类型 + qualities；B 等地与 C 等地是两个键）
 */
public record AssetHoldingKey(ActorRef owner, HexCoord location, AssetClassKey assetKey) {

  /**
   * 规范串的段分隔符 —— <b>只在 {@link #toString()} 与 {@link #parse(String)} 两处被读</b>
   * （同处一个文件，故"分隔符长什么样"在本类型只有这一个拼写点）。
   */
  private static final String SEGMENT_SEPARATOR = "|";

  public AssetHoldingKey {
    if (owner == null) {
      throw new IllegalArgumentException("AssetHoldingKey.owner 不得为 null");
    }
    if (location == null) {
      throw new IllegalArgumentException("AssetHoldingKey.location 不得为 null");
    }
    if (assetKey == null) {
      throw new IllegalArgumentException("AssetHoldingKey.assetKey 不得为 null");
    }
  }

  /** 规范串：{@code <owner>|<location>|<assetKey>}（既是变更集的 key，也是 JSON Map 的键）。 */
  @Override
  public String toString() {
    return owner + SEGMENT_SEPARATOR + location + SEGMENT_SEPARATOR + assetKey;
  }

  /**
   * 解析 {@link #toString()} 的产物（见类注释：按<b>头两个</b>接缝切，因为资产类段自身含分隔符）。
   *
   * <p>★ 三段各自交给上游的逆（{@link ActorRef#parseCanonical} / {@link HexCoord#parse} / {@link
   * AssetClassKey#parse}）—— <b>本类不复述它们的格式</b>，故上游改了规范串，本类的往返当场跟着红。
   */
  public static AssetHoldingKey parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("非法产权键: " + text);
    }
    int ownerEnd = text.indexOf(SEGMENT_SEPARATOR);
    if (ownerEnd <= 0) {
      throw new IllegalArgumentException("非法产权键（所有者段缺失或在首）: " + text);
    }
    int locationEnd = text.indexOf(SEGMENT_SEPARATOR, ownerEnd + SEGMENT_SEPARATOR.length());
    if (locationEnd <= ownerEnd + SEGMENT_SEPARATOR.length()
        || locationEnd == text.length() - SEGMENT_SEPARATOR.length()) {
      throw new IllegalArgumentException("非法产权键（地格段或资产类段缺失）: " + text);
    }
    return new AssetHoldingKey(
        ActorRef.parseCanonical(text.substring(0, ownerEnd)),
        HexCoord.parse(text.substring(ownerEnd + SEGMENT_SEPARATOR.length(), locationEnd)),
        AssetClassKey.parse(text.substring(locationEnd + SEGMENT_SEPARATOR.length())));
  }
}

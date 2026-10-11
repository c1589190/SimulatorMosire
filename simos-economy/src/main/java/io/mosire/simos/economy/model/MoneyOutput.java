package io.mosire.simos.economy.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.mosire.simos.economy.api.id.CurrencyId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★★★ <b>W5（2026-10-11）：铸币生产方式产出率 —— 币种 → 每 1 单位规模产出多少毫货币</b>。
 *
 * <p>★★ <b>它解决什么问题（用户 2026-10-10 原话，见约束设计书 §1）</b>：
 *
 * <blockquote>
 *
 * 「铸币/发债我认为应该是在购买环节之后，因为<b>货币本身作为债务也要被挂单</b>，铸币更是<b>必须要市场家户参与市场行为才能流入市场</b>」
 * 「因为<b>铸币方法事实上没做</b>，理论上来说<b>政府这轮能新投入多少取决于上一轮印了多少</b>」
 *
 * </blockquote>
 *
 * <p>⇒ 钱不是"政府缺钱就直接加个数字"，而是<b>生产出来的</b>：跟粮/布/工具一样挂在 {@code Industry} 的产出侧， 由<b>政府家户</b>作为组织者（{@code
 * ProductionProcess.operator}）在周期关账时收获。本类型就是那一路产出的<b>形状</b> —— 它与 {@code
 * Industry.outputPerUnit}（{@code Map<CommodityId,Long>}）<b>刻意同形</b>：
 *
 * <pre>
 * Industry.outputPerUnit      : Map&lt;CommodityId, Long&gt; 每 1 单位规模产出多少**商品单位**（结算时 ×1000 换毫）
 * Industry.moneyOutputPerUnit : MoneyOutput              每 1 单位规模产出多少**毫货币**（★ 已经是毫，不再 ×1000）
 * </pre>
 *
 * <p>★★ <b>为什么货币不能塞进 {@code outputPerUnit}（键类型不同不是洁癖）</b>：
 *
 * <ol>
 *   <li>{@code CommodityId} 与 {@code CurrencyId} 是<b>两个不同的稳定身份命名空间</b>（铁律 1）—— 把币塞进商品表会让 "这个 id
 *       到底是什么"在读口处不可判，且 {@code CurrencyId} 有自己的词表（{@code EconomyData.currencies}）与发行人守卫；
 *   <li>商品产出走<b>货物账</b>（{@code householdGoods}，可冻结/可运输/可作实物清偿介质），货币产出走<b>账户余额</b> （{@code
 *       householdMoney}，不进货物账、不参与实物保留/损耗）⇒ 两条入账路径结构上不同；
 *   <li>★ <b>量纲</b>：{@code outputPerUnit} 是"商品单位/规模"（结算时 ×1000 换毫），而货币的最小计量单位<b>就是毫</b> （{@code
 *       MoneyIssuanceRecord.amount} 一律毫）⇒ 本表的逐值是<b>毫/规模·周期</b>，故意不做 ×1000。
 * </ol>
 *
 * <p>★★ <b>空表 = 这个产业不铸币</b>（不是"铸 0"）：逐值退回 W5 之前的行为，所有既有产业（农业/纺织/作坊/贸易/官署） 一个字节都不受影响（I-C2 的"未设新东西 ⇒
 * 不动作"）。
 *
 * <p>★★ <b>确定性与冻结纪律（I7）</b>：{@code LinkedHashMap} + {@code Collections.unmodifiableMap} 写在字段赋值处，
 * <b>绝不用 {@code Map.copyOf}</b>（迭代序不是内容的纯函数 ⇒ 快照字节会抖）。逐值 {@code > 0}：0 不是一条产出 （"这个币铸
 * 0"没有表达力，删掉该键即可）。
 *
 * @param perUnit 币种 → 每 1 单位规模的产出（**毫货币** / 规模 · 周期）；不得为 null、键值不得为 null、逐值必须 &gt; 0； 空表 =
 *     不铸币。保序不可变
 */
public record MoneyOutput(Map<CurrencyId, Long> perUnit) {

  /** ★ <b>不铸币</b>（所有既有产业的缺省；空表是合法状态，不是"缺数据"）。 */
  public static final MoneyOutput NONE = new MoneyOutput(Map.of());

  public MoneyOutput {
    if (perUnit == null) {
      throw new IllegalArgumentException("MoneyOutput.perUnit 不得为 null（不铸币请给 MoneyOutput.NONE）");
    }
    // ★★ 逐字展开、不抽 helper（照 Industry 的五段先例）：SpotBugs 的 EI_EXPOSE_REP 不做跨过程分析，
    //   只认它看得见的包装 ⇒ 把"复制 + unmodifiableMap"塞进 helper 会让本组件被报为"暴露内部表示"。
    Map<CurrencyId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<CurrencyId, Long> entry : perUnit.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("MoneyOutput.perUnit 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() <= 0L) {
        throw new IllegalArgumentException(
            "MoneyOutput.perUnit 的数量必须为正（0 不是一条产出，删掉该键即可）："
                + entry.getKey().value()
                + " = "
                + entry.getValue());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    perUnit = Collections.unmodifiableMap(copy); // ★ 冻在赋值处
  }

  /**
   * 空表（不铸币）。
   *
   * <p>★★ <b>线格式契约（W5-fix，2026-10-11）</b>：这是<b>派生判断</b>、不是状态组件 ⇒ 必须 {@link JsonIgnore}。Jackson 的
   * bean 内省把 {@code isEmpty()} 当成属性 {@code empty} <b>写进字节</b>（实得 {@code
   * "moneyOutputPerUnit":{"perUnit":{},"empty":true}}），而全仓读侧（{@code FAIL_ON_UNKNOWN_PROPERTIES}
   * 保持默认的严格，见 {@code SimosObjectMapper}）随即炸在 {@code UnrecognizedPropertyException: Unrecognized
   * field "empty" … (one known property: "perUnit")} ⇒ <b>含铸币产业的快照/检查点/变更集自己写出来的字节读不回</b>（W5
   * 引入的往返缺陷， 铁律 5）：{@code EconomyCodecTest} 12 红一个根因、{@code simos-app} 创世后第一次 replay 即 {@code
   * REPLAY_DECODE_FAILED}。
   *
   * <p>★★ <b>为什么必须落在类型上、不能只靠 codec 的 mixin</b>（与 {@link MarketTable#isEmpty()}、{@link
   * EconomyMeta#isCurrentRuntimeVersion()} 逐字同一条理由）：重放走 Core 的第四台 mapper（{@code
   * Timeline.CHANGESET_MAPPER}）与真实 Shell 的 {@code SimosObjectMapper.create()} 裸
   * mapper，两者都<b>看不见模块私有 mixin</b> ⇒ 不摘掉本属性，任何改过 {@code industries} 的 revision 都会在重放时因未知键 {@code
   * empty} fail-closed。
   *
   * <p>★ <b>它不是"把严格关掉"</b>：{@code @JsonIgnore} <b>只具名</b>摘掉这一个派生属性，其余未知字段照旧 fail-closed
   * （漂移信号不丢）；读侧同时把它登记成<b>可忽略</b>属性 ⇒ 本次修复前写出的那些带 {@code "empty"} 的字节也仍能读回。 ★ 同一族先例：{@link
   * MarketTable#isEmpty()}（W1f）、{@link EconomyMeta#isCurrentRuntimeVersion()}。
   */
  @JsonIgnore
  public boolean isEmpty() {
    return perUnit.isEmpty();
  }

  /** 这个币的每单位产出；不含该币 ⇒ {@code 0}（= 本产业不铸这个币）。 */
  public long perUnitOf(CurrencyId currency) {
    return perUnit.getOrDefault(currency, 0L);
  }

  /**
   * ★★ <b>本周期这一路铸币的产出（毫）</b>：{@code scale × perUnit[currency]}，逐币种、精确加法（溢出即抛）。
   *
   * <p>★ 与商品侧同一条口径（{@code gross = scale × quantityPerUnit}），<b>但没有 ×1000</b>（量纲注见类头）。
   */
  public Map<CurrencyId, Long> grossOf(long scale) {
    if (scale < 0L) {
      throw new IllegalArgumentException("铸币规模不得为负: " + scale);
    }
    Map<CurrencyId, Long> gross = new LinkedHashMap<>();
    for (Map.Entry<CurrencyId, Long> entry : perUnit.entrySet()) {
      long minted = Math.multiplyExact(scale, entry.getValue());
      if (minted > 0L) {
        gross.put(entry.getKey(), minted);
      }
    }
    return Collections.unmodifiableMap(gross);
  }
}

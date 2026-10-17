package io.mosire.simos.production.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import java.util.Optional;

/**
 * ★★ <b>生产地点（显式格键）</b>——D-032 A 批「方案/实例身份分层」的地点一半（复核裁决 §1；设计书 §13.4 A 批第 4 条）。
 *
 * <p>★★ <b>它解决什么</b>：在 A 批之前，实例的"我在哪一格"只能从 {@link IndustryId} 的规范串里<b>反解</b> （{@code
 * IndustryHexKeys.hexKeyOf}）。那是"地址即身份"的老病：方案一旦不再按地点各存一份，实例就没有 自己的地点了。A 批把地点做成实例的<b>显式组件</b>：
 *
 * <pre>
 * 方案（IndustryId）  ——  不绑地点的稳定身份；一份定义可被多个地点的实例引用
 * 实例（ProductionProcess）—— 自己的稳定身份 + 本类型的地点 + 对方案的引用
 * </pre>
 *
 * <p>★★ <b>不强制新增一个特定名称的 ID 类型</b>（复核裁决 §1 末条）：本类型<b>不是</b>新的方案 ID， 而是一个纯地点值；方案身份仍是 {@link
 * IndustryId}（它的构造/解析只校验非空白，见其类注）。
 *
 * <p>★ <b>规范形</b>：值为 {@code <q>_<r>}（与 {@code IndustryHexKeys.hexKey(int,int)} 逐字同形）。 规范拼写 ⇒
 * 同一格只有一种值、{@code equals} 即"同一格"；不在这里做整数校验（那是生成侧/命令层的守 卫，{@code IndustryUpserts}
 * 已有），本类型的职责是"承载并可比对"。
 *
 * <p>★ 裸值 {@code toString()} + {@code static parse} 三件套（铁律 1）；{@code parse} 是 <b>opaque</b>
 * 的：旧档里已落盘的地点原样读回，不重算、不归一。
 *
 * @param value 规范格键；非空白
 */
public record ProductionLocation(String value) {

  public ProductionLocation {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("ProductionLocation 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白，不做格式约束（格式守卫属生成侧/命令层）。 */
  public static ProductionLocation parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("ProductionLocation 不得为空白: " + text);
    }
    return new ProductionLocation(text);
  }

  /**
   * ★ <b>方案携带的地点（若有）</b>：{@code IndustryId} 的规范串里带 {@code <kind>@<q>_<r>} 形状时取其 {@code <q>_<r>}
   * 段；不带（A 批起合法的"不绑地点方案"）⇒ {@link Optional#empty()}。
   *
   * <p>★ <b>用途仅限"把既有生成约定读成地点"</b>（播种/命令解析侧的机械取用）；实例的权威地点是 {@code
   * ProductionProcess.location()}，不是每次现解一次串。
   */
  public static Optional<ProductionLocation> ofPlan(IndustryId plan) {
    if (plan == null) {
      return Optional.empty();
    }
    return IndustryHexKeys.hexKeyOf(plan).map(ProductionLocation::new);
  }

  /**
   * ★★ <b>「地点没落定」的具名占位</b>（A 批施工期桥；★ B3 已收口为<b>只读防御</b>）。
   *
   * <p>★★ <b>B3 收口结论</b>：main 里**已无任何构造点**（{@code ofPlanOrUnspecified} 已删除；三个 economy 构点与
   * production 的播种段各自 fail-closed）。它留给读侧做**具名防御**：{@code parse} 只校验非空白 ⇒ {@code location:"?"}
   * 这类坏载荷仍可构造出它，而任何"地点有语义"的读点（跨表引用守卫 / 清区归属 / 创建守卫）读到它一律具名拒。
   *
   * <p>★★ <b>为什么当初需要它</b>：{@code ProductionProcess.location} 是<b>必需</b>组件（构造期拒 null）—— 这是对的（缺地点的实例
   * = 状态自相矛盾）。但既有 7 处 economy 构点写的是 {@code ProductionLocation.ofPlan(planId).orElse(null)}：A
   * 批起"不绑地点的方案"合法 ⇒ 那一句会构造出 {@code location=null} ⇒ <b>运行期 IAE</b>（不是编译错，是最坏的一类）。
   *
   * <p>★ <b>本占位做什么、不做什么</b>：
   *
   * <ul>
   *   <li>做：让"方案串解不出地点"这条路径<b>显式可读</b>（{@link #UNSPECIFIED} 是具名常量，读数/日志一眼 能看出"这个实例的地点没有落定"），而不是把
   *       {@code null} 塞进必需组件；
   *   <li><b>不做</b>：它<b>不是</b>一个真实格子，<b>不</b>把两格混成同一格（它的值 {@code "?"} 不是任何
   *       合法格键）、<b>不</b>参与市场/关系/资产键解析。按地点查表的调用方遇到它必须 fail-closed （{@link #isSpecified()} == false）。
   * </ul>
   *
   * <p>★★ <b>为什么它是"施工期桥"而不是最终形态</b>（复核裁决 §4 原文：**不准**用"默认空地点"来机械修
   * 编译）：真正的收口是<b>实例创建时就必须拿到真实地点</b>（由创建请求显式给出，或由方案/命令解析给出）， 那条收口属 B 批（economy 写路径改经 {@code
   * ProductionCreationRequest}）。本常量只是让 A 批的 7 处旧构点<b>不产生 null</b>，且把"地点没落定"做成可检测的显式状态而不是静默降级。
   */
  public static final ProductionLocation UNSPECIFIED = new ProductionLocation("?");

  /**
   * ★ 本地点是否是"没有落定"的占位（{@link #UNSPECIFIED}）——按地点查表的调用方必须据此 fail-closed。
   *
   * <p>★★ <b>线格式契约（B6，2026-10-17）</b>：这是<b>派生判据</b>、不是 record 组件 ⇒ 必须 {@link JsonIgnore}。Jackson 的
   * bean 内省把 {@code isSpecified()} 当成属性 {@code "specified"} <b>写进字节</b>（{@code
   * {"value":"3_7","specified":true}}），而全仓读侧保持默认的严格（{@code FAIL_ON_UNKNOWN_PROPERTIES}，见 {@code
   * SimosObjectMapper}）⇒ <b>含实例的快照/变更集自己写出来的字节读不回</b>（V10 实测：{@code Unrecognized field "specified"
   * (class … ProductionLocation), not marked as ignorable (one known property: "value")}，现场 {@code
   * ProductionData["instances"] → ProductionProcess["location"]}）。
   *
   * <p>★ <b>为什么必须落在类型上、不能只靠 codec 的 mixin</b>（与 {@code MoneyOutput.isEmpty()}、{@code
   * MarketTable.isEmpty()}、{@code EconomyMeta.isCurrentRuntimeVersion()} 逐字同一条理由）：重放走 Core 的第四台
   * mapper（{@code Timeline}）与真实 Shell 的 {@code SimosObjectMapper.create()} 裸 mapper，两者都<b>看不见模块私有
   * mixin</b> ⇒ 不摘掉本属性，任何改过 {@code instances} 的 revision 都会在重放时因未知键 {@code specified} fail-closed。
   *
   * <p>★ <b>它不是"把严格关掉"</b>：{@code @JsonIgnore} <b>只具名</b>摘掉这一个派生属性，其余未知字段照旧 fail-closed
   * （漂移信号不丢）。{@code value} 仍是唯一的状态组件、逐字进出线格式。
   */
  @JsonIgnore
  public boolean isSpecified() {
    return !"?".equals(value);
  }

  // ★★★ D-032 B3（§13.8 裁定二第 3/4 条）收口：`ofPlanOrUnspecified(plan)`（解不出地点就返回占位）**已删除**。
  //   它是 A 批的施工期桥；B3 起"实例必须有真实地点"是**每个构造点各自 fail-closed** 的判据，不再有
  //   "把地点没落定写成占位"的工厂方法：
  //     · 载入/命令边界（播种载荷、economy 载荷）⇒ 具名业务拒绝 `location-unknown`（IllegalArgumentException
  //       由命令层折 Rejected + INFO）；
  //     · 跨切片结算路径（economy 建实例/迁移搬迁）⇒ 契约故障 ERROR 不降级（IllegalStateException）。
  //   ★ 逐点处置表见账本 B3-§8。`UNSPECIFIED` 常量与 `isSpecified()` **保留**为具名防御：`parse` 只校验非空白，
  //     `"?"` 这类坏载荷仍可能构造出占位，读到它的语义边界一律具名拒（EconomyReferenceValidation /
  //     ClearRegionDefinitionsHandler / CreateInstanceHandler）。
}

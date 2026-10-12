package io.mosire.simos.util.spi;

/**
 * 决策人 ID（调用方给的短名，spec §二.1）。★ <b>不可用 {@code agent:}</b>（N15：实测该命名空间无 resolver）——决策人走 {@code
 * sd:decision-maker.<id>}（地址族的裁量属批次 3e）。<b>归属是默认权限边界，不是硬约束</b>（spec §二.3）。 裸值 {@code toString()} +
 * {@code static parse} 三件套（铁律 1）；不自增、不用随机 UUID。
 *
 * <p>★★ <b>2026-10-12 批次 3c：本类型自 {@code io.mosire.simos.sd.id} 整体迁入 {@code
 * util.spi}</b>（值语义一字未改）。依据两条：
 *
 * <ol>
 *   <li><b>ADR-1 原文</b>（{@code AGENTS.md}）：「命令跨模块边界是"不透明载荷"……<b>新增契约一律放 {@code
 *       io.mosire.simos.util.spi}</b>，既有契约原地不动」——本类型是随 3c 新出现的跨模块契约；
 *   <li><b>现有的同款先例</b>：{@link DmUsableCommand} 当初也是因为"九个域都要用、util 是唯一下层"而落这里（见其类注）。
 * </ol>
 *
 * <p>★★ <b>为什么必须上移到 util（而不是留在 sd、也不是放 gov）</b>：本批把 <b>nation 归属</b>交给 {@code simos-gov} 裁定，那张表的
 * 键就是本类型； 而 {@code simos-gov} 的 enforcer <b>禁 {@code simos-sd}</b>（gov/sd 互禁）⇒ 留在 sd 则 gov 拿不到它。 同时
 * {@code simos-ga}（决策人表）、{@code simos-app}（权限分派）与 {@code simos-army}（3d 的军队归属）也都要用它。 {@code util}
 * 是这四个模块<b>全部显式声明</b>的唯一下层 ⇒ <b>零 pom 改动、零环</b>（与 {@code DmUsableCommand} 的裁定同一条推理）。
 *
 * <p>★ <b>它不是"新 id 族"</b>：地址族（{@code sd:decision-maker.<id>}）与持久形态（决策人表仍在 {@code ga}）都<b>一字未改</b>——
 * 变的只是这个 Java 类型的家。地址族本身的裁量属批次 3e（sd 空壳退役），不在本批。
 */
public record DecisionMakerId(String value) {

  public DecisionMakerId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("DecisionMakerId 不得为空白");
    }
  }

  /** 裸值（变更集 key 与 JSON 边界用；不是给人看的调试输出）。 */
  @Override
  public String toString() {
    return value;
  }

  /** 解析 {@link #toString()} 的产物；空白与 {@code null} 抛（与构造器同口径：宁抛不静默）。 */
  public static DecisionMakerId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("DecisionMakerId 不得为空白: " + text);
    }
    return new DecisionMakerId(text);
  }
}

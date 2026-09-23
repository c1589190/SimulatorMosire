package io.mosire.simos.social.gen;

/**
 * {@link NationSetup} 按 seed 落成的**具体参数**：可直接喂给 {@link
 * SettlementGenerator#generate(SettlementRequest, TerrainView, SettlementParams)}。
 *
 * <p>★ 出生即是"自洽的一组"：{@code request} 里的标量都已按同一 seed 定好点（抖动 + 夹紧后），{@code params} 是国家共用的生成器系数。
 *
 * <p>★ 本类型不加任何 {@code isXxx()} 实例方法（见 {@link PlannedCity} 类注释）。
 *
 * @param request 该国的生成请求（含按 seed 定点的标量、首都锚点、文档地名、格集）
 * @param params 生成器系数（来自配置 {@code defaults}，本笔不随机化）
 */
public record ResolvedNation(SettlementRequest request, SettlementParams params) {

  public ResolvedNation {
    if (request == null) {
      throw new IllegalArgumentException("request 不得为 null");
    }
    if (params == null) {
      throw new IllegalArgumentException("params 不得为 null");
    }
  }
}

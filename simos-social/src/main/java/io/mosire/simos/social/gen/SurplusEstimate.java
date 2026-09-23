package io.mosire.simos.social.gen;

/**
 * 单格的土地剩余估算（{@link SettlementPlan#audit()} 的值类型）。**中间量，别丢** —— 它是"为什么这座城在这里、为什么这么大"的唯一可读 证据，P3 落盘时进
 * {@code SocialCity.props} 的是城市级量，本类型则留在内存里供审计/回归对拍。
 *
 * <p>三个原始量的口径（单位都是"人"或无量纲指数，见 {@link SettlementGenerator} 的类注释）：
 *
 * <ul>
 *   <li>{@code ruralCapacity}：**该格自身维持口径** —— 落在该格上的农村人口（{@code Σ ruralCapacity == ruralTotal}）；
 *   <li>{@code surplusPotential}：可输出给非农人口的剩余 = {@code ruralCapacity × 地形剩余率 × agrarianSurplusRate ×
 *       河流乘数 × 沿海乘数}；
 *   <li>{@code transport}：交通优势启发式（河口/沿河/沿海/山口/平原/深山/贫瘠，{@link SettlementParams.TransportBonuses}
 *       叠乘）；
 *   <li>{@code candidateScore}：{@code surplus / transport / historical} 三项**各自归一到 [0,1] 后**的加权和 ——
 *       见 {@link SettlementGenerator} 关于"不归一会被量纲淹没"的说明。
 * </ul>
 *
 * @param ruralCapacity 该格的农村人口（自身维持口径）；&ge; 0
 * @param surplusPotential 可输出的农业剩余；&ge; 0
 * @param transport 交通优势（叠乘后的指数）；&gt; 0
 * @param candidateScore 建城候选分，**已归一到 [0,1]**（三项加权和为 1）
 */
public record SurplusEstimate(
    double ruralCapacity, double surplusPotential, double transport, double candidateScore) {

  public SurplusEstimate {
    if (!Double.isFinite(ruralCapacity) || ruralCapacity < 0) {
      throw new IllegalArgumentException("ruralCapacity 必须是有限非负数: " + ruralCapacity);
    }
    if (!Double.isFinite(surplusPotential) || surplusPotential < 0) {
      throw new IllegalArgumentException("surplusPotential 必须是有限非负数: " + surplusPotential);
    }
    if (!Double.isFinite(transport) || transport <= 0) {
      throw new IllegalArgumentException("transport 必须是有限正数: " + transport);
    }
    if (!Double.isFinite(candidateScore) || candidateScore < 0 || candidateScore > 1) {
      throw new IllegalArgumentException("candidateScore 必须落在 [0,1]: " + candidateScore);
    }
  }
}

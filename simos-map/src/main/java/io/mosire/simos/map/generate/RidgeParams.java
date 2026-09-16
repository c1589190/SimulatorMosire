package io.mosire.simos.map.generate;

/**
 * 脊线的**形状参数**：主脊线与次级脊线各自的长度/偏移/抖动/权重区间，以及"脊线 → 海拔"的衰减与谷地参数。
 *
 * <p>★ 全部字段来自 GSimulator 的 {@code MapGenerator.placeRidges}（`:58-117`，脊线的摆放） 与 {@code
 * ContourQueryEngine} 的 {@code computeRidgeHeight} / {@code computeValleyPenalty}
 * （`:181-205`，脊线如何变成海拔）。**本任务没有新造任何数值** —— 每一条都能指到上面两处的行号。
 *
 * <p>★ **区间的写法是 {@code Min} + {@code Span}**，不是 {@code Min}/{@code Max}：GSimulator 的原式一律是 {@code
 * Min + rng.nextDouble() * Span}，两种写法在浮点上**不等价**（{@code max - min} 再相加会引入一次
 * 舍入），照抄原式才谈得上"同一份参数、同一张图"。故这里存的是原式的两个加数。
 *
 * <p>★ **U1**：本类型不含任何"海拔多高算山"的分界 —— {@code heightWeight}/{@code decayBase} 这些是
 * "脊线距离如何衰减成海拔值"的**连续**系数，它们塑造海拔场，不对海拔做任何**分类**； 本类型也没有一个 String 组件。地形名与高度带的唯一持有者是 {@link
 * io.mosire.simos.map.terrain.TerrainCatalog}。
 *
 * <p>★ **次级脊线的"条数"不在本类型**：{@code Math.max(2, fragmentCount / 2)}（`MapGenerator.java:85`）
 * 是**碎片预算怎么切**的规则，归 {@link FragmentParams}（那是 {@code frags = fragmentCount - secondary}
 * 那一行能算出负数的成因）。本类型只管次级脊线的**形状**。
 *
 * <p>★ 构造期**不设守卫**：本类型的字段是长度/权重/抖动这类开放区间的形状量，取负只是"另一个分布"， 不会静默失效（对比 {@link GenerationSpec} 与 {@link
 * NoiseBands} 里那些"越界就整层地貌消失"的守卫）。
 *
 * @param mainCompanionAngleMin 第二条主脊线相对主方向的偏角下界（`MapGenerator.java:63` 的 {@code + 0.3}）
 * @param mainCompanionAngleSpan 偏角的随机跨度（`:63` 的 {@code rng.nextDouble() * 0.5}）
 * @param mainLengthMin 主脊线长度下界（`:64` 的 {@code radius * (1.3 + …)}）
 * @param mainLengthSpan 主脊线长度的随机跨度（`:64` 的 {@code rng.nextDouble() * 0.4}）
 * @param mainOffsetMin 主脊线垂直偏移下界（`:67` 的 {@code radius * (0.20 + …)}）
 * @param mainOffsetSpan 偏移的随机跨度（`:67` 的 {@code rng.nextDouble() * 0.30}）
 * @param mainStartJitter 起点的高斯抖动（`:68-69` 的 {@code rng.nextGaussian() * radius * 0.03}）
 * @param mainCurveSpan 贝塞尔控制点偏移的随机跨度（`:71` 的 {@code rng.nextDouble() * radius * 0.18}）
 * @param mainTailLength 尾段占全长的比例（`:75-76` 的 {@code len * 0.48}）
 * @param mainTailJitter 尾端的高斯抖动（`:75-76` 的 {@code rng.nextGaussian() * radius * 0.02}）
 * @param mainHeadLength 头段占全长的比例（`:79-80` 的 {@code len * 0.52}）
 * @param mainHeadJitter 头端的高斯抖动（`:79-80` 的 {@code rng.nextGaussian() * radius * 0.03}）
 * @param mainWeightMin 主脊线权重下界（`:81` 的 {@code 0.75 + …}）
 * @param mainWeightSpan 主脊线权重的随机跨度（`:81` 的 {@code rng.nextDouble() * 0.25}）
 * @param secondaryAngleMin 次级脊线偏角的下界（`:87` 的 {@code + 0.12}）
 * @param secondaryAngleSpan 次级偏角的随机跨度（`:87` 的 {@code rng.nextDouble() * 0.4}）
 * @param secondaryOffsetMin 次级脊线垂直距离下界（`:88` 的 {@code radius * (0.10 + …)}）
 * @param secondaryOffsetSpan 次级垂直距离的随机跨度（`:88` 的 {@code rng.nextDouble() * 0.28}）
 * @param secondaryLengthMin 次级脊线长度下界（`:89` 的 {@code radius * (0.50 + …)}）
 * @param secondaryLengthSpan 次级长度的随机跨度（`:89` 的 {@code rng.nextDouble() * 0.45}）
 * @param secondaryAlongMin 沿主方向的起点下界（`:90`、`:92` 的 {@code radius * (0.05 + …)}）
 * @param secondaryAlongSpan 沿主方向起点的随机跨度（`:90`、`:92` 的 {@code rng.nextDouble() * 0.22}）
 * @param secondaryTailLength 次级尾段占全长的比例（`:96-97` 的 {@code len * 0.5}）
 * @param secondaryHeadLength 次级头段占全长的比例（`:99-100` 的 {@code len * 0.5}）
 * @param secondaryJitter 次级两端的高斯抖动（`:96-100` 的 {@code rng.nextGaussian() * radius * 0.02}）
 * @param secondaryWeightMin 次级脊线权重下界（`:101` 的 {@code 0.25 + …}）
 * @param secondaryWeightSpan 次级权重的随机跨度（`:101` 的 {@code rng.nextDouble() * 0.30}）
 * @param heightWeight 脊线高度对海拔的贡献权重（`ContourQueryEngine.java:159` 的 {@code ridgeH * 0.68}）
 * @param decayBase 脊线距离衰减的基数（`ContourQueryEngine.java:185` 的 {@code k = 5.5 + …}）
 * @param decayPerWeight 权重对衰减的加成（`ContourQueryEngine.java:185` 的 {@code … * 2.0}）
 * @param valleyMinRidges 谷地惩罚生效所需的最少脊线数（`ContourQueryEngine.java:193` 的 {@code size() < 2}）
 * @param valleySigma 谷地高斯核的宽度，占半径的比例（`ContourQueryEngine.java:203` 的 {@code radius * 0.10}）
 * @param valleyWeight 谷地惩罚的最大削减量（`ContourQueryEngine.java:204` 的 {@code * 0.30}）
 */
public record RidgeParams(
    double mainCompanionAngleMin,
    double mainCompanionAngleSpan,
    double mainLengthMin,
    double mainLengthSpan,
    double mainOffsetMin,
    double mainOffsetSpan,
    double mainStartJitter,
    double mainCurveSpan,
    double mainTailLength,
    double mainTailJitter,
    double mainHeadLength,
    double mainHeadJitter,
    double mainWeightMin,
    double mainWeightSpan,
    double secondaryAngleMin,
    double secondaryAngleSpan,
    double secondaryOffsetMin,
    double secondaryOffsetSpan,
    double secondaryLengthMin,
    double secondaryLengthSpan,
    double secondaryAlongMin,
    double secondaryAlongSpan,
    double secondaryTailLength,
    double secondaryHeadLength,
    double secondaryJitter,
    double secondaryWeightMin,
    double secondaryWeightSpan,
    double heightWeight,
    double decayBase,
    double decayPerWeight,
    int valleyMinRidges,
    double valleySigma,
    double valleyWeight) {}

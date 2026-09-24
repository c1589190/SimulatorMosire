package io.mosire.simos.app.tools;

/**
 * ★★ **"只给 GM 桶"的读工具标记**（2026-09-24，工具面 M4）。
 *
 * <p>由来：读工具**默认四桶共享**（{@code SimosToolSource} 先装读工具、再按 {@code Role} 追加写工具）， 而侦察报告 {@code
 * .superpowers/sdd/2026-09-22-tool-surface/m4-inventory.md} 逐条核过：有几条读口照抄 GUI 会**越过决策人的可见范围**（如
 * {@code map.path} 是**地形探测**、{@code sd.decision-makers} 是**别人的底牌**）。
 * 当时无处可写这个归属——它由结构默认给的，不是"写出来"的（creed 五要求的正是"写出来"）。
 *
 * <p>实现它的工具类即声明：**只在 {@link io.mosire.simos.app.tools.SimosToolSource.Role#GM} 桶里出现**， 决策人桶剔除。★
 * 它**不**改变权限/资源判定，只改"这张脸给谁看"。
 */
public interface GmOnlyRead {}

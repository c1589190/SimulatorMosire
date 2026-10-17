package io.mosire.simos.ga.spi;

/**
 * ★★ **"只给 GM 桶"的读工具标记**（2026-09-24，工具面 M4；2026-10-12 B1 自 {@code
 * io.mosire.simos.app.tools.GmOnlyRead} **原样迁入**本契约层）。
 *
 * <p>由来：读工具**默认四桶共享**（{@code SimosToolSource} 先装读工具、再按 {@code Role} 追加写工具）， 而侦察报告 {@code
 * .superpowers/sdd/2026-09-22-tool-surface/m4-inventory.md} 逐条核过：有几条读口照抄 GUI 会**越过决策人的可见范围**（如
 * {@code map.path} 是**地形探测**、{@code sd.decision-makers} 是**别人的底牌**）。
 * 当时无处可写这个归属——它由结构默认给的，不是"写出来"的（creed 五要求的正是"写出来"）。
 *
 * <p>实现它的工具类即声明：**只在 GM 桶里出现**，决策人桶剔除。★ 它**不**改变权限/资源判定， 只改"这张脸给谁看"。
 *
 * <p>★ <b>B1 的迁移口径</b>：本接口是 {@code app/tools/GmOnlyRead.java} 的**契约层副本**， 旧文件**保留不动**（本批不做全仓替换——那是
 * B4 的收口；两处并存是迁移中间态，语义逐字相同）。 ★ 收口方向：B4 起"只给 GM 看"由 {@link SimosTool#toolRoles()} 表达（{@code {GM}}），
 * 本标记随旧文件一并退场；在那之前它仍是 app 侧桶装配的依据。
 */
public interface GmOnlyRead {}

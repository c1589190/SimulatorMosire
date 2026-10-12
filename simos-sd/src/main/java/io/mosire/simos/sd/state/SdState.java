package io.mosire.simos.sd.state;

/**
 * sd 模块的完整状态树（spec §三.1，铁律 5 的"完整状态类型"）——★★ <b>2026-10-12 批次 3b-4 起是 <b>0 组件</b> 的空树</b>。
 *
 * <p>★ **树与切片分工照 map 的 {@code GameMap}（树）/ {@code MapSnapshot}（切片）**：本类是树，落盘切片是 {@link
 * SdSnapshot}。spec §三.1 把 {@code implements Snapshot} 写在了状态树头（设计形状的笔误），执行期按"树 / 切片分离"落地 ——
 * 记入台账取代说明。
 *
 * <p>★★ <b>本树的收缩史（七次，逐批如实记）</b>：14 →（批次 3a）12 —— {@code decisionPackets} / {@code
 * mergedEffectPlans} 整表搬进 {@code simos-ga}；12 →（批次 3b-1）10 —— {@code directives} / {@code verdicts}
 * 同样搬走；10 →（批次 3b-2）8 —— {@code decisionMakers} 与 {@code info} 整表搬进 {@code GaState}；8 →（3c）5 ——
 * 外交三表（{@code nations} / {@code diplomaticRelations} / {@code diplomaticEvents}）连同四个 handler 搬进
 * {@code simos-gov}（用户裁定「<b>外交是 gov 的事</b>」＋「<b>NationId 落点 → GOV 模块</b>」）；5 →（3d-1）4 —— {@code
 * armies}（<b>军队身份</b>）搬进 {@code simos-army}；4 →（3d-2）1 —— 旧战斗族三张表<b>整族退役</b>；<b>1 →（本批 3b-4）0</b>
 * —— 最后一个组件 {@code effects}（决策层的「未来／条件动作队列」）<b>连同整族值类型与三条生产路径搬进 {@code simos-ga}</b> （用户裁定「<b>sd
 * 整个弃用！</b>」＋「<b>那让它依赖unit呗</b>」，规划 §15 / §18.2 第 3 步）。{@code GaState} 因此由六组件收成 <b>七组件</b>，sd 原 14
 * 组件里的决策侧 7 件<b>全部</b>归 ga。
 *
 * <p>★★ <b>为什么保留这个 0 组件的类型（而不是连它一起删）—— 由实现方判断，理由三条</b>：
 *
 * <ol>
 *   <li><b>批次 3e 才退役整个模块</b>（根 pom −1 模块、{@code ArchitectureGuardsTest.MODULES} −1、地址族与命令串清零）：
 *       本批的红线是"2 条改名不改数 + effect 的求值/触发/落地语义逐字不变"，删模块是<b>另一次</b>结构变动（要动根 pom、 要动 app 的四个创世世界与全部 sd
 *       切片夹具）；
 *   <li><b>它今天仍是一个合法的模块切片</b>：{@code SdCodec} 仍注册在 {@code Shell}（{@code namespace() = "sd"}），
 *       {@code SdSnapshot} 仍在四个创世世界里被构造 ⇒ 删掉类型就要同步删 {@code SdCodec}/{@code SdSnapshot} 与那几条装配，
 *       牵动面远大于本责任区；
 *   <li><b>空切片是"模块还在、但不再持数据"的诚实表达</b>：它与"静默留着旧表"相反——0 组件是<b>编译期可验证</b>的 （{@code SdChangeSet}/{@code
 *       SdCodec} 同样收敛到 0 组件、0 键），谁再往里加组件都会立刻撞上 {@code SdRoundTripTest} 的反射枚举（收尾期由测试代理迁移）。
 * </ol>
 *
 * <p>★ <b>{@code effects} 的值类型去了哪（如实记）</b>：{@code Effect} / {@code EffectKind} / {@code
 * EffectStatus} / {@code Trigger} / {@code Action} <b>整族搬进 {@code io.mosire.simos.ga.effect}</b>；★
 * {@code EffectId} <b>仍住本模块</b>（{@code io.mosire.simos.sd.id.EffectId}）——{@code
 * Directive.effects()}（值类型仍住 sd）以它为字段类型， 搬它就会造出 {@code sd → ga} 的环（enforcer 当场拒）⇒ 由 ga 反向引用（既有的
 * {@code ga → sd} 方向，不新增边）。
 *
 * <p>★ <b>仍有读 ga 三张表的地方</b>（{@code SdResolver} 的 {@code directive} / {@code decision-maker}
 * 两支存在性）：走注入的 {@link io.mosire.simos.sd.spi.DecisionTablesLookup}（实现在 ga、组合根接线），见该接口的类注。
 */
public record SdState() {

  /** 往返用例与 handlers 的起点（本批起恒等：本树没有组件）。 */
  public static SdState empty() {
    return new SdState();
  }
}

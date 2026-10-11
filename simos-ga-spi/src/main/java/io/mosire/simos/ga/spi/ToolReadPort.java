package io.mosire.simos.ga.spi;

import io.mosire.simos.util.facet.FacetEntry;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;

/**
 * 工具面的**窄读口**：替代工具类直连宿主（今天 98 个工具用 app 的 {@code QueryService}，实际只用到 3 个方法面—— {@code stateAt} 76 次 /
 * {@code resolve} 2 次 / {@code facets} 2 次）。
 *
 * <p>★ <b>为什么必须有这一层</b>：工具下放到领域模块后（设计书 §4.3），领域模块**不许**依赖 {@code app}/{@code core} （{@code
 * simos-map} 的 enforcer 会当场拒），而"取某个坐标的状态"这件事天然是宿主能力 ⇒ 由宿主**构造期注入**一个窄口。
 *
 * <p>★ <b>坐标用值参，不用宿主的 {@code QueryTarget}</b>：{@code QueryTarget} 是 app 的类型，进不了契约层（I-G2 的同一理由）。
 * 端口因此只收 {@code (branch, revision)} 两个 util 类型 —— {@code revision == null} 与载荷里 {@code revision}
 * 缺省同义：**取该分支的 head**。宿主的适配器负责把这组值参还原成它自己的查询坐标（含 head 解析、创世等待、读缓存）。
 *
 * <p>★ <b>只用 util 类型</b>：{@link SimulationState} 与 {@link FacetEntry} 都在 {@code simos-util} ⇒ 本端口与
 * {@link ToolWritePort} 同款，不引 core、不引任何领域模块（I-G2）。
 *
 * <p>★ <b>失败语义不许折平</b>：本端口**不 catch、不兜底**——"分支不存在"、"世界还没就绪"、"坐标不在时间线上"这些
 * 失败由实现原样抛出（今天它们就是异常），调用方按既有口径折成工具结果。把失败折成"空状态/空候选"会把故障静默成 "世界里什么都没有"，属放宽。
 */
public interface ToolReadPort {

  /**
   * 取目标坐标的完整状态。
   *
   * @param branch 分支（调用方从载荷取；缺省分支见 {@link ToolResources#DEFAULT_BRANCH}）
   * @param revision 版本号；{@code null} = 该分支 head
   * @return 该坐标的完整状态（深度不可变；实现可自由缓存，同一坐标两次读必须逐字段相等）
   * @throws IllegalArgumentException 分支不存在（没有 head 可查）等宿主侧失败
   */
  SimulationState stateAt(BranchId branch, RevisionId revision);

  /**
   * 查某个**规范地址**上的跨模块视图（facet）。
   *
   * <p>★ 地址文本由调用方按自己的命名空间拼（地图工具用 {@code map:<mapId>:hex.<q>_<r>} 这类的 Address AST 造），
   * 实现**原样转交**、不改写、不重排（与今天的查询门面同口径）。
   *
   * @param address 规范地址文本
   * @param branch 分支
   * @param revision 版本号；{@code null} = 该分支 head
   * @return 该地址上的视图条目（无候选 ⇒ 空表，不是异常）
   */
  List<FacetEntry> facets(String address, BranchId branch, RevisionId revision);
}

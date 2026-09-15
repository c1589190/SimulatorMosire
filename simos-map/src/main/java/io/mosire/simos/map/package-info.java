/**
 * MapSimos —— 六边形网格地图。
 *
 * <p>地形类型、区域、连通性、地形生成与编辑，以及 {@code map:} 命名空间的地址解析实现。
 *
 * <p><b>硬约束</b>：不做任何存储（序列化/反序列化上收到 CoreSimos）；永远不知道
 * SocialSimos / UnitSimos 存在——跨模块可见性走 Util 的 Facet 扩展查询。
 */
package io.mosire.simos.map;

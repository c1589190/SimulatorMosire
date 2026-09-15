/**
 * UtilSimos —— 零领域依赖的地基。
 *
 * <p>本包只提供原语，不理解任何领域概念：地址与身份的表示与解析、模拟时间与数据版本的正交坐标、 快照与变更集的协议接口、外挂式 Info 属性系统、时态序列，以及供各领域模块注册的
 * Resolver SPI。
 *
 * <p><b>硬约束</b>：不依赖 AgentLibMosire，不依赖任何其它 simos 模块，不碰文件系统。
 */
package io.mosire.simos.util;

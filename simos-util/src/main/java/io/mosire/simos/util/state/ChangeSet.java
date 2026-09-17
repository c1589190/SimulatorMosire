package io.mosire.simos.util.state;

/**
 * 变更集（总纲 §4.5）：字段清单由**各模块从自己的 Snapshot 类型派生**（铁律 5）， Util 只给接口与往返断言工具（{@code
 * io.mosire.simos.util.verify.RoundTripAssertions}）。
 *
 * <p>★ **本接口是标记接口**（M4 / U 裁定，spec §十）：原 `baseRevision()` 已删。**版本戳不属于变更集**—— 它是 {@code Revision}
 * 层的事实，在 M4 由 revision 行的 `parent_revision` 指针承担（C27）： {@code apply}
 * 只能拿父行指向的变更集作用在父行重建出的状态上，**这是结构不变量，比测试断言更强**。 原本守着"变更集必须相对 base"的那条**快照专用往返断言**随之一并删除（R15 钉住它全仓 0
 * 处——扫描不分注释与代码， 本文件的 Javadoc 因此也不写它的名字）。
 *
 * <p>★ **契约面一旦成型即稳定**（U13）：后续要动 = 大版本更新。
 */
public interface ChangeSet {}

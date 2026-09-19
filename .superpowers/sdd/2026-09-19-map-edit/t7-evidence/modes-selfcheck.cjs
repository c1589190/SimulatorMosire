// M8 T7 白名单纯函数自检（T2 未做 ⇒ 不进 Maven 门禁，证据级 node 运行）。
// 每个模式一组断言（spec §三）；另含 fail-closed 与"返回值是快照"的纯度断言。
// 用法: node modes-selfcheck.cjs   （退出码 0=全 PASS，1=有 FAIL）
"use strict";

const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const SRC = path.join(__dirname, "../../../../simos-app/src/main/resources/webui/modes.js");
const sandbox = { window: {} };
vm.createContext(sandbox);
vm.runInContext(fs.readFileSync(SRC, "utf8"), sandbox, { filename: SRC });
const M = sandbox.window.SimosModes;

const failures = [];
function check(name, ok, detail) {
  console.log("CHECK " + name + ": " + (ok ? "PASS" : "FAIL") + (detail ? " " + detail : ""));
  if (!ok) failures.push(name);
}

check("module-loaded", !!M && typeof M.isWriteAllowed === "function");

check(
  "labels-order",
  M.modeIds().join(",") === "view,region,map-edit,region-edit,unit",
  JSON.stringify(M.modeIds())
);
check(
  "labels-text",
  M.modeLabel("view") === "常规" &&
    M.modeLabel("region") === "区域查看" &&
    M.modeLabel("map-edit") === "地图编辑" &&
    M.modeLabel("region-edit") === "区域编辑" &&
    M.modeLabel("unit") === "单位移动编辑",
  JSON.stringify(M.modeIds().map((id) => M.modeLabel(id)))
);

// 组 1：常规（无写）
check(
  "view-no-writes",
  M.allowedWrites("view").length === 0 &&
    M.isWriteAllowed("view", "map.SetTerrain") === false &&
    M.isWriteAllowed("view", "unit.PlanRoute") === false,
  JSON.stringify(M.allowedWrites("view"))
);

// 组 2：区域查看（无写）
check(
  "region-no-writes",
  M.allowedWrites("region").length === 0 &&
    M.isWriteAllowed("region", "map.SetTerrain") === false &&
    M.isWriteAllowed("region", "map.UpdateRegion") === false,
  JSON.stringify(M.allowedWrites("region"))
);

// 组 3：地图编辑（SetTerrain + T11 的 SetEdge/RandomizeRegion + 区域信息编辑的 UpdateRegion）
check(
  "map-edit-writes",
  M.isWriteAllowed("map-edit", "map.SetTerrain") === true &&
    M.isWriteAllowed("map-edit", "map.SetEdge") === true &&
    M.isWriteAllowed("map-edit", "map.RandomizeRegion") === true &&
    M.isWriteAllowed("map-edit", "map.UpdateRegion") === true &&
    M.isWriteAllowed("map-edit", "map.CreateRegion") === false &&
    M.isWriteAllowed("map-edit", "unit.PlanRoute") === false,
  JSON.stringify(M.allowedWrites("map-edit"))
);

// 组 4：区域编辑（三命令）
check(
  "region-edit-writes",
  M.isWriteAllowed("region-edit", "map.CreateRegion") === true &&
    M.isWriteAllowed("region-edit", "map.UpdateRegion") === true &&
    M.isWriteAllowed("region-edit", "map.DeleteRegion") === true &&
    M.isWriteAllowed("region-edit", "map.SetTerrain") === false,
  JSON.stringify(M.allowedWrites("region-edit"))
);

// 组 5：单位移动编辑（spec §三 两条 + 既有编辑器四条，见报告分歧）
check(
  "unit-writes",
  M.isWriteAllowed("unit", "unit.PlanRoute") === true &&
    M.isWriteAllowed("unit", "unit.CancelRoute") === true &&
    M.isWriteAllowed("unit", "map.SetTerrain") === false,
  JSON.stringify(M.allowedWrites("unit"))
);

// fail-closed
check(
  "fail-closed",
  M.isWriteAllowed("nope", "map.SetTerrain") === false &&
    M.isWriteAllowed("view", "") === false &&
    M.isWriteAllowed("view", null) === false &&
    M.isWriteAllowed(undefined, "map.SetTerrain") === false,
  "unknown/empty/null 均拒绝"
);

// 纯度：返回的是快照，改它不影响下一次
const a = M.allowedWrites("map-edit");
a.push("unit.CreateUnit");
check(
  "allowedWrites-returns-copy",
  M.allowedWrites("map-edit").indexOf("unit.CreateUnit") < 0 &&
    M.isWriteAllowed("map-edit", "unit.CreateUnit") === false,
  JSON.stringify(M.allowedWrites("map-edit"))
);

console.log("SELFCHECK RESULT: " + (failures.length === 0 ? "ALL PASS" : failures.length + " FAIL " + failures.join(",")));
process.exit(failures.length === 0 ? 0 : 1);

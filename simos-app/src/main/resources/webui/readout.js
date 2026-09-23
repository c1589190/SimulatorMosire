// readout.js —— 左栏读数的纯函数（M12 第三波：自 panels.js 顶层逐行搬出，函数体一字不动）。
//
// 无状态、无 DOM、无 IO：读数换算（毫 MP / 千分比 → 人话）、区域按 tag 分组与并集统计、
// 移动信息读数。`movementReadout` 只读 window.SimosBlocks.terrainAt（纯函数，blocks.js 已先加载）。
//
// ★ 引入顺序：blocks.js → … → readout.js → panels.js（panels.js 顶层按名取回）。

(function () {
  "use strict";

  var UNTAGGED_LABEL = "未标注";

  var IMPASSABLE_MOVE_COST = 999;

  /** tag 归一化：null / undefined / 纯空白 ⇒ 「未标注」（判据④ / R4）。 */
  function normalizeTag(tag) {
    if (tag === null || tag === undefined) {
      return UNTAGGED_LABEL;
    }
    var text = String(tag).trim();
    return text === "" ? UNTAGGED_LABEL : text;
  }

  /**
   * 纯函数：overview 的 regions → `[{tag, regions:[…]}, …]`。不查 IO、不碰 DOM。
   * 桶按 tag 字典序，`未标注` 恒排最后；桶内区域按 id 排序（输出与输入顺序无关，便于逐值断言）。
   */
  function groupByTag(regions) {
    var buckets = {};
    var order = [];
    (regions || []).forEach(function (region) {
      if (!region || region.id === null || region.id === undefined) {
        return;
      }
      var tag = normalizeTag(region.meta ? region.meta.tag : null);
      if (!Object.prototype.hasOwnProperty.call(buckets, tag)) {
        buckets[tag] = [];
        order.push(tag);
      }
      buckets[tag].push(region);
    });
    order.sort(function (a, b) {
      if (a === UNTAGGED_LABEL) {
        return 1;
      }
      if (b === UNTAGGED_LABEL) {
        return -1;
      }
      return a.localeCompare(b);
    });
    return order.map(function (tag) {
      var list = buckets[tag].slice().sort(function (a, b) {
        return String(a.id).localeCompare(String(b.id));
      });
      return { tag: tag, regions: list };
    });
  }

  /**
   * 纯函数（M8 T9，无 DOM/IO）：左栏"该格的从属区域"读数。
   *   `entries` = `[{id, name, hexCount, hexes}]`（各区域自己的详情；hexes 取不到 ⇒ null/undefined）
   * 返回 `{rows, unionCount, hexCountSum, sharedHexCount, complete}`：
   *   `rows`            —— 每个区域**它自己的** `hexCount`（**不**做任何跨区域合并）；
   *   `unionCount`      —— 合计 = **真并集**（逐 hex 去重，同一格多属只计一次）；
   *   `hexCountSum`     —— 各区域 `hexCount` 之和（**只用来核对"和是否等于并集"**，绝不当作面积/并集显示）；
   *   `sharedHexCount`  —— 被 ≥2 个区域共同拥有的格数；
   *   `complete`        —— 有区域取不到 hex 列表 ⇒ false（这时**不给并集数字**，宁可说"不知道"）。
   * ★★ 裁定 72.1：`hexCountSum` 与 `unionCount` 在重叠时**必然不等**（M8-U1：从属是多对多），
   *    任何把"各区域 hexCount 求和"当并集/总面积的读法都是错的 —— 本函数是这条裁定的落地点。
   */
  function regionMembershipSummary(entries) {
    var rows = [];
    var unionKeys = [];
    var ownersByKey = {};
    var sum = 0;
    var complete = true;
    (entries || []).forEach(function (entry) {
      if (!entry || entry.id === null || entry.id === undefined) {
        return;
      }
      var hexes = Array.isArray(entry.hexes) ? entry.hexes : null;
      if (!hexes) {
        complete = false;
      }
      var count = typeof entry.hexCount === "number" ? entry.hexCount : hexes ? hexes.length : null;
      if (typeof count === "number") {
        sum += count;
      }
      (hexes || []).forEach(function (h) {
        if (!h || h.q === null || h.q === undefined || h.r === null || h.r === undefined) {
          return;
        }
        var key = h.q + "_" + h.r;
        if (!Object.prototype.hasOwnProperty.call(ownersByKey, key)) {
          ownersByKey[key] = 0;
          unionKeys.push(key);
        }
        ownersByKey[key] += 1;
      });
      rows.push({ id: entry.id, name: entry.name || entry.id, hexCount: count });
    });
    var shared = 0;
    unionKeys.forEach(function (key) {
      if (ownersByKey[key] >= 2) {
        shared += 1;
      }
    });
    return {
      rows: rows,
      unionCount: complete ? unionKeys.length : null,
      hexCountSum: sum,
      sharedHexCount: complete ? shared : null,
      complete: complete,
    };
  }

  /** 只读读数里的浮点数去掉二进制尾巴（0.6000000000000001 → 0.6）；整数/文本原样。 */
  function formatValue(value) {
    if (typeof value === "number" && Number.isFinite(value) && !Number.isInteger(value)) {
      return Number(value.toFixed(3));
    }
    return value;
  }

  /**
   * 毫 MP ⇒ 人话（B15）：`4000` 毫 ⇒ `"4 MP"`。
   *
   * <p>★ 移动预算 / 每格成本 / 总成本在领域里都是**毫 MP 定点**（`UnitMoves` 口径），直接印 4000 是
   * **内部单位泄漏** —— 一律走这里换算，**不让用户自己除 1000**。取不到 ⇒ `"—"`（不显示成 0）。
   */
  function millisToMpText(millis) {
    if (millis === null || millis === undefined) {
      return "—";
    }
    if (typeof millis !== "number" || !Number.isFinite(millis)) {
      return String(millis);
    }
    var mp = millis / 1000;
    return (Number.isInteger(mp) ? mp : Number(mp.toFixed(3))) + " MP";
  }

  /** ‰ 定点 ⇒ 人话（B15）：`1000` ⇒ `"1.0×（1000‰）"`（人话在前、原值括注，便于对账）。 */
  function perMilleToRateText(perMille) {
    if (perMille === null || perMille === undefined) {
      return "—";
    }
    if (typeof perMille !== "number" || !Number.isFinite(perMille)) {
      return String(perMille);
    }
    var rate = perMille / 1000;
    return (
      (Number.isInteger(rate) ? rate.toFixed(1) : Number(rate.toFixed(3))) + "×（" + perMille + "‰）"
    );
  }

  /** 装备表渲染成 `键=值；…`（空表显示"（空）"）。 */
  function equipmentText(equipment) {
    if (!equipment) {
      return "（空）";
    }
    var keys = Object.keys(equipment);
    if (!keys.length) {
      return "（空）";
    }
    return keys
      .map(function (key) {
        return key + "=" + equipment[key];
      })
      .join("；");
  }

  function hexLabel(coord) {
    return "q=" + coord.q + ", r=" + coord.r;
  }

  /**
   * 纯函数（不碰 DOM、不查 IO）：单位 + 总览 ⇒ 移动读数。
   *
   * 预算速率 = speedAtDeparture × 1000（毫 MP/tick，与 UnitMoves 的 budget 同式）；
   * 每格成本 = terrainTypes[地形(目标格)].moveCost × mobilityPerMilleAtDeparture（与 TerrainMovementCost 同式）；
   * 预计到达 tick = departedAt.tick + ceil(路线总成本 / 预算速率)——总成本 = 沿途每格成本之和。
   */
  function movementReadout(unit, overview) {
    var m = unit.movement;
    if (!m) {
      return null;
    }
    var path = (m.route && m.route.path) || [];
    var blocks = (overview && overview.blocks) || [];
    var typeByKey = {};
    ((overview && overview.terrainTypes) || []).forEach(function (t) {
      typeByKey[t.key] = t;
    });
    function costOf(coord) {
      if (!coord) {
        return null;
      }
      var terrain =
        window.SimosBlocks && window.SimosBlocks.terrainAt
          ? window.SimosBlocks.terrainAt(blocks, coord.q, coord.r)
          : null;
      var type = typeByKey[terrain];
      if (!type || type.moveCost >= IMPASSABLE_MOVE_COST) {
        return null;
      }
      return type.moveCost * m.mobilityPerMilleAtDeparture;
    }
    var totalCost = 0;
    var computable = true;
    for (var i = 0; i + 1 < path.length; i++) {
      var step = costOf(path[i + 1]);
      if (step === null) {
        computable = false;
        break;
      }
      totalCost += step;
    }
    var rate = m.speedAtDeparture * 1000;
    var etaTick =
      computable && rate > 0 ? m.departedAt.tick + Math.ceil(totalCost / rate) : null;
    return {
      budgetPerTickMillis: rate,
      stepCostMillis: costOf(m.nextHex),
      totalCostMillis: computable ? totalCost : null,
      status: m.status,
      currentHex: m.currentHex,
      nextHex: m.nextHex,
      remainingMillis: m.remainingMillis,
      etaTick: etaTick,
      departedAtTick: m.departedAt.tick,
      speedAtDeparture: m.speedAtDeparture,
      mobilityPerMilleAtDeparture: m.mobilityPerMilleAtDeparture,
      pathLength: path.length,
    };
  }

  /**
   * 编制那节的可见性（纯函数，B8）：**该格真有单位时**才与 hex 详情并列显示。
   *
   * <p>★★ 2026-09-23 用户实测（**判定为真 bug**）：常规模式点一个无单位的 hex，左栏详情写着
   * 「该处单位：无」，正下方却挂着编制树（「编制 · <单位名> <id>」）⇒ **自相矛盾**
   * ——这格没单位，编制从哪来？编制树是**全世界的**单位树（`unitTree.js` 渲染），
   * 与"这一格有什么"无关 ⇒ 只在**确实有单位**时露面。
   *
   * <p>口径：hex + **取到了**格上单位且非空 ⇒ 显示；hex + 格上无单位 ⇒ 隐藏；
   * 选中单位 / 区域 / **什么都没选** ⇒ 显示（那时没有可矛盾的对象，编制树本来就靠它点单位）。
   * ★ "取不到"（请求失败）**不当成"没有"** —— 调用方在失败分支里不碰可见性。
   */
  function unitTreeSectionVisible(selection, unitsHere) {
    if (selection && selection.kind === "hex") {
      return !!(unitsHere && unitsHere.length);
    }
    return true;
  }

  // ★ B9（用户 2026-09-23 实测）：单位**自身**状态的中文标签。与 AFFILIATION_LABELS 同款——
  //   **只做展示映射**、不改值域；未知值**原样透出**（不掩成空、也不假装认识）。
  var UNIT_STATUS_LABELS = {
    MOVING: "行军中",
    RESTING: "休整中",
    ENGAGED: "交战中",
  };

  /** 单位状态 → 人话；非字符串/空串给「—」，词表外的值原样返回（fail-visible）。 */
  function unitStatusText(raw) {
    if (typeof raw !== "string" || raw === "") {
      return "—";
    }
    return UNIT_STATUS_LABELS[raw] || raw;
  }

  window.SimosReadout = {
    UNTAGGED_LABEL: UNTAGGED_LABEL,
    IMPASSABLE_MOVE_COST: IMPASSABLE_MOVE_COST,
    UNIT_STATUS_LABELS: UNIT_STATUS_LABELS,
    normalizeTag: normalizeTag,
    groupByTag: groupByTag,
    regionMembershipSummary: regionMembershipSummary,
    formatValue: formatValue,
    millisToMpText: millisToMpText,
    perMilleToRateText: perMilleToRateText,
    equipmentText: equipmentText,
    hexLabel: hexLabel,
    movementReadout: movementReadout,
    unitTreeSectionVisible: unitTreeSectionVisible,
    unitStatusText: unitStatusText,
  };
})();

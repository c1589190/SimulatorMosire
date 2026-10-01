// panel-right.js —— 右栏区域面板（M7 T6：按 RegionMeta.tag 分组）。（M12 第四波：自 panels.js 整体搬出）
// ★ 无框架、无构建：原生 DOM；所有只读取数经 window.SimosApi 并带 window.SimosApp.target()
//   （面板值随游标的 {branch, revision} 变化）。
// ★ 本文件不含任何写调用（唯一的世界外动作是 `app.setHighlightRegions` / `app.setRegionFocus` 这类选择态写）。
// ★ 右栏 = 区域查看模式（`data-modes="region"` 控制可见性）；点区域 ⇒ `setHighlightRegions([id])` 只高亮该区
//   + 出详情；点标签 ⇒ 该标签下全部区域一起高亮。
// ★ 模块级状态（rightToken / rightKey / rightRegions / selectedRegion）随三个渲染函数一起搬来 ⇒ **独占**，
//   只被本文件的 renderRight / drawRight / regionDetail 读写。
// ★ 加载顺序：paneldom.js → panel-right.js → panels.js（本文件只依赖 paneldom + app/api/readout）。

(function () {
  "use strict";

  var app = window.SimosApp;
  var api = window.SimosApi;
  var worldModel = window.SimosWorldModel || null;
  var groupByTag = window.SimosReadout.groupByTag;
  var loadOverview = window.SimosPanelDom.loadOverview;
  var targetLabel = window.SimosPanelDom.targetLabel;
  var appendRow = window.SimosPanelDom.appendRow;

  // ── 右栏区域分组（M7 T6）────────────────────────────────────────────
  var rightToken = 0;
  var rightKey = null;
  var rightRegions = null;
  var selectedRegion = null;

  function regionDetail(container) {
    var dl = app.el("dl", { class: "kv region-detail", id: "region-detail" });
    if (!selectedRegion) {
      appendRow(dl, "提示", "点区域看详情；点标签高亮该标签下全部区域。");
      container.appendChild(dl);
      return;
    }
    var meta = selectedRegion.meta || {};
    appendRow(dl, "name", selectedRegion.name || selectedRegion.id);
    appendRow(dl, "id", selectedRegion.id);
    appendRow(dl, "hexCount", selectedRegion.hexCount);
    appendRow(dl, "color", meta.color);
    appendRow(dl, "tag", meta.tag);
    appendRow(dl, "description", meta.description);
    appendRow(dl, "annexedBy", meta.annexedBy);
    container.appendChild(dl);
  }

  /** 用缓存的 regions + 当前 state 重画右栏（分组、高亮选中态、详情）。 */
  function drawRight(state) {
    var mount = app.byId("region-panel-mount");
    if (!mount) {
      return;
    }
    app.clear(mount);
    var groups = groupByTag(rightRegions || []);
    if (!groups.length) {
      mount.appendChild(app.el("p", { class: "empty", text: "该快照无区域。" }));
      return;
    }
    var highlighted = state.highlightRegions || [];
    var highlightSet = {};
    highlighted.forEach(function (id) {
      highlightSet[id] = true;
    });
    // ★ M8 T10：区域编辑模式下"当前目标区域"（focus）也算选中态（它由 map.js 渲染正常色/其它淡色）。
    var regionFocus = state.regionFocus || null;
    groups.forEach(function (group) {
      var ids = group.regions.map(function (region) {
        return region.id;
      });
      var allActive =
        ids.length > 0 &&
        highlighted.length === ids.length &&
        ids.every(function (id) {
          return highlightSet[id];
        });
      var tagButton = app.el("button", {
        type: "button",
        class: "region-tag" + (allActive ? " active" : ""),
        "data-tag": group.tag,
        title: "高亮「" + group.tag + "」下全部 " + ids.length + " 个区域",
      });
      tagButton.appendChild(app.el("span", { class: "region-tag-name", text: group.tag }));
      tagButton.appendChild(app.el("span", { class: "region-tag-count", text: String(ids.length) }));
      tagButton.addEventListener("click", function () {
        selectedRegion = null;
        // ★ U3：点 tag ⇒ 该 tag 下所有区域**等亮度**（group）。
        app.setHighlightRegions(ids.slice(), "group");
      });
      mount.appendChild(tagButton);

      var list = app.el("div", { class: "region-list" });
      group.regions.forEach(function (region) {
        var isSelected = !!highlightSet[region.id] || region.id === regionFocus;
        var item = app.el("button", {
          type: "button",
          class: "region-item" + (isSelected ? " selected" : ""),
          "data-region-id": region.id,
        });
        item.appendChild(
          app.el("span", { class: "region-name", text: app.text(region.name || region.id) })
        );
        item.appendChild(
          app.el("span", { class: "region-hexcount", text: app.text(region.hexCount) + " 格" })
        );
        item.addEventListener("click", function () {
          selectedRegion = region;
          if (app.getState().mode === "region-edit") {
            app.setRegionFocus(region.id);
          } else {
            // ★ U3：点单个区域 ⇒ 该区域更亮、同 tag 其他区域淡色（single）。
            app.setHighlightRegions([region.id], "single");
          }
        });
        list.appendChild(item);
      });
      mount.appendChild(list);
    });
    regionDetail(mount);
  }

  // ── F1：世界总览（view / region 模式；无选中时显示，有选中让位给详情）────────────────
  //
  // ★ 数据来自三个 target 缓存端点（城市 / 区域汇总 / 经济总览）+ cachedUnits + cachedDecisionMakers；
  //   map.js 取数后会经 setWorldData 推同一份数据（重复调用走缓存，不发第二次请求）。
  // ★ 缺数据如实显示"—"，不拿 0 冒充；"待批"取不到时显示"未接入"。
  var worldToken = 0;
  var worldKey = null;
  var worldData = null;
  var pendingCount = null;
  var pendingState = "idle"; // idle | loading | loaded | failed
  var pendingToken = 0;

  function pendingCountOf(body) {
    if (Array.isArray(body)) {
      return body.length;
    }
    if (body && Array.isArray(body.pending)) {
      return body.pending.length;
    }
    if (body && Array.isArray(body.items)) {
      return body.items.length;
    }
    return null;
  }

  function sumNumbers(list, pick) {
    var total = 0;
    var known = false;
    (list || []).forEach(function (item) {
      var value = pick(item);
      if (typeof value === "number" && isFinite(value)) {
        total += value;
        known = true;
      }
    });
    return known ? total : null;
  }

  /** map.js 取数后推来的世界数据（同一 target）；右栏不因此再发请求。 */
  function setWorldData(data) {
    worldKey = targetLabel();
    // 每次推新世界数据（含 revision 变化）都重取一次待批数：审批会随写命令变化，"缓存一份旧计数"会撒谎。
    pendingState = "idle";
    pendingCount = null;
    worldData = {
      cities: (data && data.cities) || [],
      regionSummaries: (data && data.regionSummaries) || [],
      economyOverview: data ? data.economyOverview : null,
      units: (data && data.units) || [],
      decisionMakers: (data && data.decisionMakers) || [],
    };
    renderWorldOverview(app.getState());
  }

  function ensureWorldData(state) {
    var mount = app.byId("world-overview-mount");
    if (!mount) {
      return;
    }
    var key = targetLabel();
    if (key === worldKey && worldData) {
      renderWorldOverview(state);
      return;
    }
    worldKey = key;
    pendingState = "idle";
    pendingCount = null;
    var token = ++worldToken;
    Promise.all([
      api.cachedCities(app.target()).catch(function () {
        return null;
      }),
      api.cachedRegionSummaries(app.target()).catch(function () {
        return null;
      }),
      api.cachedEconomyOverview(app.target()).catch(function () {
        return null;
      }),
      api.cachedUnits(app.target()).catch(function () {
        return null;
      }),
      api.cachedDecisionMakers(app.target()).catch(function () {
        return null;
      }),
    ]).then(function (results) {
      if (token !== worldToken) {
        return;
      }
      worldData = {
        cities: (results[0] && results[0].cities) || [],
        regionSummaries: (results[1] && results[1].regions) || [],
        economyOverview: results[2] || null,
        units: (results[3] && results[3].units) || [],
        decisionMakers: (results[4] && results[4].decisionMakers) || [],
      };
      renderWorldOverview(app.getState());
    });
  }

  function refreshPendingCount() {
    if (pendingState === "loading" || pendingState === "loaded" || pendingState === "failed") {
      return;
    }
    pendingState = "loading";
    var token = ++pendingToken;
    api
      .approvals()
      .then(function (body) {
        if (token !== pendingToken) {
          return;
        }
        pendingCount = pendingCountOf(body);
        pendingState = "loaded";
        renderWorldOverview(app.getState());
      })
      .catch(function () {
        if (token !== pendingToken) {
          return;
        }
        pendingCount = null;
        pendingState = "failed";
        renderWorldOverview(app.getState());
      });
  }

  function pendingText() {
    if (pendingState === "loaded") {
      return pendingCount === null ? "—（响应形状未知）" : pendingCount;
    }
    if (pendingState === "failed") {
      return "—（审批未接入）";
    }
    return "…";
  }

  function renderWorldOverview(state) {
    var mount = app.byId("world-overview-mount");
    if (!mount) {
      return;
    }
    state = state || app.getState();
    var section = app.byId("world-overview-section");
    var visible = state.mode === "view" || state.mode === "region";
    var show = visible && !state.selection;
    if (section) {
      section.hidden = !show;
    }
    if (!show) {
      return;
    }
    if (!worldData) {
      app.clear(mount);
      mount.appendChild(app.el("p", { class: "empty", text: "载入世界总览…" }));
      return;
    }
    var cities = worldData.cities;
    var regionSummaries = worldData.regionSummaries;
    var units = worldData.units;
    var makers = worldData.decisionMakers;
    var govCount = units.filter(function (unit) {
      return !!(unit.module && unit.module.kind === "gov");
    }).length;
    app.clear(mount);
    var wrap = app.el("div", { class: "world-overview" });
    var dl = app.el("dl", { class: "kv world-stat" });
    appendRow(dl, "区域", regionSummaries.length);
    appendRow(dl, "城市", cities.length);
    appendRow(
      dl,
      "城市人口",
      sumNumbers(cities, function (city) {
        return city.population;
      }) === null
        ? "—"
        : sumNumbers(cities, function (city) {
            return city.population;
          })
    );
    appendRow(dl, "单位", units.length);
    appendRow(dl, "GOV", govCount);
    appendRow(dl, "决策人", makers.length);
    appendRow(dl, "待批", pendingText());
    var economy = worldData.economyOverview;
    if (!economy) {
      appendRow(dl, "经济", "—（经济总览未载入）", "该端点取不到数据。");
    } else if (!economy.activated) {
      appendRow(dl, "经济", "未激活", "economy 切片的 meta 为空（这一版世界还没播种经济）。");
    } else {
      var classFirst = economy.classFirst || {};
      if (classFirst.available === true) {
        appendRow(
          dl,
          "经济",
          "class-first 世界级 · tick " + app.text(economy.tick),
          economy.scope || "世界级读数（非逐格）。"
        );
        if (classFirst.conservation) {
          appendRow(
            dl,
            "世界人口",
            app.text(classFirst.conservation.population),
            "class-first 守恒读数里的世界人口（世界级，不是某一格/某一国）。"
          );
          appendRow(
            dl,
            "货币守恒",
            classFirst.conservation.moneyBalanced === true
              ? "平衡"
              : classFirst.conservation.moneyBalanced === false
                ? "不平衡"
                : "—（不可得）",
            "Σ池+放贷+托管 == 创世家户+放贷。"
          );
        } else {
          appendRow(dl, "世界人口", "—（不可得：classFirst.conservation 缺失）");
        }
      } else {
        appendRow(
          dl,
          "经济",
          "已激活但 class-first 不可得",
          classFirst.unavailable === null || classFirst.unavailable === undefined
            ? "economy.classFirst 没有 available=true 的权威读数。"
            : String(classFirst.unavailable)
        );
      }
    }
    wrap.appendChild(dl);
    var cards = worldModel && worldModel.nationSummaries
      ? worldModel.nationSummaries(regionSummaries, cities, units, makers)
      : [];
    var cardsNode = app.el("div", { class: "nation-cards" });
    if (!cards.length) {
      cardsNode.appendChild(
        app.el("p", { class: "empty", text: "无国家区域（区域 meta.tag 无 nation: 前缀）。" })
      );
    }
    cards.forEach(function (card) {
      var cardNode = app.el("div", { class: "nation-card" });
      cardNode.appendChild(app.el("h3", { text: card.name + "（" + card.id + "）" }));
      var cdl = app.el("dl");
      appendRow(cdl, "人口", card.population === null ? "—" : card.population);
      appendRow(cdl, "区域", card.regionCount);
      appendRow(cdl, "城市", card.cityCount);
      appendRow(cdl, "城市人口", card.cityPopulation === null ? "—" : card.cityPopulation);
      appendRow(cdl, "单位", card.unitCount === null ? "—" : card.unitCount);
      appendRow(cdl, "GOV", card.govCount === null ? "—" : card.govCount);
      appendRow(cdl, "决策人", card.decisionMakerCount);
      cardNode.appendChild(cdl);
      cardsNode.appendChild(cardNode);
    });
    wrap.appendChild(cardsNode);
    mount.appendChild(wrap);
    refreshPendingCount();
  }

  /** 右栏渲染：目标 {branch,revision} 变化才重取 overview；高亮/选择变化只重画（不重取）。 */
  function renderRight(state) {
    state = state || app.getState();
    // ★ F1：世界总览独立于区域面板：先按当前模式/选中决定显隐，再走区域列表的既有取数。
    ensureWorldData(state);
    var mount = app.byId("region-panel-mount");
    if (!mount) {
      return;
    }
    var key = targetLabel();
    if (key === rightKey) {
      if (rightRegions !== null) {
        drawRight(state);
      }
      return;
    }
    rightKey = key;
    var token = ++rightToken;
    app.clear(mount);
    mount.appendChild(app.el("p", { class: "empty", text: "载入区域…（" + key + "）" }));
    loadOverview()
      .then(function (body) {
        if (token !== rightToken) {
          return;
        }
        rightRegions = body.regions || [];
        drawRight(state);
      })
      .catch(function (e) {
        if (token !== rightToken) {
          return;
        }
        rightRegions = null;
        app.clear(mount);
        mount.appendChild(app.el("p", { class: "empty", text: "区域载入失败：" + e.message }));
      });
  }

  window.SimosPanelRight = {
    renderRight: renderRight,
    // ★ F1：map.js 取数后推世界数据（同一份缓存；右栏不重复请求）。
    setWorldData: setWorldData,
  };
})();

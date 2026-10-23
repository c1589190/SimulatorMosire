# 前端维护手册

> **本文件属于项目维护。** 旧 `07-六-前端.md` 保留详细历史；当前数字不再写死在常驻/维护文档里，
> 以本轮门禁实际输出为准。

## 一、形态

- 无 npm、无打包器；纯 `<script>` 静态文件。
- 两个宿主页都要同步：旧地图页 `map.html` 与工作台 `index.html`。
- JS 文件加载顺序是语义的一部分：
  `hexgeom → hexcolor → regionShape → map.js → map-mapeditor.js → map-regioneditor.js → renderer.js`。
- `simos-app/src/test/js/helpers/webui-loader.cjs` 的 `BUNDLE_DEPS` 必须表达“新文件依赖它取用的宿主”，不能反过来。
- 可变绑定跨文件必须走 getter；取快照会恒为初始值。

## 二、门禁

```bash
node simos-app/src/test/js/run-gate.cjs
tools/gate.sh focused simos-app
tools/gate.sh full
```

- 前端门禁 fail-closed；下界与实际测试数由门禁脚本/本轮输出决定，禁止凭历史数字写“通过”。
- 新增 `.test.cjs` 文件必须进 `REQUIRED_FILES`；下界若有两处同值，必须同改。
- 旧 402/412 等数字只作历史证据；不得把本文当当前测试数。

## 三、同步点

- 地图旧页与工作台共用 `map*.js`、`renderer.js`；改一处必须检查另一处。
- `renderer.js` 加载期依赖 `core.renderEdgeKindOptions`；新模块不能先于宿主执行。
- UI 写白名单、按钮状态、只读提示必须与后端命令/权限同步；不能只改前端。

## 四、验证边界

- 纯 JS 门禁只能证明脚本层；涉及 API/真实浏览器/权限的，按 `VERIFICATION.md` 走真实进程或明确写 `NOT_RUN`。
- 前端门禁通过不等于完整 Maven full gate；报告必须区分 focused/full。

## 五、旧长文

- `07-六-前端.md`：加载顺序、历史数字与具体用例；
- `10-九-实测失败形态.md`：前端观测/假绿相关事故。

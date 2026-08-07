# 执行计划：激活码/设备管理员 Web 后台

工作目录：`radio-proxy/`（独立仓库 [yuntingtv-radio-proxy](https://github.com/Uynaity/yuntingtv-radio-proxy)，
本机 clone 于 `/Users/ai/YuntingTV/radio-proxy`）。该仓库与主仓库（本 Trellis 任务所在仓库）无 git 归属关系，
提交时需在 `radio-proxy/` 目录内单独 `git add`/`git commit`/`git push`，不进主仓库的提交。

## 执行顺序

### 1. 后端：`GET /admin/devices`

- [ ] 新建 `radio-proxy/admin_devices.go`：`adminDevice` 结构体、`handleAdminDevices`、`listDevices`
      （SQL 见 `design.md` 的 `LEFT JOIN LATERAL` 查询）。
- [ ] 在 `admin.go` 的 `registerAdminRoutes` 里追加一行路由注册：
      `mux.HandleFunc("/admin/devices", requireAdmin(handleAdminDevices))`（放在现有
      `mux.HandleFunc("/admin/devices/", ...)` 之前或之后均可，两者是独立注册，顺序不影响匹配结果）。
- [ ] 新建 `radio-proxy/admin_devices_test.go`，覆盖 `design.md` 列出的 8 个场景：空列表、未绑定设备、
      生效码命中、过期码排除、吊销码排除、多码历史只留生效那条、401、405。
      **参照 `activation_test.go` 现有的测试库接入方式**（`TestMain`/`TEST_DATABASE_URL`/`embedded-postgres`），
      不要另起一套 DB 测试 fixture。
- [ ] `cd radio-proxy && go test ./...` 全绿，重点看新测试文件与 `activation_test.go`/`main_test.go` 均通过
      （改动可能触碰共享的测试辅助函数，需确认没有破坏既有用例）。
- [ ] `go vet ./...`、`gofmt -l .`（无输出即通过）。

### 2. 后端：管理页静态资源托管

- [ ] 新建目录 `radio-proxy/admin_ui/`，放 `index.html`、`app.js`、`style.css`（内容见下一节）。
- [ ] 新建 `radio-proxy/admin_ui.go`：`//go:embed admin_ui` + `registerAdminUIRoutes`（见 `design.md`）。
- [ ] 在 `admin.go` 的 `registerAdminRoutes` 末尾（`adminToken == ""` 早退之后）追加
      `registerAdminUIRoutes(mux)` 调用。
- [ ] 手动验证路由优先级不冲突：本地起服务后 `curl -I http://localhost:8080/admin/codes`（应 404 without
      token 或走 `requireAdmin`，不应被 UI 的 `/admin/` 兜底路由截获）、`curl http://localhost:8080/admin/`
      （应返回 `index.html` 内容）。

### 3. 前端页面实现

- [ ] `index.html`：登录表单 + 两个 Tab（激活码 / 设备）的容器骨架，`<script src="app.js">`、
      `<link rel="stylesheet" href="style.css">`（相对路径，因为最终挂在 `/admin/` 下，`http.StripPrefix`
      后资源本身在根路径，`index.html` 里用 `app.js`/`style.css` 相对引用即可，不要写死 `/admin/app.js`
      绝对路径，避免部署路径变化时失效）。
- [ ] `app.js`：
  - `apiFetch` 封装（见 `design.md`，401 自动回登录态）。
  - 登录表单提交逻辑。
  - 激活码 Tab：拉取 `GET /admin/codes`、渲染表格、状态筛选（前端过滤）、文本搜索框（按码/备注前端过滤）、
    生成表单（含数量 N 循环调用）、吊销按钮（`confirm()` + `POST /admin/codes/{code}/revoke`）。
  - 设备 Tab：拉取 `GET /admin/devices`、渲染表格、解绑按钮（`confirm()` +
    `POST /admin/devices/{deviceHash}/unbind`）、"当前生效码"点击跳转激活码 Tab 并预填搜索框。
- [ ] `style.css`：最小可用样式（表格、表单、状态徽标配色、Tab 切换高亮），不接 CDN、不用预处理器。

### 4. 联调验证（本地）

- [ ] 本地起 Postgres（或复用测试用的 `embedded-postgres`/已有本地库），设置 `DATABASE_URL`、
      `ADMIN_TOKEN`，`go run .` 起服务。
- [ ] 浏览器访问 `http://localhost:8080/admin/`：
  - 未输入/输入错误 token → 看到错误提示，不展示数据。
  - 输入正确 `ADMIN_TOKEN` → 看到激活码列表（初始应为空或已有测试数据）。
  - 生成一个码（时长 7 天，数量 1）→ 列表出现该码，状态"未使用"。
  - 用该码在设备侧走一遍现有兑换流程（可直接 `curl -X POST /v1/activation/redeem`）→ 刷新设备页能看到
    对应设备，"当前生效码"字段命中刚兑换的码；激活码页该码状态变为"生效中"。
  - 吊销该码 → 激活码页状态变"已吊销"；设备页"当前生效码"变空（下次拉取时，无需实时推送）。
  - 重新生成并兑换一个码，走强制解绑 → 设备页"当前生效码"清空，激活码页该码仍是"生效中"状态（`expiresAt`
    不变，只是 `boundDevice` 清了——对应 `design.md` 里"解绑不影响 `expiresAt`"的既有后端语义）。
  - 关闭标签页重开 → 需重新登录（`sessionStorage` 生命周期验证）。
  - 未配置 `ADMIN_TOKEN` 重启服务 → `/admin/`、`/admin/devices`、`/admin/codes` 均 404。

### 5. Quality check（trellis-check 或等价人工检查）

- [ ] 对照 `prd.md` 的 Acceptance Criteria 逐条核对。
- [ ] 确认没有改动 `activation.go`/`gateway.go`/`handlers.go` 的既有业务逻辑（`git diff` 只应涉及新文件
      + `admin.go` 的路由注册追加）。
- [ ] 确认无第三方 CDN 引用、无新增 `go.mod` 依赖（`git diff go.mod go.sum` 应为空）。

### 6. 提交与部署

- [ ] `radio-proxy/` 内提交（不进主仓库）：commit message 说明新增管理员 Web UI + `GET /admin/devices`。
- [ ] push 到 `yuntingtv-radio-proxy` 远端。
- [ ] **提醒用户**：该仓库无 CI/CD，需手动同步部署到 `radio.hku.wtf`（拉代码、重新构建镜像、
      `docker compose up -d --build`），部署完成前功能不会在生产可用。本任务不新增环境变量/卷，部署步骤
      与现状一致。

## 验证命令汇总

```bash
cd radio-proxy
go build ./...
go vet ./...
gofmt -l .
go test ./...
```

## 回滚点

- 任一步骤出问题都可以 `git revert` 到本任务开始前的 commit（纯增量改动，无 schema 迁移，无需清库）。
- 若线上部署后发现问题，`ADMIN_TOKEN` 置空重启即可让整个管理面（含 UI）瞬间不可达，作为最快的应急下线
  手段（不需要单独的 feature flag）。

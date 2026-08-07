# 激活码/设备管理员 Web 后台

## Goal

关联任务 [08-06-tunein-activation-code](../08-06-tunein-activation-code/prd.md)（状态 `in_progress`，尚未归档）
给 `radio-proxy` 加了激活码/设备绑定机制，并提供了一组带 `Bearer ADMIN_TOKEN` 鉴权的管理 API，但明确把
「管理员 Web UI」列为 Non-goal——查看/生成/吊销激活码、强制解绑设备目前只能靠裸调 HTTP 接口（curl/Postman）。
本任务补上这块：给管理员一个网页，能可视化查看和管理激活码、设备绑定信息，不必再手搓请求。

## 背景 / 已确认事实（代码库证据）

- **现有管理 API**（[admin.go](../../../radio-proxy/admin.go)，独立私有仓库
  [yuntingtv-radio-proxy](https://github.com/Uynaity/yuntingtv-radio-proxy)，本机已 clone 在 `radio-proxy/`）：
  - `POST /admin/codes` body `{durationDays, note?}` → 生成新码
  - `GET /admin/codes[?status=unused|active|expired|revoked]` → 列出激活码（含 `boundDevice`/`activatedAt`/`expiresAt`/
    `lastRebindAt`/`revokedAt`/`note`/`status`）
  - `POST /admin/codes/{code}/revoke` → 吊销
  - `POST /admin/devices/{deviceHash}/unbind` → 强制解绑（只清 `bound_device`/`last_rebind_at`，保留 `expires_at`）
  - 鉴权：`Authorization: Bearer <ADMIN_TOKEN>`，常量时间比较；`ADMIN_TOKEN` 未配置时整组 `/admin/*` 路由
    **不注册**（而非注册后拒绝）。单一 owner 场景，无多管理员/权限分级。
- **缺口**：`devices` 表（`device_hash`、`first_seen_at`、`last_seen_at`）已存在（[db.go:34-38](../../../radio-proxy/db.go)），
  但**从未有接口把它列出来**——管理员目前只能通过某个码的 `boundDevice` 字段间接看到"哪个码绑了哪个设备"，
  看不到设备维度的全貌（尤其是码已过期/已吊销/已解绑之后，设备就从码的视角消失了）。
- **`devices` 表的实际写入口径（实现阶段核实，修正本 PRD 初稿的错误假设）**：`touchDevice` 全项目**只有一个调用点**
  ——[activation.go:265](../../../radio-proxy/activation.go:265)，在 `redeemCode` 事务内、且在"码不存在/已吊销/已过期"
  三道校验**之后**。门禁校验（`/proxy`、`/seg`、`/v1/stream`）与 `/v1/activation/status` 都是**纯读**，不写 `devices`。
  因此：
  - `devices` 表 = "曾经成功兑换/换绑过激活码的设备"，**不包含**装了 App 但从未兑换的设备。
  - `last_seen_at` 的语义是"最近一次成功兑换/换绑"，**不是**"最近活跃"。
  - 本 PRD 初稿写的"每次校验/兑换都会 `touchDevice` 更新"是**错的**，据此推导出的"能看到访问过但从未绑定的设备"
    这条验收项在不改动服务端既有逻辑的前提下**不可达**，已从下方 Acceptance Criteria 移除。
  - 用户确认真实诉求是"设备装了 App 并打开过就记录下来"，这需要 App 启动时主动上报 + 服务端在状态接口落记录，
    跨 App 与服务端两侧，已拆为平级任务
    [08-06-app-device-report](../08-06-app-device-report/prd.md) 单独立项，不并入本任务。
- **一台设备可能历史上被多个码的 `bound_device` 指向**（换码不会清空旧码的 `bound_device`），"当前对该设备生效的码"
  需要按 [lookupActivation](../../../radio-proxy/activation.go:159-184) 同口径的逻辑计算：`bound_device = 该设备
  AND revoked_at IS NULL AND expires_at > now`，按 `expires_at` 取最新一条。
- **部署形态**：单 Go 二进制 + Postgres + Caddy 反代（[docker-compose.yml](../../../radio-proxy/docker-compose.yml)、
  [Caddyfile](../../../radio-proxy/Caddyfile)），1C1G 小机器，**无 CI/CD**（改动 push 到仓库后仍需手动同步部署到
  `radio.hku.wtf`）。`Caddyfile` 对 `/admin/*` 没有任何特殊 matcher，走末尾的 `reverse_proxy` 兜底转发，即**现有
  JSON 管理接口本就是公网可达、仅靠 Bearer token 门禁**的模型（无 IP 白名单/额外网络层限制）。
- 激活码总量是"人工发放的量级"（既有代码注释原话，见 `admin.go:141`），不是海量数据，列表/过滤不需要分页或服务端
  动态查询优化。

## 关键产品决策（已与用户确认）

| 决策点 | 结论 | 理由 |
|---|---|---|
| 技术栈与部署形态 | **内嵌进 `radio-proxy` 服务**：Go `embed.FS` 托管静态 HTML/CSS/原生 JS，同一二进制、同一 Caddy 路由，不新增容器/构建链/CI | 项目当前无 CI/CD、单人维护、1C1G 小机器，新增独立前端项目意味着长期多一份构建工具链与部署面；内嵌零新增基础设施，与既有"最小化运维"风格一致 |
| Web 登录方式 | 登录页收集 `ADMIN_TOKEN`（作为"密码"输入），存 `sessionStorage`，后续所有请求带 `Authorization: Bearer` header | 复用现有鉴权模型，不引入 session/cookie/CSRF 防护体系；`sessionStorage` 随标签页关闭自动清除，无需专门"登出"状态管理 |
| 设备列表 | **新增 `GET /admin/devices` 接口** + 独立设备管理页，展示所有见过的设备（含从未绑定任何码的） | `devices` 表已存在、已被写入，只是没有读接口；用户明确要"查看用户设备信息"，现状看不到设备全貌 |
| 页面访问的网络层防护 | 沿用现有模型（仅 Bearer token 门禁，不加 IP 白名单/Caddy 双重鉴权） | 与现有 JSON 管理接口暴露方式保持一致，不引入不对称的安全模型；`ADMIN_TOKEN` 本身是 `openssl rand -hex 32` 级别的随机串，暴力破解不可行 |

## Requirements

### 服务端（`radio-proxy`，新增/扩展）

1. 新增 `GET /admin/devices` 管理接口（同一鉴权中间件 `requireAdmin`）：列出所有 `devices` 表记录，
   每条附带该设备当前生效的激活码（若有，按 PRD 背景所述口径计算）及其到期时间；按 `last_seen_at` 倒序。
2. 新增管理员 Web UI 路由（`GET /admin/` 及其静态资源），仅在 `ADMIN_TOKEN` 已配置时注册——与现有
   `/admin/codes` 等接口共享"未配置 token 则整组不注册"的既有约定，不能形成"UI 裸奔、API 受保护"的不一致状态。
3. UI 静态资源通过 Go `embed.FS` 打包进二进制，不新增外部依赖（不接 CDN，不新增 npm/构建步骤）。

### 管理员 Web UI（页面功能）

1. **登录**：未存有效 token 时展示登录表单；输入后暂存于 `sessionStorage`，用一次真实管理接口调用验证
   （401 则清空并提示"Token 错误"）。
2. **激活码管理页**：
   - 列表展示：码、状态（未使用/生效中/已过期/已吊销，用不同视觉样式区分）、时长（天）、绑定设备（哈希截断
     显示+复制按钮）、激活时间、到期时间、备注。
   - 生成新码：填写有效时长（天）、备注（可选）、数量（默认 1，>1 时前端循环调用 `POST /admin/codes` N 次，
     不新增批量后端接口）。
   - 吊销：二次确认后调用 `POST /admin/codes/{code}/revoke`，成功后列表内该码状态更新为"已吊销"。
   - 按状态筛选 + 按码/备注文本搜索（一次性拉全量列表，前端过滤——量级小，不必服务端分页）。
3. **设备管理页**：
   - 列表展示：设备哈希（截断+复制）、首次出现时间、最近活跃时间、当前生效码（若有，可点击跳转到激活码页
     并定位该码）。
   - 强制解绑：二次确认后调用 `POST /admin/devices/{deviceHash}/unbind`，成功后该设备"当前生效码"清空。
4. 两个页面共用同一登录态与顶部导航（Tab 切换），无需服务端路由跳转（单页多 Tab 即可，无需引入前端路由库）。

## Non-goals（明确不做）

- 不做多管理员账号/权限分级/操作审计日志（沿用现有"单一 owner、一个 token"决策）。
- 不做激活码备注编辑、手动调整到期时间等**新的管理动作**（超出现有 admin API 能力范围，需要就单独立项）。
- 不做批量生成的专门后端接口（前端循环调用单个生成接口即可满足"一次发多个码"的需求）。
- 不引入 session/cookie 鉴权体系或 CSRF 防护（沿用 Bearer token + `sessionStorage` 模型，无 ambient 凭证，
  不存在 CSRF 攻击面）。
- 不新增 IP 白名单或 Caddy 层双重门禁（与现有 JSON 管理接口的暴露方式保持一致）。
- 不做移动端专项适配（响应式基本可用即可，非重点场景）。
- 不改动激活码兑换/门禁的核心业务逻辑（`/v1/stream`、`/proxy`、`/seg`、`/v1/activation/*`），只加管理面查询与 UI。

## Acceptance Criteria

- [ ] `ADMIN_TOKEN` 未配置时，`GET /admin/`（及其静态资源）与 `GET /admin/devices` 均不可达（404，路由未注册），
      与现有 `/admin/codes` 行为一致。
- [ ] `ADMIN_TOKEN` 已配置，未登录/token 错误时访问管理页看到登录表单；输入错误 token 后任何管理数据请求返回 401，
      页面提示错误、不展示任何数据。
- [ ] 输入正确 token 后：激活码页展示的列表与 `GET /admin/codes` 一致；设备页展示的列表与新 `GET /admin/devices`
      一致；token 存于 `sessionStorage`，刷新页面后仍保持登录，关闭标签页后失效需重新登录。
- [ ] 能在页面上生成新码（填写时长/备注/数量），生成后新码立刻出现在列表里；数量 >1 时生成对应个数的码。
- [ ] 能在页面上吊销一个码（二次确认后生效），吊销后该码状态变为"已吊销"。
- [ ] 能在页面上强制解绑一个设备（二次确认后生效），解绑后该设备"当前生效码"清空，原激活码 `expiresAt` 不变
      （验证 UI 正确调用现有接口并刷新展示，不改动后端解绑语义）。
- [ ] 设备页能看到"当前没有生效码"的设备（码已过期、已吊销、或已被强制解绑/换绑到别的设备），
      这类设备从激活码页的 `boundDevice` 视角是看不全的。
      （原验收项"能看到从未绑定任何码但访问过的设备"已移除，理由见上方"`devices` 表的实际写入口径"。）
- [ ] 设备的"当前生效码"计算正确排除已过期/已吊销的码（与 `lookupActivation` 同口径）。
- [ ] 设备页的列名如实反映 `devices` 表语义，不出现"最近活跃"这类会让运维误判为"最近在听"的措辞。
- [ ] `go test ./...` 全绿，新增 `GET /admin/devices` 的单测覆盖：空列表、含未绑定设备、含生效码、排除已过期码、
      排除已吊销码、鉴权失败（401）、方法不允许（405）。
- [ ] 管理页面不引入任何第三方 CDN 依赖，纯手写 HTML/CSS/原生 JS，通过 `embed.FS` 打包进现有二进制，不新增容器/
      构建步骤。

## 部署提醒

改动提交到独立仓库 [yuntingtv-radio-proxy](https://github.com/Uynaity/yuntingtv-radio-proxy)，push 后仍需手动同步
部署到 `radio.hku.wtf`（该仓库暂无 CI/CD）。本任务不新增环境变量/容器/卷，部署步骤与现状一致（拉代码、重新构建
镜像、`docker compose up -d`）。

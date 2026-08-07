# 技术设计：激活码/设备管理员 Web 后台

## 边界与契约

- 全部改动在独立仓库 [yuntingtv-radio-proxy](https://github.com/Uynaity/yuntingtv-radio-proxy)（本机 clone 于
  `radio-proxy/`），改动 push 后仍需手动同步部署到 `radio.hku.wtf`。
- **不改动**现有业务逻辑文件的核心行为：`activation.go`（兑换/门禁）、`gateway.go`（`/v1/stream`）、
  `handlers.go`（`/proxy`、`/seg`）。只读取，不修改。
- **新增文件**：
  - `radio-proxy/admin_devices.go` — 新管理接口 `GET /admin/devices` 的 handler + SQL。
  - `radio-proxy/admin_ui.go` — `embed.FS` 静态资源托管 + 路由注册。
  - `radio-proxy/admin_ui/index.html`、`admin_ui/app.js`、`admin_ui/style.css` — 页面本体（embed 源目录）。
  - `radio-proxy/admin_devices_test.go`（或并入现有 `activation_test.go`，实现阶段按现有测试文件组织习惯定）。
- **修改文件**：
  - `radio-proxy/admin.go` — `registerAdminRoutes` 里追加两处新路由注册（devices 列表、UI）。

---

## 服务端：`GET /admin/devices`

### 语义

复用 [lookupActivation](../../../radio-proxy/activation.go:159-184) 判定"当前生效码"的同一口径：
`bound_device = 该设备 AND revoked_at IS NULL AND expires_at IS NOT NULL AND expires_at > now`，按
`expires_at` 取最新一条。一台设备历史上可能被多个码的 `bound_device` 指向（换绑不清空旧码的
`bound_device`，见 `redeemCode` 的换绑分支），所以不能假设"设备 → 码"是天然的一对一关系，必须显式按
这个口径挑一条。

### 响应结构

```go
// adminDevice 是管理面的设备视图。ActiveCode 为 nil 表示该设备当前没有生效码
// （从未绑定过，或绑过的码都已过期/被吊销/换绑到了别的设备）。
type adminDevice struct {
    DeviceHash      string  `json:"deviceHash"`
    FirstSeenAt     int64   `json:"firstSeenAt"`
    LastSeenAt      int64   `json:"lastSeenAt"`
    ActiveCode      *string `json:"activeCode"`
    ActiveExpiresAt *int64  `json:"activeExpiresAt"`
}
```

### SQL

```sql
SELECT d.device_hash, d.first_seen_at, d.last_seen_at, ac.code, ac.expires_at
FROM devices d
LEFT JOIN LATERAL (
    SELECT code, expires_at
    FROM activation_codes
    WHERE bound_device = d.device_hash
      AND revoked_at IS NULL
      AND expires_at IS NOT NULL
      AND expires_at > $1
    ORDER BY expires_at DESC
    LIMIT 1
) ac ON true
ORDER BY d.last_seen_at DESC
```

`$1` = `time.Now().Unix()`。`LEFT JOIN LATERAL` 是 Postgres 语法（项目已是 Postgres-only，无兼容顾虑），
每设备最多一行输出，避免"一设备多活跃码"在结果集里重复出现（正常业务下不应发生，但 SQL 层面天然
排除比事后去重更简单）。

### Handler 与路由

```go
// GET /admin/devices  列出所有设备
func handleAdminDevices(w http.ResponseWriter, r *http.Request) {
    if r.Method != http.MethodGet {
        http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
        return
    }
    listDevices(w)
}
```

在 `admin.go` 的 `registerAdminRoutes` 里追加（与现有 `/admin/codes` + `/admin/codes/` 的注册模式完全对称）：

```go
mux.HandleFunc("/admin/devices", requireAdmin(handleAdminDevices))       // 新增：精确匹配，列表
// mux.HandleFunc("/admin/devices/", requireAdmin(handleAdminDeviceAction)) // 已存在：前缀匹配，{hash}/unbind
```

`net/http.ServeMux` 对精确路径 `"/admin/devices"` 与子树路径 `"/admin/devices/"`是两条独立注册，互不覆盖
（与现有 `/admin/codes` vs `/admin/codes/` 的先例完全一致），不需要额外的路径解析逻辑。

### 测试

新增（`admin_devices_test.go`，走真实测试用 Postgres，与项目现有 DB 测试策略一致——见
`TestMain`/`embedded-postgres` 或 `TEST_DATABASE_URL`）：

1. 空库 → 返回 `[]`（非 `null`）。
2. 设备已 `touchDevice` 但从未绑定任何码 → 出现在列表，`activeCode` 为 `nil`。
3. 设备绑定了一个未过期未吊销的码 → `activeCode`/`activeExpiresAt` 命中该码。
4. 设备曾绑定的码已过期 → `activeCode` 为 `nil`（不能把过期码算作"生效"）。
5. 设备曾绑定的码已被吊销 → `activeCode` 为 `nil`。
6. 设备历史上被两个码的 `bound_device` 指向（一个过期、一个生效）→ 只返回生效的那个，不重复输出该设备。
7. 未带 `Authorization` 或 token 错误 → 401。
8. `POST /admin/devices` → 405（复用现有 `handleAdminCodes` 对方法校验的写法）。

---

## 服务端：管理员 Web UI 静态资源

### 目录结构

```
radio-proxy/
  admin_ui.go
  admin_ui/
    index.html
    app.js
    style.css
```

### embed 与路由

```go
package main

import (
    "embed"
    "io/fs"
    "net/http"
)

//go:embed admin_ui
var adminUIFS embed.FS

// registerAdminUIRoutes 挂载管理页静态资源。调用方（registerAdminRoutes）已经
// 保证只在 ADMIN_TOKEN 配置时才调用这里——UI 本身不需要再判断一次。
func registerAdminUIRoutes(mux *http.ServeMux) {
    sub, err := fs.Sub(adminUIFS, "admin_ui")
    if err != nil {
        panic(err) // embed 内容是编译期常量，运行时不可能出这个错，出了就是构建配置错了
    }
    mux.Handle("/admin/", http.StripPrefix("/admin/", http.FileServer(http.FS(sub))))
}
```

在 `admin.go` 的 `registerAdminRoutes` 末尾追加 `registerAdminUIRoutes(mux)` 调用（仍在 `adminToken == ""`
的 `return` 之后，保持"未配置 token 则 UI 与 API 一起不可达"的不变式）。

**路由优先级**：`net/http.ServeMux` 按最长匹配前缀选择 handler，`/admin/codes`（精确）、`/admin/codes/`、
`/admin/devices`（精确）、`/admin/devices/` 均比 `/admin/` 更具体，会优先命中，`/admin/` 只兜底其余路径
（`/admin/`、`/admin/app.js`、`/admin/style.css`）。不存在路由冲突。

**为什么不做 SPA 前端路由**：页面内容是"登录 + 两个 Tab"，用 JS 直接切换 DOM 可见性即可，不需要
`history.pushState`/客户端路由库，也就不需要"未知路径 fallback 到 index.html"的常见 SPA 部署技巧。

**为什么 UI 本身不校验 token**：静态 HTML/JS/CSS 不含任何敏感数据，真正的门禁在管理 API 侧（`requireAdmin`
中间件）。这与"登录页本身公开可访问，只有登录后的操作需要凭证"是标准做法一致。

### 前端实现（`admin_ui/app.js`，原生 JS，无构建步骤）

关键结构：

```js
const TOKEN_KEY = 'adminToken';

function getToken() { return sessionStorage.getItem(TOKEN_KEY); }
function setToken(t) { sessionStorage.setItem(TOKEN_KEY, t); }
function clearToken() { sessionStorage.removeItem(TOKEN_KEY); }

async function apiFetch(path, opts = {}) {
  const res = await fetch(path, {
    ...opts,
    headers: { ...(opts.headers || {}), Authorization: `Bearer ${getToken()}` },
  });
  if (res.status === 401) {
    clearToken();
    showLogin('Token 错误或已失效');
    throw new Error('unauthorized');
  }
  return res.json();
}
```

- 登录：表单提交后 `setToken(input.value)`，立刻发一次 `apiFetch('/admin/codes')` 验真；成功则渲染主界面，
  401 会被 `apiFetch` 自身处理（清空 token、回到登录态、给出错误提示），不需要登录逻辑单独处理这一路径。
- 两个 Tab（激活码 / 设备）用普通 DOM 显隐切换，各自的数据在切换到该 Tab 时（或登录成功后一次性）拉取。
- 生成多个码（数量 N）：`for (let i = 0; i < n; i++) await apiFetch('/admin/codes', {method:'POST', body: JSON.stringify({durationDays, note})})`，
  串行执行（避免 `POST /admin/codes` 内部生成码重试逻辑在高并发下互相竞争、也让失败时能准确报告"成功了几个"）。
- 吊销/解绑：`confirm()` 原生二次确认即可，不需要自定义弹窗组件（管理页非高频操作，简单可靠优先于美观）。
- 设备页"当前生效码"可点击 → 跳转激活码 Tab 并把搜索框填成该码值（复用已有的文本过滤逻辑定位，不需要
  单独实现"高亮滚动到某一行"）。

### 为什么不用任何前端框架/CDN

- 页面复杂度低（两个表格 + 两个表单 + 登录态），原生 JS 完全够用，引入 React/Vue 需要构建步骤，与
  "内嵌进现有二进制、零新增构建链"的架构决策矛盾。
- 不接 CDN（如 Bootstrap CDN）：避免管理页依赖外部网络可用性（服务器所在网络环境不确定），且从
  `embed.FS` 打包进二进制本身就要求资源自包含。

---

## 兼容性与回滚

- 纯增量改动：新增两个 Go 文件、一组静态资源、一条路由注册语句；不修改任何现有 HTTP 契约或数据库 schema。
- 回滚方式：`git revert` 即可，不涉及数据库迁移（`GET /admin/devices` 只读，不新增表/字段）。
- `ADMIN_TOKEN` 未配置时（现状：大多数场景下就是这样，除非运维专门设置）新代码路径完全不注册，行为与
  改动前一致，零风险开关已经天然存在（复用 `admin.go` 现有的 `if adminToken == "" { return }` 早退）。

## 风险 / 待实现阶段关注

- `embed.FS` 打包的静态资源体积很小（几十 KB 级别的手写 HTML/CSS/JS），不影响构建产物大小或编译时间。
- 前端未做 token 强度/格式校验（登录表单接受任意字符串），错误 token 的唯一反馈路径是"调用 API 得到
  401"——足够，管理员是唯一使用者，不需要额外的前端输入校验体验打磨。
- `LEFT JOIN LATERAL` 要确认在项目当前 Postgres 版本（`postgres:17-alpine`，见 `docker-compose.yml`）下
  行为符合预期——17 版本完全支持，且比 17 早得多的版本就已支持（LATERAL 自 Postgres 9.3 起可用），
  无版本兼容顾虑，仅在实现阶段用真实测试库跑一次确认语法正确。

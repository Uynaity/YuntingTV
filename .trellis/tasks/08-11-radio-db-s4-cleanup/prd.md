# 阶段4:删除现场调用与 tunein_catalog 旧路径

父任务:[08-11-radio-db-migration](../08-11-radio-db-migration/prd.md)
前置:[阶段3](../08-11-radio-db-s3-cutover/prd.md) 三个来源全部切 `db` 并稳定运行一段时间

## Goal

收网。删掉服务端里已经没人走的现场调用代码,让三个来源第一次真正统一走一套 DB 读取代码。
这一阶段是纯删除,不新增功能。

## Requirements

1. 删除 [source_yunting.go](../../../radio-proxy/source_yunting.go) /
   [source_qingting.go](../../../radio-proxy/source_qingting.go) 中
   `Provinces` / `Categories` / `Channels` 的现场上游调用及其专属依赖:
   MD5 签名、Header 构造、上游 JSON 结构体、字段清洗(如 `ytStripLivePrefix`)。
   - **注意**:这两个文件里 Program / stream 相关的代码**不能删**——那部分仍走现场调用。
   - subtitle 合并仍需调用 `Source.Channels()`,删除范围必须精确到「已被 DB 取代的部分」。
2. 删除 [tunein_catalog.go](../../../radio-proxy/tunein_catalog.go) 的 JSON 文件加载路径
   (整文件是否删除取决于阶段3 的实现落点),以及随之失效的 JSON 产物与加载配置。
3. 移除 `CHANNEL_SOURCE_MODE` 开关及 `live` 分支——回退窗口到此关闭。
4. 清理因上述删除而不再被引用的依赖、常量、测试桩。

## 非目标

- 不重构剩余代码结构,不顺手改无关逻辑
- 不动 Program / stream / proxy / seg 任何路径

## Acceptance Criteria

- [ ] 服务端全仓库检索:Province / Category / Channel 列表已无任何现场上游调用
- [ ] 云听/蜻蜓的签名、Header、上游 JSON 结构体、字段清洗代码已删除,且 Program/stream 路径未受牵连
- [ ] `tunein_catalog.go` 的 JSON 加载已删除,服务端不再依赖任何 JSON 产物文件
- [ ] `CHANNEL_SOURCE_MODE` 及其 `live` 分支已移除
- [ ] `go build` + 全量测试通过,无未使用的依赖/常量残留(`go vet` 干净)
- [ ] 五个接口出参与阶段3 切库后完全一致
- [ ] `/v1/programs`、`/v1/replay`、`/v1/stream`、`/proxy`、`/seg` 回归无变化
- [ ] APP 端无改动即可正常工作

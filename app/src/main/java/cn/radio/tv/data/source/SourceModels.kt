package cn.radio.tv.data.source

import java.util.TimeZone

/**
 * 节目单的时区口径。三个来源的节目单都是按北京时间划天与排期的，所以日期分档、
 * 发给网关的 date、播出时间展示一律钉死在 +08:00，不跟设备时区走 —— 否则设备
 * 时区一改（或人在境外），「今天」和节目时间会整体平移。
 * 服务端同口径见 radio-proxy `gateway.go:cnZone`。
 */
val BEIJING_TIME_ZONE: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

/**
 * 一条可播的直播流：地址与类型作为**不可分的一个值**传递。
 *
 * 分成两个参数传会出现「带了地址却漏了类型」的中间状态，而漏掉类型的表现是播放器
 * 选错解析器后永远停在「缓冲中」—— 曾经的 TuneIn HLS 台就是这么卡住的。
 *
 * [isHls] 为真时上层显式设 `MimeTypes.APPLICATION_M3U8`；为假不设 mimeType，
 * 交给 ExoPlayer 按内容嗅探（TuneIn 直链可能是 mp3/aac/ogg 任一，猜错容器比不猜更糟）。
 *
 * [proxyActivated] / [proxyExpiresAtSeconds] 顺带带回本设备的「TuneIn 代理」激活状态
 * （epoch 秒）—— 起播本来就要问一次 `/v1/stream`，这个信号是白给的，用来让设置页的
 * 开关状态不至于停留在上次查询的旧值。**它不是门禁**：真正的拦截在服务端。
 */
data class ResolvedStream(
    val url: String,
    val isHls: Boolean,
    val proxyActivated: Boolean = false,
    val proxyExpiresAtSeconds: Long = 0,
)

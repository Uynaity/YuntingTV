package cn.radio.tv.data.source

import kotlinx.serialization.Serializable

/**
 * 电台来源。两个来源各自独立提供数据：列表互不混合；收藏分源存储，但合并展示。
 * [key] 用于持久化（DataStore 偏好键后缀），[displayName] 用于设置页展示。
 * [Serializable]：作为 FavoriteChannel 字段随收藏持久化（按枚举名）。
 */
@Serializable
enum class RadioSourceType(val key: String, val displayName: String) {
    YUNTING("yunting", "云听"),
    QINGTING("qingting", "蜻蜓FM");

    companion object {
        /** 默认来源：云听（与历史版本一致，老用户升级后保持原状）。 */
        val DEFAULT = YUNTING

        fun fromKey(key: String?): RadioSourceType =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

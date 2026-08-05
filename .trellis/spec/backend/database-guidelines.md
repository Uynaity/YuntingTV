# Persistence Guidelines

> 本项目的本地持久化约定。（本 App 无关系型数据库，故本文覆盖「本地存储」而非 SQL/ORM。）

---

## Overview

**没有 SQLite / Room / 任何数据库。** 所有本地持久化只用 **Jetpack DataStore（Preferences 版）**，
封装在 `data/prefs/UserPreferences.kt`，store 名 `radio_prefs`。远端数据不缓存到本地库，
每次进入按需从网络拉取（见 `data/source/`）。

复杂对象（收藏列表、上次播放）用 `kotlinx.serialization` 序列化成 JSON 字符串存进 String key。

---

## Query Patterns

- 读：`context.dataStore.data.map { it[KEY] }` 暴露为 `Flow`，供 ViewModel 收集。
- 写：`context.dataStore.edit { it[KEY] = value }`（`suspend`，DataStore 自带事务，无需手动加锁）。
- JSON 反序列化一律 `runCatching { json.decodeFromString(...) }.getOrNull()/.getOrDefault(emptyList())`，
  坏数据降级为空而非崩溃。`Json { ignoreUnknownKeys = true }`。

---

## 按来源隔离（本项目核心约定）

多电台来源（云听 / 蜻蜓FM）的「所在城市、上次播放、收藏列表」各自独立，用 **key 后缀** 隔离，
互不混合、不同步。全局项（当前来源、启动自动播放）无后缀。

- **云听沿用历史无后缀 key 名**，保证老用户升级后收藏 / 上次播放不丢。新来源必须带后缀。
- key 定义集中在 `UserPreferences` 的 `companion object`，不要散落到别处。

---

## Migrations

DataStore 无 schema 迁移机制。改动数据结构时靠 `ignoreUnknownKeys` + 反序列化失败降级兼容旧值；
新增字段给默认值即可。**不要**重命名或改变已上线 key 的语义——会静默丢用户数据。

---

## Common Mistakes

- ❌ 为缓存远端列表引入 Room / SQLite —— 本项目不需要，按需拉取即可（YAGNI）。
- ❌ 新来源复用云听的无后缀 key —— 会导致两来源收藏 / 上次播放串台。
- ❌ 直接 `decodeFromString` 不包 `runCatching` —— 旧版本坏数据会让读取抛异常。

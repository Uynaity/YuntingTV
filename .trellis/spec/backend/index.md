# App Development Guidelines

> 本项目非 UI（data / player）代码的开发约定。Android TV / 手机电台 App，`cn.radio.tv`，
> MVVM + Jetpack Compose。（目录名沿用 trellis init 的 `backend`。）

---

## Overview

This directory contains guidelines for the app's non-UI conventions. 各文件已按本仓库真实约定填写。

---

## Guidelines Index

| Guide | Description | Status |
|-------|-------------|--------|
| [Directory Structure](./directory-structure.md) | 分层与包组织、来源扩展方式 | Filled |
| [Persistence Guidelines](./database-guidelines.md) | DataStore 偏好存储、按来源隔离（无 SQL DB） | Filled |
| [Error Handling](./error-handling.md) | 失败降级不崩溃、runCatching / try-catch 两种模式 | Filled |
| [Quality Guidelines](./quality-guidelines.md) | 禁用模式（线程安全、MediaSource 分派等） | Filled |
| [Logging Guidelines](./logging-guidelines.md) | 刻意少日志、仅 debug OkHttp、脱敏 | Filled |

---

## How to Fill These Guidelines

For each guideline file:

1. Document your project's **actual conventions** (not ideals)
2. Include **code examples** from your codebase
3. List **forbidden patterns** and why
4. Add **common mistakes** your team has made

The goal is to help AI assistants and new team members understand how YOUR project works.

---

**Language**: All documentation should be written in **English**.

# 2026-11-05：网络备份与设备/socket 支持

## 目标

在属主筛选与 FIFO 修复（同日上一轮）基础上，补齐实验扩展要求中剩余的三块：

1. 网络备份（网盘模式 + 用户管理 + 元数据管理 + 传输加密 + 增量备份）。
2. 字符设备、块设备与套接字的清单记录与尽力还原（FIFO 已于上一轮完成）。
3. 与 whynotcooper/backup 分支的成果合流：其 `780d006` 属主筛选已改编进入主线，本轮继续移植其网络包与设备支持，并保留本仓库的 FIFO 不变量（不打开管道流）与软链接属性安全恢复。

## 落地方式

- 新增 `com.backup.network` 五个类与 Network 标签页（移植自已评审的 whynotcooper/backup `8ce5922`，还原路径复用本地 `BackupService.restoreDetailed`，服务器端发布/保留复用 `BackupCatalog`）。
- `EntryType` 扩展 CHARACTER_DEVICE/BLOCK_DEVICE/SOCKET；`FileScanner` 经 `unix:mode`/`unix:rdev` 分类并记录「主:次」设备号；`ArchiveService` 沿用 skipped 语义以 mknod 尽力重建，套接字只记录。
- `EncryptionService` 将 GCM 认证失败包装为明确的口令错误提示。

## 验证

Windows 11 + OpenJDK 21.0.13：`mvn test` 102 项全部通过（25 项为平台能力跳过）。合并后未改动 FIFO/软链接/属主既有语义。

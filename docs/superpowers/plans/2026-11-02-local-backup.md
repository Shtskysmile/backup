# Local Backup Implementation Plan

> **For agentic workers:** Use executing-plans for integration and dispatching-parallel-agents for isolated filter/catalog modules. Track validation below.

**Goal:** Deliver the approved local file filtering, links, metadata and retention design without a server.

**Architecture:** Extend the existing scanner and versioned manifests; isolate pure filter rules and group catalog from archive I/O. All backup triggers share immutable options; restore returns warnings separately from data errors.

**Tech Stack:** Java 21, Swing, JUnit 5, ZIP/GZIP, existing AES-GCM.

**Spec:** `docs/superpowers/specs/2026-11-02-local-backup-design.md` (approved 2026-11-02).

## Global Constraints

- Preserve existing code and staged files; repository has no HEAD, so work in place on `codex/local-backup`, without commits.
- Keep version 1 archives readable. Do not start servers or clean real user archives.
- Retention defaults off, preserves complete groups, rolls after 10 snapshots.
- Unsupported attributes produce warnings; links must never cause external writes.
- Incremental restore remains manual overlay, without deletion synchronization.

## Review Focus

- Existing target symlinks and ancestor symlinks must never redirect restore writes.
- Filtered hardlinks must reference selected, restorable content.
- A failed archive must never become a baseline or trigger retention.
- Different tasks sharing a destination must not share baselines or delete each other.
- Metadata-only edits and busy realtime triggers must not be lost.

### Task 1: Filter rules (isolated module)

Files: create `core/BackupFilter.java`, `core/BackupFilterTest.java`.

Interface: immutable record `(String includePaths, String excludePaths, String extensions, boolean includeExtensionless, String names, Long modifiedAfterMillis, Long modifiedBeforeMillis, Long minBytes, Long maxBytes)`; `all()`, `matches(String path,long size,long modified)`, `excludesDirectory(String path)`, `isUnrestricted()`, `identity()`.

- [x] Write and run failing tests for five criteria, combinations, invalid input and boundaries.
- [x] Implement normalized `/` globs, semicolon alternatives, AND between criteria, exclusion priority.
- [x] Run module tests and report evidence.

### Task 2: Group catalog (isolated module)

Files: create `core/BackupCatalog.java`, `core/BackupCatalogTest.java`.

Interface: `BackupCatalog.Snapshot(String taskId,String snapshotId,String groupId,String parentId,int sequence,String archiveName,Instant createdAt)`; static `publish(Path destination, Snapshot snapshot)`, `prune(Path destination,String taskId,int keepGroups,Consumer<String> warnings)`.

Catalog writes `archiveName + ".snapshot.properties"` as index; validates archive and `archiveName + ".manifest.properties"` pair and all identifiers before deletion. Only manages catalog-indexed files. Preserve old/unknown/corrupt entries and current group; report warnings. Prune groups oldest first, children first.

- [x] Write failing tests with real temporary directories, multiple tasks, corrupt/path-escaping indexes and group dependencies.
- [x] Implement atomic index publication and group pruning (no calls automatically beyond explicit prune).
- [x] Run module tests and report evidence.

### Task 3: Entry model, scan, archive and restore

Files: modify `BackupEntry`, `BackupManifest`, `FileScanner`, `io/ArchiveService`; add `EntryType`, `RestoreResult`, focused core tests.

Interfaces: retain old constructors; append entry `EntryType type,String linkTarget,Long lastAccessMillis,Long creationMillis`; `directory()` remains. Manifest version 2 reads v1. `scan(root,filter,warnings)`; `restoreDetailed(...): RestoreResult(manifest,warnings)` while old restore delegates.

- [x] Write failing roundtrip tests for soft/hard links, selected hardlink, safe paths, metadata and v1 fixtures.
- [x] Implement scanner before hashing metadata; nofollow links; identify hardlinks after filters.
- [x] Implement ZIP/GZIP v2, plain-file temp replacement, link creation after payload, deepest-directory metadata last.
- [x] Run full tests, including old four tests.

### Task 4: Backup service and retention integration

Files: modify `BackupOptions`, `BackupService`, `BackupResult`, `BackupManifest`; add integration tests.

Interfaces: options append `BackupFilter filter,int keepGroups` plus old constructor; result append warnings plus old constructor. Manifest snapshot info carried in `BackupCatalog.Snapshot snapshot()` with optional old constructor. Service tracks baseline per task in memory; new service/process first backup full. New groups on changed configuration, changed password, requested full or sequence 10 with retention. Atomic publication before pruning.

- [x] Write failing tests for identity separation, same-second names, chain rollover, failure preservation, metadata-only changes.
- [x] Implement immutable options, task identity, snapshot chain publication, whole-group retention.
- [x] Run full tests.

### Task 5: Swing, automation and documentation

Files: modify `ui/BackupFrame`, automation classes as needed; update README and relevant doc sections; add automation tests.

- [x] Write failing pending-trigger tests; implement shared busy lock with one pending automatic request; serial backup/restore.
- [x] Add collapsible filters, retention setting, warnings and restore chain guidance.
- [x] Update user docs and record acceptance commands.
- [x] Run full tests and Swing inspection.

### Task 6: Review and final validation

- [x] Fresh read-only review of changes against design and focus list.
- [x] Reproduce and fix important findings, then run full suite and Java 21 build.
- [x] Deliver summary, test evidence and explicit platform limitations.

## Execution Ledger

- Plan approved by user's “继续吧” after design review; proceed without further approval checkpoints.
- Ruling: unborn repository prevents normal worktree and commit-range tooling. Preserve original sources in local scratch, compare filesystem changes for final review, and leave all user staging untouched.

- Task 1: complete — BackupFilter 8 tests RED→GREEN.
- Task 2: complete — BackupCatalog 9 tests GREEN; refuses corrupt indexes and verifies sidecar identities.
- Task 3: complete — link/external-write tests RED→GREEN; independent handcrafted v1 compatibility and metadata tests verified.
- Ruling: original incremental test used different backup names for its full/incremental calls. Updated both names to `test`, since approved task identity intentionally isolates different backup names.
- Ruling: snapshot UUID added to archive names. Retention integration reproduced old path reuse after same-second pruning; new names cannot be confused with a deleted snapshot.
- Ruling: all groups roll at 10 snapshots, even when retention is off, so the group model remains valid if retention is later enabled. Disabled retention still never deletes archives.

- Task 4: complete — six integration tests GREEN; group rollover, task isolation, metadata-only changes and failed-backup preservation verified.
- Task 5: complete — UI/gate integrated, gate eight tests GREEN; watcher failed-start regression RED→GREEN, native restart and scheduler verified; docs and disposable demo delivered.
- Ruling: macOS POSIX nofollow permission setter demonstrably modified symlink target permissions. Skip symlink owner/group/permissions with warning on all platforms; keep nofollow time restoration, backed by external-target sentinel test.
- Tooling: found bundled Maven at /Applications/IntelliJ IDEA.app/Contents/plugins/maven-plugin/lib/maven3/bin/mvn. Offline run lacked cached dependencies; normal run successfully reached Surefire. Final validation will use real Maven/JUnit.

- Final review: fresh reviewer identified streaming hash validation, full-payload completeness, atime-only incremental restoration and per-task pending queues. Each reproduced with failing tests, then fixed.
- Final fix: link creation now stages before replacement, so a missing hardlink base cannot delete an existing destination file (RED→GREEN).
- Final ruling: unchanged files receive complete incremental manifest metadata only after verifying baseline content/type/link identity; avoids archive-induced atime churn while preserving atime-only user edits.
- Final verification: bundled Maven `package` — 64 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS; Java release 21. JAR `target/backup-1.0-SNAPSHOT.jar`.
- UI verification: rendered backup/filter/automation/restore views, inspected filter scroll area; real Swing buttons exercised filtered encrypted backup and restore with content, hardlink identity and symlink target assertions. No server or automatic user task enabled.
- Integration: changes left uncommitted on `codex/local-backup` in the original directory; original staging preserved. No merge/push is part of the user's request.

- Follow-up user request: removed all four network client/server classes, Network tab, upload controls and upload steps; BackupFrame captures BackupOptions directly. Updated product docs, left original course template unchanged.
- Follow-up verification: clean Maven package GREEN 64/64; no network classes in JAR; module dependencies java.base,java.desktop; three-tab real-button GUI roundtrip GREEN.
- Presentation handoff: created doc/Claude-Code-PPT制作说明.md plus current-version screenshots under doc/assets/ppt; real team identities and Gantt dates remain explicit placeholders.

- Authorized integration follow-up (2026-11-02): user requested origin https://github.com/Shtskysmile/Backup.git, staged commits, merge into main and push. Recorded the saved original implementation followed by independently tested feature stages. This supersedes the earlier no-commit/no-push scope; commits use actual timestamps.

# Backup

Backup is a local Java 21 desktop backup tool built with Swing.
The interface contains Backup, Restore and Automation tabs; no server is needed.

## Features

- Recursive file discovery, including symbolic and hard links where supported
- File selection by relative path/name glob, extension, modification time and byte size
- File metadata capture and best-effort restoration with warnings
- ZIP and GZIP archives, with optional AES-GCM encryption
- Manifest-based incremental backups grouped as one full backup plus up to nine increments
- Optional retention of complete groups, isolated by backup task
- Scheduled backups with `ScheduledExecutorService` and real-time backups with `WatchService`
- Archive restore with path traversal and destination symlink protection
- Compatibility with existing version 1 ZIP, GZIP and encrypted archives

## Run and test

Requirements: JDK 21 or newer; Maven 3.9 or newer for the standard build/test flow.

```bash
mvn test
mvn clean package
java -jar target/backup-1.0-SNAPSHOT.jar
```

The repository does not include a Maven wrapper. Maven may not be on the shell PATH. IntelliJ IDEA can bundle Maven; on this
macOS environment it is available at
`/Applications/IntelliJ IDEA.app/Contents/plugins/maven-plugin/lib/maven3/bin/mvn`.
Use `mvn test` as the standard test command (or invoke an installed Maven by its
absolute path). To compile and run without Maven:

```bash
mkdir -p target/classes
javac --release 21 -d target/classes $(find src/main/java -name '*.java' -print)
java -cp target/classes com.backup.BackupApplication
```

Direct application compilation does not run the JUnit tests. See
[本地功能验收](doc/本地功能验收.md) for disposable local demonstrations and the
platform-dependent checks to record separately.

## Selection and retention

Path globs use source-relative paths with `/`; name globs match only the basename.
Separate alternatives with `;` (OR). Different criteria combine with AND, and path
exclusions take precedence. Globs are case sensitive; extensions ignore case and
an optional leading dot. Modification-time and byte-size bounds are inclusive.
With extensions empty, the extensionless option unchecked means no type restriction;
checked means only extensionless files. With extensions set, checking it additionally
includes extensionless files. Dotfiles and names ending in a dot are extensionless.

A group contains sequence 1 (full) through at most sequence 10 (nine increments).
The eleventh backup starts another full group. `keepGroups=0` disables deletion;
it still starts a new group after ten snapshots. Positive values retain the newest
N whole valid groups for that task, while protecting the latest snapshot's group.
Unknown files, invalid chains and unsafe catalog records are preserved with warnings.
Retention never infers ownership from a ZIP/GZIP extension.

A task is identified by the canonical source path, backup name and normalized filter.
The destination separates its stored baseline. A new `BackupService` or restarted
application begins with a full backup. Changing compression, encryption, an active
encryption password, or switching retention between disabled/enabled starts a full
group. Changing a positive retention count alone does not force a full backup.

Archives use `<name>-<full|incremental>-<timestamp>-<UUID><extension>[.enc]`.
Each archive has `.manifest.properties` and `.snapshot.properties` sidecars.
The latter records group ownership and parent relationships for safe retention.
The legacy `.backpack-manifest.properties` is still written for inspection, but is
not loaded to resume another process's incremental baseline.

## Restore

Restore a full archive, then each incremental archive in sequence into the same
destination. The interface restores one selected archive at a time; it does not
automatically assemble a chain. Incremental restore overlays changed entries and
does not delete files removed from the source. Keep the whole chain when copying
an incremental backup elsewhere. Access/creation times recorded in an incremental
manifest are reapplied to unchanged files only after verifying the restored baseline;
this does not require storing their contents again. Missing or different baseline files
produce warnings instead of receiving unrelated snapshot metadata.

Restoration recreates supported links and attempts permissions, owner/group,
modification/access/creation times. Unsupported filesystem operations or insufficient
privileges produce warnings; these capabilities vary by platform. For safety,
owner/group/permission changes are skipped for symbolic links with an explicit
warning because some platforms can follow the link despite no-follow options.
Destination symlinks are refused. Regular files are replaced atomically so an existing hard link
outside the restore directory does not have its contents overwritten.



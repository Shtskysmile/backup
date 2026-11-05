# Local owner filtering and FIFO repair plan

Authorized scope: incorporate owner filtering, support FIFO safely, update platform-dependent tests; no networking, devices or socket reconstruction. Preserve existing presentation/ and the v3 review report.

1. Owner rules and UI: retain the old filter constructor, add exact case-insensitive owner alternatives, pass captured owner from scanner. Test before implementation.
2. Platform assumptions: probe individual required filesystem capabilities, skip unsupported Windows cases, preserve assertions and ordinary-file tests.
3. FIFO: record type and metadata with zero payload; create staged FIFO using mkfifo -m. Never apply JDK file-opening attribute setters to pipes. Use bounded touch commands for access/modified times, report precision/creation-time limitations. Ownership uses no-follow owner/group setters. Atomic replacement protects existing target; failures warn and exclude that path from metadata application.
4. Regressions: child-process deadline for real ZIP/GZIP/encrypted FIFO roundtrips, incremental permissions, type mismatch, unavailable/nonzero tools, existing-target preservation, path protection and unsupported platform outcomes.
5. Update local-only README, verification and presentation facts; no network classes or endpoints.
6. Full Maven verification, independent review, meaningful staged commits, then authorized main integration and push (retaining development history).

## Validation ledger

- Owner filter tests RED (missing API), GREEN; scanner integration RED until owner capture wired, then GREEN.
- Platform assumptions verified on macOS, with unsupported ZIP-filesystem probes aborting individually.
- First FIFO API tests RED (missing FIFO support), then 5 cases GREEN, expanded to 12.
- Original blocking POSIX setter mutation caught by child-JVM deadline (RED), child forcibly terminated, production fix restored.
- Independent review found unchanged-increment destination permission drift; regression RED (expected0600 actual0666), fixed using staged FIFO recreation.
- Full clean package: 81 tests, 0 failures/errors/skips on macOS, compiler release21. User explicitly reaffirmed Java21 after unrelated concurrent edits briefly switched release25.
- Network package remains absent. Existing untracked presentation/ left untouched.

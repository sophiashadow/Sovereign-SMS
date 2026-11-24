# ADR-0001: Fork Fossify Messages 1.10.3 as the foundation

Date: 2026-11-24. Status: accepted for P0 (it can be reversed before any release).

**Context.** We need a maintained, GPL-compatible, offline Android SMS/MMS client. It must use the Telephony provider as its store and have an XML UI we can extend without a rewrite.

**Decision.** Fork Fossify Messages at tag 1.10.3 and keep its full history. To keep upstream merges cheap:
- Keep the upstream Kotlin package (`org.fossify.messages`) and namespace.
- Change only `applicationId` (`com.sovereignops.sms`).
- Put new code in `com.sovereignops.sms.*`.
- Reach upstream code through small, commented hooks: the receivers, the notification helper, settings and the thread menu.

The rules engine and screening are pure Kotlin with JVM tests. Android glue goes through one seam (`Sovereign.kt`).

**Alternatives.** See PROVENANCE ("Evaluated and rejected"). We also considered a Compose rewrite and rejected it as cost with no user value.

**Consequences.**
- We inherit Fossify's permissions and the Commons library (GPL-3.0). The whole app is therefore GPL-3.0.
- Upstream changes are merged by hand (`git merge` from the upstream tag). Conflicts are limited to the marked hooks.
- Upstream has no tests. Sovereign code has its own, but upstream behaviour is covered only by manual and device checks.
- Message storage stays in the system provider. If Sovereign SMS is uninstalled, the messages remain on the phone. Only Sovereign settings are lost (export the rules first).

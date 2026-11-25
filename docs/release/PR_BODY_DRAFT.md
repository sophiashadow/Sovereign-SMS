# Draft: pull request and tag for v0.1.0-alpha.2

**Not created.** This file is a draft for the owner. No pull request has been opened and no tag has been created or pushed. Both need the owner's authorization.

## Proposed pull request

- **From:** `ccr-5af7f929-w70bd7`
- **Into:** `main`
- **Title:** Sovereign SMS 0.1.0-alpha.2: fork of Fossify Messages 1.10.3 with local rules, encrypted vault, portability and release tooling

### Body

#### Summary

This brings Sovereign SMS to its first alpha release candidate on top of Fossify Messages 1.10.3. Upstream history is preserved: it is imported at `60f3557b` and merged, not squashed.

- **Slices A–G:**
  - encrypted vault and cards;
  - the bidirectional pattern engine;
  - quarantine, channels, lock and Quick Cover;
  - templates and the availability queue;
  - rules precedence, schedules, the decision log and the ledger;
  - preservation and the `.sovbak` backup;
  - workload insights.
- Daily summaries (off by default).
- Security review fixes: `MmsReceiver` unexported (F-1), the regex budget (F-2) and import size limits (F-3).
- Portability:
  - backup content v2, with newer files refused with "update the app";
  - confirm and undo on settings-pack import;
  - warnings before every unencrypted export;
  - formats documented in `docs/BACKUP_RESTORE.md`.
- Ledger realized-cash basis (vault schema 3, an additive migration). Historical days are kept as legacy days, not reinterpreted.
- Release tooling (`scripts/verify-apk.sh`, `scripts/release-artifacts.sh`) and the owner signing workflow (`docs/BUILD_AND_SIGNING.md`).
- Group-conversation stabilization (alpha.2): unrecognized addresses are never titles or destinations; no inline reply in groups; read-only Check conversations report (`docs/GROUP_CONVERSATIONS.md`).
- Shipped notices completed: IndicatorFastScroll (MIT, previously recorded wrongly as Apache-2.0), ez-vcard (BSD-2-Clause) and W3C.

#### Evidence

See `docs/release/RELEASE_NOTES_0.1.0-alpha.2.md` § Verification for exact commands, counts and checksums.

- Local JVM tests, lint, and debug and release builds were run.
- The release APK was checked with `scripts/verify-apk.sh`. It was signed with a **throwaway key** only.
- CI not run: Actions is not set up for this repository.

#### Not done (release gates)

- No device, emulator or carrier test. MMS receipt with the release APK is a gate (`docs/DEVICE_ACCEPTANCE.md`).
- Signing with the owner's permanent key.
- Dependency CVE scan: NOT_RUN (osv.dev unreachable from the build container).

#### Deferred

- Per-message tags and notes (owner decision).

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01YYUBz9BMJr1SckcVCzaMj9

## Proposed tag

Create it only after the owner has signed the release with the permanent key, run `docs/DEVICE_ACCEPTANCE.md`, and decided to release.

```sh
git tag -a v0.1.0-alpha.2 <release-commit> -m "Sovereign SMS 0.1.0-alpha.2"
git push origin v0.1.0-alpha.2      # owner only
```

`<release-commit>` is the commit named in `dist/0.1.0-alpha.2/SHA256SUMS`, the first line of that file. If code changes after device testing, raise `VERSION_CODE`, rebuild, and tag the new commit instead.

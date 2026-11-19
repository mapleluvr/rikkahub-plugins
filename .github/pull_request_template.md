# Pull Request

## Summary

<!-- Describe the user-visible behavior and why this change is needed. -->

## Scope and risk

- Modules/files affected:
- Data, persistence, permission, or compatibility impact:
- Recovery, cancellation, or rollback behavior:

## Verification

- [ ] `pnpm install --frozen-lockfile`
- [ ] `pnpm test`
- [ ] Relevant Gradle JVM or Android checks (list exact tasks and report
      counts)
- [ ] SDK/QuickJS contract checks, when applicable
- [ ] Documentation and release notes updated, when applicable

Commands and actual results:

## Review checklist

- [ ] No credentials, private data, machine-specific configuration, or
      generated build artifacts
- [ ] Tests were not removed or weakened to make the change pass
- [ ] User-visible and known limitations are documented
- [ ] For Agent recovery, database, IPC, authorization, or updater changes:
      describe the crash/cancel/revocation boundary and its evidence

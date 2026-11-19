# Contributing

Thank you for helping improve RikkaHub Plugin Host. The project is a
community-maintained derivative of RikkaHub; it is not the upstream RikkaHub
project.

## Before opening a change

1. Open or find an issue for substantial behavior changes. Security
   vulnerabilities must go through [SECURITY.md](.github/SECURITY.md), not a
   public issue.
2. Keep changes focused and describe user-visible behavior, affected modules,
   persistence or permission impact, and recovery behavior.
3. Do not edit a local upstream checkout. Maintained changes belong in this
   repository's `platform/`, `adapters/rikka/`, profile and patch inputs.
   Exported patches must replay from the pinned upstream revision into two
   clean workspaces with identical source trees.
4. Never include credentials, signing keys, private user data, local
   configuration, build output, or unredacted logs.

## Local checks

Prerequisites are listed in the README and pinned by the profile/toolchain
files. Run:

```powershell
pnpm install --frozen-lockfile
pnpm test
node tools/hostctl/cli.mjs doctor
```

For maintained source changes, prepare from the pinned upstream revision, run
the relevant Gradle JVM tests, and verify replay in a second clean output
directory. Include the actual test report identity and distinguish skipped
tests, environment failures, and controls from ordinary passes. Android
instrumentation evidence should state the device API/ABI and which device
instance was used.

Plugin SDK changes must run the SDK tests and the real QuickJS contract
verifier. Release and signing procedures are maintainer-controlled; pull
requests do not receive release secrets.

## Pull requests

Use the pull request template. Include a concise motivation, scope, risks,
tests actually run, and documentation updates. For changes to Agent recovery,
persistence, IPC, authorization, or APK updates, describe the relevant
crash/cancellation/revocation boundary and how it was verified.

By submitting a contribution, you confirm that you have the right to submit it
under the repository license. Contributions are distributed under
AGPL-3.0-only unless the repository states otherwise for a specific component.
No separate CLA is currently required.

A maintainer may request changes, defer work, or close a proposal that is
outside the supported scope. Review and response times are best effort.

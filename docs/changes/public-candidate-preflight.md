# Public Candidate Preflight

## Candidate

The sanitized publication candidate is a separate checkout at
`.work/public-candidate`. At this report's commit, it contains only `main`,
has no tags, uses the GitHub noreply address for all author and committer
identities, and has a clean worktree. The private maintenance repository,
upstream checkout, and their refs were not rewritten. No push or visibility
change was performed.

The candidate history was rewritten to remove internal maintenance material
and personal paths. Searches across every candidate commit found no personal
filesystem paths or reviewer/model markers. Gitleaks v8.25.1 scanned all 83
commits (about 1.73 MB) and reported no leaks. This is a scan result, not a
guarantee that the history contains no sensitive information; review future
history changes before publication.

## Verification

- Two clean assemblies against upstream
  `ce0e3607a8dc8a450abe5309e12d30f727a6c54a` reproduced application source tree
  `94a90fca262cc1a6ed223d52c507ac53a46bb6dc`.
- Maintenance-tool and SDK tests passed 71/71. The QuickJS CLI passed 103/103
  in the existing candidate verification record.
- On the clean replay, the app Kotlin compile task passed. The app JVM suite ran
  672 tests: 661 passed, 11 failed, and 1 was skipped.
- The pinned upstream checkout ran on the same Windows environment with a
  temporary credential-free Firebase configuration. Its app JVM suite ran 544
  tests: 532 passed, the same 11 tests failed, and the same one test was
  skipped. Failure names match exactly across both XML reports. They cover
  Windows-sensitive atomic file replacement and storage-path alias behavior.
- Markdown lint reported zero issues for the public entry documents, and local
  Markdown links in those documents resolve.
- The public PR workflow has read-only repository permissions and SHA-pinned
  actions. It has not yet run on GitHub's Ubuntu runner. The local Windows
  baseline does not establish the Ubuntu result.

The environment gate actively verifies Node.js, Java and Git. It reports pnpm,
Gradle, AGP, Kotlin, Android SDK levels and QuickJS as unverified. The profile
declares their expected versions, but Gradle distribution checksums, resolved
Maven dependency checksums and exact Android SDK revision verification remain
incomplete. README and `docs/MAINTENANCE.md` disclose these limits.

## Remaining Gates

Before changing the repository to Public, confirm the publication revision and
review GitHub rulesets and required-check settings against the configured
workflow. Run the PR workflow on a private candidate branch when repository
settings permit. Otherwise, make the first post-publication action a CI run and
keep the repository in Preview until the Ubuntu workflow succeeds. The
source-only candidate retains upstream license notices and documents the
incomplete Android dependency inventory. A signed APK release requires a
complete release-specific third-party inventory plus the separate signing-key
recovery, source provenance, anonymous download and updater checks documented
in `docs/THIRD_PARTY_LICENSES.md` and the release runbook.

The remote repository remains Private. No remote operation was performed as
part of this preflight.

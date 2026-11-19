# Maintenance

## Contribution path

1. Create a focused branch and describe the user-visible behavior change.
2. Run `pnpm install --frozen-lockfile`, `pnpm test`, and
   `node tools/hostctl/cli.mjs doctor` for maintenance-tool or SDK changes.
3. For host changes, update the profile's formal patch inputs and source map,
   then assemble and replay them against the pinned upstream revision in clean
   directories. Run the relevant JVM suites and Kotlin compilation in the
   assembled workspace.
4. Include the exact commands, test counts, and platform limitations in the
   pull request. Keep failures and skips distinct from passing checks.

The upstream checkout is an input to assembly. Develop host changes in the
maintenance source and patch series, then verify that clean assembly reproduces
the intended application tree. Do not commit generated `.work` directories.

## Security reports

Report vulnerabilities through the repository's private security reporting
channel. Do not publish exploit details, credentials, conversation content, or
personal data in an issue. Include the affected version and a minimal sanitized
reproduction when it is safe to do so.

Plugins are untrusted code. Capabilities are mediated by the host and require
user authorization. A completed source review or a successful package build is
not a security certification.

## Releases

Source publication and signed APK distribution have separate release gates.
A signed host APK requires verified signing-key recovery, matching source
provenance, install and same-signature upgrade tests, anonymous asset download,
and an end-to-end in-app update check. Release notes must state supported
versions, device scope, and known limitations.

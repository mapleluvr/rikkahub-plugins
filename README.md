# RikkaHub Plugin Host

RikkaHub Plugin Host is a community-maintained derivative of
[RikkaHub](https://github.com/rikkahub/rikkahub). It adds a replayable upstream
patch series, a QuickJS plugin platform, and a separately identified Android
host distribution. It is not an official RikkaHub project.

**Status: Developer Preview preparation.** The source and integration design are
evolving. A public APK release is not available yet. The accepted internal
host-v5 candidate is not a public download; do not install APKs from unofficial
mirrors.

## What is here

- `platform/`: Android-independent protocol, plugin archive contract, execution
  and QuickJS runtime.
- `adapters/rikka/`: RikkaHub application, persistence, Agent and UI integration.
- `patches/rikka-github/`: replayable changes against the upstream commit pinned
  in `profiles/rikka-github/profile.json`
  (`ce0e3607a8dc8a450abe5309e12d30f727a6c54a`).
- `sdk/plugin/`: TypeScript SDK and commands for creating, testing, packaging
  and publishing plugins.
- `tools/hostctl/`: input validation, upstream assembly, replay verification
  and source export.
- `contracts/wire/v1/`: plugin manifest, capabilities and runtime protocol.

## Requirements

- Git
- Node.js `24.15.0`
- pnpm `11.28.11`
- For Android builds: the JDK, Android SDK and Gradle versions declared by the
  profile/toolchain.

## Maintenance-tool and SDK checks

```powershell
pnpm install --frozen-lockfile
pnpm test
node tools/hostctl/cli.mjs doctor
```

## Rebuild the patched application source

Fetch the exact public upstream revision and replay the maintained patch inputs:

```powershell
git clone https://github.com/rikkahub/rikkahub.git .work/upstream
git -C .work/upstream checkout --detach ce0e3607a8dc8a450abe5309e12d30f727a6c54a
node tools/hostctl/cli.mjs prepare --profile rikka-github \
  --source .work/upstream --out .work/integration
node tools/hostctl/cli.mjs verify-replay --profile rikka-github \
  --source .work/upstream --expected .work/integration --out .work/replay
```

Builds require Android SDK components available to the pinned AGP toolchain. Use
the generated workspace Gradle wrapper for the application and JVM suites. See
[CONTRIBUTING](CONTRIBUTING.md) for change and verification expectations.

## Develop a plugin

Start with [the plugin SDK guide](sdk/plugin/README.md), then read the
[wire v1 contract](contracts/wire/v1/README.md). Plugins are untrusted code:
capabilities are host-mediated and require user authorization. Run SDK and real
QuickJS contract checks before distributing a package. The preview does not
promise compatibility or support for every device.

## Releases and support

The repository currently has no official public host APK release. When one is
published, its GitHub Release will include the APK, corresponding source,
provenance, release index and checksums. Use only releases attached to this
repository and verify the published checksum and signing certificate.

- Bugs and feature proposals: GitHub Issues, using the supplied forms.
- Security vulnerabilities: follow [SECURITY.md](.github/SECURITY.md); do not
  post exploit details publicly.
- Contributions: see [CONTRIBUTING](CONTRIBUTING.md) and the
  [Code of Conduct](.github/CODE_OF_CONDUCT.md).

## Source and license

The upstream source is [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub),
pinned at the commit above. Upstream notices and the repository license must
be preserved for redistributed derived works. This repository is licensed under
AGPL-3.0-only; see [LICENSE](LICENSE).

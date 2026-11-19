# Third-Party Licenses

The repository's own source, including the plugin SDK and examples, is
licensed under AGPL-3.0-only. The upstream RikkaHub license and copyright
notices are retained in [`LICENSE`](../LICENSE). The pinned upstream revision
is recorded in `profiles/rikka-github/profile.json`.

The current pnpm workspace dependency inventory reports:

| Package | Version | License | Use |
| --- | --- | --- | --- |
| `saxes` | 6.0.0 | ISC | XML parsing in JavaScript tooling |
| `xmlchars` | 2.2.0 | MIT | XML character definitions used by `saxes` |

These packages are resolved by the workspace lockfile; their source archives
are not vendored in this repository. Gradle/Maven dependencies are declared
and resolved by the Android build rather than copied into this source tree.
This source publication does not distribute an APK or those resolved binary
artifacts.

Before publishing a host APK, generate and review a complete inventory for the
exact release inputs, include all required third-party notices with the
corresponding source and binary release, and retain the inventory with release
provenance. The pnpm inventory above is not a complete Android dependency
report.

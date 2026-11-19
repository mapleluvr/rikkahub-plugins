# Security Policy

## Supported versions

The project is in Developer Preview. Security fixes are best effort; no release
or device support SLA is currently offered. When a versioned host release is
available, include the host version, plugin version, Android API level, and ABI
in a report. Update to the latest supported host release before testing a
suspected issue when safe to do so.

## Report a vulnerability privately

Please do not open a public issue for an unpatched vulnerability. Use GitHub's
**Report a vulnerability** feature on this repository when available. If the
private channel is unavailable, contact the repository owner through the
verified contact details on the GitHub profile. Include "RikkaHub Plugin Host
security report" in the subject.

Include impact, affected versions, prerequisites, a minimal reproduction, and
redacted logs. Do not include API tokens, private conversation data, signing
material, or third-party data. The maintainer will acknowledge and coordinate
disclosure on a best-effort basis; no response-time SLA is promised.

## Security boundaries

Plugins are untrusted code. They execute in an isolated QuickJS service and
receive only host-mediated capabilities explicitly authorized by the user.
This boundary reduces access; it does not make every host integration or
granted capability risk-free. Review requested permissions before approval.
A result whose execution cannot be confirmed may remain pending for recovery
rather than being replayed automatically. Stop cancels the active run; it does
not prove that an external side effect was rolled back.

Host APK updates verify package identity, version, signing certificate, asset
metadata, and digest before handing the APK to Android's system installer.
Android still requires the user to confirm installation. Do not install host
APKs from unofficial mirrors.

When a vulnerability is confirmed, the maintainer will assess impact, prepare
a fix, validate affected release paths, and publish an advisory and fixed
version when appropriate. Coordinate disclosure timing with the reporter
according to impact and fix availability.

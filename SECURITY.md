# Security policy

OpenLife is an offline Android application. Please do not include personal
images, vault files, provider URIs, logs, keys, or other user data in a report.
Synthetic reproduction steps are preferred.

## Reporting a vulnerability

Use the repository's private GitHub Security Advisory reporting channel. Do
not open a public issue for a vulnerability that could expose user data. If
the private channel is unavailable, contact the maintainers through the
private contact method configured in the repository settings and include only
the minimum technical detail needed to reproduce the problem.

The maintainers will acknowledge a report within 3 calendar days. We target a
fix or a documented mitigation plan within 30 calendar days. These are targets
rather than a promise that every issue can be fixed within that period; the
maintainers will communicate material changes in status through the private
report.

## Supported versions

Only the current repository tip and the most recent tagged release, when one
exists, are expected to receive security fixes. This project is not yet ready
for real-world use; see the warning in `README.md`.

## Scope and limits

Reports may cover the Android app, vault storage, import boundary, build
verification, and release configuration. The threat-model boundary does not
include a compromised operating system, an already-authorised accessibility
service or keyboard, a person observing the screen, or forensic recovery from
device storage. Please distinguish those limits from a defect in the app.

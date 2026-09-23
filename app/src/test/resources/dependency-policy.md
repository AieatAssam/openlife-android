# Dependency policy

This policy is enforced by `DependencyBoundaryTest`, the Gradle licence
checker, and Gradle dependency verification. It applies to the shipped
`releaseRuntimeClasspath`.

## Denylist

The following coordinate prefixes are prohibited in a shipped runtime:

```text
com.google.firebase
com.google.android.datatransport
com.google.android.gms:play-services-measurement
io.sentry
com.bugsnag
com.squareup.okhttp3
io.ktor
com.android.volley
com.google.android.gms:play-services-ads
org.apache.httpcomponents
retrofit2
```

## Time-boxed exception

ML Kit currently brings the transport and Firebase support coordinates above
transitively. They are one exception record, `mlkit-transitive-transport`,
valid only until P2-05. The exception is not permission for a direct
dependency and must be removed when ML Kit is removed.

```text
id: mlkit-transitive-transport
coordinates: com.google.android.datatransport, com.google.firebase
expires: P2-05
reason: ML Kit's current bundled recognizer transitively resolves these support classes.
origin: com.google.mlkit:text-recognition
path: transitive-only
```

## Allowlisted licences

Runtime dependency licences are limited to Apache-2.0, MIT, BSD-2-Clause,
BSD-3-Clause, EPL-1.0, EPL-2.0, MPL-2.0, ISC, Unicode-DFS-2016, and CC0-1.0.
The machine-readable Gradle checker input is
[`config/allowed-licenses.json`](../config/allowed-licenses.json).

The exact labels accepted by the boundary test, including the two ADR-backed
labels used by the current ML Kit/platform baseline, are:

```text
Apache-2.0
Apache License, Version 2.0
Android Software Development Kit License
ML Kit Terms of Service
MIT
BSD-2-Clause
BSD-3-Clause
EPL-1.0
EPL-2.0
MPL-2.0
ISC
Unicode-DFS-2016
CC0-1.0
```

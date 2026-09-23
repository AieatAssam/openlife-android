# Permission rationale

This inventory describes the merged release manifest produced by
`./gradlew :app:processReleaseManifest`. OpenLife requests no dangerous or
runtime user permissions.

| Manifest entry | Why it is present | Denial path |
| --- | --- | --- |
| `org.openlife.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (`signature`) | AndroidX uses this app-private signature permission to protect a non-exported dynamic receiver boundary. It is not a user capability and is not granted to another ordinary app. | No runtime denial path; the system enforces the signature boundary. |
| `android.permission.BIND_JOB_SERVICE` on an AndroidX-internal service | Android framework protection for a service component. It is not a `<uses-permission>` request made by OpenLife and cannot be granted by a normal user. | No runtime denial path; the system controls service binding. |

The source manifest contains merge-removal markers for `INTERNET` and
`ACCESS_NETWORK_STATE` because a pinned transitive dependency names transport
classes. The release manifest contains neither permission. It also contains no
`READ_MEDIA_*`, `READ_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE`,
`READ_CONTACTS`, `READ_SMS`, `BIND_NOTIFICATION_LISTENER_SERVICE`,
`BIND_ACCESSIBILITY_SERVICE`, or `CAMERA` permission.

The current intake uses the Android share grant or Photo Picker selection, so
there is no broad storage permission to deny. A future reminder/boot feature
must separately justify `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, and
`SCHEDULE_EXACT_ALARM` in P4-04 before adding any of them.

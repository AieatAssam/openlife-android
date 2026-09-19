# Release signing runbook

OpenLife release artefacts must never be debug-signed. The release Gradle
variant is unsigned when all four signing variables are absent and uses the
owner-provided upload key only when all four are present:

- `OPENLIFE_KEYSTORE_B64`
- `OPENLIFE_KEYSTORE_PASSWORD`
- `OPENLIFE_KEY_ALIAS`
- `OPENLIFE_KEY_PASSWORD`

The build fails closed when only part of the set is supplied. Secret values
are read from the environment and are not printed. The temporary decoded
keystore is removed when the Gradle build finishes.

## Generate the owner key offline

Run these commands on an owner-controlled machine. Keep the keystore and
passwords offline; do not commit them or place them in the repository.

```bash
keytool -genkeypair \
  -alias openlife-upload \
  -keyalg RSA -keysize 4096 -sigalg SHA256withRSA \
  -validity 9125 \
  -keystore openlife-upload.jks
```

An EC P-256 key is also acceptable when the target distribution has been
checked for compatibility:

```bash
keytool -genkeypair \
  -alias openlife-upload \
  -keyalg EC -groupname secp256r1 -sigalg SHA256withECDSA \
  -validity 9125 \
  -keystore openlife-upload.jks
```

The 9,125-day validity is approximately 25 years. Record the alias and
passwords in the owner's offline secret store. Base64-encode the keystore for
the CI secret without putting the result in shell history where possible:

```bash
base64 -w 0 openlife-upload.jks
```

## Configure CI and verify

Add the four values as repository or environment secrets with the exact names
above. On a signed build, verify the certificate before publishing:

```bash
apksigner verify --verbose --print-certs app-release.apk
```

The release workflow records the certificate SHA-256 digest from the
`apksigner` output (the line is labelled `Signer #1` or `V3.0 Signer`,
depending on the Android build-tools version) in
`CERTIFICATE-FINGERPRINT-SHA256.txt` and includes it in `SHA-256SUMS`.
Compare that value with the published README fingerprint and preserve the
workflow run URL and release assets as provenance.

## Rotation and owner action OA-1

Generate a new offline key before the current key expires or if compromise is
suspected. Update all four CI secrets together, build a tagged release, and
verify the new fingerprint. Do not overwrite historical release records.
Before the first public tag (OA-1), the owner must verify the signed CI
artefact and replace the README placeholder with its SHA-256 certificate
fingerprint. If a signing key is compromised, stop publishing, revoke or
retire the affected distribution credential where supported, document the
incident, and publish a new signed release with the rotated key.

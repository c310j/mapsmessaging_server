TITLE: fix(license): self-heal a stale *_installed marker whose key is unloadable

BASE: Maps-Messaging/mapsmessaging_server : development
HEAD: c310j:osi/fix-license-recreate-marker   (1 commit)

Independent of the other osi/* branches - touches only LicenseController and
.gitignore, no overlap.

---

## Commit -- fix(license): self-heal a stale *_installed marker whose key is unloadable

### Symptom

A MAPS broker (community edition, license server auto-provisioning) starts
cleanly, logs no errors at any level, and binds **zero** network transports
-- no MQTT, no TCP, no REST, nothing. `startAll()` iterates an empty
endpoint-manager map because `NetworkManager` never registered any: every
protocol was disabled by `FeatureManager`, which had zero licensed features
to enable, because `LicenseController` silently failed to (re)install the
license.

This reproduces reliably in any environment where the license key's storage
and the `*_installed` marker file live in different places with different
lifetimes -- concretely, the officially-supported `maps-drone` Debian image:
the key is stored via `java.util.prefs`, which defaults to a path under the
service user's home (`/opt/maps/.java/.userPrefs/...`, part of a Docker
container's writable layer -- wiped on every recreate), while
`licenses/license_community.lic_installed` lives under the data directory
(a persistent volume -- survives recreate). Any `docker compose up
--force-recreate` / `down && up` cycle on such a setup loses the key while
the marker survives.

### Root cause

`LicenseController.installLicenses()` skips installing a freshly-fetched
`.lic` file whenever the corresponding `.lic_installed` marker file merely
**exists** -- it never checks whether the license that marker once pointed
to is actually still loadable:

```java
File installedFile = new File(licenseDir, LICENSE_KEY + edition + ".lic_installed");
if (!installedFile.exists()) {
  processLicenseFile(licenseFile, edition, installedFile);
}
```

Separately, `loadInstalledLicenses()` already has logic to drop a marker
whose license turns out to be expired/invalid (`processLicense(...)`
returning `false`) -- but that path is never reached when `manager.load()`
itself **throws** (which is what happens when the underlying key material is
missing, as opposed to present-but-expired). The constructor's own recovery
path makes this worse, not better:

```java
if (licenses.isEmpty()) {
  boolean fetched = fetchLicenseFromServer(licenseDir, uniqueId, serverUUID);
  ...
  installLicenses(licenseDir);              // <-- still sees the stale marker, no-ops
  licenses.addAll(loadInstalledLicenses(licenseDir));  // <-- same throw, same catch, same no-op
}
```

A fresh `.lic` file gets fetched and saved to disk, but is never installed,
because the stale marker from the lost key is still sitting there blocking
`installLicenses()`. End state: `licenses` stays permanently empty,
`getFeatureManager()` returns a `FeatureManager` with nothing enabled, and
the broker binds no transports -- with no error surfaced anywhere above
`LicenseController`'s own log line ("Failed to load license edition
community"), which is not fatal-looking on its own.

### Fix

Treat a `manager.load()` failure exactly like the existing expired/invalid
case: delete the stale marker and `manager.uninstall()`, so the *next*
`installLicenses()` pass can actually (re)install a freshly fetched license
instead of skipping it forever.

```java
License license = null;
try {
  license = manager.load();
} catch (IllegalArgumentException | LicenseManagementException e) {
  logger.log(ServerLogMessages.LICENSE_FAILED_LOADING, edition, e);
}

if (!processLicense(license, licenseList)) {
  logger.log(ServerLogMessages.LICENSE_UNINSTALLING, edition);
  if (!installedFile.delete()) {
    logger.log(ServerLogMessages.LICENSE_FAILED_DELETE_FILE, installedFile.getAbsolutePath());
  }
  manager.uninstall();
}
```

No behaviour change for the healthy path (key loads fine, license valid);
the only change is that an unloadable marker now self-heals on the very next
`installLicenses()` pass within the same constructor run, instead of wedging
the broker into a silent zero-transport state indefinitely.

Also fixes `.gitignore` to exclude the self-signed test certs/keystores the
`config-self-signed-certs` build step generates at the repo root
(`ca.jks`/`ca.pem`/`my-keystore.jks`/`my-truststore.jks`/`root.jks`/
`root.pem`/`server.pem`) -- unrelated to the license bug, caught while
preparing this patch.

### Not fixed here (design decision for maintainers, noted for the record)

The deeper structural issue -- license key storage (`java.util.prefs`) and
the install marker (`MAPS_DATA`) having different default persistence
lifetimes at all -- is still there; this patch makes the failure mode
recoverable rather than permanent, but a key that's lost and successfully
refetched still costs a license-server round trip on every recreate. Two
options, either of which would remove the need for that round trip:

1. Default the `java.util.prefs` root under `MAPS_DATA` (packaging-level,
   `startDocker.sh`/similar -- not in this repo).
2. Have `LicenseController` persist/verify the key itself rather than via
   `java.util.prefs` at all.

Happy to help with either if there's interest; this PR only fixes the silent
zero-transport failure mode, which is the actively harmful part.

## Testing

`mvn -o clean compile` -- BUILD SUCCESS. No existing unit tests cover
`LicenseController` (none added here -- it's built on `java.util.prefs` +
`truelicense`, both awkward to unit test in isolation; the fix was instead
verified end-to-end).

Verified against a real Docker recreate cycle (`repmus-estonia-demo`,
`maps-drone`-based image): with the key deleted and the marker left in
place -- reproducing the exact failure condition -- a
`docker compose up --force-recreate` on **unpatched** `LicenseController`
left the broker listening on nothing but the JMX exporter port; on
**patched** `LicenseController`, the same recreate self-healed (one
license-server round trip, ~15s) and came back with all configured
transports bound (STOMP/WS, MQTT, REST, etc.), confirmed via `ss -tlnp`
inside the container both times.

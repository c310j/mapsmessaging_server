TITLE: feat(state): CONTACT twin type + TAK tasking support for external sensor detections

BASE: Maps-Messaging/mapsmessaging_server : development
HEAD: c310j:osi/contact-twin-tak-tasking   (1 commit)

Independent of the other osi/* branches (aggregator-config-fixes,
fix-protocol-name-trim, cot-tak-tls) - touches TwinManager/TAK only, no
overlap.

---

## Commit -- feat(state): add CONTACT twin type and TAK tasking support

Adds a third `TwinType` alongside `DRONE`/`GROUND_CONTROL`: `CONTACT`, for
twins that represent an externally-sourced sensor detection (a sonar/camera
mine-like-contact, an acoustic hit, anything an operator needs to see and
task in TAK) rather than a live platform. No detection-format-specific code
lives in this repo - that stays out-of-tree in a `StateMessageAdapter` SPI
jar (our internal MILCO connector is the first consumer; the pattern is
generic). This PR is the minimal core-side support that any such adapter
needs to actually get its twins onto a TAK map.

  * `TwinType`: new `CONTACT` value.
  * `TwinManager.purgeExpiredTwins()`: skips `CONTACT` twins. Live telemetry
    twins age out because their source stops sending; a CONTACT twin's
    absence of updates doesn't mean it stopped being a real-world contact -
    it stays until its source explicitly removes it (or is deleted upstream
    of TwinManager).
  * `TakEventMapper`: CoT mapping for CONTACT twins -
    - MIL-STD-2525 atom type `a-u-U` (unknown affiliation / underwater
      battle-dimension - "needs investigation", not a confirmed threat)
    - a configurable stale window (currently 1h, vs. the 30s default for
      live telemetry) so a marker doesn't flicker between updates
    - `<archive/>` so the receiving TAK client persists it locally
    - a red marker tint (`<color argb="-65536"/>`)
    - tasking-oriented `<remarks>` (requested task/specialization, status,
      probability/depth/size/source, all pulled from the twin's generic
      `attributes` map - no MILCO-specific fields anywhere in core)
    - `mapRemoval()` now always includes a `<point>` (built from the twin's
      last known position) - TAK Server's CoT parser
      (`SubmissionService.processNextEvent` / `CotEventContainer.getLat`)
      throws an NPE on any event without one, removals included; this was
      silently breaking every CONTACT auto-purge before the CONTACT-purge
      exclusion above made it moot, but it's a real latent bug for any twin
      type that gets removed without a known position workaround.
  * `TakXmlSerialiser` / `TakDetail` / `TakPrecisionLocation`: plumbing for
    the new `archive`, `color`, and `precisionlocation/geopointsrc`
    attributes used above.

No new config surface - CONTACT is just another `TwinType` value, and the
above is all keyed off it.

## Testing

`mvn compile` clean. Exercised end-to-end against a live TAK Server 5.7 and
WebTAK: an out-of-tree SPI adapter replayed 35 sonar contact detections from
a FeatureLab export over MQTT -> TwinManager -> this CoT mapping -> TAK
Server -> WebTAK, filtered to a configurable minimum-probability threshold.
Confirmed markers render with correct type/remarks/tasking info, persist
across the adapter's normal update cycle, and (pre CONTACT-purge-exclusion)
that a purge-triggered removal round-trips through TAK without the
`cot2protoBuf found message without a point!` NPE.

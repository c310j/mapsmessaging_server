TITLE: AggregatorManager (and 5 other managers) fail runtime JSON-schema validation of their own default config

TYPE: Issue (not a patch -- needs a design decision on where the fix belongs)

---

## Summary

With `MessageDaemon` config-schema validation active, several manager configs
that are shipped and documented as working do not validate against the schema
`RuntimeJsonSchemaGenerator` derives from their DTO classes. Validation errors set
`hasErrors`, and `MessageDaemon.java:267`

```java
if (isHasErrors() && isExitOnConfigError()) { ... System.exit(1); }
```

turns that into a hard startup failure whenever an operator sets
`exitOnConfigError: true` (it defaults to `false` in `MessageDaemon.yaml`, so
today this is silent -- errors are logged and ignored).

## Two mismatch classes

1. **Envelope shape.** `RuntimeJsonSchemaGenerator` generates a *flat* schema from
   the DTO (e.g. `AggregatorManagerConfigDTO` -> `{ aggregatorConfigList: [...] }`),
   but the instance actually validated is the on-disk document, which is wrapped
   in the `data:` envelope:

   ```yaml
   AggregatorManager:
     data:
       aggregatorConfigList: [ ... ]
   ```

   The generated schema has no `data` property (and, if `additionalProperties`
   is false, rejects it). Affects the 6 managers that use the `data:` envelope:
   `AggregatorManager`, `DestinationManager`, `DeviceManager`,
   `NetworkConnectionManager`, `NetworkManager`, `TransformationManager`.

2. **Spurious `required`.** Fields that have defaults and are routinely omitted --
   `schemaLoadingVersion`, `type` -- are emitted as `required`, so a minimal valid
   config is reported invalid.

## Impact

* Today: noise in the log, `hasErrors` set, no functional effect (default
  `exitOnConfigError: false`).
* If an operator turns on `exitOnConfigError` (a reasonable thing to want): the
  server refuses to start on a config that is actually correct.

## Possible directions (for maintainers to choose)

* Generate the schema against the enveloped shape (wrap in `data` for the
  envelope managers), or validate the unwrapped `data:` subtree.
* Honour DTO defaults / `@Nullable` so defaulted fields are not `required`.
* Alternatively, downgrade generated-schema mismatches to warnings that never
  feed `hasErrors`.

Happy to help with whichever direction you prefer.

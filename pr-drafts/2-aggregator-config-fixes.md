TITLE: fix(config): make jsonmapper / jsonmutate transformer configs usable from YAML

BASE: Maps-Messaging/mapsmessaging_server : development
HEAD: c310j:osi/aggregator-config-fixes   (1 commit, 5 new tests)

---

Two independent breakages when a `jsonmapper` or `jsonmutate` transformation is
declared in an `AggregatorManager` (or link) `transformer:` chain from YAML.

## 1. JsonMapperTransformationConfig NPEs without an undocumented wrapper

```java
ConfigurationProperties jsonMapper = (ConfigurationProperties) props.get("jsonMapper");
Object rawOperations = jsonMapper.get("operations");   // NPE when there is no jsonMapper: sub-map
```

`JsonMutateTransformationConfig`, `GeoHashResolverTransformationConfig`, etc. all
read their fields directly off the entry. `jsonmapper` alone required a
`jsonMapper: { operations: [...] }` nesting, and dereferenced it with no null
check, so the natural

```yaml
- type: jsonmapper
  operations:
    - { from: a.b, to: x.y }
```

threw in the constructor.

**Fix:** read `operations:` directly off the entry; fall back to
`jsonMapper.operations` only for the legacy nested form; null-guard.

## 2. JsonMutateTransformationConfig rejects string SET values

```java
operationDto.setValue(JsonParser.parseString(value));   // strict
```

A `SET` op's `value:` from YAML is normally a bare scalar. `JsonParser.parseString`
is strict, so `value: NEW` or `value: http://example/...` (the `:` breaks it)
throws `MalformedJsonException` and string SET values are impossible.

**Fix:** parse as JSON when the value *is* valid JSON (number / boolean / null /
quoted string / object / array); treat it as a literal string otherwise.

## Tests

5 new cases in `TransformationConfigFactoryTest` (10/10 pass):
direct `operations:` on jsonmapper, the legacy nested form still works, jsonmapper
with no operations doesn't throw, jsonmutate SET accepts bare strings / a URL,
jsonmutate SET still parses numeric & boolean scalars.

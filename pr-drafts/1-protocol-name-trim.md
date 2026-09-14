TITLE: fix(network): trim the configured protocol name in ProtocolFactory

BASE: Maps-Messaging/mapsmessaging_server : development
HEAD: c310j:osi/fix-protocol-name-trim   (1 commit)

---

## What

`ProtocolFactory(String protocols)` does `this.protocols = protocols.toLowerCase()`.
`toLowerCase()` does not strip whitespace, so a listener whose `protocol:` value is
read from a CRLF-terminated `NetworkManager.yaml` arrives as `"cot\r"` (or `"cot "`).

`getBoundedProtocol()` then compares with an exact match
(`ProtocolImplFactory.matches` → `name.equalsIgnoreCase(protocols)`), which never
matches a single-protocol listener, so the accept path silently falls back to
byte-level protocol detection. For any protocol whose `Detection` is a no-op /
always-false that means every inbound connection is rejected with
`"No known protocol detected"`.

## Change

```java
-    this.protocols = protocols.toLowerCase();
+    this.protocols = protocols.trim().toLowerCase();
```

One line, plus a comment. `detect()`'s `contains()` checks are unaffected.

## Risk

Minimal — it can only make a previously non-matching config match. Comma-lists
(`"stomp,ws"`) are unchanged (only leading/trailing whitespace is trimmed).

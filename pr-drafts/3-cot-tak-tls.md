TITLE: feat: CoT XML passthrough listener + optional TAK-server mutual TLS

BASE: Maps-Messaging/mapsmessaging_server : development
HEAD: c310j:osi/cot-tak-tls   (2 commits)

Depends on: nothing hard, but pairs naturally with
c310j:osi/fix-protocol-name-trim (CRLF-config robustness).
Can be split into two PRs -- commit 1 (TAK TLS) is a prerequisite for commit 2's
mTLS forward.

---

## Commit 1 -- feat(state/tak): optional mutual TLS on the TwinManager -> TAK publisher

Opt-in mutual TLS on the existing `state.drone.tak.*` CoT publisher. Plain TCP
stays the default and is unchanged.

  * `TakProtocolDTO`: `tlsEnabled` / `tlsContext` / `keyStore` / `trustStore`
  * `state.config.TwinManagerConfig`: parse the new `tak.*` keys
  * `TakSocketConnection`: new `(host, port, SSLSocketFactory)` ctor
  * `TakTwinObserver`: build the SSLContext via `SslHelper.createContext`
  * `StateLogMessages.STATE_MANAGER_TAK_TLS_CONTEXT_FAILED`

```yaml
tak:
  tlsEnabled: true
  tlsContext: "TLSv1.2"
  keyStore:   { type: PKCS12, managerFactory: SunX509, path: ..., passphrase: ... }
  trustStore: { type: PKCS12, managerFactory: SunX509, path: ..., passphrase: ... }
```

## Commit 2 -- feat(protocol): cot -- raw Cursor-on-Target XML passthrough listener

A first-class `cot` protocol (this had been maintained out-of-tree as an SPI jar +
patch set). A client opens a `tcp://` or `ssl://` connection and streams a raw,
unframed sequence of CoT `<event>` XML documents; each complete document is
republished byte-for-byte on `/tak/cot/inbound` and, when `takHostname` is set,
relayed to a real TAK server over its own socket (plain, or mutual TLS via
commit 1).

  * `network.protocol.impl.cot.CotProtocol` / `CotProtocolFactory`
  * `config.protocol.impl.CotProtocolConfig` + `dto...impl.CotProtocolConfigDTO`
    (`takHostname` / `takPort` / `takTlsEnabled` / `takTlsContext` /
     `takKeyStore` / `takTrustStore` -- flat keys on the listener entry)
  * `EndPointConfigFactory`: `case "cot"`
  * `ProtocolImplFactory` SPI registration

`CotProtocolFactory` uses `MultiByteArrayDetection` (`<?xml` / `<event`) rather
than `NoOpDetection` so the listener is recognised on a plain `tcp://` bind, not
only behind `ssl://` where the TLS handshake substitutes for byte detection.

The session authenticates as the built-in anonymous identity (same as the
anonymous STOMP interface). Logging is via SLF4J for now -- no new
`ServerLogMessages` constants.

```yaml
- name: "TAK CoT ingest"
  url: "ssl://0.0.0.0:8089/"        # or tcp://
  protocol: cot
  auth: anon
  takHostname: takserver
  takPort: 8089
  takTlsEnabled: true
  takKeyStore:   { type: PKCS12, managerFactory: SunX509, path: ..., passphrase: ... }
  takTrustStore: { type: PKCS12, managerFactory: SunX509, path: ..., passphrase: ... }
```

## Testing

`mvn compile` clean. Exercised end-to-end against a live TAK Server 5.7: replayed
a captured RHIB track into an `ssl://` cot listener with a renamed uid + offset
coordinates; it appears as a distinct contact in TAK's `latestcot` alongside the
live track, and is echoed on `/tak/cot/inbound`.

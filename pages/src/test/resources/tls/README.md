# Test certificates

Throwaway keys for `TlsMaterialTest` and `PublicTlsTest`, valid for a century and trusted by nothing
outside these tests. Never use them anywhere else.

| File | What it is |
|---|---|
| `server-chain.pem`, `server.key` | A server certificate for `*.sites.test`, `localhost` and `127.0.0.1`, followed by the CA that signed it; its PKCS#8 key |
| `server-ca.pem` | That CA alone, which a test client trusts |
| `server-pkcs1.key` | The same server key in the older PKCS#1 encoding, which is refused with a hint |
| `stranger.key` | A key that belongs to no certificate here |
| `client-ca.pem` | The CA the server requires client certificates from, standing in for a CDN's origin-pull CA |
| `client.p12` | A client certificate signed by it, with its key; password `changeit` |

# MASQUE engine notice

The Android native binding and Go MASQUE engine used by this add-on are built from the open-source Usque project by Diniboy1123 and contributors.

- Upstream project: https://github.com/Diniboy1123/usque
- Android binding source: https://github.com/Abobo7/usque-android
- Android binding source revision: `1bc50c004b99e6df42fc17f4f93320d782020dde`
- Protocol: Cloudflare WARP over MASQUE / HTTP/3 CONNECT-IP
- The upstream usque source is licensed under MIT; see `USQUE_LICENSE.md`.
- This is an unofficial Cloudflare-compatible client and is not affiliated with Cloudflare.

GitHub Actions checks out the pinned source revision and applies `usque-android-socket-protector.patch`. This adds a gomobile callback to Android `VpnService.protect(fd)` and applies it before QUIC connects. The generated AAR supplies the Java binding and four ABI libraries; the old JAR and two-ABI native libraries remain only as source-tree references.

SHA-256:

```text
83d885610a3ad080e9539a3799f8cd0f7370ee2ba0a8374f37ed9150c2ecbfb5  usque-classes.jar
465c5c5213da0501d96a5cb2e1fd862df1cd9432dbe11f4db1f134d8d3aa05a8  arm64-v8a/libgojni.so
a931ef96b8a248788de51f2771d5f2396b9c0097f4a2fa4617d1d6c133b071d3  armeabi-v7a/libgojni.so
```

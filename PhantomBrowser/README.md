# Phantom Browser

A privacy-first Android browser with SOCKS5 proxy routing, ad blocking, and encrypted password vault.

## Architecture

```
Browser WebView
      ↓
 Local HTTP Bridge (127.0.0.1:8118)
      ↓
 SOCKS5H handshake (DNS resolved by proxy, not locally)
      ↓
 SOCKS5 upstream server
      ↓
   Internet
```

## Features

- **SOCKS5H Proxy** — all traffic tunnelled, DNS resolved by proxy (no leaks)
- **Connection Chain View** — see each hop, exit IP, and latency live
- **Ad Blocker** — URL-level + JS DOM injection blocking
- **WebRTC Blocked** — no IP leak via WebRTC
- **QUIC Disabled** — no protocol bypass
- **Password Vault** — AES-encrypted on-device storage via EncryptedStorage
- **Downloads** — saved to local device storage (your "local cloud")
- **Canvas Fingerprint Noise** — minor randomisation injected
- **Incognito WebView** — no cookies, no history, no cache

## Project Structure

```
PhantomBrowser/
├── src/
│   ├── App.tsx
│   ├── screens/
│   │   ├── BrowserScreen.tsx   # WebView + URL bar + status
│   │   ├── ProxyScreen.tsx     # SOCKS5 config + chain visualiser
│   │   ├── PasswordScreen.tsx  # Encrypted password vault
│   │   ├── DownloadsScreen.tsx # File manager + local sync
│   │   └── SettingsScreen.tsx
│   └── services/
│       ├── ProxyContext.tsx    # Global proxy state
│       └── AdBlocker.ts       # URL + JS ad blocking
└── android/
    └── app/src/main/java/com/phantombrowser/
        ├── proxy/
        │   └── ProxyBridgeModule.java   # Native SOCKS5H bridge
        └── modules/
            └── PhantomPackage.java
```

## Building

### GitHub Actions (automatic)

Push to `main` → workflow builds release APK → download from Actions artifacts.

### Local

```bash
npm install
cd android && ./gradlew assembleRelease
```

APK output: `android/app/build/outputs/apk/release/app-release.apk`

## SOCKS5H Protocol

The native bridge (`ProxyBridgeModule.java`) implements the same approach as the original Python `local_proxy.py`:

1. WebView sends HTTP CONNECT to `127.0.0.1:8118`
2. Bridge does full SOCKS5 handshake with upstream, sending **hostname** (not IP) as ATYP=0x03
3. Proxy resolves DNS — your device never touches a DNS server for browsing traffic
4. Bidirectional TCP pipe established

## Requirements

- Android 7.0+ (minSdk 24)
- React Native 0.73
- Java 17 for build

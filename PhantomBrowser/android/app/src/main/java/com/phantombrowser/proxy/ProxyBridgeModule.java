package com.phantombrowser.proxy;

import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ProxyBridgeModule
 *
 * Implements the same architecture as local_proxy.py from SOCKS5-Termux_v3:
 *   Browser WebView → HTTP/CONNECT on 127.0.0.1:8118
 *                   → SOCKS5H handshake to upstream proxy
 *                   → Internet
 *
 * SOCKS5H: hostname sent to proxy for resolution (no local DNS leak).
 */
public class ProxyBridgeModule extends ReactContextBaseJavaModule {
    private static final String TAG = "ProxyBridge";
    private static final int BUFFER_SIZE = 8192;
    private static final int SOCKS5_CONNECT_TIMEOUT_MS = 30_000;

    private ServerSocket serverSocket;
    private ExecutorService threadPool;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Upstream proxy config (set on startBridge)
    private String upstreamHost;
    private int    upstreamPort;
    private String upstreamUser;
    private String upstreamPass;

    public ProxyBridgeModule(ReactApplicationContext ctx) {
        super(ctx);
    }

    @Override
    public String getName() { return "ProxyBridgeModule"; }

    // ─────────────────────────────────────────────────────────────────────────
    // JS-callable methods
    // ─────────────────────────────────────────────────────────────────────────

    @ReactMethod
    public void startBridge(String host, int port, String user, String pass,
                            int localPort, Promise promise) {
        if (running.get()) {
            // Already running — stop old one first
            stopBridgeInternal();
        }
        upstreamHost = host;
        upstreamPort = port;
        upstreamUser = user;
        upstreamPass = pass;

        // 1. Verify connectivity & get exit IP
        new Thread(() -> {
            try {
                long start = System.currentTimeMillis();
                String exitIP = fetchIPThroughProxy(host, port, user, pass);
                long latency = System.currentTimeMillis() - start;

                if (exitIP == null) {
                    WritableMap err = Arguments.createMap();
                    err.putBoolean("success", false);
                    err.putString("error", "Proxy verification failed — could not fetch exit IP");
                    promise.resolve(err);
                    return;
                }

                // 2. Start the local bridge
                startLocalBridge(localPort);

                WritableMap result = Arguments.createMap();
                result.putBoolean("success", true);
                result.putString("exitIP", exitIP);
                result.putDouble("latency", latency);
                promise.resolve(result);

            } catch (Exception e) {
                WritableMap err = Arguments.createMap();
                err.putBoolean("success", false);
                err.putString("error", e.getMessage());
                promise.resolve(err);
            }
        }).start();
    }

    @ReactMethod
    public void stopBridge(Promise promise) {
        stopBridgeInternal();
        promise.resolve(null);
    }

    @ReactMethod
    public void checkIP(String host, int port, String user, String pass, Promise promise) {
        new Thread(() -> {
            try {
                long start = System.currentTimeMillis();
                String ip = fetchIPThroughProxy(host, port, user, pass);
                long latency = System.currentTimeMillis() - start;
                WritableMap r = Arguments.createMap();
                r.putString("exitIP", ip != null ? ip : "unknown");
                r.putDouble("latency", latency);
                promise.resolve(r);
            } catch (Exception e) {
                promise.reject("CHECK_IP_FAILED", e.getMessage());
            }
        }).start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal: start the HTTP→SOCKS5H bridge server
    // ─────────────────────────────────────────────────────────────────────────

    private void startLocalBridge(int localPort) throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress("127.0.0.1", localPort));
        running.set(true);
        threadPool = Executors.newCachedThreadPool();

        threadPool.submit(() -> {
            Log.i(TAG, "Local bridge started on 127.0.0.1:" + localPort);
            while (running.get()) {
                try {
                    Socket client = serverSocket.accept();
                    threadPool.submit(() -> handleClient(client));
                } catch (IOException e) {
                    if (running.get()) Log.w(TAG, "Accept error: " + e.getMessage());
                }
            }
        });
    }

    private void stopBridgeInternal() {
        running.set(false);
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        if (threadPool != null) threadPool.shutdownNow();
        Log.i(TAG, "Bridge stopped");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Handle one client connection (same logic as local_proxy.py handle_client)
    // ─────────────────────────────────────────────────────────────────────────

    private void handleClient(Socket client) {
        Socket remote = null;
        try {
            client.setSoTimeout(60_000);
            InputStream cin = client.getInputStream();
            OutputStream cout = client.getOutputStream();

            // Read first request line
            byte[] buf = new byte[BUFFER_SIZE];
            int n = cin.read(buf);
            if (n <= 0) return;
            String request = new String(buf, 0, n, StandardCharsets.ISO_8859_1);

            if (request.startsWith("CONNECT")) {
                // HTTPS CONNECT tunnel
                String[] parts = request.split(" ");
                String hostPort = parts[1];
                String[] hp = hostPort.split(":");
                String targetHost = hp[0];
                int targetPort = hp.length > 1 ? Integer.parseInt(hp[1]) : 443;

                remote = connectViaSocks5h(targetHost, targetPort);
                cout.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes());
                cout.flush();

            } else {
                // Plain HTTP — parse Host header
                String targetHost = "example.com";
                int targetPort = 80;
                for (String line : request.split("\r\n")) {
                    if (line.toLowerCase().startsWith("host:")) {
                        String hostVal = line.substring(5).trim();
                        if (hostVal.contains(":")) {
                            String[] parts = hostVal.split(":");
                            targetHost = parts[0];
                            targetPort = Integer.parseInt(parts[1]);
                        } else {
                            targetHost = hostVal;
                        }
                        break;
                    }
                }
                remote = connectViaSocks5h(targetHost, targetPort);
                // Forward original request bytes
                remote.getOutputStream().write(Arrays.copyOf(buf, n));
                remote.getOutputStream().flush();
            }

            // Bidirectional pipe
            Socket finalRemote = remote;
            Thread t1 = new Thread(() -> pipe(cin, getStream(finalRemote, true)));
            Thread t2 = new Thread(() -> pipe(getStream(finalRemote, false), cout));
            t1.start();
            t2.start();
            t1.join();
            t2.join();

        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (!msg.contains("closed") && !msg.contains("reset") && !msg.contains("broken pipe")) {
                Log.w(TAG, "Client handler error: " + msg);
            }
        } finally {
            closeQuietly(client);
            closeQuietly(remote);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SOCKS5H handshake (mirrors _make_socks_socket in local_proxy.py)
    // RFC 1928 + RFC 1929 with hostname sent to proxy (SOCKS5H)
    // ─────────────────────────────────────────────────────────────────────────

    private Socket connectViaSocks5h(String targetHost, int targetPort) throws IOException {
        Socket sock = new Socket();
        sock.connect(new InetSocketAddress(upstreamHost, upstreamPort), SOCKS5_CONNECT_TIMEOUT_MS);
        sock.setSoTimeout(SOCKS5_CONNECT_TIMEOUT_MS);

        InputStream in  = sock.getInputStream();
        OutputStream out = sock.getOutputStream();

        // === Auth negotiation ===
        boolean hasAuth = upstreamUser != null && !upstreamUser.isEmpty();
        if (hasAuth) {
            // Offer no-auth + username/password
            out.write(new byte[]{0x05, 0x02, 0x00, 0x02});
        } else {
            // Offer no-auth only
            out.write(new byte[]{0x05, 0x01, 0x00});
        }
        out.flush();

        byte[] authResp = readExact(in, 2);
        if (authResp[0] != 0x05) throw new IOException("SOCKS5: bad version in auth response");

        if (authResp[1] == 0x02) {
            // Username/password auth (RFC 1929)
            byte[] user = upstreamUser.getBytes(StandardCharsets.UTF_8);
            byte[] pass = upstreamPass.getBytes(StandardCharsets.UTF_8);
            byte[] authPkt = new byte[3 + user.length + pass.length];
            authPkt[0] = 0x01;
            authPkt[1] = (byte) user.length;
            System.arraycopy(user, 0, authPkt, 2, user.length);
            authPkt[2 + user.length] = (byte) pass.length;
            System.arraycopy(pass, 0, authPkt, 3 + user.length, pass.length);
            out.write(authPkt);
            out.flush();
            byte[] authAck = readExact(in, 2);
            if (authAck[1] != 0x00) throw new IOException("SOCKS5: auth rejected");
        } else if (authResp[1] != 0x00) {
            throw new IOException("SOCKS5: no acceptable auth method");
        }

        // === CONNECT request with hostname (SOCKS5H: ATYP=0x03) ===
        byte[] hostBytes = targetHost.getBytes(StandardCharsets.UTF_8);
        byte[] req = new byte[7 + hostBytes.length];
        req[0] = 0x05; // VER
        req[1] = 0x01; // CMD CONNECT
        req[2] = 0x00; // RSV
        req[3] = 0x03; // ATYP: domain name
        req[4] = (byte) hostBytes.length;
        System.arraycopy(hostBytes, 0, req, 5, hostBytes.length);
        req[5 + hostBytes.length] = (byte) ((targetPort >> 8) & 0xFF);
        req[6 + hostBytes.length] = (byte) (targetPort & 0xFF);
        out.write(req);
        out.flush();

        // === Read reply ===
        byte[] rep = readExact(in, 4);
        if (rep[0] != 0x05) throw new IOException("SOCKS5: bad version in reply");
        if (rep[1] != 0x00) throw new IOException("SOCKS5: connect failed, code=" + (rep[1] & 0xFF));

        // Skip bound address
        int atyp = rep[3] & 0xFF;
        if (atyp == 0x01) readExact(in, 4);       // IPv4
        else if (atyp == 0x03) { int len = in.read(); readExact(in, len); } // domain
        else if (atyp == 0x04) readExact(in, 16); // IPv6
        readExact(in, 2); // port

        sock.setSoTimeout(0); // back to blocking
        return sock;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IP check through proxy (for validation + re-check)
    // ─────────────────────────────────────────────────────────────────────────

    private String fetchIPThroughProxy(String host, int port, String user, String pass)
            throws IOException {
        // Save current config temporarily
        String savedHost = upstreamHost; int savedPort = upstreamPort;
        String savedUser = upstreamUser; String savedPass = upstreamPass;
        upstreamHost = host; upstreamPort = port;
        upstreamUser = user; upstreamPass = pass;

        try {
            Socket sock = connectViaSocks5h("api.ipify.org", 80);
            OutputStream out = sock.getOutputStream();
            out.write("GET / HTTP/1.0\r\nHost: api.ipify.org\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();

            InputStream in = sock.getInputStream();
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) != -1) sb.append(new String(buf, 0, n));
            sock.close();

            String body = sb.toString();
            int idx = body.indexOf("\r\n\r\n");
            return idx >= 0 ? body.substring(idx + 4).trim() : body.trim();
        } finally {
            upstreamHost = savedHost; upstreamPort = savedPort;
            upstreamUser = savedUser; upstreamPass = savedPass;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void pipe(InputStream src, OutputStream dst) {
        byte[] buf = new byte[BUFFER_SIZE];
        try {
            int n;
            while ((n = src.read(buf)) != -1) {
                dst.write(buf, 0, n);
                dst.flush();
            }
        } catch (IOException ignored) {}
    }

    private byte[] readExact(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int read = 0;
        while (read < n) {
            int r = in.read(buf, read, n - read);
            if (r < 0) throw new IOException("Stream closed during read");
            read += r;
        }
        return buf;
    }

    private InputStream getStream(Socket s, boolean input) {
        try { return input ? s.getInputStream() : null; } catch (Exception e) { return null; }
    }

    private OutputStream getStream(Socket s) {
        try { return s.getOutputStream(); } catch (Exception e) { return null; }
    }

    private void closeQuietly(Socket s) {
        if (s != null) try { s.close(); } catch (Exception ignored) {}
    }
}

package com.woltwatch;

import android.os.Build;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * TLS setup.
 *  - Android 7+ (API 24+): nothing to do here, res/xml/network_security_config.xml trusts
 *    system + user-installed + bundled (res/raw/cacerts.pem) roots.
 *  - Android 4.4-6.x: network security config does not exist, so we build the trust manager
 *    ourselves = system store (which includes user certs on these versions) OR the bundled roots,
 *    and enable TLS 1.1/1.2 (off by default before API 20).
 */
final class Tls {

    private static SSLSocketFactory cached;

    static void apply(HttpsURLConnection c) throws IOException {
        if (Build.VERSION.SDK_INT >= 24) return;
        try {
            c.setSSLSocketFactory(factory());
        } catch (Exception e) {
            throw new IOException("TLS setup failed: " + e, e);
        }
    }

    private static synchronized SSLSocketFactory factory() throws Exception {
        if (cached != null) return cached;

        final X509TrustManager system = trustManager(null);
        KeyStore bundled = KeyStore.getInstance(KeyStore.getDefaultType());
        bundled.load(null, null);
        int n = 0;
        for (X509Certificate cert : readBundledCerts()) {
            bundled.setCertificateEntry("bundled" + (n++), cert);
        }
        final X509TrustManager extra = trustManager(bundled);

        X509TrustManager composite = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String type) throws CertificateException {
                system.checkClientTrusted(chain, type);
            }

            @Override public void checkServerTrusted(X509Certificate[] chain, String type) throws CertificateException {
                try {
                    system.checkServerTrusted(chain, type);
                } catch (CertificateException first) {
                    try {
                        extra.checkServerTrusted(chain, type);
                    } catch (CertificateException second) {
                        throw first;
                    }
                }
            }

            @Override public X509Certificate[] getAcceptedIssuers() {
                X509Certificate[] a = system.getAcceptedIssuers();
                X509Certificate[] b = extra.getAcceptedIssuers();
                X509Certificate[] all = new X509Certificate[a.length + b.length];
                System.arraycopy(a, 0, all, 0, a.length);
                System.arraycopy(b, 0, all, a.length, b.length);
                return all;
            }
        };

        SSLContext ctx = SSLContext.getInstance("TLSv1.2");
        ctx.init(null, new TrustManager[]{composite}, null);
        cached = new ProtocolFixFactory(ctx.getSocketFactory());
        return cached;
    }

    private static X509TrustManager trustManager(KeyStore ks) throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) return (X509TrustManager) tm;
        }
        throw new IllegalStateException("no X509TrustManager");
    }

    private static List<X509Certificate> readBundledCerts() throws Exception {
        InputStream in = App.context().getResources().openRawResource(R.raw.cacerts);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
        in.close();
        String pem = bo.toString("UTF-8");

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Matcher m = Pattern.compile("-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----", Pattern.DOTALL).matcher(pem);
        List<X509Certificate> out = new ArrayList<X509Certificate>();
        while (m.find()) {
            try {
                byte[] der = Base64.decode(m.group(1), Base64.DEFAULT);
                out.add((X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der)));
            } catch (Exception ignored) {
                // skip a certificate this Android version cannot parse
            }
        }
        return out;
    }

    /** Enables TLS 1.1/1.2 on sockets (needed on API < 21). */
    private static class ProtocolFixFactory extends SSLSocketFactory {
        private final SSLSocketFactory d;

        ProtocolFixFactory(SSLSocketFactory delegate) { this.d = delegate; }

        private Socket fix(Socket s) {
            if (s instanceof SSLSocket) {
                ((SSLSocket) s).setEnabledProtocols(new String[]{"TLSv1.1", "TLSv1.2"});
            }
            return s;
        }

        @Override public String[] getDefaultCipherSuites() { return d.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return d.getSupportedCipherSuites(); }
        @Override public Socket createSocket(Socket s, String h, int p, boolean a) throws IOException {
            return fix(d.createSocket(s, h, p, a));
        }
        @Override public Socket createSocket(String h, int p) throws IOException { return fix(d.createSocket(h, p)); }
        @Override public Socket createSocket(String h, int p, InetAddress la, int lp) throws IOException {
            return fix(d.createSocket(h, p, la, lp));
        }
        @Override public Socket createSocket(InetAddress h, int p) throws IOException { return fix(d.createSocket(h, p)); }
        @Override public Socket createSocket(InetAddress h, int p, InetAddress la, int lp) throws IOException {
            return fix(d.createSocket(h, p, la, lp));
        }
    }
}

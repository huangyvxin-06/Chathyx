package com.hyx.mcmy.agent;

import android.content.Context;

import com.hyx.mcmy.R;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * TLS 证书锁定：只信任内置的自签证书，拒绝系统 CA 与任何其他证书，
 * 即使设备系统信任了别的 CA，也无法冒充本服务器（防中间人攻击）。
 */
public final class TlsTrust {

    private static volatile SSLSocketFactory factory;

    private TlsTrust() {
    }

    public static SSLSocketFactory factory(Context ctx) throws Exception {
        if (factory != null) {
            return factory;
        }
        synchronized (TlsTrust.class) {
            if (factory != null) {
                return factory;
            }
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Certificate ca;
            InputStream in = ctx.getApplicationContext().getResources()
                    .openRawResource(R.raw.chathyx_cert);
            try {
                ca = cf.generateCertificate(in);
            } finally {
                in.close();
            }

            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            ks.setCertificateEntry("chathyx", ca);

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, tmf.getTrustManagers(), null);
            factory = sslContext.getSocketFactory();
            return factory;
        }
    }
}

package com.gathin.kit;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.os.Build;
import android.sun.security.x509.AlgorithmId;
import android.sun.security.x509.CertificateAlgorithmId;
import android.sun.security.x509.CertificateExtensions;
import android.sun.security.x509.CertificateIssuerName;
import android.sun.security.x509.CertificateSerialNumber;
import android.sun.security.x509.CertificateSubjectName;
import android.sun.security.x509.CertificateValidity;
import android.sun.security.x509.CertificateVersion;
import android.sun.security.x509.CertificateX509Key;
import android.sun.security.x509.KeyIdentifier;
import android.sun.security.x509.PrivateKeyUsageExtension;
import android.sun.security.x509.SubjectKeyIdentifierExtension;
import android.sun.security.x509.X500Name;
import android.sun.security.x509.X509CertImpl;
import android.sun.security.x509.X509CertInfo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.Inet4Address;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.AdbStream;
import io.github.muntashirakon.adb.android.AdbMdns;

/**
 * This projector's own ADB, reached through Wireless debugging: a key pair kept in the app's private files (so a pairing
 * is remembered), pairing with the 6-digit code, connecting, and shell commands. The shell runs as the "shell" user, the
 * same as `adb shell` from a computer, which is what installing the launcher and its permissions needs.
 */
final class Adb extends AbsAdbConnectionManager {
    private static Adb instance;
    private final Context context;
    private final PrivateKey privateKey;
    private final Certificate certificate;

    static synchronized Adb get(Context context) throws Exception {
        if (instance == null) instance = new Adb(context.getApplicationContext());
        return instance;
    }

    /**
     * Throws the current connection away and starts clean. A connection can lock up (a stream stuck waiting for the
     * TV, and closing it waits for the same lock), so it is never closed in the caller's thread: it is let go, closed
     * in the background, and the next {@link #get} makes a new one with the same key (already allowed by the TV).
     */
    static synchronized void reset() {
        Adb old = instance;
        instance = null;
        if (old == null) return;
        Thread closer = new Thread(() -> {
            try {
                old.close();
            } catch (Throwable ignored) {
            }
        }, "adb-close");
        closer.setDaemon(true);
        closer.start();
    }

    /** A fresh connection to this TV, at most 45 s of trying; false when the TV does not answer. */
    static boolean fresh(Context context) {
        reset();
        boolean[] ok = {false};
        Thread connect = new Thread(() -> {
            try {
                ok[0] = get(context).connectSelf();
            } catch (Throwable ignored) {
            }
        }, "adb-connect");
        connect.setDaemon(true);
        connect.start();
        try {
            connect.join(45_000);
        } catch (InterruptedException ignored) {
        }
        if (connect.isAlive()) {
            reset();
            return false;
        }
        return ok[0];
    }

    private Adb(Context context) throws Exception {
        this.context = context;
        setApi(Build.VERSION.SDK_INT);
        // Nothing waits for ever: a connection or a new stream that gets no answer in 20 s fails (and is retried).
        setTimeout(20, java.util.concurrent.TimeUnit.SECONDS);
        File keyFile = new File(context.getFilesDir(), "adb.key");
        File certFile = new File(context.getFilesDir(), "adb.crt");
        if (keyFile.isFile() && certFile.isFile()) {
            privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(keyFile.toPath())));
            try (InputStream in = new ByteArrayInputStream(Files.readAllBytes(certFile.toPath()))) {
                certificate = CertificateFactory.getInstance("X.509").generateCertificate(in);
            }
            return;
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, SecureRandom.getInstance("SHA1PRNG"));
        KeyPair pair = generator.generateKeyPair();
        PublicKey publicKey = pair.getPublic();
        privateKey = pair.getPrivate();
        String algorithm = "SHA512withRSA";
        Date notBefore = new Date();
        Date notAfter = new Date(System.currentTimeMillis() + 10L * 365 * 86_400_000L);
        CertificateExtensions extensions = new CertificateExtensions();
        extensions.set("SubjectKeyIdentifier", new SubjectKeyIdentifierExtension(new KeyIdentifier(publicKey).getIdentifier()));
        extensions.set("PrivateKeyUsage", new PrivateKeyUsageExtension(notBefore, notAfter));
        X500Name name = new X500Name("CN=Formovie Kit");
        X509CertInfo info = new X509CertInfo();
        info.set("version", new CertificateVersion(2));
        info.set("serialNumber", new CertificateSerialNumber(new Random().nextInt() & Integer.MAX_VALUE));
        info.set("algorithmID", new CertificateAlgorithmId(AlgorithmId.get(algorithm)));
        info.set("subject", new CertificateSubjectName(name));
        info.set("key", new CertificateX509Key(publicKey));
        info.set("validity", new CertificateValidity(notBefore, notAfter));
        info.set("issuer", new CertificateIssuerName(name));
        info.set("extensions", extensions);
        X509CertImpl cert = new X509CertImpl(info);
        cert.sign(privateKey, algorithm);
        certificate = cert;
        Files.write(keyFile.toPath(), privateKey.getEncoded());
        Files.write(certFile.toPath(), certificate.getEncoded());
    }

    @Override
    protected PrivateKey getPrivateKey() {
        return privateKey;
    }

    @Override
    protected Certificate getCertificate() {
        return certificate;
    }

    @Override
    protected String getDeviceName() {
        return "Formovie Kit";
    }

    /** This projector's Wi-Fi address, the one its Wireless debugging listens on. */
    static String ownAddress(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        LinkProperties props = cm == null ? null : cm.getLinkProperties(cm.getActiveNetwork());
        if (props != null) {
            for (LinkAddress address : props.getLinkAddresses()) {
                if (address.getAddress() instanceof Inet4Address) return address.getAddress().getHostAddress();
            }
        }
        return "127.0.0.1";
    }

    /** The pairing port Wireless debugging announces while its "pair with code" window is open, or -1. */
    int findPairingPort(long timeoutMs) throws InterruptedException {
        AtomicInteger found = new AtomicInteger(-1);
        CountDownLatch done = new CountDownLatch(1);
        AdbMdns mdns = new AdbMdns(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING, (host, port) -> {
            found.set(port);
            done.countDown();
        });
        mdns.start();
        try {
            done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } finally {
            mdns.stop();
        }
        return found.get();
    }

    /** Pairs with the code shown in the projector's "pair with code" window (found by itself on the network). */
    boolean pairWithCode(String code) throws Exception {
        int port = findPairingPort(15_000);
        if (port <= 0) throw new IllegalStateException("no-pairing-window");
        return pair(ownAddress(context), port, code);
    }

    /** Connects to this projector's Wireless debugging (its port is found on the network), else to the old port 5555. */
    boolean connectSelf() throws Exception {
        if (isConnected()) return true;
        // First the TV's own port: on most Google TVs USB debugging alone opens it, and the TV only asks
        // "Allow USB debugging?" (no IP, no code). Wireless debugging's paired connection is the fallback.
        for (String host : new String[]{"127.0.0.1", ownAddress(context)}) {
            try {
                if (connect(host, 5555)) return true;
            } catch (Throwable ignored) {
                // not open there
            }
        }
        try {
            return connectTls(context, 10_000);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * What a computer's "adb tcpip PORT" does: the TV's ADB starts listening on this port on every network (Wi-Fi and
     * Tailscale), so a computer can connect to it from elsewhere. The TV's ADB restarts, which ends this connection.
     */
    String tcpip(int port) throws Exception {
        AdbStream[] opened = {null};
        Thread open = new Thread(() -> {
            try {
                opened[0] = openStream("tcpip:" + port);
            } catch (Throwable ignored) {
            }
        }, "adb-tcpip");
        open.setDaemon(true);
        open.start();
        open.join(10_000);
        if (opened[0] == null) throw new IllegalStateException("הטלוויזיה לא ענתה (tcpip)");
        StringBuilder reply = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (InputStream in = opened[0].openInputStream()) {
                byte[] buffer = new byte[256];
                int n;
                while ((n = in.read(buffer)) > 0) reply.append(new String(buffer, 0, n, java.nio.charset.StandardCharsets.UTF_8));
            } catch (Throwable ignored) {
            }
        }, "adb-tcpip-read");
        reader.setDaemon(true);
        reader.start();
        reader.join(3000);
        reset();
        return reply.toString().trim();
    }

    /** A connection that stopped answering is let go and a fresh one made (see {@link #fresh}). */
    boolean reconnect() {
        return fresh(context);
    }

    /** One shell command, its output (and errors) once it is done; 45 s a try, up to 3 tries. */
    String sh(String command) throws Exception {
        return sh(command, 45_000, 3);
    }

    /** One shell command with this time limit, up to 3 tries. */
    String sh(String command, long limitMs) throws Exception {
        return sh(command, limitMs, 3);
    }

    /**
     * One shell command with a time limit and a number of tries. The TV's ADB connection can freeze for a while (seen
     * on a TCL in the middle of the theme files: every command after it waited): a try that is not done in time drops
     * that connection, makes a new one and runs the command again. Never waits for ever; the last failure is reported.
     */
    String sh(String command, long limitMs, int tries) throws Exception {
        IllegalStateException last = null;
        Adb current = this;
        for (int attempt = 1; attempt <= Math.max(1, tries); attempt++) {
            try {
                return current.shOnce(command, limitMs);
            } catch (IllegalStateException e) {
                last = e;
                if (attempt < tries) {
                    if (!fresh(context)) Thread.sleep(3000);
                    current = get(context);
                }
            }
        }
        throw last;
    }

    private String shOnce(String command, long limitMs) throws Exception {
        if (!isConnected() && !connectSelf()) throw new IllegalStateException("החיבור לטלוויזיה נפל (ניפוי באגים כבוי?)");
        AdbStream[] opened = {null};
        Thread open = new Thread(() -> {
            try {
                opened[0] = openStream("shell:" + command);
            } catch (Throwable ignored) {
            }
        }, "adb-open");
        open.setDaemon(true);
        open.start();
        open.join(20_000);
        if (opened[0] == null) throw new IllegalStateException("הטלוויזיה לא ענתה (פתיחת פקודה)");
        AdbStream running = opened[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = running.openInputStream()) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    synchronized (out) {
                        out.write(buffer, 0, n);
                    }
                }
            } catch (java.io.IOException ignored) {
                // the stream ends when the command does
            }
        }, "adb-sh");
        reader.setDaemon(true);
        reader.start();
        reader.join(limitMs);
        if (reader.isAlive()) {
            Thread closer = new Thread(() -> {
                try {
                    running.close();
                } catch (Throwable ignored) {
                }
            }, "adb-close-stream");
            closer.setDaemon(true);
            closer.start();
            reader.interrupt();
            String shortCommand = command.length() > 60 ? command.substring(0, 60) + "…" : command;
            throw new IllegalStateException("פקודה בטלוויזיה לא הסתיימה תוך " + (limitMs / 1000) + " שניות: " + shortCommand);
        }
        synchronized (out) {
            return out.toString(StandardCharsets.UTF_8.name()).replace("\r", "").trim();
        }
    }
}

package dev.clipbridge.adb;

import android.content.Context;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.Random;

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

/**
 * The RSA key pair and self-signed certificate ClipBridge uses to authenticate to this phone's own
 * adbd. Generated once and kept in the app's private storage; the matching public key is authorised
 * inside adbd when the user pairs, and stays authorised across reboots.
 */
public final class AdbKeyStore {
    private static final String ALGORITHM = "SHA512withRSA";
    private static final long CERT_VALIDITY_MS = 10L * 365 * 24 * 60 * 60 * 1000; // 10 years

    private final File keyFile;
    private final File certFile;
    private PrivateKey privateKey;
    private Certificate certificate;

    public AdbKeyStore(@NonNull Context context) throws Exception {
        File dir = new File(context.getFilesDir(), "adb");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        keyFile = new File(dir, "key.pk8");
        certFile = new File(dir, "cert.der");
        if (keyFile.exists() && certFile.exists()) {
            load();
        } else {
            generate();
        }
    }

    @NonNull
    public PrivateKey getPrivateKey() {
        return privateKey;
    }

    @NonNull
    public Certificate getCertificate() {
        return certificate;
    }

    private void load() throws Exception {
        byte[] keyBytes = Files.readAllBytes(keyFile.toPath());
        privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        try (java.io.InputStream in = Files.newInputStream(certFile.toPath())) {
            certificate = CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    private void generate() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, SecureRandom.getInstance("SHA1PRNG"));
        java.security.KeyPair pair = generator.generateKeyPair();
        PublicKey publicKey = pair.getPublic();
        privateKey = pair.getPrivate();

        Date notBefore = new Date();
        Date notAfter = new Date(System.currentTimeMillis() + CERT_VALIDITY_MS);
        X500Name owner = new X500Name("CN=ClipBridge");

        CertificateExtensions extensions = new CertificateExtensions();
        extensions.set("SubjectKeyIdentifier",
                new SubjectKeyIdentifierExtension(new KeyIdentifier(publicKey).getIdentifier()));
        extensions.set("PrivateKeyUsage", new PrivateKeyUsageExtension(notBefore, notAfter));

        X509CertInfo info = new X509CertInfo();
        info.set("version", new CertificateVersion(CertificateVersion.V3));
        info.set("serialNumber", new CertificateSerialNumber(new BigInteger(64, new Random()).abs()));
        info.set("algorithmID", new CertificateAlgorithmId(AlgorithmId.get(ALGORITHM)));
        info.set("subject", new CertificateSubjectName(owner));
        info.set("key", new CertificateX509Key(publicKey));
        info.set("validity", new CertificateValidity(notBefore, notAfter));
        info.set("issuer", new CertificateIssuerName(owner));
        info.set("extensions", extensions);

        X509CertImpl cert = new X509CertImpl(info);
        cert.sign(privateKey, ALGORITHM);
        certificate = cert;

        writeAtomic(keyFile, privateKey.getEncoded());   // PKCS#8
        writeAtomic(certFile, cert.getEncoded());        // DER
    }

    private static void writeAtomic(File target, byte[] bytes) throws Exception {
        File tmp = new File(target.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
            out.getFD().sync();
        }
        if (!tmp.renameTo(target)) {
            throw new java.io.IOException("could not store " + target.getName());
        }
        //noinspection ResultOfMethodCallIgnored
        target.setReadable(false, false);
        //noinspection ResultOfMethodCallIgnored
        target.setReadable(true, true);
    }
}

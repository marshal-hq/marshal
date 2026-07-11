package dev.marshalhq.registry;

import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.BCPGOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder;

import java.io.ByteArrayOutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.util.Date;

/**
 * Shared BouncyCastle helpers for the whitelist signature tests: generate a throwaway
 * PGP key and produce a detached, armored signature over arbitrary bytes.
 */
final class PgpTestSupport {

    static final char[] PASSPHRASE = "test".toCharArray();

    private PgpTestSupport() {
    }

    static void registerProvider() {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    record Keys(PGPSecretKey secretKey, byte[] publicKeyRing) {
    }

    static Keys generateKeys() throws Exception {
        registerProvider();
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        PGPKeyPair pgpKp = new JcaPGPKeyPair(PGPPublicKey.RSA_GENERAL, kp, new Date());
        var sha1 = new JcaPGPDigestCalculatorProviderBuilder().build().get(HashAlgorithmTags.SHA1);
        PGPSecretKey secretKey = new PGPSecretKey(
                PGPSignature.DEFAULT_CERTIFICATION, pgpKp, "marshal-release", sha1, null, null,
                new JcaPGPContentSignerBuilder(pgpKp.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256),
                new JcePBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256, sha1)
                        .setProvider("BC").build(PASSPHRASE));
        return new Keys(secretKey, secretKey.getPublicKey().getEncoded());
    }

    static byte[] sign(byte[] data, Keys keys) throws Exception {
        PGPPrivateKey privateKey = keys.secretKey().extractPrivateKey(
                new JcePBESecretKeyDecryptorBuilder(
                        new JcaPGPDigestCalculatorProviderBuilder().setProvider("BC").build())
                        .setProvider("BC").build(PASSPHRASE));

        PGPSignatureGenerator sigGen = new PGPSignatureGenerator(
                new JcaPGPContentSignerBuilder(keys.secretKey().getPublicKey().getAlgorithm(),
                        HashAlgorithmTags.SHA256).setProvider("BC"));
        sigGen.init(PGPSignature.BINARY_DOCUMENT, privateKey);
        sigGen.update(data);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ArmoredOutputStream armored = new ArmoredOutputStream(out);
                BCPGOutputStream bcpg = new BCPGOutputStream(armored)) {
            sigGen.generate().encode(bcpg);
        }
        return out.toByteArray();
    }
}

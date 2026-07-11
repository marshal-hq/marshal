package dev.marshalhq.registry;

import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Verifies a detached GPG signature over the live whitelist against the public key
 * embedded in the jar. Trust rides on the same channel the user already trusted to get
 * the binary, so this adds no new root of trust. A failed check is treated as a
 * possible attack: the caller throws the fetched file away and falls back.
 */
public final class WhitelistSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(WhitelistSignatureVerifier.class);

    /**
     * @param data            the exact bytes of the fetched whitelist document.
     * @param detachedSig      the detached signature bytes (armored or binary).
     * @param publicKeyRing    the embedded public key(s) to verify against.
     * @return true only if the signature is present, well-formed, made by a key in the
     *         ring, and valid over {@code data}. Any error returns false — never throws.
     */
    public boolean verify(byte[] data, byte[] detachedSig, InputStream publicKeyRing) {
        try {
            PGPSignature signature = firstSignature(detachedSig);
            if (signature == null) {
                log.warn("Whitelist signature payload contained no signature");
                return false;
            }
            PGPPublicKeyRingCollection rings = new PGPPublicKeyRingCollection(
                    PGPUtil.getDecoderStream(publicKeyRing), new JcaKeyFingerprintCalculator());
            PGPPublicKey key = rings.getPublicKey(signature.getKeyID());
            if (key == null) {
                log.warn("Whitelist signature was made by an unknown key {}", Long.toHexString(signature.getKeyID()));
                return false;
            }
            signature.init(new JcaPGPContentVerifierBuilderProvider(), key);
            signature.update(data);
            return signature.verify();
        }
        catch (IOException | PGPException e) {
            log.warn("Whitelist signature verification failed: {}", e.getMessage());
            return false;
        }
    }

    private static PGPSignature firstSignature(byte[] detachedSig) throws IOException {
        try (InputStream in = PGPUtil.getDecoderStream(new ByteArrayInputStream(detachedSig))) {
            JcaPGPObjectFactory factory = new JcaPGPObjectFactory(in);
            Object obj = factory.nextObject();
            if (obj instanceof PGPSignatureList list && !list.isEmpty()) {
                return list.get(0);
            }
            return null;
        }
    }
}

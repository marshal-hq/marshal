package dev.marshalhq.registry;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The signature gate accepts a document signed by the trusted key and rejects
 * everything else: wrong key, tampered payload, garbage.
 */
class WhitelistSignatureVerifierTest {

    private static final WhitelistSignatureVerifier verifier = new WhitelistSignatureVerifier();

    @BeforeAll
    static void registerProvider() {
        PgpTestSupport.registerProvider();
    }

    @Test
    void acceptsADocumentSignedByTheTrustedKey() throws Exception {
        PgpTestSupport.Keys keys = PgpTestSupport.generateKeys();
        byte[] doc = "version: 1\nentries: []\n".getBytes(StandardCharsets.UTF_8);
        byte[] sig = PgpTestSupport.sign(doc, keys);

        assertThat(verifier.verify(doc, sig, new ByteArrayInputStream(keys.publicKeyRing()))).isTrue();
    }

    @Test
    void rejectsWhenPayloadWasTamperedAfterSigning() throws Exception {
        PgpTestSupport.Keys keys = PgpTestSupport.generateKeys();
        byte[] doc = "version: 1\nentries: []\n".getBytes(StandardCharsets.UTF_8);
        byte[] sig = PgpTestSupport.sign(doc, keys);

        byte[] tampered = "version: 1\nentries: [evil]\n".getBytes(StandardCharsets.UTF_8);
        assertThat(verifier.verify(tampered, sig, new ByteArrayInputStream(keys.publicKeyRing()))).isFalse();
    }

    @Test
    void rejectsSignatureFromADifferentKey() throws Exception {
        PgpTestSupport.Keys signer = PgpTestSupport.generateKeys();
        PgpTestSupport.Keys other = PgpTestSupport.generateKeys();
        byte[] doc = "version: 1\n".getBytes(StandardCharsets.UTF_8);
        byte[] sig = PgpTestSupport.sign(doc, signer);

        assertThat(verifier.verify(doc, sig, new ByteArrayInputStream(other.publicKeyRing()))).isFalse();
    }

    @Test
    void rejectsGarbageSignatureBytesWithoutThrowing() throws Exception {
        PgpTestSupport.Keys keys = PgpTestSupport.generateKeys();
        byte[] doc = "version: 1\n".getBytes(StandardCharsets.UTF_8);
        assertThat(verifier.verify(doc, new byte[]{1, 2, 3, 4}, new ByteArrayInputStream(keys.publicKeyRing())))
                .isFalse();
    }
}

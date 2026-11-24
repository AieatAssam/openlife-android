import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Generates the P1-18-R7 forSource golden fixtures with the JDK alone,
 * independently of OpenLife's codec classes. Run: java scripts/fixtures/GenerateForSourceGoldenEnvelopes.java
 * Output lines ARTEFACT= and WRAPPED_DEK= are the contents of
 * vault/src/test/resources/envelope/golden-artefact-forsource-v1.hex and
 * golden-wrapped-dek-forsource-v1.hex.
 */
public class GenerateForSourceGoldenEnvelopes {
    static byte[] aad(String label, UUID id) {
        byte[] l = label.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer b = ByteBuffer.allocate(1 + 4 + l.length + 16);
        b.put((byte) 1).putInt(l.length).put(l).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
        return b.array();
    }
    static String envelope(byte[] key, byte[] nonce, byte[] plaintext, byte[] aad) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        c.updateAAD(aad);
        byte[] ct = c.doFinal(plaintext);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(MAGIC_OLV1);
        out.write(1); out.write(nonce.length); out.write(nonce);
        out.write(ByteBuffer.allocate(4).putInt(ct.length).array()); out.write(ct);
        return HexFormat.of().formatHex(out.toByteArray());
    }
    /** ASCII "OLV1", the envelope magic (design §9). */
    static final byte[] MAGIC_OLV1 = {0x4f, 0x4c, 0x56, 0x31};
    public static void main(String[] args) throws Exception {
        UUID id = UUID.fromString("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");
        HexFormat h = HexFormat.of();
        byte[] key = h.parseHex("202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f");
        byte[] dek = h.parseHex("404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f");
        System.out.println("AAD_ARTEFACT=" + h.formatHex(aad("openlife.artefact.v1", id)));
        System.out.println("AAD_SOURCE_KEY=" + h.formatHex(aad("openlife.source-key.v1", id)));
        System.out.println("ARTEFACT=" + envelope(dek, h.parseHex("a0a1a2a3a4a5a6a7a8a9aaab"),
            "OpenLife forSource fixture plaintext".getBytes(StandardCharsets.UTF_8), aad("openlife.artefact.v1", id)));
        System.out.println("WRAPPED_DEK=" + envelope(key, h.parseHex("b0b1b2b3b4b5b6b7b8b9babb"), dek, aad("openlife.source-key.v1", id)));
    }
}

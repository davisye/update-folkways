import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Java 21 source-file tool. Keys are base64 DER; never prints private keys. */
public final class ManifestCrypto {
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "generate" -> {
                var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
                Files.writeString(Path.of(args[1]), Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()),
                        java.nio.file.StandardOpenOption.CREATE_NEW);
                Files.writeString(Path.of(args[2]), Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                        java.nio.file.StandardOpenOption.CREATE_NEW);
            }
            case "sign" -> {
                var key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(
                        Base64.getDecoder().decode(Files.readString(Path.of(args[1])).trim())));
                var signer = Signature.getInstance("Ed25519");
                signer.initSign(key);
                signer.update(Files.readAllBytes(Path.of(args[2])));
                Files.writeString(Path.of(args[3]), Base64.getEncoder().encodeToString(signer.sign()));
            }
            case "verify" -> {
                var key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(
                        Base64.getDecoder().decode(Files.readString(Path.of(args[1])).trim())));
                var verifier = Signature.getInstance("Ed25519");
                verifier.initVerify(key);
                verifier.update(Files.readAllBytes(Path.of(args[2])));
                if (!verifier.verify(Base64.getDecoder().decode(Files.readString(Path.of(args[3])).trim()))) {
                    throw new SecurityException("Manifest signature verification failed");
                }
            }
            default -> throw new IllegalArgumentException("generate PRIVATE PUBLIC | sign PRIVATE DATA SIG | verify PUBLIC DATA SIG");
        }
    }
}

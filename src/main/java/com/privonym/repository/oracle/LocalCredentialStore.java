package com.privonym.repository.oracle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class LocalCredentialStore {
    private LocalCredentialStore() { }

    static char[] decrypt(String encoded) {
        byte[] key = null;
        byte[] plain = null;
        try {
            Path path = Path.of(System.getProperty("privonym.secret.key.file",
                    "config/privonym-secret.key")).toAbsolutePath();
            key = Base64.getDecoder().decode(Files.readString(path).trim());
            if (key.length != 32) throw new IllegalStateException("Invalid Privonym local secret key");
            byte[] payload = Base64.getDecoder().decode(encoded);
            byte[] nonce = Arrays.copyOfRange(payload, 0, 12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, nonce));
            plain = cipher.doFinal(payload, 12, payload.length - 12);
            return new String(plain, java.nio.charset.StandardCharsets.UTF_8).toCharArray();
        } catch (Exception error) {
            throw new IllegalStateException("Unable to decrypt saved connection password using "
                    + System.getProperty("privonym.secret.key.file", "config/privonym-secret.key")
                    + ": " + error.getMessage(), error);
        } finally {
            if (key != null) Arrays.fill(key, (byte) 0);
            if (plain != null) Arrays.fill(plain, (byte) 0);
        }
    }
}

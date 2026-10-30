package softm.android.gagyebu;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class LocalCipher {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "softm.android.gagyebu.capture.v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    String encrypt(String plainText) {
        if (plainText == null || plainText.isEmpty()) return "";
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] ciphertext = cipher.doFinal(plainText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] iv = cipher.getIV();
            ByteBuffer payload = ByteBuffer.allocate(2 + iv.length + ciphertext.length);
            payload.put((byte) 1);
            payload.put((byte) iv.length);
            payload.put(iv);
            payload.put(ciphertext);
            return Base64.encodeToString(payload.array(), Base64.NO_WRAP);
        } catch (Exception failure) {
            throw new IllegalStateException("감지 내용을 암호화하지 못했습니다.", failure);
        }
    }

    String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isEmpty()) return "";
        try {
            ByteBuffer payload = ByteBuffer.wrap(Base64.decode(encrypted, Base64.NO_WRAP));
            int version = payload.get() & 0xff;
            int ivLength = payload.get() & 0xff;
            if (version != 1 || ivLength < 12 || ivLength > 16 || payload.remaining() <= ivLength) {
                throw new IllegalArgumentException("지원하지 않는 암호문입니다.");
            }
            byte[] iv = new byte[ivLength];
            payload.get(iv);
            byte[] ciphertext = new byte[payload.remaining()];
            payload.get(ciphertext);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception failure) {
            throw new IllegalStateException("감지 내용을 복호화하지 못했습니다.", failure);
        }
    }

    private synchronized SecretKey key() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        java.security.Key existing = keyStore.getKey(ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build());
        return generator.generateKey();
    }
}


package dev.znxki.security;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public final class SecretCipher {
    private static final byte VERSION = 1;
    private static final int HEADER_LENGTH = 2;
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_LENGTH = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<Byte, SecretKey> keys;
    private final byte activeKeyId;

    public SecretCipher(@NotNull Map<Byte, byte[]> rawKeys, byte activeKeyId) {
        if(!rawKeys.containsKey(activeKeyId))
            throw new IllegalArgumentException("Active key not found!");

        Map<Byte, SecretKey> parsed = new HashMap<>();
        rawKeys.forEach((id, bytes) -> {
            if(bytes.length != KEY_LENGTH)
                throw new IllegalArgumentException("Key " + id + " must be 32 bytes!");

            parsed.put(id, new SecretKeySpec(bytes, "AES"));
        });

        this.keys = Map.copyOf(parsed);
        this.activeKeyId = activeKeyId;
    }

    @Contract("_, _ -> new")
    public static @NotNull SecretCipher fromEnv(String keysVar, String activeVar) {
        Map<Byte, byte[]> raw = new HashMap<>();
        for(String entry : requireEnv(keysVar).split(",")) {
            String[] parts = entry.trim().split(":", 2);
            if(parts.length != 2)
                throw new IllegalArgumentException(keysVar + " entries must look like <id>:<base64 key>");

            if(raw.put(Byte.parseByte(parts[0].trim()), Base64.getDecoder().decode(parts[1].trim())) != null)
                throw new IllegalArgumentException("Duplicate key id in " + keysVar);
        }

        return new SecretCipher(raw, Byte.parseByte(requireEnv(activeVar).trim()));
    }

    private static @NotNull String requireEnv(String name) {
        String value = System.getenv(name);
        if(value == null || value.isBlank())
            throw new IllegalStateException("Missing environment variable " + name);

        return value;
    }

    public String encrypt(@NotNull String plain, @NotNull String context) throws GeneralSecurityException {
        byte[] iv = new byte[IV_LENGTH];
        RANDOM.nextBytes(iv);
        byte[] header = {VERSION, activeKeyId};

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(header);
        cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

        byte[] out = ByteBuffer.allocate(HEADER_LENGTH + IV_LENGTH + encrypted.length)
                .put(header).put(iv).put(encrypted).array();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
    }

    @Contract("_, _ -> new")
    public @NotNull String decrypt(String data, String context) throws GeneralSecurityException {
        byte[] all = Base64.getUrlDecoder().decode(data);
        if(all.length < HEADER_LENGTH + IV_LENGTH + TAG_BITS / 8)
            throw new GeneralSecurityException("Malformed payload");

        if(all[0] != VERSION)
            throw new GeneralSecurityException("Unsupported version");

        SecretKey key = keys.get(all[1]);
        if(key == null)
            throw new GeneralSecurityException("Unknown key id");

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, HEADER_LENGTH, IV_LENGTH));
        cipher.updateAAD(all, 0, HEADER_LENGTH);
        cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
        int offset = HEADER_LENGTH + IV_LENGTH;

        return new String(cipher.doFinal(all, offset, all.length - offset), StandardCharsets.UTF_8);
    }
}

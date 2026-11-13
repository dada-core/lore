package com.dadacore.lore.identity.security;

import java.security.SecureRandom;
import java.util.HexFormat;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Hashes passwords with Argon2id. Only the hash is stored.
 */
@Component
public class PasswordHasher {

    private final Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    private final String decoy;

    public PasswordHasher() {
        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        this.decoy = encoder.encode(HexFormat.of().formatHex(random));
    }

    public String hash(String password) {
        return encoder.encode(password);
    }

    public boolean matches(String password, String hash) {
        return password != null && hash != null && encoder.matches(password, hash);
    }

    /**
     * Spends the same time as a real check when no account matches the address, so that the
     * response time does not reveal which accounts exist.
     */
    public void matchDecoy(String password) {
        encoder.matches(password == null ? "" : password, decoy);
    }
}

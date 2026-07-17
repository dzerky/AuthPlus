package com.authplus;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import org.bukkit.configuration.file.FileConfiguration;
import org.mindrot.jbcrypt.BCrypt;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

public class PasswordHasher {

    public enum HashAlgorithm {
        MD5,
        SHA256,
        SHA512,
        BCRYPT2A,
        BCRYPT2Y,
        PBKDF2,
        ARGON2ID,
        ARGON2I,
        ARGON2D
    }

    private final HashAlgorithm algorithm;
    private final int saltLength;
    private final int bcryptCost;
    private final int pbkdf2Iterations;
    private final int pbkdf2KeyLength;
    private final int argon2Iterations;
    private final int argon2Memory;
    private final int argon2Parallelism;
    private final int argon2HashLength;
    private final SecureRandom random = new SecureRandom();

    public PasswordHasher(FileConfiguration config) {
        String algo = config.getString("hashing.algorithm", "SHA256").toUpperCase().replace("-", "");
        this.algorithm = parseAlgorithm(algo);
        this.saltLength = config.getInt("hashing.salt-length", 16);
        this.bcryptCost = config.getInt("hashing.bcrypt-cost", 12);
        this.pbkdf2Iterations = config.getInt("hashing.pbkdf2-iterations", 600000);
        this.pbkdf2KeyLength = config.getInt("hashing.pbkdf2-key-length", 256);
        this.argon2Iterations = config.getInt("hashing.argon2-iterations", 3);
        this.argon2Memory = config.getInt("hashing.argon2-memory", 65536);
        this.argon2Parallelism = config.getInt("hashing.argon2-parallelism", 1);
        this.argon2HashLength = config.getInt("hashing.argon2-hash-length", 32);
    }

    private HashAlgorithm parseAlgorithm(String algo) {
        try {
            return HashAlgorithm.valueOf(algo);
        } catch (IllegalArgumentException e) {
            System.out.println("[AuthPlus] Unknown hash algorithm '" + algo + "', falling back to SHA256.");
            return HashAlgorithm.SHA256;
        }
    }

    /**
     * Hash a password. Returns a string that can be stored directly.
     * Format depends on algorithm:
     *   MD5/SHA256/SHA512: "ALGO:base64salt:base64hash"
     *   PBKDF2:           "PBKDF2:iterations:keylen:base64salt:base64hash"
     *   BCRYPT2A/2Y:      "$2a$..." or "$2y$..."
     *   ARGON2*:          "$argon2id$..." (standard Argon2 encoded format)
     */
    public String hash(String password) {
        switch (algorithm) {
            case MD5:
                return hashWithDigest(password, "MD5");
            case SHA256:
                return hashWithDigest(password, "SHA-256");
            case SHA512:
                return hashWithDigest(password, "SHA-512");
            case BCRYPT2A:
                return hashBcrypt(password, false);
            case BCRYPT2Y:
                return hashBcrypt(password, true);
            case PBKDF2:
                return hashPbkdf2(password);
            case ARGON2ID:
                return hashArgon2(password, Argon2Factory.Argon2Types.ARGON2id);
            case ARGON2I:
                return hashArgon2(password, Argon2Factory.Argon2Types.ARGON2i);
            case ARGON2D:
                return hashArgon2(password, Argon2Factory.Argon2Types.ARGON2d);
            default:
                return hashWithDigest(password, "SHA-256");
        }
    }

    /**
     * Verify a password against a stored hash.
     */
    public boolean verify(String password, String storedHash) {
        try {
            if (storedHash.startsWith("MD5:")) {
                return verifyDigest(password, storedHash, "MD5");
            } else if (storedHash.startsWith("SHA-256:")) {
                return verifyDigest(password, storedHash, "SHA-256");
            } else if (storedHash.startsWith("SHA-512:")) {
                return verifyDigest(password, storedHash, "SHA-512");
            } else if (storedHash.startsWith("PBKDF2:")) {
                return verifyPbkdf2(password, storedHash);
            } else if (storedHash.startsWith("$2a$") || storedHash.startsWith("$2y$")) {
                return verifyBcrypt(password, storedHash);
            } else if (storedHash.startsWith("$argon2")) {
                return verifyArgon2(password, storedHash);
            } else {
                // Fallback: plain text comparison (for old unhashed passwords / migration)
                return password.equals(storedHash);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    // ========================
    // MD5 / SHA-256 / SHA-512
    // ========================

    private String hashWithDigest(String password, String algo) {
        try {
            byte[] salt = generateSalt();
            MessageDigest md = MessageDigest.getInstance(algo);
            md.update(salt);
            byte[] hash = md.digest(password.getBytes(StandardCharsets.UTF_8));
            String b64Salt = Base64.getEncoder().encodeToString(salt);
            String b64Hash = Base64.getEncoder().encodeToString(hash);
            return algo + ":" + b64Salt + ":" + b64Hash;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Hash algorithm not available: " + algo, e);
        }
    }

    private boolean verifyDigest(String password, String storedHash, String algo) {
        try {
            String[] parts = storedHash.split(":", 3);
            if (parts.length != 3) return false;
            byte[] salt = Base64.getDecoder().decode(parts[1]);
            byte[] expectedHash = Base64.getDecoder().decode(parts[2]);
            MessageDigest md = MessageDigest.getInstance(algo);
            md.update(salt);
            byte[] actualHash = md.digest(password.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(expectedHash, actualHash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Hash algorithm not available: " + algo, e);
        }
    }

    // ========================
    // BCrypt (2a / 2y)
    // ========================

    private String hashBcrypt(String password, boolean use2y) {
        String salt = BCrypt.gensalt(bcryptCost);
        String hash = BCrypt.hashpw(password, salt);
        if (use2y) {
            // Replace $2a$ prefix with $2y$ — the algorithm is identical,
            // $2y$ simply indicates the PHP-compatible variant
            hash = "$2y$" + hash.substring(4);
        }
        return hash;
    }

    private boolean verifyBcrypt(String password, String storedHash) {
        String hashForCheck = storedHash;
        // jBCrypt only understands $2a$, so convert $2y$ back for verification
        if (hashForCheck.startsWith("$2y$")) {
            hashForCheck = "$2a$" + hashForCheck.substring(4);
        }
        return BCrypt.checkpw(password, hashForCheck);
    }

    // ========================
    // PBKDF2
    // ========================

    private String hashPbkdf2(String password) {
        try {
            byte[] salt = generateSalt();
            PBEKeySpec spec = new PBEKeySpec(
                    password.toCharArray(), salt, pbkdf2Iterations, pbkdf2KeyLength
            );
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] hash = factory.generateSecret(spec).getEncoded();
            spec.clearPassword();

            String b64Salt = Base64.getEncoder().encodeToString(salt);
            String b64Hash = Base64.getEncoder().encodeToString(hash);
            return "PBKDF2:" + pbkdf2Iterations + ":" + pbkdf2KeyLength + ":" + b64Salt + ":" + b64Hash;
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("PBKDF2 hashing failed", e);
        }
    }

    private boolean verifyPbkdf2(String password, String storedHash) {
        try {
            String[] parts = storedHash.split(":", 5);
            if (parts.length != 5) return false;
            int iterations = Integer.parseInt(parts[1]);
            int keyLength = Integer.parseInt(parts[2]);
            byte[] salt = Base64.getDecoder().decode(parts[3]);
            byte[] expectedHash = Base64.getDecoder().decode(parts[4]);

            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, keyLength);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] actualHash = factory.generateSecret(spec).getEncoded();
            spec.clearPassword();

            return MessageDigest.isEqual(expectedHash, actualHash);
        } catch (Exception e) {
            throw new RuntimeException("PBKDF2 verification failed", e);
        }
    }

    // ========================
    // Argon2 (id / i / d)
    // ========================

    private String hashArgon2(String password, Argon2Factory.Argon2Types type) {
        Argon2 argon2 = Argon2Factory.create(type, saltLength, argon2HashLength);
        try {
            return argon2.hash(argon2Iterations, argon2Memory, argon2Parallelism,
                    password.toCharArray());
        } finally {
            argon2.wipeArray(password.toCharArray());
        }
    }

    private boolean verifyArgon2(String password, String storedHash) {
        // Detect the Argon2 variant from the hash prefix
        Argon2Factory.Argon2Types type;
        if (storedHash.startsWith("$argon2id$")) {
            type = Argon2Factory.Argon2Types.ARGON2id;
        } else if (storedHash.startsWith("$argon2i$")) {
            type = Argon2Factory.Argon2Types.ARGON2i;
        } else if (storedHash.startsWith("$argon2d$")) {
            type = Argon2Factory.Argon2Types.ARGON2d;
        } else {
            return false;
        }
        Argon2 argon2 = Argon2Factory.create(type, saltLength, argon2HashLength);
        return argon2.verify(storedHash, password.toCharArray());
    }

    // ========================
    // Utility
    // ========================

    private byte[] generateSalt() {
        byte[] salt = new byte[saltLength];
        random.nextBytes(salt);
        return salt;
    }

    public HashAlgorithm getAlgorithm() {
        return algorithm;
    }
}

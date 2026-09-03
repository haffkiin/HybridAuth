package com.hybridauth.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/**
 * Хеширование паролей с использованием встроенного в Java PBKDF2WithHmacSHA256.
 * Используется вместо BCrypt для избежания внешних зависимостей.
 * Обеспечивает высокий уровень криптографической стойкости.
 */
public class PasswordHasher {

    /**
     * Текущий рабочий фактор (OWASP рекомендует >= 600k для SHA-256; 310k — компромисс
     * между стойкостью и задержкой на серверном потоке). Счётчик итераций читается
     * из сохранённого хеша, поэтому старые записи остаются валидными и прозрачно
     * перехешируются при следующем успешном входе (см. needsRehash).
     */
    private static final int ITERATIONS = 310_000;
    private static final int KEY_LENGTH = 256;
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Создает хеш пароля с новой солью.
     * Формат: iterations:salt(base64):hash(base64)
     */
    public static String hash(String password) {
        return hash(password, ITERATIONS);
    }

    /** Package-private for tests — allows creating hashes with a legacy work factor. */
    static String hash(String password, int iterations) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(password.toCharArray(), salt, iterations, KEY_LENGTH);

        return iterations + ":" + Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(hash);
    }

    /**
     * Проверяет соответствие пароля хешу.
     */
    public static boolean verify(String password, String storedHash) {
        try {
            String[] parts = storedHash.split(":");
            if (parts.length != 3) {
                return false;
            }

            int iterations = Integer.parseInt(parts[0]);
            byte[] salt = Base64.getDecoder().decode(parts[1]);
            byte[] hash = Base64.getDecoder().decode(parts[2]);

            byte[] testHash = pbkdf2(password.toCharArray(), salt, iterations, hash.length * 8);

            // Защита от timing attacks - сравнение за константное время
            return slowEquals(hash, testHash);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Возвращает true, если хеш создан с устаревшим рабочим фактором
     * и должен быть перехеширован при следующем успешном входе.
     */
    public static boolean needsRehash(String storedHash) {
        try {
            int iterations = Integer.parseInt(storedHash.split(":")[0]);
            return iterations < ITERATIONS;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int keyLengthBits) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, keyLengthBits);
            SecretKeyFactory skf = SecretKeyFactory.getInstance(ALGORITHM);
            return skf.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("Ошибка хеширования: алгоритм не поддерживается", e);
        }
    }

    private static boolean slowEquals(byte[] a, byte[] b) {
        int diff = a.length ^ b.length;
        for (int i = 0; i < a.length && i < b.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }
}

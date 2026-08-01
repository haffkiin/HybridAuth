package com.hybridauth.auth;

import java.security.SecureRandom;
import java.util.Locale;

public final class RecoveryCodeGenerator {

    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int CODE_LENGTH = 20;

    private RecoveryCodeGenerator() {
    }

    public static String generate() {
        StringBuilder code = new StringBuilder(CODE_LENGTH + 3);
        for (int i = 0; i < CODE_LENGTH; i++) {
            if (i > 0 && i % 5 == 0) {
                code.append('-');
            }
            code.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return code.toString();
    }

    public static String normalize(String code) {
        return code == null
                ? ""
                : code.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
    }
}

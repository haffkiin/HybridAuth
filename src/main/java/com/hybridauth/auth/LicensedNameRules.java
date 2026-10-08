package com.hybridauth.auth;

/**
 * Правило для ника, который одновременно есть в кракнутой записи и зарегистрирован в Mojang.
 */
public final class LicensedNameRules {

    private LicensedNameRules() {
    }

    /**
     * Конфликт возникает только при точном совпадении с каноническим ником Mojang.
     * Пара вида {@code remure} (кракнутый) / {@code ReMure} (лицензионный) — разрешённая,
     * поэтому конфликтом она не считается.
     *
     * @return true, если вход под этим ником нужно проверять через Mojang, а не пускать в кракнутую запись
     */
    public static boolean isLicensedExactConflict(String username,
                                                  PremiumLookupResult.Status status,
                                                  String canonicalName) {
        return status == PremiumLookupResult.Status.PREMIUM
                && username != null
                && username.equals(canonicalName);
    }
}

package com.hybridauth.whitelist;

import com.hybridauth.auth.OfflineUuid;

import java.util.UUID;

/**
 * Правило авто-ремонта whitelist: какие записи считаются старым offline-дублем
 * верифицированного лицензионного игрока и могут быть удалены.
 */
public final class WhitelistRepairRules {

    private WhitelistRepairRules() {
    }

    /**
     * Запись удаляется только если все условия выполнены:
     * имя совпадает с лицензионным ником точно (с учётом регистра),
     * UUID записи — offline-UUID этого имени (а не лицензионный),
     * и для этого ника нет кракнутой записи в базе авторизации.
     *
     * Записи с отличающимся регистром не трогаются: это может быть отдельный кракнутый игрок.
     */
    public static boolean isStaleOfflineEntry(String entryName,
                                              UUID entryId,
                                              String premiumName,
                                              UUID premiumId,
                                              boolean crackedRecordExists) {
        if (entryName == null || entryId == null || premiumName == null || premiumId == null) {
            return false;
        }
        return entryName.equals(premiumName)
                && !entryId.equals(premiumId)
                && OfflineUuid.forName(entryName).equals(entryId)
                && !crackedRecordExists;
    }
}

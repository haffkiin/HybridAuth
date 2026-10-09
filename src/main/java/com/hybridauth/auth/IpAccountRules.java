package com.hybridauth.auth;

import com.hybridauth.storage.PlayerData;

import java.util.List;
import java.util.UUID;

/** Подсчёт пиратских аккаунтов, замеченных с одного IP-адреса. Нужен для предупреждения админам. */
public final class IpAccountRules {

    private IpAccountRules() {
    }

    /**
     * Сколько других пиратских аккаунтов последний раз заходили с этого адреса.
     *
     * @param self аккаунт, который проверяется; в счёт не входит
     */
    public static int countOtherCrackedOnIp(List<PlayerData> accounts, String ip, UUID self) {
        if (ip == null || ip.isBlank() || "-".equals(ip)) {
            return 0;
        }
        int count = 0;
        for (PlayerData account : accounts) {
            if (account.isCracked() && ip.equals(account.getLastLoginIp()) && !account.getUuid().equals(self)) {
                count++;
            }
        }
        return count;
    }

    /** Нужно ли предупредить администраторов; {@code threshold == 0} выключает предупреждение. */
    public static boolean shouldWarn(int otherAccountsOnIp, int threshold) {
        return threshold > 0 && otherAccountsOnIp + 1 >= threshold;
    }
}

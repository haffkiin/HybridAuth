package com.hybridauth.auth;

import com.hybridauth.storage.PlayerData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistrationLimiterTest {

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;

    @Test
    void allowsUpToLimitThenBlocksThatIpOnly() {
        RegistrationLimiter limiter = new RegistrationLimiter(2, 0);
        long now = 10 * HOUR;
        limiter.record("1.1.1.1", now);
        limiter.record("1.1.1.1", now + MINUTE);

        RegistrationLimiter.Decision blocked = limiter.check("1.1.1.1", now + 2 * MINUTE);
        assertFalse(blocked.allowed());
        // Место освободится через час после первой регистрации, то есть через 58 минут
        assertEquals(58 * 60, blocked.retryAfterSeconds());
        assertTrue(limiter.check("2.2.2.2", now + 2 * MINUTE).allowed());
    }

    @Test
    void slotFreesWhenWindowPasses() {
        RegistrationLimiter limiter = new RegistrationLimiter(1, 0);
        long now = 10 * HOUR;
        limiter.record("1.1.1.1", now);
        assertFalse(limiter.check("1.1.1.1", now + 59 * MINUTE).allowed());
        assertTrue(limiter.check("1.1.1.1", now + HOUR + 1).allowed());
    }

    @Test
    void globalLimitCoversAllAddresses() {
        RegistrationLimiter limiter = new RegistrationLimiter(0, 3);
        long now = 10 * HOUR;
        limiter.record("1.1.1.1", now);
        limiter.record("2.2.2.2", now);
        limiter.record("3.3.3.3", now);
        RegistrationLimiter.Decision blocked = limiter.check("4.4.4.4", now + MINUTE);
        assertFalse(blocked.allowed());
        assertEquals(9 * 60, blocked.retryAfterSeconds());
        assertTrue(limiter.check("4.4.4.4", now + 10 * MINUTE + 1).allowed());
    }

    @Test
    void zeroDisablesBothLimits() {
        RegistrationLimiter limiter = new RegistrationLimiter(0, 0);
        for (int i = 0; i < 100; i++) {
            limiter.record("1.1.1.1", 10 * HOUR + i);
        }
        assertTrue(limiter.check("1.1.1.1", 10 * HOUR + 200).allowed());
    }

    @Test
    void checkDoesNotRecord() {
        RegistrationLimiter limiter = new RegistrationLimiter(1, 0);
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.check("1.1.1.1", 10 * HOUR).allowed());
        }
    }

    private static PlayerData account(PlayerData.PlayerType type, String ip) {
        PlayerData data = new PlayerData(UUID.randomUUID(), "n" + UUID.randomUUID().toString().substring(0, 6), type);
        data.setLastLoginIp(ip);
        return data;
    }

    @Test
    void countsOnlyOtherCrackedAccountsOnSameIp() {
        PlayerData self = account(PlayerData.PlayerType.CRACKED, "5.5.5.5");
        List<PlayerData> all = List.of(
                self,
                account(PlayerData.PlayerType.CRACKED, "5.5.5.5"),
                account(PlayerData.PlayerType.CRACKED, "5.5.5.5"),
                account(PlayerData.PlayerType.PREMIUM, "5.5.5.5"),
                account(PlayerData.PlayerType.CRACKED, "6.6.6.6"));
        assertEquals(2, IpAccountRules.countOtherCrackedOnIp(all, "5.5.5.5", self.getUuid()));
        assertEquals(0, IpAccountRules.countOtherCrackedOnIp(all, "-", self.getUuid()));
        assertEquals(0, IpAccountRules.countOtherCrackedOnIp(all, null, self.getUuid()));
    }

    @Test
    void warnThresholdCountsTheNewAccountToo() {
        assertFalse(IpAccountRules.shouldWarn(3, 5)); // всего 4 с новым
        assertTrue(IpAccountRules.shouldWarn(4, 5));  // всего 5 с новым
        assertFalse(IpAccountRules.shouldWarn(100, 0));
    }
}

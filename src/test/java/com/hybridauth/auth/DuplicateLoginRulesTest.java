package com.hybridauth.auth;

import org.junit.jupiter.api.Test;

import static com.hybridauth.auth.DuplicateLoginRules.Decision.KICK_EXISTING;
import static com.hybridauth.auth.DuplicateLoginRules.Decision.REJECT_NEWCOMER;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DuplicateLoginRulesTest {

    @Test
    void authenticatedCrackedSessionIsNotKickedFromAnotherAddress() {
        // Главный сценарий: атакующий вводит ник онлайн-игрока с другого IP
        assertEquals(REJECT_NEWCOMER,
                DuplicateLoginRules.decide(false, true, "1.2.3.4", "5.6.7.8"));
    }

    @Test
    void reconnectFromSameAddressKicksStaleSession() {
        // Зависшая сессия после обрыва связи: переподключение с того же IP должно работать
        assertEquals(KICK_EXISTING,
                DuplicateLoginRules.decide(false, true, "1.2.3.4", "1.2.3.4"));
    }

    @Test
    void unauthenticatedExistingSessionIsReplaceable() {
        // Незалогиненная сессия не защищена паролем, её можно заменить (ванильное поведение)
        assertEquals(KICK_EXISTING,
                DuplicateLoginRules.decide(false, false, "1.2.3.4", "5.6.7.8"));
    }

    @Test
    void verifiedPremiumNewcomerAlwaysWins() {
        // Владелец лицензии подтвердил личность через Mojang, подменить её нельзя
        assertEquals(KICK_EXISTING,
                DuplicateLoginRules.decide(true, true, "1.2.3.4", "5.6.7.8"));
    }

    @Test
    void unknownExistingAddressIsRejectedForAuthenticatedSession() {
        assertEquals(REJECT_NEWCOMER,
                DuplicateLoginRules.decide(false, true, null, "5.6.7.8"));
    }
}

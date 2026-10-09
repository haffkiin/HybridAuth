package com.hybridauth.auth;

import com.hybridauth.auth.NameLookupRules.Candidate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NameLookupRulesTest {

    private static final UUID PREMIUM = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CRACKED = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void exactCaseWinsWhenBothPairMembersExist() {
        List<Candidate> pair = List.of(new Candidate("ReMure", PREMIUM), new Candidate("remure", CRACKED));
        assertEquals(PREMIUM, NameLookupRules.pick("ReMure", pair).orElseThrow().uuid());
        assertEquals(CRACKED, NameLookupRules.pick("remure", pair).orElseThrow().uuid());
    }

    @Test
    void ambiguousPairWithoutExactMatchGivesNothing() {
        List<Candidate> pair = List.of(new Candidate("ReMure", PREMIUM), new Candidate("remure", CRACKED));
        assertEquals(Optional.empty(), NameLookupRules.pick("REMURE", pair));
    }

    @Test
    void singleRecordMatchesAnyCase() {
        List<Candidate> one = List.of(new Candidate("ReMure", CRACKED));
        assertEquals("ReMure", NameLookupRules.pick("remure", one).orElseThrow().username());
        assertEquals("ReMure", NameLookupRules.pick("REMURE", one).orElseThrow().username());
    }

    @Test
    void noRecordsGivesNothing() {
        assertEquals(Optional.empty(), NameLookupRules.pick("ReMure", List.of()));
        assertEquals(Optional.empty(), NameLookupRules.pick(null, List.of(new Candidate("a", CRACKED))));
    }

    @Test
    void keepTypedCaseRestoresOnlyCaseDifferences() {
        assertEquals("ReMure", NameLookupRules.keepTypedCase("remure", "ReMure"));
        assertEquals("remure", NameLookupRules.keepTypedCase("remure", "Other"));
        assertEquals("remure", NameLookupRules.keepTypedCase("remure", null));
    }

    @Test
    void offlineUuidDependsOnCaseSoTheFixMatters() {
        // Причина ошибки: UUID, посчитанный по нику в нижнем регистре, не совпадает с настоящим
        assertEquals(false, OfflineUuid.forName("remure").equals(OfflineUuid.forName("ReMure")));
    }
}

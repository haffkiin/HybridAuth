package com.hybridauth.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Как сопоставить ник из команды ({@code /op}, {@code /whitelist add}, {@code /ban}) с аккаунтом HybridAuth.
 *
 * Ванильный кэш профилей приводит ник к нижнему регистру, и на офлайн-сервере для ещё не заходившего
 * игрока считает UUID по {@code remure}, а не по {@code ReMure}. Игрок с ником {@code ReMure} заходит
 * с другим UUID и оказывается вне whitelist, без прав оператора и бана. Здесь выбирается запись по
 * точному регистру, а при её отсутствии по единственному совпадению без учёта регистра.
 */
public final class NameLookupRules {

    private NameLookupRules() {
    }

    /** Запись базы авторизации: ник в том регистре, в котором он сохранён, и UUID. */
    public record Candidate(String username, UUID uuid) {
    }

    /**
     * @param typed      ник так, как его ввёл администратор
     * @param candidates записи, у которых ник совпадает с введённым без учёта регистра
     * @return запись, которую нужно использовать; пусто, если выбор неоднозначен или записей нет
     */
    public static Optional<Candidate> pick(String typed, List<Candidate> candidates) {
        if (typed == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        for (Candidate candidate : candidates) {
            if (candidate.username().equals(typed)) {
                return Optional.of(candidate);
            }
        }
        // Точного совпадения нет: подходит только единственная запись (remure → ReMure)
        return candidates.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty();
    }

    /**
     * Ник, который нужно передать в поиск профиля вместо уже приведённого к нижнему регистру.
     * Возвращает исходный ник, только если он отличается от приведённого лишь регистром.
     */
    public static String keepTypedCase(String lowered, String typed) {
        return typed != null && typed.equalsIgnoreCase(lowered) ? typed : lowered;
    }
}

package com.hybridauth.auth;

/**
 * Решение, что делать, когда к серверу подключается игрок с UUID, который уже онлайн.
 *
 * У кракнутого игрока UUID выводится из ника, поэтому любой, кто введёт чужой ник,
 * получает тот же UUID. Ванильный логин кикает старую сессию раньше, чем HybridAuth
 * может проверить пароль, — поэтому решение принимается здесь.
 */
public final class DuplicateLoginRules {

    public enum Decision {
        /** Старая сессия выкидывается, новичок продолжает вход (ванильное поведение). */
        KICK_EXISTING,
        /** Новичок отклоняется, авторизованная сессия остаётся онлайн. */
        REJECT_NEWCOMER
    }

    private DuplicateLoginRules() {
    }

    /**
     * @param newcomerVerifiedPremium личность новичка подтверждена Mojang (hasJoined)
     * @param existingAuthenticated   старая сессия уже прошла авторизацию
     * @param existingIp              IP старой сессии
     * @param newcomerIp              IP нового подключения
     */
    public static Decision decide(boolean newcomerVerifiedPremium,
                                  boolean existingAuthenticated,
                                  String existingIp,
                                  String newcomerIp) {
        // Владелец лицензии доказал личность через Mojang, подделать это нельзя.
        if (newcomerVerifiedPremium) {
            return Decision.KICK_EXISTING;
        }
        // Неавторизованная сессия не защищена паролем: её можно заменить.
        if (!existingAuthenticated) {
            return Decision.KICK_EXISTING;
        }
        // Переподключение с того же адреса (зависшая сессия после обрыва связи).
        if (existingIp != null && existingIp.equals(newcomerIp)) {
            return Decision.KICK_EXISTING;
        }
        return Decision.REJECT_NEWCOMER;
    }
}

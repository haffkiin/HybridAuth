package com.hybridauth.transfer;

import java.util.Optional;

/**
 * Проверки перед переносом кракнутого аккаунта на новый ник. Без Minecraft и без файлов,
 * поэтому покрываются юнит-тестами.
 */
public final class AccountTransferRules {

    public enum SourceKind {
        MISSING,
        PREMIUM,
        CRACKED
    }

    public enum TargetLicense {
        FREE,
        LICENSED,
        UNKNOWN
    }

    public enum Rejection {
        SOURCE_NOT_FOUND("Аккаунт со старым ником не найден (ник указывается точно, с регистром)."),
        SOURCE_PREMIUM("Лицензионный аккаунт переносить нельзя: его UUID привязан к Mojang."),
        SAME_NAME("Старый и новый ник совпадают."),
        TARGET_INVALID_NAME("Новый ник не подходит: латиница, цифры и _, длина 3–16 символов."),
        SOURCE_ONLINE("Старый аккаунт сейчас на сервере. Перенос возможен только когда игрок вышел."),
        TARGET_ONLINE("Новый ник сейчас на сервере. Перенос возможен только когда игрок вышел."),
        TARGET_ACCOUNT_EXISTS("На новом нике уже есть аккаунт HybridAuth."),
        TARGET_WORLD_DATA_EXISTS("Для нового ника уже есть данные мира (playerdata, stats или advancements)."),
        TARGET_LIST_CONFLICT("Новый ник конфликтует с whitelist, ops или банами."),
        TARGET_LICENSED("Новый ник зарегистрирован в Mojang как лицензионный: пиратку на нём не создать."),
        TARGET_LICENSE_UNKNOWN("Не удалось проверить новый ник в Mojang. Повторите позже.");

        private final String message;

        Rejection(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    /** Факты о переносе, собранные сервером. Сами по себе не меняют данных. */
    public record Request(
            SourceKind source,
            boolean sourceOnline,
            boolean sameName,
            boolean targetNameValid,
            boolean targetOnline,
            boolean targetAccountExists,
            boolean targetWorldDataExists,
            boolean targetListConflict,
            TargetLicense targetLicense) {
    }

    private AccountTransferRules() {
    }

    /** @return первая причина, по которой перенос нельзя выполнить; пусто, если можно */
    public static Optional<Rejection> firstRejection(Request request) {
        if (request.source() == SourceKind.MISSING) {
            return Optional.of(Rejection.SOURCE_NOT_FOUND);
        }
        if (request.source() == SourceKind.PREMIUM) {
            return Optional.of(Rejection.SOURCE_PREMIUM);
        }
        if (request.sameName()) {
            return Optional.of(Rejection.SAME_NAME);
        }
        if (!request.targetNameValid()) {
            return Optional.of(Rejection.TARGET_INVALID_NAME);
        }
        if (request.sourceOnline()) {
            return Optional.of(Rejection.SOURCE_ONLINE);
        }
        if (request.targetOnline()) {
            return Optional.of(Rejection.TARGET_ONLINE);
        }
        if (request.targetAccountExists()) {
            return Optional.of(Rejection.TARGET_ACCOUNT_EXISTS);
        }
        if (request.targetWorldDataExists()) {
            return Optional.of(Rejection.TARGET_WORLD_DATA_EXISTS);
        }
        if (request.targetListConflict()) {
            return Optional.of(Rejection.TARGET_LIST_CONFLICT);
        }
        if (request.targetLicense() == TargetLicense.UNKNOWN) {
            return Optional.of(Rejection.TARGET_LICENSE_UNKNOWN);
        }
        if (request.targetLicense() == TargetLicense.LICENSED) {
            return Optional.of(Rejection.TARGET_LICENSED);
        }
        return Optional.empty();
    }
}

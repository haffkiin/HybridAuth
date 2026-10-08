package com.hybridauth.skin;

/** Ошибка получения скина. {@link Reason} определяет, какое сообщение увидит игрок. */
public final class SkinException extends RuntimeException {

    public enum Reason {
        /** Ник не найден в Mojang или у него нет скина. */
        NOT_FOUND,
        /** Внешний сервис просит подождать. */
        RATE_LIMITED,
        /** Внешний сервис недоступен или ответил непонятно. */
        UNAVAILABLE,
        /** MineSkin не принял картинку (не PNG, неверный размер, ошибка скачивания). */
        REJECTED,
        /** Не задан или не принят ключ MineSkin. */
        NOT_CONFIGURED
    }

    private final Reason reason;

    public SkinException(Reason reason, String detail) {
        super(detail);
        this.reason = reason;
    }

    public SkinException(Reason reason, String detail, Throwable cause) {
        super(detail, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

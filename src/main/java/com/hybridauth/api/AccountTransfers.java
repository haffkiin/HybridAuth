package com.hybridauth.api;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Реестр обработчиков переноса аккаунтов. Регистрировать можно в любой момент,
 * например в setup своего мода. Регистрация не сбрасывается при остановке сервера.
 */
public final class AccountTransfers {

    private static final List<AccountTransferHandler> HANDLERS = new CopyOnWriteArrayList<>();

    private AccountTransfers() {
    }

    public static void register(AccountTransferHandler handler) {
        HANDLERS.add(Objects.requireNonNull(handler, "handler"));
    }

    public static List<AccountTransferHandler> handlers() {
        return List.copyOf(HANDLERS);
    }
}

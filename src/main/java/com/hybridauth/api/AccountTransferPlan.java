package com.hybridauth.api;

import java.util.UUID;

/**
 * Перенос кракнутого аккаунта со старого ника на новый.
 *
 * @param fromId   UUID старого аккаунта (offline-UUID старого ника)
 * @param fromName старый ник, точный регистр
 * @param toId     UUID нового аккаунта (offline-UUID нового ника)
 * @param toName   новый ник, точный регистр
 */
public record AccountTransferPlan(UUID fromId, String fromName, UUID toId, String toName) {
}

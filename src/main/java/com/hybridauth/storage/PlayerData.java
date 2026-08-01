package com.hybridauth.storage;

import java.time.Instant;
import java.util.UUID;

/**
 * Данные об игроке, хранящиеся в БД авторизации.
 */
public class PlayerData {

    public enum PlayerType {
        PREMIUM, CRACKED
    }

    private UUID uuid;
    private String username;
    private PlayerType type;
    private String passwordHash; // null for premium players
    private String recoveryCodeHash;
    private Instant registeredAt;
    private Instant lastLoginAt;
    private String lastLoginIp;

    public PlayerData() {
    }

    public PlayerData(UUID uuid, String username, PlayerType type) {
        this.uuid = uuid;
        this.username = username;
        this.type = type;
        this.registeredAt = Instant.now();
        this.lastLoginAt = Instant.now();
    }

    // --- Getters ---

    public UUID getUuid() {
        return uuid;
    }

    public String getUsername() {
        return username;
    }

    public PlayerType getType() {
        return type;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getRecoveryCodeHash() {
        return recoveryCodeHash;
    }

    public Instant getRegisteredAt() {
        return registeredAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public String getLastLoginIp() {
        return lastLoginIp;
    }

    // --- Setters ---

    public void setUuid(UUID uuid) {
        this.uuid = uuid;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public void setType(PlayerType type) {
        this.type = type;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public void setRecoveryCodeHash(String recoveryCodeHash) {
        this.recoveryCodeHash = recoveryCodeHash;
    }

    public void setRegisteredAt(Instant registeredAt) {
        this.registeredAt = registeredAt;
    }

    public void setLastLoginAt(Instant lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public void setLastLoginIp(String lastLoginIp) {
        this.lastLoginIp = lastLoginIp;
    }

    public boolean isPremium() {
        return type == PlayerType.PREMIUM;
    }

    public boolean isCracked() {
        return type == PlayerType.CRACKED;
    }
}

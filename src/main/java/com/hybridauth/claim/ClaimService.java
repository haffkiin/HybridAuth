package com.hybridauth.claim;

import com.hybridauth.HybridAuthMod;
import com.hybridauth.auth.AuthManager;
import com.hybridauth.auth.LicensedNameRules;
import com.hybridauth.auth.PremiumLookupResult;
import com.hybridauth.config.ModConfig;
import com.hybridauth.storage.PlayerData;
import com.hybridauth.transfer.AccountTransferService;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.loading.FMLPaths;

import java.util.Optional;

/**
 * Перенос пиратского аккаунта на лицензионный, когда игрок купил лицензию на свой ник.
 *
 * Порядок: пират вводит {@code /claim confirm} (его отключает), затем заходит с лицензионного клиента.
 * Вход проверяется у Mojang обычным способом, и только после подтверждённой лицензии, до того как
 * игрок попал в мир, данные переносятся на лицензионный UUID. Перенос использует тот же механизм,
 * что и {@code /hybridauth transfer}: бэкапы, откат при сбое, списки, скин, данные других модов.
 */
public final class ClaimService {

    private ClaimService() {
    }

    /**
     * Для входа с ником, на котором уже есть пиратская запись: нужно ли вместо входа по паролю
     * проверить лицензию. Да, если игрок подал заявку и перенос возможен.
     */
    public static boolean shouldVerifyLicense(MinecraftServer server, PlayerData exactCracked,
                                              PremiumLookupResult lookup) {
        if (!ModConfig.SERVER.claimEnabled.get()) {
            return false;
        }
        if (!LicensedNameRules.isLicensedExactConflict(
                exactCracked.getUsername(), lookup.status(), lookup.canonicalName())) {
            return false;
        }
        ClaimRegistry claims = HybridAuthMod.getClaimRegistry();
        Optional<ClaimRegistry.Pending> pending = claims.find(exactCracked.getUsername(), System.currentTimeMillis());
        if (pending.isEmpty() || !pending.get().crackedUuid().equals(exactCracked.getUuid())) {
            return false;
        }
        AccountTransferService.Report check = AccountTransferService.runClaim(
                server, FMLPaths.CONFIGDIR.get(), exactCracked,
                lookup.premiumUuid(), lookup.canonicalName(), false, false, "claim");
        if (!check.success()) {
            HybridAuthMod.getAuthManager().audit("CLAIM_REJECTED", exactCracked.getUsername(),
                    exactCracked.getUuid(), "-", "reason=" + String.join(" ", check.lines()));
            return false;
        }
        return true;
    }

    /**
     * Вызывается после подтверждённого Mojang входа, до входа в мир. Если на этот ник подана заявка,
     * переносит данные.
     *
     * @return {@code null}, если переносить нечего или перенос выполнен; иначе причина отказа
     */
    public static String completeIfPending(MinecraftServer server, GameProfile verified) {
        if (!ModConfig.SERVER.claimEnabled.get()) {
            return null;
        }
        ClaimRegistry claims = HybridAuthMod.getClaimRegistry();
        Optional<ClaimRegistry.Pending> pending = claims.find(verified.getName(), System.currentTimeMillis());
        if (pending.isEmpty()) {
            return null;
        }
        AuthManager authManager = HybridAuthMod.getAuthManager();
        PlayerData source = authManager.getStorage().load(pending.get().crackedUuid())
                .filter(PlayerData::isCracked)
                .orElse(null);
        // Заявка устарела: пиратской записи уже нет (удалена или перенесена). Переносить нечего
        claims.remove(verified.getName());
        if (source == null) {
            return null;
        }

        AccountTransferService.Report report = AccountTransferService.runClaim(
                server, FMLPaths.CONFIGDIR.get(), source,
                verified.getId(), verified.getName(), true, false, "claim");
        if (!report.success()) {
            authManager.audit("CLAIM_FAILED", source.getUsername(), source.getUuid(), "-",
                    "reason=" + String.join(" ", report.lines()));
            return String.join(" ", report.lines());
        }
        claims.putNotice(verified.getId(), ModConfig.SERVER.msgClaimDone.get());
        HybridAuthMod.getLogger().warn("[HybridAuth] Пиратский аккаунт {} перенесён на лицензию {}.",
                source.getUsername(), verified.getId());
        return null;
    }
}

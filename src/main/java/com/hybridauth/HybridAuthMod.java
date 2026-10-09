package com.hybridauth;

import com.hybridauth.auth.AuthManager;
import com.hybridauth.auth.HybridIdentityResolver;
import com.hybridauth.auth.MojangApiClient;
import com.hybridauth.api.HybridAuthApi;
import com.hybridauth.auth.SessionManager;
import com.hybridauth.audit.AuthAuditLogger;
import com.hybridauth.claim.ClaimRegistry;
import com.hybridauth.commands.AuthCommands;
import com.hybridauth.commands.ClaimCommands;
import com.hybridauth.commands.SkinCommands;
import com.hybridauth.config.ModConfig;
import com.hybridauth.events.AuthEventHandler;
import com.hybridauth.events.PremiumSpawnProtection;
import com.hybridauth.skin.MineSkinClient;
import com.hybridauth.skin.MojangSkinFetcher;
import com.hybridauth.skin.SkinService;
import com.hybridauth.skin.SkinStorage;
import com.hybridauth.storage.JsonPlayerStorage;
import com.hybridauth.storage.PlayerStorage;
import com.hybridauth.whitelist.WhitelistGatewayImpl;
import com.hybridauth.whitelist.WhitelistRepairService;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig.Type;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Точка входа в мод HybridAuth.
 */
@Mod("hybridauth")
public class HybridAuthMod {

    private static final Logger LOGGER = LoggerFactory.getLogger("HybridAuth");

    private static AuthManager authManager;
    private static MojangApiClient mojangClient;
    private static SkinService skinService;
    private static String modVersion = "dev";
    private static final PremiumSpawnProtection PREMIUM_SPAWN_PROTECTION = new PremiumSpawnProtection();
    private static final ClaimRegistry CLAIM_REGISTRY = new ClaimRegistry();
    
    public HybridAuthMod(IEventBus modEventBus, ModContainer modContainer) {
        modVersion = modContainer.getModInfo().getVersion().toString();
        LOGGER.info("[HybridAuth] Инициализация мода...");

        // Регистрация конфигурации
        modContainer.registerConfig(Type.SERVER, ModConfig.SERVER_SPEC, "hybridauth-server.toml");

        // Регистрация событий
        modEventBus.addListener(this::setup);
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        
        // Регистрация обработчика действий игроков
        NeoForge.EVENT_BUS.register(new AuthEventHandler());
    }

    private void setup(final FMLCommonSetupEvent event) {
        LOGGER.info("[HybridAuth] Общая настройка...");
        
        mojangClient = new MojangApiClient();
        
        PlayerStorage storage = new JsonPlayerStorage(FMLPaths.CONFIGDIR.get());
        SessionManager sessionManager = new SessionManager();
        AuthAuditLogger auditLogger = new AuthAuditLogger(FMLPaths.CONFIGDIR.get());
        
        authManager = new AuthManager(storage, sessionManager, auditLogger);
        skinService = new SkinService(
                new SkinStorage(FMLPaths.CONFIGDIR.get().resolve("hybridauth")),
                mojangClient,
                new MojangSkinFetcher(),
                new MineSkinClient(modVersion));
        HybridAuthApi.install(new HybridAuthApi(
                new HybridIdentityResolver(mojangClient),
                new WhitelistGatewayImpl(storage, auditLogger)));
    }

    private void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("[HybridAuth] Сервер запускается. Применение настроек...");
        
        mojangClient.setTimeoutMs(ModConfig.SERVER.mojangApiTimeoutMs.get());
        mojangClient.setCacheExpirationMinutes(ModConfig.SERVER.cacheExpirationMinutes.get());
        
        authManager.reloadConfig();
        applySkinConfig();

        if (ModConfig.SERVER.autoRepairVerifiedWhitelist.get()) {
            WhitelistRepairService.repairVerifiedPremiumEntries(
                    event.getServer(),
                    authManager.getStorage(),
                    FMLPaths.CONFIGDIR.get());
        }
        
        if (event.getServer().usesAuthentication()) {
            LOGGER.warn("[HybridAuth] ВНИМАНИЕ: Сервер запущен в режиме online-mode=true!");
            LOGGER.warn("[HybridAuth] Для корректной работы гибридной авторизации установите online-mode=false в server.properties.");
        }
    }

    private void onServerStopping(ServerStoppingEvent event) {
        LOGGER.info("[HybridAuth] Остановка сервера. Сохранение данных...");
        if (authManager != null) {
            authManager.shutdown();
        }
        if (skinService != null) {
            skinService.mineSkin().shutdown();
        }
        HybridAuthApi.clear();
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        AuthCommands.register(event.getDispatcher());
        SkinCommands.register(event.getDispatcher());
        ClaimCommands.register(event.getDispatcher());
    }

    public static AuthManager getAuthManager() {
        return authManager;
    }

    public static MojangApiClient getMojangClient() {
        return mojangClient;
    }

    public static SkinService getSkinService() {
        return skinService;
    }

    /** Применяет настройки скинов из конфига (при старте и по /hybridauth reload). */
    public static void applySkinConfig() {
        if (skinService == null) {
            return;
        }
        skinService.mineSkin().configure(
                ModConfig.SERVER.skinsMineskinApiKey.get(),
                ModConfig.SERVER.skinsRequestTimeoutSeconds.get());
        skinService.mojangSkins().setTimeoutMs(ModConfig.SERVER.mojangApiTimeoutMs.get());
    }

    public static PremiumSpawnProtection getPremiumSpawnProtection() {
        return PREMIUM_SPAWN_PROTECTION;
    }

    public static ClaimRegistry getClaimRegistry() {
        return CLAIM_REGISTRY;
    }

    public static Logger getLogger() {
        return LOGGER;
    }

    public static net.minecraft.server.MinecraftServer getServer() {
        return ServerLifecycleHooks.getCurrentServer();
    }
}

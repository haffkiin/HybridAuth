package com.hybridauth.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Конфигурация мода, генерируемая через NeoForge Config API.
 * Сохраняется в config/hybridauth-server.toml
 */
public class ModConfig {

    public static final ServerConfig SERVER;
    public static final ModConfigSpec SERVER_SPEC;

    static {
        final Pair<ServerConfig, ModConfigSpec> specPair = new ModConfigSpec.Builder().configure(ServerConfig::new);
        SERVER_SPEC = specPair.getRight();
        SERVER = specPair.getLeft();
    }

    public static class ServerConfig {
        public final ModConfigSpec.BooleanValue enabled;
        
        // Premium
        public final ModConfigSpec.BooleanValue enablePremiumAutologin;
        public final ModConfigSpec.EnumValue<ApiFailureAction> onMojangApiFailure;
        public final ModConfigSpec.IntValue mojangApiTimeoutMs;
        public final ModConfigSpec.IntValue cacheExpirationMinutes;
        public final ModConfigSpec.BooleanValue autoRepairVerifiedWhitelist;
        public final ModConfigSpec.BooleanValue premiumSpawnProtection;
        public final ModConfigSpec.IntValue premiumSpawnProtectionSeconds;
        
        // Cracked
        public final ModConfigSpec.IntValue minPasswordLength;
        public final ModConfigSpec.IntValue maxPasswordLength;
        public final ModConfigSpec.IntValue maxLoginAttempts;
        public final ModConfigSpec.IntValue loginAttemptWindowSeconds;
        public final ModConfigSpec.IntValue loginLockoutSeconds;
        public final ModConfigSpec.IntValue authTimeoutSeconds;
        public final ModConfigSpec.BooleanValue enableIpSession;
        public final ModConfigSpec.IntValue sessionDurationMinutes;

        // Messages
        public final ModConfigSpec.ConfigValue<String> msgLoginPrompt;
        public final ModConfigSpec.ConfigValue<String> msgRegisterPrompt;
        public final ModConfigSpec.ConfigValue<String> msgLoginSuccess;
        public final ModConfigSpec.ConfigValue<String> msgRegisterSuccess;
        public final ModConfigSpec.ConfigValue<String> msgWrongPassword;
        public final ModConfigSpec.ConfigValue<String> msgPasswordsDontMatch;
        public final ModConfigSpec.ConfigValue<String> msgPasswordTooShort;
        public final ModConfigSpec.ConfigValue<String> msgPasswordEqualsUsername;
        public final ModConfigSpec.ConfigValue<String> msgAlreadyRegistered;
        public final ModConfigSpec.ConfigValue<String> msgNotRegistered;
        public final ModConfigSpec.ConfigValue<String> msgAlreadyLoggedIn;
        public final ModConfigSpec.ConfigValue<String> msgAuthTimeout;
        public final ModConfigSpec.ConfigValue<String> msgTooManyAttempts;
        public final ModConfigSpec.ConfigValue<String> msgPremiumKick;
        public final ModConfigSpec.ConfigValue<String> msgMojangApiError;
        public final ModConfigSpec.ConfigValue<String> msgPasswordChanged;
        public final ModConfigSpec.ConfigValue<String> msgInvalidUsername;
        public final ModConfigSpec.ConfigValue<String> msgLoginRequired;
        public final ModConfigSpec.ConfigValue<String> msgRecoveryOnlyPassword;
        public final ModConfigSpec.ConfigValue<String> msgPasswordAuthOnly;
        public final ModConfigSpec.ConfigValue<String> msgRecoveryNotConfigured;
        public final ModConfigSpec.ConfigValue<String> msgInvalidRecoveryCode;
        public final ModConfigSpec.ConfigValue<String> msgRecoverySuccess;
        public final ModConfigSpec.ConfigValue<String> msgPasswordTooLong;
        public final ModConfigSpec.ConfigValue<String> msgRecoveryCode;
        public final ModConfigSpec.ConfigValue<String> msgRecoveryCodeWarning;
        public final ModConfigSpec.ConfigValue<String> msgPremiumLoginNotice;
        public final ModConfigSpec.ConfigValue<String> msgCrackedPremiumNickWarning;
        public final ModConfigSpec.ConfigValue<String> msgAuthServerError;
        public final ModConfigSpec.ConfigValue<String> msgInvalidEncryptionKey;
        public final ModConfigSpec.ConfigValue<String> msgAccountDeleted;
        public final ModConfigSpec.ConfigValue<String> msgAdminReloaded;
        public final ModConfigSpec.ConfigValue<String> msgAdminBackupCreated;
        public final ModConfigSpec.ConfigValue<String> msgAdminBackupFailed;
        public final ModConfigSpec.ConfigValue<String> msgAdminAccountNotFound;
        public final ModConfigSpec.ConfigValue<String> msgAdminPremiumNoRecovery;
        public final ModConfigSpec.ConfigValue<String> msgAdminRecoveryCode;
        public final ModConfigSpec.ConfigValue<String> msgAdminRecoveryUsage;
        public final ModConfigSpec.ConfigValue<String> msgDuplicateLogin;
        public final ModConfigSpec.ConfigValue<String> msgLicensedNameOccupied;
        public final ModConfigSpec.ConfigValue<String> msgPasswordCheckPending;

        public ServerConfig(ModConfigSpec.Builder builder) {
            builder.push("general");
            enabled = builder.comment("Enable/disable the mod").define("enabled", true);
            builder.pop();

            builder.push("premium");
            enablePremiumAutologin = builder.comment("Enable auto-login for premium players").define("enablePremiumAutologin", true);
            onMojangApiFailure = builder.comment("Action on Mojang API failure").defineEnum("onMojangApiFailure", ApiFailureAction.KICK);
            mojangApiTimeoutMs = builder.comment("API timeout in milliseconds").defineInRange("mojangApiTimeoutMs", 5000, 1000, 30000);
            cacheExpirationMinutes = builder.comment("Cache expiration time in minutes").defineInRange("cacheExpirationMinutes", 10, 1, 1440);
            autoRepairVerifiedWhitelist = builder.comment(
                    "Repair only proven premium/offline whitelist mismatches after creating a backup")
                    .define("autoRepairVerifiedWhitelist", true);
            premiumSpawnProtection = builder.comment("Protect premium players from damage until their first movement after joining")
                    .define("premiumSpawnProtection", true);
            premiumSpawnProtectionSeconds = builder.comment("Maximum duration of premium spawn protection in seconds")
                    .defineInRange("premiumSpawnProtectionSeconds", 5, 1, 60);
            builder.pop();

            builder.push("cracked");
            minPasswordLength = builder.comment("Minimum password length").defineInRange("minPasswordLength", 6, 1, 64);
            maxPasswordLength = builder.comment("Maximum password length").defineInRange("maxPasswordLength", 64, 8, 256);
            maxLoginAttempts = builder.comment("Max login attempts before kick").defineInRange("maxLoginAttempts", 5, 1, 20);
            loginAttemptWindowSeconds = builder.comment("Window for failed login attempts")
                    .defineInRange("loginAttemptWindowSeconds", 300, 30, 86400);
            loginLockoutSeconds = builder.comment("Lockout after too many failed attempts")
                    .defineInRange("loginLockoutSeconds", 300, 30, 86400);
            authTimeoutSeconds = builder.comment("Time to login/register before kick (0 = disabled)").defineInRange("authTimeoutSeconds", 60, 0, 600);
            enableIpSession = builder.comment("Remember IP to avoid entering password every time. Disable on servers with many shared IPs (NAT, mobile operators)")
                    .define("enableIpSession", true);
            sessionDurationMinutes = builder.comment("IP session lifetime in minutes, counted from the last password/license login. Not extended by use. (0 = until server restart)")
                    .defineInRange("sessionDurationMinutes", 720, 0, Integer.MAX_VALUE);
            builder.pop();

            builder.push("messages");
            msgLoginPrompt = builder.define("loginPrompt", "§eВведите §6/login <пароль> §eдля входа.");
            msgRegisterPrompt = builder.define("registerPrompt", "§eВведите §6/register <пароль> <пароль> §eдля регистрации.");
            msgLoginSuccess = builder.define("loginSuccess", "§aВы успешно авторизованы!");
            msgRegisterSuccess = builder.define("registerSuccess", "§aВы успешно зарегистрированы!");
            msgWrongPassword = builder.define("wrongPassword", "§cНеверный пароль! Попытка %attempt% из %max%.");
            msgPasswordsDontMatch = builder.define("passwordsDontMatch", "§cПароли не совпадают!");
            msgPasswordTooShort = builder.define("passwordTooShort", "§cПароль слишком короткий! Минимум %min% символов.");
            msgPasswordEqualsUsername = builder.define("passwordEqualsUsername", "§cПароль не должен совпадать с ником!");
            msgAlreadyRegistered = builder.define("alreadyRegistered", "§cВы уже зарегистрированы. Используйте /login.");
            msgNotRegistered = builder.define("notRegistered", "§cВы не зарегистрированы. Используйте /register.");
            msgAlreadyLoggedIn = builder.define("alreadyLoggedIn", "§cВы уже авторизованы!");
            msgAuthTimeout = builder.define("authTimeout", "§cВремя на авторизацию истекло.");
            msgTooManyAttempts = builder.define("tooManyAttempts", "§cСлишком много неудачных попыток.");
            msgPremiumKick = builder.define("premiumKick", "§cЭтот ник принадлежит лицензионному аккаунту.\n§eЕсли это ваш аккаунт — войдите через официальный лаунчер.");
            msgMojangApiError = builder.define("mojangApiError", "§cОшибка проверки аккаунта. Попробуйте позже.");
            msgPasswordChanged = builder.define("passwordChanged", "§aПароль успешно изменён!");
            msgInvalidUsername = builder.define("invalidUsername", "§cНекорректное имя игрока.");
            msgLoginRequired = builder.define("loginRequired", "§cСначала авторизуйтесь!");
            msgRecoveryOnlyPassword = builder.define("recoveryOnlyPassword", "§cКоды восстановления доступны только для парольных аккаунтов.");
            msgPasswordAuthOnly = builder.define("passwordAuthOnly", "§cСмена пароля доступна только для парольных аккаунтов.");
            msgRecoveryNotConfigured = builder.define("recoveryNotConfigured", "§cКод восстановления не настроен. Попросите администратора выполнить /hybridauth recovery %username%.");
            msgInvalidRecoveryCode = builder.define("invalidRecoveryCode", "§cНеверный код восстановления.");
            msgRecoverySuccess = builder.define("recoverySuccess", "§aПароль изменён. Старый код восстановления больше недействителен.");
            msgPasswordTooLong = builder.define("passwordTooLong", "§cПароль слишком длинный! Максимум %max% символов.");
            msgRecoveryCode = builder.define("recoveryCode", "§eКод восстановления: &f%code%");
            msgRecoveryCodeWarning = builder.define("recoveryCodeWarning", "§cСохраните код в надёжном месте. Он показывается только один раз и заменяет предыдущий.");
            msgPremiumLoginNotice = builder.define("premiumLoginNotice", "§aВаш ник лицензионный — вход выполнен автоматически.");
            msgCrackedPremiumNickWarning = builder.define("crackedPremiumNickWarning", "§eВнимание: ваш ник зарегистрирован в Mojang как лицензионный.\n§eЕсли это ваш аккаунт — войдите через официальный лаунчер, иначе он останется занят.");
            msgAuthServerError = builder.define("authServerError", "§cОшибка сервера авторизации. Попробуйте позже.");
            msgInvalidEncryptionKey = builder.define("invalidEncryptionKey", "§cОшибка шифрования: неверный ключ.");
            msgAccountDeleted = builder.define("accountDeleted", "§eВаша учётная запись была удалена администратором.");
            msgAdminReloaded = builder.define("adminReloaded", "§aHybridAuth: конфигурация перезагружена.");
            msgAdminBackupCreated = builder.define("adminBackupCreated", "§aHybridAuth: резервная копия создана.");
            msgAdminBackupFailed = builder.define("adminBackupFailed", "§cHybridAuth: не удалось создать резервную копию. Смотрите лог сервера.");
            msgAdminAccountNotFound = builder.define("adminAccountNotFound", "§cАккаунт не найден: %username%");
            msgAdminPremiumNoRecovery = builder.define("adminPremiumNoRecovery", "§cУ премиум-аккаунтов нет кодов восстановления.");
            msgAdminRecoveryCode = builder.define("adminRecoveryCode", "§eОдноразовый код для &f%username%&e: &f%code%");
            msgAdminRecoveryUsage = builder.define("adminRecoveryUsage", "§7Игрок должен использовать /recover <код> <новый пароль> <повтор пароля>.");
            msgDuplicateLogin = builder.define("duplicateLogin", "§cЭтот ник уже играет на сервере с другого адреса. Дождитесь завершения старой сессии или обратитесь в техподдержку.");
            msgLicensedNameOccupied = builder.define("licensedNameOccupied", "§cЭтот ник принадлежит лицензионному аккаунту.\n§eЕсли это ваша пиратка — войдите с паролем и обратитесь в техподдержку для переноса на другой ник.\n§eЕсли вы владелец лицензии — обратитесь в техподдержку.");
            msgPasswordCheckPending = builder.define("passwordCheckPending", "§eПроверка пароля уже выполняется, подождите.");
            builder.pop();
        }
    }

    public enum ApiFailureAction {
        KICK, ALLOW_CRACKED
    }
}

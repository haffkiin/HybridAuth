package com.hybridauth.commands;

import com.hybridauth.HybridAuthMod;
import com.hybridauth.auth.MinecraftNames;
import com.hybridauth.transfer.AccountTransferRules.TargetLicense;
import com.hybridauth.transfer.AccountTransferService;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * {@code /hybridauth transfer <старый ник> <новый ник>} — предпросмотр,
 * {@code /hybridauth transfer <старый ник> <новый ник> confirm} — выполнение.
 */
public final class AccountTransferCommands {

    private AccountTransferCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("transfer")
                .then(Commands.argument("from", StringArgumentType.word())
                        .then(Commands.argument("to", StringArgumentType.word())
                                .executes(context -> start(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "from"),
                                        StringArgumentType.getString(context, "to"),
                                        false))
                                .then(Commands.literal("confirm")
                                        .executes(context -> start(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "from"),
                                                StringArgumentType.getString(context, "to"),
                                                true)))));
    }

    private static int start(CommandSourceStack source, String fromName, String toName, boolean execute) {
        MinecraftServer server = source.getServer();
        Path configDir = FMLPaths.CONFIGDIR.get();
        String actor = source.getTextName();

        if (!MinecraftNames.isValid(toName)) {
            // Ник с недопустимыми символами не отправляем в Mojang, сервис отклонит его сам
            send(source, AccountTransferService.run(server, configDir, fromName, toName,
                    TargetLicense.FREE, execute, actor));
            return 1;
        }

        // Проверка Mojang асинхронная: результат возвращаем на поток сервера
        HybridAuthMod.getMojangClient().checkPremium(toName).whenComplete((result, failure) ->
                server.execute(() -> send(source, AccountTransferService.run(
                        server,
                        configDir,
                        fromName,
                        toName,
                        AccountTransferService.licenseOf(toName, failure == null ? result : null),
                        execute,
                        actor))));
        return 1;
    }

    private static void send(CommandSourceStack source, AccountTransferService.Report report) {
        for (String line : report.lines()) {
            Component component = Component.literal(line);
            if (report.success()) {
                source.sendSuccess(() -> component, false);
            } else {
                source.sendFailure(component);
            }
        }
    }
}

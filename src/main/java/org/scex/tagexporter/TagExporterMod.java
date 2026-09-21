package org.scex.tagexporter;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

@Mod(TagExporterMod.ID)
public final class TagExporterMod {
    public static final String ID = "scex_tag_exporter";
    static final Logger LOGGER = LogUtils.getLogger();
    static final AtomicLong TAG_GENERATION = new AtomicLong();

    public TagExporterMod() {
        NeoForge.EVENT_BUS.addListener(this::registerCommands);
        NeoForge.EVENT_BUS.addListener(this::tagsUpdated);
    }

    private void tagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) TAG_GENERATION.incrementAndGet();
    }

    private void registerCommands(RegisterCommandsEvent event) {
        var root = Commands.literal("scextags").requires(source -> source.hasPermission(2))
            .executes(context -> help(context.getSource()))
            .then(Commands.literal("export").executes(context -> ExportService.export(context.getSource(), "*", "zh_cn"))
                .then(Commands.literal("*").executes(context -> ExportService.export(context.getSource(), "*", "zh_cn"))
                    .then(Commands.argument("language", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(List.of("zh_cn", "en_us"), builder))
                        .executes(context -> ExportService.export(context.getSource(), "*", StringArgumentType.getString(context, "language")))))
                .then(Commands.argument("namespace", StringArgumentType.word())
                    .suggests((context, builder) -> SharedSuggestionProvider.suggest(Stream.concat(Stream.of("*"), Stream.of(BuiltInRegistries.ITEM, BuiltInRegistries.BLOCK, BuiltInRegistries.FLUID).flatMap(registry -> registry.keySet().stream()).map(ResourceLocation::getNamespace).distinct().sorted()), builder))
                    .executes(context -> ExportService.export(context.getSource(), StringArgumentType.getString(context, "namespace"), "zh_cn"))
                    .then(Commands.argument("language", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(List.of("zh_cn", "en_us"), builder))
                        .executes(context -> ExportService.export(context.getSource(), StringArgumentType.getString(context, "namespace"), StringArgumentType.getString(context, "language"))))))
            .then(Commands.literal("hand").executes(context -> {
                var stack = context.getSource().getPlayerOrException().getMainHandItem();
                return entry(context.getSource(), "item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            }));
        for (String kind : List.of("item", "block", "fluid")) {
            root.then(Commands.literal(kind).then(Commands.argument("id", StringArgumentType.greedyString())
                .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(registry(kind).keySet(), builder))
                .executes(context -> entry(context.getSource(), kind, StringArgumentType.getString(context, "id")))));
            root.then(Commands.literal("tag").then(Commands.literal(kind).then(Commands.argument("tag", StringArgumentType.greedyString())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(registry(kind).getTagNames().map(tag -> tag.location().toString()), builder))
                .executes(context -> tag(context.getSource(), kind, StringArgumentType.getString(context, "tag"))))));
        }
        event.getDispatcher().register(root);
        LOGGER.info("SCEX Tag Exporter ready: /scextags export");
    }

    private static int help(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("/scextags export [模组命名空间或 *] [zh_cn|en_us]\n/scextags hand\n/scextags item|block|fluid <ID>\n/scextags tag item|block|fluid <标签>\n导出的是当前已加载标签；修改 KubeJS 后 /reload 完成再导出。多人服文件位于服务端目录。"), false);
        return 1;
    }

    private static Registry<?> registry(String kind) {
        return switch (kind) { case "item" -> BuiltInRegistries.ITEM; case "block" -> BuiltInRegistries.BLOCK; case "fluid" -> BuiltInRegistries.FLUID; default -> throw new IllegalArgumentException(kind); };
    }

    private static int entry(CommandSourceStack source, String kind, String text) {
        ResourceLocation id = ResourceLocation.tryParse(text);
        var holder = id == null ? null : registry(kind).getHolder(id).orElse(null);
        if (holder == null) { source.sendFailure(Component.literal("未找到 " + kind + " " + text)); return 0; }
        List<String> tags = holder.tags().map(tag -> "#" + tag.location()).sorted().toList();
        source.sendSuccess(() -> Component.literal(kind + " " + id + "\n" + (tags.isEmpty() ? "无标签" : String.join("\n", tags))), false);
        return 1;
    }

    private static int tag(CommandSourceStack source, String kind, String text) {
        String tagId = text.startsWith("#") ? text.substring(1) : text;
        var found = registry(kind).getTags().filter(pair -> pair.getFirst().location().toString().equals(tagId)).findFirst();
        if (found.isEmpty()) { source.sendFailure(Component.literal("未找到 " + kind + " 标签 #" + tagId)); return 0; }
        List<String> members = found.get().getSecond().stream().map(holder -> holder.unwrapKey().orElseThrow().location().toString()).sorted().toList();
        source.sendSuccess(() -> Component.literal(kind + " #" + tagId + "：" + members.size() + " 个成员\n" + String.join("\n", members.stream().limit(50).toList()) + (members.size() > 50 ? "\n仅显示前 50 项，完整列表请导出 Excel。" : "")), false);
        return 1;
    }
}

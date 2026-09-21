package org.scex.tagexporter;

import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.fluids.FluidStack;
import org.scex.tagexporter.export.ExportSnapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RegistrySnapshot {
    static ExportSnapshot capture(MinecraftServer server, String namespace, String locale) {
        if (!server.isSameThread()) throw new IllegalStateException("Registry capture must run on the server thread");
        LanguageCatalog language = LanguageCatalog.load(locale);
        Map<String, String> modNames = new HashMap<>();
        List<ExportSnapshot.Mod> mods = ModList.get().getMods().stream().map(mod -> {
            modNames.put(mod.getModId(), mod.getDisplayName());
            return new ExportSnapshot.Mod(mod.getModId(), mod.getDisplayName(), mod.getVersion().toString());
        }).sorted(Comparator.comparing(ExportSnapshot.Mod::id)).toList();
        List<ExportSnapshot.Entry> entries = new ArrayList<>();
        List<ExportSnapshot.Tag> tags = new ArrayList<>();
        BuiltInRegistries.ITEM.holders().forEach(holder -> {
            var id = holder.key().location();
            if (!matches(namespace, id.getNamespace())) return;
            var item = holder.value();
            var stack = item.getDefaultInstance();
            String key = item.getDescriptionId(stack);
            List<String> membership = holder.tags().map(tag -> tag.location().toString()).sorted().toList();
            String linked = item instanceof BlockItem blockItem ? BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString() : "";
            entries.add(entry("item", id.toString(), id.getNamespace(), modNames, language.translate(key, item.getName(stack).getString()), key, membership, linked));
        });
        BuiltInRegistries.BLOCK.holders().forEach(holder -> {
            var id = holder.key().location();
            if (!matches(namespace, id.getNamespace())) return;
            var block = holder.value();
            String key = block.getDescriptionId();
            List<String> membership = holder.tags().map(tag -> tag.location().toString()).sorted().toList();
            String linked = block.asItem() == Items.AIR ? "" : BuiltInRegistries.ITEM.getKey(block.asItem()).toString();
            entries.add(entry("block", id.toString(), id.getNamespace(), modNames, language.translate(key, block.getName().getString()), key, membership, linked));
        });
        BuiltInRegistries.FLUID.holders().forEach(holder -> {
            var id = holder.key().location();
            if (!matches(namespace, id.getNamespace())) return;
            var fluid = holder.value();
            var stack = new FluidStack(fluid, 1000);
            String key = fluid.getFluidType().getDescriptionId(stack);
            List<String> membership = holder.tags().map(tag -> tag.location().toString()).sorted().toList();
            String linked = fluid.getBucket() == Items.AIR ? "" : BuiltInRegistries.ITEM.getKey(fluid.getBucket()).toString();
            entries.add(entry("fluid", id.toString(), id.getNamespace(), modNames, language.translate(key, fluid.getFluidType().getDescription(stack).getString()), key, membership, linked));
        });
        collectTags("item", BuiltInRegistries.ITEM, namespace, tags);
        collectTags("block", BuiltInRegistries.BLOCK, namespace, tags);
        collectTags("fluid", BuiltInRegistries.FLUID, namespace, tags);
        entries.sort(Comparator.comparing(ExportSnapshot.Entry::kind).thenComparing(ExportSnapshot.Entry::id));
        tags.sort(Comparator.comparing(ExportSnapshot.Tag::kind).thenComparing(ExportSnapshot.Tag::id));
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("schemaVersion", "1");
        metadata.put("exporterVersion", ModList.get().getModContainerById(TagExporterMod.ID).orElseThrow().getModInfo().getVersion().toString());
        metadata.put("minecraftVersion", SharedConstants.getCurrentVersion().getName());
        metadata.put("namespaceFilter", namespace);
        metadata.put("tagScope", namespace.equals("*") ? "All loaded item/block/fluid tags, including empty tags" : "Members restricted to registry namespace; only tags with matching members included");
        metadata.put("nameSource", "Installed mod assets language JSON, overridden by config/scex-tag-exporter/lang/<locale>.json; requested locale, then en_us, then runtime name/key. Client resource-pack overrides are not applied.");
        metadata.put("languageKeyCount", Integer.toString(language.translatedKeys()));
        metadata.put("languageReadFailures", Integer.toString(language.failedFiles()));
        metadata.put("oreRule", "item/block tags with path ores, ores/* or *_ores; never guessed from entry ID. Not world generation/distribution.");
        metadata.put("tagGeneration", Long.toString(TagExporterMod.TAG_GENERATION.get()));
        metadata.put("selectedDataPacks", String.join("\n", server.getPackRepository().getSelectedIds()));
        metadata.put("itemCount", Long.toString(entries.stream().filter(e -> e.kind().equals("item")).count()));
        metadata.put("blockCount", Long.toString(entries.stream().filter(e -> e.kind().equals("block")).count()));
        metadata.put("fluidCount", Long.toString(entries.stream().filter(e -> e.kind().equals("fluid")).count()));
        return new ExportSnapshot(Instant.now().toString(), locale, server.isDedicatedServer() ? "dedicated_server" : "integrated_server", metadata, entries, tags, mods);
    }

    private static ExportSnapshot.Entry entry(String kind, String id, String namespace, Map<String, String> modNames, String name, String key, List<String> tags, String linked) {
        List<String> oreTags = kind.equals("fluid") ? List.of() : tags.stream().filter(RegistrySnapshot::isOreTag).toList();
        return new ExportSnapshot.Entry(kind, id, namespace, modNames.getOrDefault(namespace, namespace), name, key, tags, linked, !oreTags.isEmpty(), String.join("\n", oreTags));
    }

    private static boolean isOreTag(String id) {
        String path = id.substring(id.indexOf(':') + 1);
        return path.equals("ores") || path.startsWith("ores/") || path.endsWith("_ores");
    }

    private static <T> void collectTags(String kind, Registry<T> registry, String namespace, List<ExportSnapshot.Tag> target) {
        registry.getTags().forEach(pair -> {
            List<String> members = pair.getSecond().stream().map(holder -> holder.unwrapKey().orElseThrow().location())
                .filter(id -> matches(namespace, id.getNamespace())).map(Object::toString).sorted().toList();
            if (namespace.equals("*") || !members.isEmpty()) target.add(new ExportSnapshot.Tag(kind, pair.getFirst().location().toString(), members));
        });
    }

    private static boolean matches(String filter, String namespace) { return filter.equals("*") || filter.equals(namespace); }
}

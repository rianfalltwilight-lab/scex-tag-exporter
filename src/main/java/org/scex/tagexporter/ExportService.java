package org.scex.tagexporter;

import com.google.gson.GsonBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;
import org.scex.tagexporter.export.ExportSnapshot;
import org.scex.tagexporter.export.XlsxExporter;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

final class ExportService {
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    static int export(CommandSourceStack source, String namespace, String locale) {
        if (!LanguageCatalog.validLocale(locale)) {
            source.sendFailure(Component.literal("语言代码示例：zh_cn、en_us。"));
            return 0;
        }
        if (!BUSY.compareAndSet(false, true)) {
            source.sendFailure(Component.literal("已有导出正在写入，请等完成后再试。"));
            return 0;
        }
        try {
            ExportSnapshot snapshot = RegistrySnapshot.capture(source.getServer(), namespace, locale);
            if (snapshot.entries().isEmpty()) {
                source.sendFailure(Component.literal("没有找到命名空间 " + namespace + " 的物品、方块或流体。使用 /scextags export 导出全部。"));
                BUSY.set(false);
                return 0;
            }
            source.sendSuccess(() -> Component.literal("已读取 " + snapshot.entries().size() + " 个注册条目、" + snapshot.tags().size() + " 个标签，正在生成 Excel…"), false);
            // A non-daemon worker finishes its bounded file writes even during a normal server shutdown.
            Thread worker = new Thread(() -> write(source, snapshot), "scex-tag-export-writer");
            worker.setDaemon(false);
            worker.start();
            return 1;
        } catch (Exception error) {
            BUSY.set(false);
            TagExporterMod.LOGGER.error("SCEX_TAG_EXPORT_CAPTURE_FAILED", error);
            source.sendFailure(Component.literal("读取注册表失败：" + error.getMessage() + "；详情见日志。"));
            return 0;
        }
    }

    private static void write(CommandSourceStack source, ExportSnapshot snapshot) {
        Path directory = FMLPaths.GAMEDIR.get().resolve("exports/scex-tags").resolve(STAMP.format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 8));
        try {
            Files.createDirectories(directory);
            Path json = directory.resolve("snapshot.json.partial");
            try (Writer writer = Files.newBufferedWriter(json, StandardCharsets.UTF_8)) {
                new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(snapshot, writer);
            }
            commit(json, directory.resolve("snapshot.json"));
            Path xlsx = directory.resolve("registry-tags.xlsx.partial");
            XlsxExporter.write(xlsx, snapshot);
            commit(xlsx, directory.resolve("registry-tags.xlsx"));
            Files.writeString(directory.resolve("COMPLETE.txt"), "SCEX Tag Exporter " + snapshot.metadata().get("exporterVersion") + "\n" + snapshot.exportedAt() + "\n", StandardCharsets.UTF_8);
            String message = "标签导出完成：" + directory.toAbsolutePath() + "（registry-tags.xlsx / snapshot.json）";
            TagExporterMod.LOGGER.info("SCEX_TAG_EXPORT_COMPLETE {} entries={} tags={}", directory.toAbsolutePath(), snapshot.entries().size(), snapshot.tags().size());
            if (!source.getServer().isStopped()) source.getServer().execute(() -> source.sendSuccess(() -> Component.literal(message), false));
        } catch (Exception error) {
            TagExporterMod.LOGGER.error("SCEX_TAG_EXPORT_WRITE_FAILED {}", directory, error);
            if (!source.getServer().isStopped()) source.getServer().execute(() -> source.sendFailure(Component.literal("导出失败：" + error.getMessage() + "；详情见日志。未完成文件保留于 " + directory.toAbsolutePath())));
        } finally { BUSY.set(false); }
    }

    private static void commit(Path from, Path to) throws IOException {
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(from, to); }
    }
}

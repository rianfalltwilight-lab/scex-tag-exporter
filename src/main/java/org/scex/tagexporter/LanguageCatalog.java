package org.scex.tagexporter;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Server-safe language lookup: no client-only classes or network requests. */
final class LanguageCatalog {
    private static final Pattern LOCALE = Pattern.compile("[a-z]{2,3}_[a-z]{2,3}");
    private final Map<String, String> fallback = new HashMap<>();
    private final Map<String, String> selected = new HashMap<>();
    private int failedFiles;

    static boolean validLocale(String locale) { return LOCALE.matcher(locale).matches(); }

    static LanguageCatalog load(String locale) {
        LanguageCatalog catalog = new LanguageCatalog();
        catalog.classpath("en_us", catalog.fallback);
        catalog.classpath(locale, catalog.selected);
        for (var file : ModList.get().getModFiles()) {
            Path assets = file.getFile().findResource("assets");
            if (!Files.isDirectory(assets)) continue;
            try (var namespaces = Files.list(assets)) {
                for (Path namespace : namespaces.filter(Files::isDirectory).sorted().toList()) {
                    catalog.read(namespace.resolve("lang/en_us.json"), catalog.fallback);
                    catalog.read(namespace.resolve("lang/" + locale + ".json"), catalog.selected);
                }
            } catch (IOException | RuntimeException error) {
                catalog.failedFiles++;
                TagExporterMod.LOGGER.warn("Cannot inspect language assets in {}", assets, error);
            }
        }
        Path overrides = FMLPaths.CONFIGDIR.get().resolve("scex-tag-exporter/lang");
        catalog.read(overrides.resolve("en_us.json"), catalog.fallback);
        catalog.read(overrides.resolve(locale + ".json"), catalog.selected);
        return catalog;
    }

    private void classpath(String locale, Map<String, String> values) {
        try (InputStream in = LanguageCatalog.class.getResourceAsStream("/assets/minecraft/lang/" + locale + ".json")) {
            if (in != null) parse(new InputStreamReader(in, StandardCharsets.UTF_8), values);
        } catch (IOException | RuntimeException error) { failedFiles++; }
    }

    private void read(Path path, Map<String, String> values) {
        if (!Files.isRegularFile(path)) return;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            parse(reader, values);
        } catch (IOException | RuntimeException error) {
            failedFiles++;
            TagExporterMod.LOGGER.warn("Cannot load language file {}", path, error);
        }
    }

    private static void parse(Reader reader, Map<String, String> values) {
        for (Map.Entry<String, JsonElement> entry : JsonParser.parseReader(reader).getAsJsonObject().entrySet()) {
            if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString()) {
                values.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
    }

    String translate(String key, String defaultName) {
        if (selected.containsKey(key)) return selected.get(key);
        if (fallback.containsKey(key)) return fallback.get(key);
        return defaultName == null || defaultName.isBlank() ? key : defaultName;
    }

    int failedFiles() { return failedFiles; }
    int translatedKeys() { return selected.size(); }
}

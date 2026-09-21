package org.scex.tagexporter.export;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable registry/tag data detached from the Minecraft server thread. */
public record ExportSnapshot(
        String exportedAt,
        String language,
        String source,
        Map<String, String> metadata,
        List<Entry> entries,
        List<Tag> tags,
        List<Mod> mods) {

    public ExportSnapshot {
        Objects.requireNonNull(exportedAt, "exportedAt");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(metadata, "metadata");
        Map<String, String> metadataCopy = new LinkedHashMap<>();
        metadata.forEach((key, value) -> metadataCopy.put(
                Objects.requireNonNull(key, "metadata key"),
                Objects.requireNonNull(value, "metadata value")));
        metadata = Collections.unmodifiableMap(metadataCopy);
        entries = List.copyOf(entries);
        tags = List.copyOf(tags);
        mods = List.copyOf(mods);
    }

    public record Entry(
            String kind,
            String id,
            String namespace,
            String modName,
            String name,
            String translationKey,
            List<String> tags,
            String linkedId,
            boolean ore,
            String oreEvidence) {
        public Entry {
            kind = requireKind(kind);
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(namespace, "namespace");
            Objects.requireNonNull(modName, "modName");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(translationKey, "translationKey");
            tags = List.copyOf(tags);
            Objects.requireNonNull(linkedId, "linkedId");
            Objects.requireNonNull(oreEvidence, "oreEvidence");
        }
    }

    public record Tag(String kind, String id, List<String> members) {
        public Tag {
            kind = requireKind(kind);
            Objects.requireNonNull(id, "id");
            members = List.copyOf(members);
        }
    }

    public record Mod(String id, String name, String version) {
        public Mod {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(version, "version");
        }
    }

    private static String requireKind(String value) {
        Objects.requireNonNull(value, "kind");
        if (!value.equals("item") && !value.equals("block") && !value.equals("fluid")) {
            throw new IllegalArgumentException("Unsupported registry kind: " + value);
        }
        return value;
    }
}

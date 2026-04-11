package com.dbtespresso.parser;

import com.dbtespresso.jinja.Dependency;
import com.dbtespresso.jinja.RefExtractor;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * Walks a dbt project directory tree and produces {@link ParsedModel}s.
 * Each .sql file under models/, tests/, snapshots/, analyses/ is parsed
 * for refs, sources, and config.
 */
public final class DbtProjectScanner {

    private static final Map<String, ParsedModel.ResourceType> DIR_MAP = Map.of(
            "models", ParsedModel.ResourceType.MODEL,
            "tests", ParsedModel.ResourceType.TEST,
            "snapshots", ParsedModel.ResourceType.SNAPSHOT,
            "analyses", ParsedModel.ResourceType.ANALYSIS
    );

    private final Path projectRoot;

    public DbtProjectScanner(Path projectRoot) {
        if (!Files.isDirectory(projectRoot))
            throw new IllegalArgumentException("Not a directory: " + projectRoot);
        this.projectRoot = projectRoot;
    }

    /** Scan all resource directories. */
    public List<ParsedModel> scan() {
        return DIR_MAP.entrySet().stream()
                .flatMap(e -> scanDir(e.getKey(), e.getValue()))
                .toList();
    }

    /** Scan only models/. */
    public List<ParsedModel> scanModels() {
        return scanDir("models", ParsedModel.ResourceType.MODEL).toList();
    }

    private Stream<ParsedModel> scanDir(String dirName, ParsedModel.ResourceType type) {
        Path dir = projectRoot.resolve(dirName);
        if (!Files.isDirectory(dir)) return Stream.empty();
        try {
            return Files.walk(dir)
                    .filter(p -> p.toString().endsWith(".sql"))
                    .filter(Files::isRegularFile)
                    .map(p -> parseFile(p, type));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed scanning " + dir, e);
        }
    }

    private ParsedModel parseFile(Path path, ParsedModel.ResourceType type) {
        try {
            String sql = Files.readString(path);
            return new ParsedModel(
                    deriveModelName(path), type, path, sql,
                    RefExtractor.extract(sql),
                    ConfigExtractor.extract(sql)
            );
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading " + path, e);
        }
    }

    static String deriveModelName(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}

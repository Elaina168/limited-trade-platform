package com.elaina.limitedtrade.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.core.io.ClassPathResource;

public final class EvaluationIdentity {
    private EvaluationIdentity() { }

    public static String digest(byte[] contents) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contents)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    public static byte[] resource(String name) throws IOException {
        return new ClassPathResource(name).getContentAsByteArray();
    }

    public static String source(String name) throws IOException {
        return digest(Files.readAllBytes(Path.of(name)));
    }

    public static Path report(String prefix) throws IOException {
        return Files.createTempFile(Files.createDirectories(Path.of("tools", "evaluation-baselines")), prefix, ".json");
    }
}

package io.training;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/**
 * Bounded, checksummed containers shared by value models and parity fixtures.
 */
public final class NeuralValueFiles {

    public static final int MAX_BYTES = 64 * 1024 * 1024;

    private NeuralValueFiles() { }

    public static byte[] read(Path path) throws IOException {

        if (Files.size(path) > MAX_BYTES)
            throw new IOException("Value file exceeds 64 MiB: " + path);

        byte[] file;
        try (InputStream input = Files.newInputStream(path)) {
            file = input.readNBytes(MAX_BYTES + 1);
        }

        if (file.length < 40 || file.length > MAX_BYTES
                || !MessageDigest.isEqual(
                hash(Arrays.copyOf(file, file.length - 32)),
                Arrays.copyOfRange(file, file.length - 32, file.length)))
            throw new IOException("Invalid value-file length or checksum: " + path);

        return file;

    }

    public static DataInputStream payload(byte[] file) {
        return new DataInputStream(new ByteArrayInputStream(file, 0, file.length - 32));
    }

    public static String text(DataInputStream input) throws IOException {

        int length = input.readInt();
        if (length < 0 || length > 32768)
            throw new IOException("Invalid value-file string length");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length)
            throw new EOFException();
        return new String(bytes, StandardCharsets.UTF_8);

    }

    public static String identity(DataInputStream input) throws IOException {

        byte[] bytes = input.readNBytes(32);
        if (bytes.length != 32)
            throw new EOFException();
        return HexFormat.of().formatHex(bytes);

    }

    public static byte[] hash(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(hash(bytes));
    }

}
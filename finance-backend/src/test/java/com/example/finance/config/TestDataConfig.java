package com.example.finance.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TestDataConfig {

    @Value("${finance.data-dir:data}")
    private String dataDir;

    private final Map<String, byte[]> backups = new ConcurrentHashMap<>();

    public void backup(String filename) {
        try {
            Path file = Path.of(dataDir, filename);
            if (Files.exists(file)) {
                backups.put(filename, Files.readAllBytes(file));
            }
        } catch (IOException ignored) {
        }
    }

    public void reset(String filename) {
        byte[] data = backups.get(filename);
        if (data != null) {
            try {
                Files.createDirectories(Path.of(dataDir));
                Files.write(Path.of(dataDir, filename), data);
            } catch (IOException ignored) {
            }
        }
    }

    public void resetAll() {
        backups.keySet().forEach(this::reset);
    }
}

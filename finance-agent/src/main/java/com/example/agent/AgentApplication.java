package com.example.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

@SpringBootApplication
public class AgentApplication {
    public static void main(String[] args) {
        loadDotEnv();
        SpringApplication.run(AgentApplication.class, args);
    }

    private static void loadDotEnv() {
        String[] paths = {"../.env", ".env"};
        for (String path : paths) {
            try (InputStream in = new FileInputStream(path)) {
                Properties props = new Properties();
                props.load(in);
                for (String key : props.stringPropertyNames()) {
                    System.setProperty(key, props.getProperty(key).trim());
                }
                System.out.println("[.env] Loaded from: " +
                        new java.io.File(path).getAbsolutePath());
                return;
            } catch (Exception ignored) {
            }
        }
        System.err.println("[.env] WARNING: .env file not found at ../.env or .env");
    }
}

package com.example.agent.config;

import org.springframework.boot.env.PropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Makes Spring Boot recognize .env files (same key=value format as .properties).
 *
 * Registered via META-INF/spring/org.springframework.boot.env.PropertySourceLoader.
 * After this, spring.config.import can reference .env files directly.
 */
public class EnvPropertySourceLoader implements PropertySourceLoader {

    @Override
    public String[] getFileExtensions() {
        return new String[]{"env"};
    }

    @Override
    public List<PropertySource<?>> load(String name, Resource resource) throws IOException {
        Properties props = new Properties();
        try (InputStream in = resource.getInputStream()) {
            props.load(in);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (String key : props.stringPropertyNames()) {
            map.put(key, props.getProperty(key));
        }
        return List.of(new MapPropertySource(name, map));
    }
}

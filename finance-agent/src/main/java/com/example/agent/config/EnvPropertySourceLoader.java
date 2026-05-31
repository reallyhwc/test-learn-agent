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
 * 【.env 文件属性源加载器】
 *
 * <p>让 Spring Boot 识别 {@code .env} 文件（格式同 {@code .properties}）。
 * 通过 {@code META-INF/spring/org.springframework.boot.env.PropertySourceLoader} 注册，
 * 之后 {@code spring.config.import} 可以直接引用 .env 文件。
 *
 * @see AgentApplication Agent 启动入口，加载 .env 到 System Properties
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

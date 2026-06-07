package com.example.agent.prompt;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prompt 加载器 — 从文件系统读取 Markdown 文件，缓存并拼装 System Prompt。
 *
 * <p>支持模板变量 <code>{{varName}}</code>，在拼装时替换为实际值。
 * 缓存策略：首次加载后存入 ConcurrentHashMap，文件变更需重启（或调用 clearCache()）。
 */
@Slf4j
public class PromptLoader {

    private final Path baseDir;
    private final String version;
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    public PromptLoader(String baseDirPath, String version) {
        this.baseDir = Path.of(baseDirPath);
        this.version = version;
    }

    /**
     * 加载单个 Prompt 文件。
     *
     * @param agent Agent 子目录名（supervisor/bookkeeper/analyst/single-agent）
     * @param file  文件名（不含 .md 后缀）
     * @return 文件内容，文件不存在时返回空字符串
     */
    public String load(String agent, String file) {
        String key = version + "/" + agent + "/" + file;
        return cache.computeIfAbsent(key, k -> readFile(baseDir.resolve(version).resolve(agent).resolve(file + ".md")));
    }

    /**
     * 加载 shared 目录下的文件（跨版本共享）。
     */
    public String loadShared(String file) {
        String key = "shared/" + file;
        return cache.computeIfAbsent(key, k -> readFile(baseDir.resolve("shared").resolve(file + ".md")));
    }

    /**
     * 拼装完整 System Prompt。
     *
     * @param agent Agent 名称
     * @param vars  模板变量键值对（如 userId, accountSummary 等）
     * @return 拼装后的完整 Prompt
     */
    public String assemble(String agent, Map<String, String> vars) {
        List<String> parts = switch (agent) {
            case "supervisor" -> List.of(load("supervisor", "classify"));
            case "bookkeeper" -> List.of(
                    load("bookkeeper", "system"),
                    load("bookkeeper", "tool-rules"),
                    load("bookkeeper", "response-format"));
            case "analyst" -> List.of(
                    load("analyst", "system"),
                    load("analyst", "tool-rules"),
                    load("analyst", "response-format"));
            case "single-agent" -> List.of(
                    load("single-agent", "system"),
                    load("single-agent", "tool-rules"),
                    load("single-agent", "response-format"));
            default -> throw new IllegalArgumentException("Unknown agent: " + agent);
        };

        String prompt = String.join("\n\n", parts);
        if (vars != null) {
            for (var entry : vars.entrySet()) {
                prompt = prompt.replace("{{" + entry.getKey() + "}}", entry.getValue());
            }
        }
        return prompt;
    }

    /** 清空缓存（测试/热重载用） */
    public void clearCache() {
        cache.clear();
    }

    private String readFile(Path path) {
        try {
            if (Files.exists(path)) {
                return Files.readString(path).trim();
            }
        } catch (IOException e) {
            log.warn("读取 Prompt 文件失败: {}", path, e);
        }
        log.debug("Prompt 文件未找到: {}", path);
        return "";
    }
}

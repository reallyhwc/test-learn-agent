---
alwaysApply: true
---

# CSV 存储规范

## Schema 定义

- 使用 `CsvSchema.builder().addColumn()` 链式构建
- 字段顺序必须与 Model 字段声明顺序一致
- 必须调用 `.withHeader()` 包含列头

## Schema 升级流程

1. 新增字段到 Model 类
2. 定义新 Schema（包含新字段）
3. 定义旧 Schema（`_OLD` 或 `_NO_XXX` 后缀）
4. `loadXxx()` 中检测 CSV 列头格式
5. 按格式选择对应 Schema 加载
6. 旧格式加载后自动补全新字段
7. `persistXxx()` 写回新格式

## 列头检测

```java
private boolean csvHasColumn(File file, String columnName) {
    try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
        String header = reader.readLine();
        return header != null && header.contains(columnName);
    } catch (IOException e) {
        return false;
    }
}
```

## 旧数据兼容

- 加载旧格式后必须补全新字段（不留空值）
- 使用 `inferXxx()` 方法根据已有字段推导新字段值
- 补全后立即 persist 写回新格式

## 种子数据

- CSV 为空时初始化种子数据
- 种子数据作为 `initXxxSeedData()` 独立方法
- 种子数据必须包含所有字段

## 线程安全

- 读操作：`dataLock.readLock().lock()` / `unlock()`
- 写操作：`dataLock.writeLock().lock()` / `unlock()`
- 使用 try-finally 确保锁释放

## 持久化

- 先写临时文件（`.tmp` 后缀）
- 原子重命名覆盖目标文件
- 重命名失败时回退到直接写入

## ID 生成

- 使用 `AtomicLong` 自增
- 加载后从 `max(id) + 1` 开始

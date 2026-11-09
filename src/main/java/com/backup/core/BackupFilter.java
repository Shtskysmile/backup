package com.backup.core;

import java.nio.file.FileSystems;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 不可变、规范化后的文件/链接筛选条件（record 值对象，模块间唯一筛选契约）。
 *
 * <p>
 * 匹配语义：
 * </p>
 * <ul>
 * <li>同类多值用分号分隔（OR 关系）；不同类条件之间取交集（AND 关系）；
 * 排除规则永远优先于包含规则；</li>
 * <li>路径条件使用相对正斜杠语法；名字条件只匹配最后一级文件名（基名）；
 * glob 语法区分大小写；</li>
 * <li>「双星目录段」（两个星号后跟斜杠）还能匹配零层目录（标准 glob 不支持，是本类的语义扩展）；</li>
 * <li>扩展名比较忽略大小写与前导点；点文件（.bashrc）和以点结尾的名字视为无扩展名；</li>
 * <li>未指定扩展名时：includeExtensionless=false 表示不限类型；true 表示只保留无扩展名文件；</li>
 * <li>时间与字节边界均为闭区间（含端点）；</li>
 * <li>属主名精确比较但不区分大小写；记录为空的属主永远不命中非空的属主条件；</li>
 * <li>目录剪枝只看显式排除规则（包含规则不用于剪枝，避免误跳过子树内被包含的文件）。</li>
 * </ul>
 */
public record BackupFilter(String includePaths,
                           String excludePaths,
                           String extensions,
                           boolean includeExtensionless,
                           String names,
                           String owners,
                           Long modifiedAfterMillis,
                           Long modifiedBeforeMillis,
                           Long minBytes,
                           Long maxBytes) {
    // record 不能再挂实例字段：glob 编译缓存只能做成静态有界 LRU，避免扫描大目录时每个文件重复编译。
    private static final Map<String, List<Pattern>> GLOBS =
            Collections.synchronizedMap(new LinkedHashMap<>(32, .75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<Pattern>> entry) {
                    return size() > 256;
                }
            });

    public BackupFilter {
        // 规范化顺序：先统一各条件串，再做交叉校验，最后预编译 glob（非法语法在此抛出）。
        includePaths = normalize(includePaths, false);
        excludePaths = normalize(excludePaths, false);
        extensions = normalize(extensions, true);
        names = normalize(names, false);
        owners = normalizeOwner(owners);
        if ((minBytes != null && minBytes < 0) || (maxBytes != null && maxBytes < 0))
            throw new IllegalArgumentException("Byte limits must be nonnegative");
        if (minBytes != null && maxBytes != null && minBytes > maxBytes)
            throw new IllegalArgumentException("Minimum size exceeds maximum size");
        if (modifiedAfterMillis != null && modifiedBeforeMillis != null && modifiedAfterMillis > modifiedBeforeMillis)
            throw new IllegalArgumentException("Earliest modification time exceeds latest time");
        patterns(includePaths);
        // 预编译三个 glob 条件：把「扫描一半才发现语法错」提前到构造期。
        patterns(excludePaths);
        patterns(names);
    }

    /** 无属主条件的便捷构造器（九参旧签名兼容，属主视为未提供）。 */
    public BackupFilter(String includePaths,
                        String excludePaths,
                        String extensions,
                        boolean includeExtensionless,
                        String names,
                        Long modifiedAfterMillis,
                        Long modifiedBeforeMillis,
                        Long minBytes,
                        Long maxBytes) {
        this(includePaths,
             excludePaths,
             extensions,
             includeExtensionless,
             names,
             "",
             modifiedAfterMillis,
             modifiedBeforeMillis,
             minBytes,
             maxBytes);
    }

    /** 空条件工厂：等价于 new BackupFilter(全空参数)，表示不筛选。 */
    public static BackupFilter all() {
        return new BackupFilter("", "", "", false, "", "", null, null, null, null);
    }

    /** 三参便捷重载：属主条件视为未提供（旧调用兼容）。 */
    public boolean matches(String relativePath, long size, long modified) {
        return matches(relativePath, size, modified, "");
    }

    /**
     * 完整判定：排除优先 → 路径包含 → 名字 → 属主 → 类型（扩展名/无扩展名）→ 时间与尺寸端点。
     * size&lt;0 一律不匹配（调用方把"无意义尺寸"也挡掉）。
     */
    public boolean matches(String relativePath, long size, long modified, String owner) {
        Objects.requireNonNull(relativePath, "relativePath");
        if (size < 0 || any(excludePaths, relativePath))
            return false;
        if (!includePaths.isEmpty() && !any(includePaths, relativePath))
            return false;
        String basename = relativePath.substring(relativePath.lastIndexOf('/') + 1);
        if (!names.isEmpty() && !any(names, basename))
            return false;
        if (!owners.isEmpty() && !matchesOwner(owner))
            return false;
        if (!extensions.isEmpty() || includeExtensionless) {
            int dot = basename.lastIndexOf('.');
            String extension = dot <= 0 || dot == basename.length() - 1
                                       ? ""
                                       : basename.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (extension.isEmpty()) {
                if (!includeExtensionless)
                    return false;
            } else if (!Arrays.asList(extensions.split(";")).contains(extension))
                return false;
        }
        return (modifiedAfterMillis == null || modified >= modifiedAfterMillis)
                // 时间与尺寸端点判断（均含边界）；到这里前面条件都已通过。
                && (modifiedBeforeMillis == null || modified <= modifiedBeforeMillis) &&
                (minBytes == null || size >= minBytes) && (maxBytes == null || size <= maxBytes);
    }

    /** 属主精确匹配（双方小写化）；记录为空的属主永远不命中非空条件。 */
    private boolean matchesOwner(String owner) {
        if (owner == null || owner.isBlank())
            return false;
        String normalized = owner.strip().toLowerCase(Locale.ROOT);
        return Arrays.asList(owners.split(";")).contains(normalized);
    }

    /** 目录剪枝只看排除规则（含/结尾形式），命中即整棵子树跳过。 */
    public boolean excludesDirectory(String relativePath) {
        Objects.requireNonNull(relativePath, "relativePath");
        String path = relativePath.endsWith("/") ? relativePath.substring(0, relativePath.length() - 1) : relativePath;
        return any(excludePaths, path) || any(excludePaths, path + "/");
    }

    /** 是否完全不限制：目录剪枝策略据此决定是否保留空目录。 */
    public boolean isUnrestricted() {
        return includePaths.isEmpty() && excludePaths.isEmpty() && extensions.isEmpty() && !includeExtensionless &&
                names.isEmpty() && owners.isEmpty() && modifiedAfterMillis == null && modifiedBeforeMillis == null &&
                minBytes == null && maxBytes == null;
    }

    /** 确定性、无歧义的字符串表示：同一筛选语义必得同一字符串，适合派生任务身份。 */
    public String identity() {
        StringBuilder result = new StringBuilder("filter-v2:");
        for (String value : List.of(includePaths,
                                    excludePaths,
                                    extensions,
                                    Boolean.toString(includeExtensionless),
                                    names,
                                    owners,
                                    String.valueOf(modifiedAfterMillis),
                                    String.valueOf(modifiedBeforeMillis),
                                    String.valueOf(minBytes),
                                    String.valueOf(maxBytes))) {
            result.append(value.length()).append(':').append(value);
        }
        return result.toString();
    }

    /** 多值规范化：strip → (扩展名小写+去点) → 去重 → 排序，保证相同语义得到相同字符串。 */
    private static String normalize(String value, boolean extension) {
        if (value == null)
            return "";
        return Arrays.stream(value.split(";"))
                .map(String::strip)
                .map(s -> extension ? (s.startsWith(".") ? s.substring(1) : s).toLowerCase(Locale.ROOT) : s)
                .filter(s -> !s.isEmpty())
                .distinct()
                .sorted()
                .collect(Collectors.joining(";"));
    }

    /** 属主规范化：小写化 + 去重 + 排序（与扩展名共用语义但不去前导点）。 */
    private static String normalizeOwner(String value) {
        if (value == null)
            return "";
        return Arrays.stream(value.split(";"))
                .map(String::strip)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .distinct()
                .sorted()
                .collect(Collectors.joining(";"));
    }

    /** 任一编译后的模式匹配即命中（同类条件 OR 语义的落点）。 */
    private static boolean any(String criteria, String value) {
        return patterns(criteria).stream().anyMatch(p -> p.matcher(value).matches());
    }

    /** glob 编译结果 LRU 缓存：避免大目录扫描时对同一条件串重复编译。 */
    private static List<Pattern> patterns(String criteria) {
        if (criteria.isEmpty())
            return List.of();
        synchronized (GLOBS) {
            return GLOBS.computeIfAbsent(criteria,
                                         key -> Arrays.stream(key.split(";")).map(BackupFilter::compileGlob).toList());
        }
    }

    /**
     * glob→正则转译：先借 getPathMatcher 验证语法合法性，再手工展开——
     * 双星目录段展开为零层或多层目录（标准 glob 不支持，此处扩展）、
     * 花括号为分组、字符类内特殊字符转义并保证不匹配斜杠。
     */
    private static Pattern compileGlob(String glob) {
        // 先按标准 glob 语法校验合法性（非法语法在此抛出），再应用「零层目录双星」语义扩展。
        FileSystems.getDefault().getPathMatcher("glob:" + glob);
        StringBuilder regex = new StringBuilder();
        boolean group = false;
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
            case '\\' -> regex.append(Pattern.quote(String.valueOf(glob.charAt(++i))));
            case '*' -> {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    i++;
                    if ((i == 1 || glob.charAt(i - 2) == '/') && i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                        // 双星后跟斜杠 → 零层或多层目录（本产品的语义扩展）。
                        regex.append("(?:.*/)?");
                        i++;
                    } else
                        regex.append(".*");
                } else
                    regex.append("[^/]*");
            }
            case '?' -> regex.append("[^/]");
            case '{' -> {
                regex.append("(?:");
                group = true;
            }
            case '}' -> {
                regex.append(')');
                group = false;
            }
            case ',' -> regex.append(group ? "|" : ",");
            case '[' -> {
                // 字符类：支持取反 [!..]，转义 & [ \ 三个特殊字符，并保证不匹配 /。
                regex.append('[');
                if (glob.charAt(i + 1) == '!') {
                    regex.append('^');
                    i++;
                } else if (glob.charAt(i + 1) == '^') {
                    regex.append("\\^");
                    i++;
                }
                while (glob.charAt(++i) != ']') {
                    char member = glob.charAt(i);
                    if (member == '&' || member == '[' || member == '\\')
                        regex.append('\\');
                    regex.append(member);
                }
                regex.append("&&[^/]]");
            }
            default ->
                regex.append(Pattern.quote(String.valueOf(c)));
                // 其余字符一律字面量引用：元字符不做隐式展开（安全默认）。
            }
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }
}

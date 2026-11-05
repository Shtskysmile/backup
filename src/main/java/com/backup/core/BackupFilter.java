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
 * Immutable, normalized file/link selection criteria. Alternatives are separated by semicolons;
 * different criteria intersect and exclusions win. Paths use relative forward-slash syntax,
 * names match only the basename, and globs are case sensitive. A double-star directory component
 * also matches zero directories. Extension comparisons ignore case and an optional leading dot.
 * Dotfiles and names ending in a dot count as extensionless. With no extensions specified,
 * includeExtensionless=false means no type restriction; true selects only extensionless names.
 * Time and byte bounds include both endpoints. Owner names are compared exactly but without
 * case sensitivity; a blank recorded owner never matches a nonempty owner criterion.
 * Directory pruning uses only explicit exclusions.
 */
public record BackupFilter(String includePaths, String excludePaths, String extensions,
                           boolean includeExtensionless, String names, String owners, Long modifiedAfterMillis,
                           Long modifiedBeforeMillis, Long minBytes, Long maxBytes) {
    // Records cannot hold additional instance state. A bounded cache avoids compiling globs per file.
    private static final Map<String, List<Pattern>> GLOBS = Collections.synchronizedMap(
            new LinkedHashMap<>(32, .75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, List<Pattern>> entry) {
                    return size() > 256;
                }
            });

    public BackupFilter {
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
        patterns(excludePaths);
        patterns(names);
    }

    /** Convenience constructor for criteria without an owner condition. */
    public BackupFilter(String includePaths, String excludePaths, String extensions, boolean includeExtensionless,
                        String names, Long modifiedAfterMillis, Long modifiedBeforeMillis, Long minBytes, Long maxBytes) {
        this(includePaths, excludePaths, extensions, includeExtensionless, names, "",
                modifiedAfterMillis, modifiedBeforeMillis, minBytes, maxBytes);
    }

    public static BackupFilter all() {
        return new BackupFilter("", "", "", false, "", "", null, null, null, null);
    }

    public boolean matches(String relativePath, long size, long modified) {
        return matches(relativePath, size, modified, "");
    }

    public boolean matches(String relativePath, long size, long modified, String owner) {
        Objects.requireNonNull(relativePath, "relativePath");
        if (size < 0 || any(excludePaths, relativePath)) return false;
        if (!includePaths.isEmpty() && !any(includePaths, relativePath)) return false;
        String basename = relativePath.substring(relativePath.lastIndexOf('/') + 1);
        if (!names.isEmpty() && !any(names, basename)) return false;
        if (!owners.isEmpty() && !matchesOwner(owner)) return false;
        if (!extensions.isEmpty() || includeExtensionless) {
            int dot = basename.lastIndexOf('.');
            String extension = dot <= 0 || dot == basename.length() - 1 ? ""
                    : basename.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (extension.isEmpty()) {
                if (!includeExtensionless) return false;
            } else if (!Arrays.asList(extensions.split(";")).contains(extension)) return false;
        }
        return (modifiedAfterMillis == null || modified >= modifiedAfterMillis)
                && (modifiedBeforeMillis == null || modified <= modifiedBeforeMillis)
                && (minBytes == null || size >= minBytes)
                && (maxBytes == null || size <= maxBytes);
    }

    private boolean matchesOwner(String owner) {
        if (owner == null || owner.isBlank()) return false;
        String normalized = owner.strip().toLowerCase(Locale.ROOT);
        return Arrays.asList(owners.split(";")).contains(normalized);
    }

    public boolean excludesDirectory(String relativePath) {
        Objects.requireNonNull(relativePath, "relativePath");
        String path = relativePath.endsWith("/") ? relativePath.substring(0, relativePath.length() - 1) : relativePath;
        return any(excludePaths, path) || any(excludePaths, path + "/");
    }

    public boolean isUnrestricted() {
        return includePaths.isEmpty() && excludePaths.isEmpty() && extensions.isEmpty()
                && !includeExtensionless && names.isEmpty() && owners.isEmpty()
                && modifiedAfterMillis == null && modifiedBeforeMillis == null
                && minBytes == null && maxBytes == null;
    }

    /** Deterministic, unambiguous representation suitable for deriving a task identity. */
    public String identity() {
        StringBuilder result = new StringBuilder("filter-v2:");
        for (String value : List.of(includePaths, excludePaths, extensions, Boolean.toString(includeExtensionless),
                names, owners, String.valueOf(modifiedAfterMillis), String.valueOf(modifiedBeforeMillis),
                String.valueOf(minBytes), String.valueOf(maxBytes))) {
            result.append(value.length()).append(':').append(value);
        }
        return result.toString();
    }

    private static String normalize(String value, boolean extension) {
        if (value == null) return "";
        return Arrays.stream(value.split(";"))
                .map(String::strip)
                .map(s -> extension ? (s.startsWith(".") ? s.substring(1) : s).toLowerCase(Locale.ROOT) : s)
                .filter(s -> !s.isEmpty()).distinct().sorted().collect(Collectors.joining(";"));
    }

    private static String normalizeOwner(String value) {
        if (value == null) return "";
        return Arrays.stream(value.split(";"))
                .map(String::strip)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty()).distinct().sorted().collect(Collectors.joining(";"));
    }

    private static boolean any(String criteria, String value) {
        return patterns(criteria).stream().anyMatch(p -> p.matcher(value).matches());
    }

    private static List<Pattern> patterns(String criteria) {
        if (criteria.isEmpty()) return List.of();
        synchronized (GLOBS) {
            return GLOBS.computeIfAbsent(criteria, key -> Arrays.stream(key.split(";"))
                    .map(BackupFilter::compileGlob).toList());
        }
    }

    private static Pattern compileGlob(String glob) {
        // Validate standard glob syntax before applying the zero-directory **/ extension.
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
                            regex.append("(?:.*/)?");
                            i++;
                        } else regex.append(".*");
                    } else regex.append("[^/]*");
                }
                case '?' -> regex.append("[^/]");
                case '{' -> { regex.append("(?:"); group = true; }
                case '}' -> { regex.append(')'); group = false; }
                case ',' -> regex.append(group ? "|" : ",");
                case '[' -> {
                    regex.append('[');
                    if (glob.charAt(i + 1) == '!') { regex.append('^'); i++; }
                    else if (glob.charAt(i + 1) == '^') { regex.append("\\^"); i++; }
                    while (glob.charAt(++i) != ']') {
                        char member = glob.charAt(i);
                        if (member == '&' || member == '[' || member == '\\') regex.append('\\');
                        regex.append(member);
                    }
                    regex.append("&&[^/]]");
                }
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }
}

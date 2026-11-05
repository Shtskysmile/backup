package com.backup.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BackupFilterTest {
    private BackupFilter filter(String include, String exclude, String ext, boolean noExt, String names,
                                Long after, Long before, Long min, Long max) {
        return new BackupFilter(include, exclude, ext, noExt, names, after, before, min, max);
    }

    private BackupFilter ownerFilter(String owners) {
        return new BackupFilter("", "", "", false, "", owners, null, null, null, null);
    }

    @Test void ownerCriteriaIntersectWithOtherRules() {
        var f = new BackupFilter("docs/**", "", "", false, "", "alice;root", null, null, null, null);
        assertTrue(f.matches("docs/notes.txt", 1, 1, "alice"));
        assertTrue(f.matches("docs/notes.txt", 1, 1, "ROOT"));
        assertFalse(f.matches("docs/notes.txt", 1, 1, "bob"));
        assertFalse(f.matches("docs/notes.txt", 1, 1, null));
        assertFalse(f.matches("docs/notes.txt", 1, 1, ""));
        assertFalse(f.matches("other/notes.txt", 1, 1, "alice"));
    }

    @Test void ownerNormalizationIsStableAndCaseInsensitive() {
        var first = ownerFilter(" Alice ; BOB ;alice");
        var second = ownerFilter("bob;alice");
        assertEquals(first, second);
        assertEquals(first.identity(), second.identity());
        assertTrue(first.matches("file", 1, 1, "ALICE"));
        assertFalse(first.isUnrestricted());
        assertFalse(first.matches("file", 1, 1, "charlie"));
        assertTrue(BackupFilter.all().matches("file", 1, 1, null));
        assertTrue(ownerFilter("").matches("file", 1, 1, null));
        assertTrue(ownerFilter("  ").matches("file", 1, 1, "anyone"));
    }

    @Test void ownerIdentityChangesWhenOwnersChange() {
        assertNotEquals(ownerFilter("alice").identity(), ownerFilter("bob").identity());
        assertNotEquals(ownerFilter("alice").identity(), BackupFilter.all().identity());
    }

    @Test void emptyCriteriaAcceptAllFiles() {
        var f = BackupFilter.all();
        assertTrue(f.isUnrestricted());
        assertTrue(f.matches("中文/README", 0, -100));
        assertTrue(f.matches(".hidden", Long.MAX_VALUE, Long.MAX_VALUE));
        assertFalse(f.matches("bad", -1, 0));
        assertFalse(f.excludesDirectory("anything"));
    }

    @Test void pathAlternativesAndExclusionHaveExpectedGlobSemantics() {
        var f = filter("docs/**/*.txt;图片/**", "**/private/**", "", false, "", null, null, null, null);
        assertTrue(f.matches("docs/readme.txt", 1, 1));
        assertTrue(f.matches("docs/nested/readme.txt", 1, 1));
        assertTrue(f.matches("图片/猫.png", 1, 1));
        assertFalse(f.matches("docs/private/readme.txt", 1, 1));
        assertFalse(f.matches("private/a.txt", 1, 1));
        assertFalse(f.matches("other/readme.txt", 1, 1));
        var shallow = filter("docs/*.txt", "", "", false, "", null, null, null, null);
        assertFalse(shallow.matches("docs/nested/a.txt", 1, 1));
    }

    @Test void criteriaIntersectAndBoundsAreInclusive() {
        var f = filter("docs/**", "**/skip*", ".TXT;pdf", false, "report*;总结*", 10L, 20L, 5L, 8L);
        assertTrue(f.matches("docs/report.TXT", 5, 10));
        assertTrue(f.matches("docs/总结.pdf", 8, 20));
        assertFalse(f.matches("docs/report.txt", 4, 10));
        assertFalse(f.matches("docs/report.txt", 9, 10));
        assertFalse(f.matches("docs/report.txt", 5, 9));
        assertFalse(f.matches("docs/report.txt", 5, 21));
        assertFalse(f.matches("docs/other.txt", 5, 10));
        assertFalse(f.matches("docs/report.png", 5, 10));
        assertFalse(f.matches("else/report.txt", 5, 10));
    }

    @Test void extensionlessOptionCanStandAloneOrJoinExtensions() {
        var only = filter("", "", "", true, "", null, null, null, null);
        assertTrue(only.matches("README", 1, 0));
        assertTrue(only.matches(".gitignore", 1, 0));
        assertTrue(only.matches("trailing.", 1, 0));
        assertFalse(only.matches("file.txt", 1, 0));
        assertFalse(only.isUnrestricted());
        var mixed = filter("", "", "txt", true, "", null, null, null, null);
        assertTrue(mixed.matches("README", 1, 0));
        assertTrue(mixed.matches("a.TxT", 1, 0));
        assertFalse(mixed.matches("a.pdf", 1, 0));
    }

    @Test void namesMatchBasenameAndSupportStandardGlobConstructs() {
        var f = filter("", "", "", false, "报告?.{txt,pdf};file[0-9].txt", null, null, null, null);
        assertTrue(f.matches("子目录/报告一.pdf", 1, 0));
        assertTrue(f.matches("file9.txt", 1, 0));
        assertFalse(f.matches("报告一.pdf/other", 1, 0));
        assertFalse(f.matches("filex.txt", 1, 0));
    }

    @Test void directoryExclusionsPruneSubtreeRootsWithoutApplyingOtherCriteria() {
        var f = filter("docs/**", "cache/**;**/tmp/**;secret*", "txt", false, "report*", 10L, null, 100L, null);
        assertTrue(f.excludesDirectory("cache"));
        assertTrue(f.excludesDirectory("cache/nested"));
        assertTrue(f.excludesDirectory("tmp"));
        assertTrue(f.excludesDirectory("docs/tmp"));
        assertTrue(f.excludesDirectory("secret-data"));
        assertFalse(f.excludesDirectory("other"));
        assertFalse(f.excludesDirectory("cacheable"));
    }

    @Test void normalizationProducesStableImmutableIdentity() {
        var first = filter(" b/** ;a/**;b/** ", null, " .TXT;pdf;txt ", false, " x* ; y* ", null, null, 0L, null);
        var second = filter("a/**;b/**", "", "pdf;txt", false, "y*;x*", null, null, 0L, null);
        assertEquals(first, second);
        assertEquals(first.identity(), second.identity());
        assertNotEquals(first.identity(), BackupFilter.all().identity());
        assertEquals(BackupFilter.all(), filter(null, null, null, false, null, null, null, null, null));
    }

    @Test void invalidCriteriaFailBeforeScanning() {
        assertThrows(IllegalArgumentException.class, () -> filter("[", "", "", false, "", null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> filter("", "{a", "", false, "", null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> filter("", "", "", false, "a\\", null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> filter("", "", "", false, "", null, null, -1L, null));
        assertThrows(IllegalArgumentException.class, () -> filter("", "", "", false, "", null, null, null, -1L));
        assertThrows(IllegalArgumentException.class, () -> filter("", "", "", false, "", null, null, 2L, 1L));
        assertThrows(IllegalArgumentException.class, () -> filter("", "", "", false, "", 2L, 1L, null, null));
    }
}

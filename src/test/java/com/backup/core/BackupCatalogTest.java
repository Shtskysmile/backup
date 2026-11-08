package com.backup.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 所有权目录测试：发布成对校验、组轮换（10 次上限 + keepGroups）、
 * 损坏索引保守放弃、任务隔离、删除中断保留剩余链。
 *
 * 用例清单（9 项）：
 * - prunesWholeGroupsAndSeparatesTasks
 * - zeroDisablesPruningAndLatestSnapshotGroupIsPreserved
 * - incompleteGroupAndMissingPairsRemainUntouched
 * - rejectsTraversalAndMissingOrMismatchedManifest
 * - corruptAndTraversalIndexesPreventUnsafeDeletion
 * - symlinkIndexOrArchiveIsPreserved
 * - publicationIsExplicitAndRejectsUnsafeSnapshotMetadata
 * - deterministicRootOrderingAndMissingRootProtection
 * - symlinkManifestIsNeitherPublishedNorDeleted
 */
class BackupCatalogTest {
    @TempDir Path dir;
    BackupCatalog.Snapshot snapshot(String task,String id,String group,String parent,int seq,long time) {
        return new BackupCatalog.Snapshot(task,id,group,parent,seq,id+".arbitrary",Instant.ofEpochSecond(time));
    }
    void pair(BackupCatalog.Snapshot s) throws Exception {
        Files.writeString(dir.resolve(s.archiveName()),"archive");
        Properties p=new Properties(); p.setProperty("version","2");
        p.setProperty("taskId",s.taskId());p.setProperty("snapshotId",s.snapshotId());
        p.setProperty("groupId",s.groupId());p.setProperty("parentId",s.parentId());
        p.setProperty("sequence",String.valueOf(s.sequence()));p.setProperty("archiveName",s.archiveName());
        try(var out=Files.newOutputStream(dir.resolve(s.archiveName()+".manifest.properties"))){p.store(out,"");}
    }
    void add(BackupCatalog.Snapshot s) throws Exception {pair(s);BackupCatalog.publish(dir,s);}
    boolean exists(String id){return Files.exists(dir.resolve(id+".arbitrary"));}
    /** keepGroups=1 整组淘汰：任务 a 的旧组（全量+增量）被删，新组、任务 b 的快照与无关文件全部保留。 */
    @Test void prunesWholeGroupsAndSeparatesTasks() throws Exception {
        add(snapshot("a","old","old","",1,1)); add(snapshot("a","child","old","old",2,2));
        add(snapshot("a","new","new","",1,3)); add(snapshot("b","foreign","foreign","",1,0));
        Files.writeString(dir.resolve("unrelated.zip"),"keep");
        BackupCatalog.prune(dir,"a",1,s->fail(s));
        assertFalse(exists("old"));assertFalse(exists("child"));assertTrue(exists("new"));assertTrue(exists("foreign"));
        assertTrue(Files.exists(dir.resolve("unrelated.zip")));
        assertFalse(Files.exists(dir.resolve("old.arbitrary.snapshot.properties")));
    }
    /** keepGroups=0 直接关闭淘汰；keepGroups=1 时包含全目录最新快照的组即使排序靠后也被保护。 */
    @Test void zeroDisablesPruningAndLatestSnapshotGroupIsPreserved() throws Exception {
        add(snapshot("a","old","old","",1,1));add(snapshot("a","latest","old","old",2,9));
        add(snapshot("a","new","new","",1,3));
        BackupCatalog.prune(dir,"a",0,s->{});assertTrue(exists("old"));
        BackupCatalog.prune(dir,"a",1,s->{});assertTrue(exists("old"));assertTrue(exists("latest"));
    }
    /** 断链组（parentId 指向不存在的快照）与缺清单配对的组都被保留并警告，绝不误删。 */
    @Test void incompleteGroupAndMissingPairsRemainUntouched() throws Exception {
        add(snapshot("a","old","old","",1,1));add(snapshot("a","gap","old","missing",3,2));
        add(snapshot("a","new","new","",1,3));
        List<String> warnings=new ArrayList<>();BackupCatalog.prune(dir,"a",1,warnings::add);
        assertTrue(exists("old"));assertTrue(exists("gap"));assertFalse(warnings.isEmpty());
        Files.delete(dir.resolve("gap.arbitrary.manifest.properties"));
        BackupCatalog.prune(dir,"a",1,warnings::add);assertTrue(exists("old"));
    }
    /** 发布校验：清单缺失或身份字段不匹配拒绝发布；穿越文件名与非法 taskId 在构造期即被拒绝。 */
    @Test void rejectsTraversalAndMissingOrMismatchedManifest() throws Exception {
        var s=snapshot("a","root","root","",1,1);
        assertThrows(IOException.class,()->BackupCatalog.publish(dir,s));
        pair(s);Files.writeString(dir.resolve(s.archiveName()+".manifest.properties"),"version=2\ntaskId=other\n");
        assertThrows(IOException.class,()->BackupCatalog.publish(dir,s));
        assertThrows(IllegalArgumentException.class,()->BackupCatalog.publish(dir,new BackupCatalog.Snapshot("a","root","root","",1,"../escape",Instant.now())));
        assertThrows(IllegalArgumentException.class,()->BackupCatalog.publish(dir,snapshot("../a","x","x","",1,1)));
    }
    /** 目录中出现一条恶意/损坏索引（archiveName=../outside）：整轮清理放弃，任何文件都不删。 */
    @Test void corruptAndTraversalIndexesPreventUnsafeDeletion() throws Exception {
        add(snapshot("a","old","old","",1,1));add(snapshot("a","new","new","",1,3));
        Files.writeString(dir.resolve("evil.snapshot.properties"),"version=1\narchiveName=../outside\n");
        List<String> warnings=new ArrayList<>();BackupCatalog.prune(dir,"a",1,warnings::add);
        assertTrue(exists("old"));assertFalse(warnings.isEmpty());
    }
    /** 索引或归档位置出现符号链接：按不安全处理——组保留、链接原样、外部目标不受影响。 */
    @Test void symlinkIndexOrArchiveIsPreserved() throws Exception {
        add(snapshot("a","old","old","",1,1));add(snapshot("a","new","new","",1,3));
        Path external=Files.createTempFile("catalog-test", ".properties");
        try {
            Files.createSymbolicLink(dir.resolve("evil.snapshot.properties"),external);
            List<String> warnings=new ArrayList<>();BackupCatalog.prune(dir,"a",1,warnings::add);
            assertTrue(exists("old"));assertTrue(Files.exists(external));assertFalse(warnings.isEmpty());
            Files.delete(dir.resolve("evil.snapshot.properties"));Files.delete(dir.resolve("old.arbitrary"));
            Files.createSymbolicLink(dir.resolve("old.arbitrary"),external);
            BackupCatalog.prune(dir,"a",1,warnings::add);assertTrue(Files.isSymbolicLink(dir.resolve("old.arbitrary")));
        } finally {Files.deleteIfExists(external);}
    }
    /** 发布显式化：非法序号（0/11）与"全量根 groupId 不等于 snapshotId"的身份规则都被拒绝。 */
    @Test void publicationIsExplicitAndRejectsUnsafeSnapshotMetadata() throws Exception {
        for (int i=0;i<4;i++) add(snapshot("a","g"+i,"g"+i,"",1,i));
        assertTrue(exists("g0"));assertTrue(exists("g3"));
        assertThrows(IllegalArgumentException.class,()->BackupCatalog.publish(dir,snapshot("a","x","x","",0,1)));
        assertThrows(IllegalArgumentException.class,()->BackupCatalog.publish(dir,snapshot("a","x","x","",11,1)));
        assertThrows(IllegalArgumentException.class,()->BackupCatalog.publish(dir,snapshot("a","x","different","",1,1)));
    }
    /** 同时间戳的组按 groupId 字典序稳定排序；缺全量根的孤儿增量组被保留并警告。 */
    @Test void deterministicRootOrderingAndMissingRootProtection() throws Exception {
        add(snapshot("a","alpha","alpha","",1,5));add(snapshot("a","zeta","zeta","",1,5));
        add(snapshot("a","orphan","absent","absent",2,1));
        List<String> warnings=new ArrayList<>();BackupCatalog.prune(dir,"a",1,warnings::add);
        assertFalse(exists("alpha"));assertTrue(exists("zeta"));assertTrue(exists("orphan"));assertFalse(warnings.isEmpty());
    }
    /** 清单位置是符号链接：拒绝发布；淘汰时该组保留且链接本身不被删除。 */
    @Test void symlinkManifestIsNeitherPublishedNorDeleted() throws Exception {
        var s=snapshot("a","old","old","",1,1);add(s);add(snapshot("a","new","new","",1,3));
        Path manifest=dir.resolve(s.archiveName()+".manifest.properties");
        Path original=dir.resolve("saved-manifest");Files.move(manifest,original);Files.createSymbolicLink(manifest,original);
        assertThrows(IOException.class,()->BackupCatalog.publish(dir,s));
        List<String> warnings=new ArrayList<>();BackupCatalog.prune(dir,"a",1,warnings::add);
        assertTrue(exists("old"));assertTrue(Files.isSymbolicLink(manifest));assertFalse(warnings.isEmpty());
    }

}

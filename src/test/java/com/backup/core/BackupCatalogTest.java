package com.backup.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

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
    @Test void prunesWholeGroupsAndSeparatesTasks() throws Exception {
        add(snapshot("a","old","old","",1,1)); add(snapshot("a","child","old","old",2,2));
        add(snapshot("a","new","new","",1,3)); add(snapshot("b","foreign","foreign","",1,0));
        Files.writeString(dir.resolve("unrelated.zip"),"keep");
        BackupCatalog.prune(dir,"a",1,s->fail(s));
        assertFalse(exists("old"));assertFalse(exists("child"));assertTrue(exists("new"));assertTrue(exists("foreign"));
        assertTrue(Files.exists(dir.resolve("unrelated.zip")));
        assertFalse(Files.exists(dir.resolve("old.arbitrary.snapshot.properties")));
    }
    @Test void zeroDisablesPruningAndLatestSnapshotGroupIsPreserved() throws Exception {
        add(snapshot("a","old","old","",1,1));add(snapshot("a","latest","old","old",2,9));
        add(snapshot("a","new","new","",1,3));
        BackupCatalog.prune(dir,"a",0,s->{});assertTrue(exists("old"));
        BackupCatalog.prune(dir,"a",1,s->{});assertTrue(exists("old"));assertTrue(exists("latest"));
    }
    @Test void incompleteGroupAndMissingPairsRemainUntouched() throws Exception {
        add(snapshot("a","old","old","",1,1));add(snapshot("a","gap","old","missing",3,2));
        add(snapshot("a","new","new","",1,3));
        List<String> warnings=new ArrayList<>();BackupCatalog.prune(dir,"a",1,warnings::add);
        assertTrue(exists("old"));assertTrue(exists("gap"));assertFalse(warnings.isEmpty());
        Files.delete(dir.resolve("gap.arbitrary.manifest.properties"));
        BackupCatalog.prune(dir,"a",1,warnings::add);assertTrue(exists("old"));
    }
    @Test void rejectsTraversalAndMissingOrMismatchedManifest() throws Exception {
        var s=snapshot("a","root","root","",1,1);
        assertThrows(IOException.class,()->BackupCatalog.publish(dir,s));
        pair(s);Files.writeString(dir.resolve(s.archiveName()+".manifest.properties"),"version=2\ntaskId=other\n");
        assertThrows(IOException.class,()->BackupCatalog.publish(dir,s));
        assertThrows(IllegalArgumentException.class,()->BackupCatalog.publish(dir,new BackupCatalog.Snapshot("a","root","root","",1,"../escape",Instant.now())));
        assertThrows(IllegalArgumentException.class,()->BackupCatalog.publish(dir,snapshot("../a","x","x","",1,1)));
    }
    @Test void corruptAndTraversalIndexesPreventUnsafeDeletion() throws Exception {
        add(snapshot("a","old","old","",1,1));add(snapshot("a","new","new","",1,3));
        Files.writeString(dir.resolve("evil.snapshot.properties"),"version=1\narchiveName=../outside\n");
        List<String> warnings=new ArrayList<>();BackupCatalog.prune(dir,"a",1,warnings::add);
        assertTrue(exists("old"));assertFalse(warnings.isEmpty());
    }
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
    @Test void publicationIsExplicitAndRejectsUnsafeSnapshotMetadata() throws Exception {
        for (int i=0;i<4;i++) add(snapshot("a","g"+i,"g"+i,"",1,i));
        assertTrue(exists("g0"));assertTrue(exists("g3"));
        assertThrows(IllegalArgumentException.class,()->BackupCatalog.publish(dir,snapshot("a","x","x","",0,1)));
        assertThrows(IllegalArgumentException.class,()->BackupCatalog.publish(dir,snapshot("a","x","x","",11,1)));
        assertThrows(IllegalArgumentException.class,()->BackupCatalog.publish(dir,snapshot("a","x","different","",1,1)));
    }
    @Test void deterministicRootOrderingAndMissingRootProtection() throws Exception {
        add(snapshot("a","alpha","alpha","",1,5));add(snapshot("a","zeta","zeta","",1,5));
        add(snapshot("a","orphan","absent","absent",2,1));
        List<String> warnings=new ArrayList<>();BackupCatalog.prune(dir,"a",1,warnings::add);
        assertFalse(exists("alpha"));assertTrue(exists("zeta"));assertTrue(exists("orphan"));assertFalse(warnings.isEmpty());
    }
    @Test void symlinkManifestIsNeitherPublishedNorDeleted() throws Exception {
        var s=snapshot("a","old","old","",1,1);add(s);add(snapshot("a","new","new","",1,3));
        Path manifest=dir.resolve(s.archiveName()+".manifest.properties");
        Path original=dir.resolve("saved-manifest");Files.move(manifest,original);Files.createSymbolicLink(manifest,original);
        assertThrows(IOException.class,()->BackupCatalog.publish(dir,s));
        List<String> warnings=new ArrayList<>();BackupCatalog.prune(dir,"a",1,warnings::add);
        assertTrue(exists("old"));assertTrue(Files.isSymbolicLink(manifest));assertFalse(warnings.isEmpty());
    }

}

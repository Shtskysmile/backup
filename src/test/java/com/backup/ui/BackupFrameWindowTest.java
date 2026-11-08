package com.backup.ui;

import com.backup.testing.GuiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JTabbedPane;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 主窗口的两处界面层行为：驱动真实 BackupFrame（不显示窗口，经反射设字段、点按钮）。
 * 没有显示器或没有 keytool 时按能力跳过。
 *
 * 用例清单（2 项）：
 * - noTabNeedsHorizontalScrollingAtTheDefaultWindowSize
 * - encryptedNetworkBackupsStayIncrementalAcrossButtonClicks
 */
class BackupFrameWindowTest {
    @TempDir Path temp;
    private BackupFrame frame;

    @AfterEach void closeWindow() throws Exception {
        GuiTestSupport.close(frame);
    }

    /**
     * 用例 noTabNeedsHorizontalScrollingAtTheDefaultWindowSize：页面横向滚动条被禁用，页面比窗口宽时多出的
     * 部分被静默裁掉（控件、Choose 按钮直接看不见）。Network 页曾因一行 FlowLayout 控件（1038 px）撑到
     * 1246 px 宽，而默认窗口的可视区只有约 1017 px。预期值是手算的字面量：没有任何页签被裁。
     */
    @Test void noTabNeedsHorizontalScrollingAtTheDefaultWindowSize() throws Exception {
        frame = GuiTestSupport.openFrame();
        JTabbedPane tabs = GuiTestSupport.tabs(frame);
        int tabCount = GuiTestSupport.call(tabs::getTabCount);
        List<String> clipped = new ArrayList<>();
        for (int index = 0; index < tabCount; index++) {
            int[] widths = GuiTestSupport.pageAndViewportWidth(frame, index);
            if (widths[0] > widths[1]) {
                String title = GuiTestSupport.call(() -> tabs.getTitleAt(tabs.getSelectedIndex()));
                clipped.add(title + ": page is " + widths[0] + " px wide, window shows " + widths[1] + " px");
            }
        }
        assertEquals(List.of(), clipped, "tabs whose content is cut off at the right edge:");
    }

    /**
     * 用例 encryptedNetworkBackupsStayIncrementalAcrossButtonClicks：加密口令的"记忆"存在 NetworkBackupService
     * 实例里；界面若每次点击都新建实例，勾选 Encrypt archive 的第二次备份就永远是全量。
     * 预期值是手算的字面量：源目录 2 个文件，第一次全量归档 2 条；只改 b.txt 后，第二次必须是增量且只归档 1 条。
     */
    @Test @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void encryptedNetworkBackupsStayIncrementalAcrossButtonClicks() throws Exception {
        assumeTrue(keytoolAvailable(), "keytool is required to provision the server certificate");
        frame = GuiTestSupport.openFrame();
        Path source = Files.createDirectories(temp.resolve("source"));
        Files.writeString(source.resolve("a.txt"), "alpha\n");
        Files.writeString(source.resolve("b.txt"), "beta\n");
        int port = freePort();

        GuiTestSupport.setText(frame, "serverRootField", temp.resolve("cloud").toString());
        GuiTestSupport.setSpinner(frame, "serverPortSpinner", port);
        GuiTestSupport.click(frame, "serverToggleButton");
        GuiTestSupport.awaitLog(frame, "Network server listening on port " + port, 1);
        GuiTestSupport.setText(frame, "newUsernameField", "alice");
        GuiTestSupport.setText(frame, "newUserPasswordField", "demo123");
        GuiTestSupport.click(frame, "addUserButton");
        GuiTestSupport.awaitLog(frame, "User added: alice", 1);

        GuiTestSupport.setSpinner(frame, "networkPortSpinner", port);
        GuiTestSupport.setText(frame, "networkUserField", "alice");
        GuiTestSupport.setText(frame, "networkPasswordField", "demo123");
        GuiTestSupport.setText(frame, "networkSourceField", source.toString());
        GuiTestSupport.setSelected(frame, "networkEncryptedCheckBox", true);
        GuiTestSupport.setText(frame, "networkPassphraseField", "Demo-Backup-2026!");

        GuiTestSupport.click(frame, "networkBackupButton");
        GuiTestSupport.awaitLog(frame, "Network backup complete", 1);
        Files.writeString(source.resolve("b.txt"), "beta, changed\n");
        GuiTestSupport.click(frame, "networkBackupButton");
        GuiTestSupport.awaitLog(frame, "Network backup complete", 2);

        List<String> backups = GuiTestSupport.logLines(frame, "Network backup complete");
        assertTrue(backups.get(0).contains("(full, 2 archived entries"), backups.get(0));
        assertTrue(backups.get(1).contains("(incremental, 1 archived entries"), backups.get(1));
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static boolean keytoolAvailable() {
        String name = System.getProperty("os.name", "").toLowerCase().contains("windows") ? "keytool.exe" : "keytool";
        return Files.isRegularFile(Path.of(System.getProperty("java.home"), "bin", name));
    }
}

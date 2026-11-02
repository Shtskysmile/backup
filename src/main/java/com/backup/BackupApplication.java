package com.backup;

import com.backup.ui.BackupFrame;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;

public final class BackupApplication {
    private BackupApplication() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel");
                UIManager.put("control", new Color(245, 247, 250));
                UIManager.put("info", Color.WHITE);
                UIManager.put("nimbusBase", new Color(37, 99, 235));
                UIManager.put("nimbusBlueGrey", new Color(100, 116, 139));
                UIManager.put("nimbusLightBackground", Color.WHITE);
                UIManager.put("text", new Color(31, 41, 55));
                UIManager.put("defaultFont", new Font("SansSerif", Font.PLAIN, 13));
            } catch (Exception ignored) {
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                } catch (Exception ignoredFallback) {
                    // The default Swing look and feel is a fine fallback.
                }
            }
            BackupFrame frame = new BackupFrame();
            frame.setVisible(true);
        });
    }
}

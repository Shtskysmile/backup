package com.backup;

import com.backup.ui.BackupFrame;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;

/**
 * 程序入口：EDT 上初始化 Nimbus 主题与品牌配色（失败退回系统外观），然后显示主窗口。
 * 所有 Swing 组件只在事件分发线程创建与访问。
 */
public final class BackupApplication {
    /** 工具类：不可实例化。 */
    private BackupApplication() {
    }

    /**
     * 安装应用外观：Nimbus 主题 + 品牌配色 + 13 pt 字体，失败退回系统外观。须在 EDT 上调用。
     * 字体决定控件宽度，所以界面测试直接调用本方法，保证它们量到的布局与真实应用一致。
     */
    public static void installLookAndFeel() {
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
                // 连系统外观都失败时，保留 Swing 默认外观即可正常使用。
            }
        }
    }

    /** 入口：安装外观（失败退回系统外观），EDT 上显示主窗口。 */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            installLookAndFeel();
            BackupFrame frame = new BackupFrame();
            frame.setVisible(true);
        });
    }
}

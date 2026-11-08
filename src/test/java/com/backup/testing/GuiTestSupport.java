package com.backup.testing;

import com.backup.BackupApplication;
import com.backup.ui.BackupFrame;

import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * 驱动真实 BackupFrame 的测试工具：窗口从不显示；字段与按钮经反射按名取得（与界面源码里的字段名耦合），
 * 所有 Swing 访问都落在事件分发线程上。没有显示器（headless）时，调用 {@link #openFrame()} 的用例按能力跳过。
 *
 * <p>仅供测试使用，所以放在测试目录里，不进入产品类。</p>
 */
public final class GuiTestSupport {
    private GuiTestSupport() {
    }

    /** 无返回值的 EDT 任务，可抛受检异常。 */
    @FunctionalInterface
    public interface EdtTask {
        void run() throws Exception;
    }

    /** 在 EDT 上执行并返回结果；任务里的异常原样抛回测试线程。 */
    public static <T> T call(Callable<T> task) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(task.call());
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        if (failure.get() instanceof Exception e) throw e;
        if (failure.get() instanceof Error e) throw e;
        return result.get();
    }

    public static void run(EdtTask task) throws Exception {
        call(() -> {
            task.run();
            return null;
        });
    }

    /**
     * 打开一个主窗口：外观由应用自己的 {@link BackupApplication#installLookAndFeel()} 安装（字体决定控件宽度，
     * 所以必须与真实应用一致），大小为构造器设定的默认值，窗口不显示。无显示器则跳过调用方用例。
     */
    public static BackupFrame openFrame() throws Exception {
        // 必须先于任何 AWT 初始化：macOS 上测试期间不在程序坞里闪出 Java 图标。
        System.setProperty("apple.awt.UIElement", "true");
        assumeFalse(GraphicsEnvironment.isHeadless(), "GUI tests need a display");
        run(BackupApplication::installLookAndFeel);
        return call(() -> {
            BackupFrame frame = new BackupFrame();
            Dimension defaultSize = frame.getSize();
            frame.pack();                 // 让窗口可以布局，但不显示
            frame.setSize(defaultSize);   // pack 会改成首选大小，这里恢复默认窗口大小
            frame.validate();
            return frame;
        });
    }

    /** 关闭窗口前先停掉它启动过的服务器、定时与监视（按钮文字以 Stop 开头即在运行）。 */
    public static void close(BackupFrame frame) throws Exception {
        if (frame == null) return;
        for (String toggle : List.of("serverToggleButton", "scheduleButton", "watchButton")) {
            AbstractButton button = (AbstractButton) field(frame, toggle);
            if (call(button::getText).startsWith("Stop")) run(button::doClick);
        }
        run(frame::dispose);
    }

    private static Object field(BackupFrame frame, String name) throws Exception {
        Field field = BackupFrame.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(frame);
    }

    public static void setText(BackupFrame frame, String name, String value) throws Exception {
        JTextField field = (JTextField) field(frame, name);
        run(() -> field.setText(value));
    }

    public static void setSelected(BackupFrame frame, String name, boolean selected) throws Exception {
        JCheckBox box = (JCheckBox) field(frame, name);
        run(() -> box.setSelected(selected));
    }

    public static void setSpinner(BackupFrame frame, String name, int value) throws Exception {
        JSpinner spinner = (JSpinner) field(frame, name);
        run(() -> spinner.setValue(value));
    }

    public static void click(BackupFrame frame, String name) throws Exception {
        AbstractButton button = (AbstractButton) field(frame, name);
        run(button::doClick);
    }

    /** 活动日志里包含 needle 的行（按出现顺序）。 */
    public static List<String> logLines(BackupFrame frame, String needle) throws Exception {
        List<String> hits = new ArrayList<>();
        for (String line : logText(frame).split("\\R")) {
            if (line.contains(needle)) hits.add(line);
        }
        return hits;
    }

    public static String logText(BackupFrame frame) throws Exception {
        return ((JTextArea) field(frame, "logArea")).getText();
    }

    /** 等到日志里至少有 count 行包含 needle；超时抛 AssertionError 并附上完整日志，方便定位。 */
    public static void awaitLog(BackupFrame frame, String needle, int count) throws Exception {
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (logLines(frame, needle).size() >= count) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for " + count + " log line(s) containing '" + needle
                + "'. Activity log:\n" + logText(frame));
    }

    public static JTabbedPane tabs(BackupFrame frame) {
        return find(frame.getContentPane(), JTabbedPane.class);
    }

    /** 选中第 index 个页签并重新布局；返回 {页面实际宽度, 可视区宽度}。页面比可视区宽，多出的部分被裁掉（横向滚动条被禁用）。 */
    public static int[] pageAndViewportWidth(BackupFrame frame, int index) throws Exception {
        return call(() -> {
            JTabbedPane tabs = tabs(frame);
            tabs.setSelectedIndex(index);
            frame.invalidate();
            frame.validate();
            JScrollPane scroll = (JScrollPane) tabs.getSelectedComponent();
            return new int[]{scroll.getViewport().getView().getWidth(), scroll.getViewport().getWidth()};
        });
    }

    private static <T extends Component> T find(Container root, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container container) {
                T nested = find(container, type);
                if (nested != null) return nested;
            }
        }
        return null;
    }
}

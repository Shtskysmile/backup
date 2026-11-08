package com.backup.ui;

import com.backup.automation.BackupTaskGate;
import com.backup.automation.ScheduledBackupRunner;
import com.backup.automation.WatchBackupService;
import com.backup.core.BackupManifest;
import com.backup.core.BackupFilter;
import com.backup.core.RestoreResult;
import com.backup.core.BackupOptions;
import com.backup.core.BackupResult;
import com.backup.core.BackupService;
import com.backup.core.CompressionType;
import com.backup.network.NetworkBackupClient;
import com.backup.network.NetworkBackupService;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.ResolverStyle;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.ExecutionException;

/**
 * 主窗口：Backup / Restore / Automation / Network 四个标签页 + 共享活动日志。
 *
 * <p>线程纪律：所有 Swing 组件只在 EDT 访问；耗时操作（备份/还原/网络备份/还原、
 * 服务器启停）放 SwingWorker 后台线程，结果经 done() 回 EDT 呈现。
 * 任务串行由 {@link BackupTaskGate} 统一管理——手动请求忙时拒绝弹窗，
 * 自动请求（定时/实时）忙时按任务合并，worker 的 finally 必调 complete()。</p>
 *
 * <p>样式：常量色板（PRIMARY/INK/MUTED 等）+ 统一的 style* 方法族，
 * 表单用 GridBagLayout 的 addRow 体系，保证四页视觉一致。</p>
 */
public final class BackupFrame extends JFrame {
// ==================== 常量与色板 ====================
// 界面视觉统一来源于这里的色板与字体常量：深蓝头部、白卡面板、蓝色主按钮、
// 黄色警示与红色失败；日志区用深色控制台风格。改动色板即可整体换肤。
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Color WINDOW_BACKGROUND = new Color(245, 247, 250);
    private static final Color SURFACE = Color.WHITE;
    private static final Color INK = new Color(31, 41, 55);
    private static final Color MUTED = new Color(100, 116, 139);
    private static final Color BORDER = new Color(226, 232, 240);
    private static final Color PRIMARY = new Color(37, 99, 235);
    private static final Color PRIMARY_DARK = new Color(30, 64, 175);
    private static final Color SUCCESS = new Color(22, 163, 74);
    private static final Color WARNING = new Color(217, 119, 6);
    private static final Color DANGER = new Color(220, 38, 38);
    private static final Color CONSOLE = new Color(15, 23, 42);
    private static final Font BODY_FONT = new Font("SansSerif", Font.PLAIN, 13);
    private static final Font LABEL_FONT = new Font("SansSerif", Font.BOLD, 12);
    private static final Font TITLE_FONT = new Font("SansSerif", Font.BOLD, 20);

// ==================== 核心依赖与共享状态 ====================
// backupService 承担本地备份/还原；scheduledBackupRunner 与 watchBackupService
// 是两个自动化引擎；taskGate 把所有备份/还原任务串行化（手动拒绝、自动合并）；
// scheduledRequest/watchedRequest 是启动自动化那一刻的参数快照（改参数需重启生效）。
    private final BackupService backupService = new BackupService();
    private final ScheduledBackupRunner scheduledBackupRunner = new ScheduledBackupRunner();
    private final WatchBackupService watchBackupService = new WatchBackupService();
    private final BackupTaskGate taskGate = new BackupTaskGate(SwingUtilities::invokeLater);

// ==================== Backup 页字段 ====================
// 源/目标/名称 + 压缩/增量/加密 + 六维筛选（路径/排除/扩展名/无扩展名/名字/属主/时间/尺寸）。
// 字段值只在提交时刻被读取并固化为 BackupOptions，运行中改动不影响进行中的任务。
    private final JTextField sourceField = new JTextField(34);
    private final JTextField destinationField = new JTextField(34);
    private final JTextField backupNameField = new JTextField("Backup", 20);
    private final JComboBox<CompressionType> compressionCombo = new JComboBox<>(CompressionType.values());
    private final JCheckBox incrementalCheckBox = new JCheckBox("Incremental backup", true);
    private final JCheckBox encryptedCheckBox = new JCheckBox("Encrypt archive");
    private final JPasswordField passphraseField = new JPasswordField(20);
    private final JTextField includePathsField = new JTextField(34);
    private final JTextField excludePathsField = new JTextField(34);
    private final JTextField extensionsField = new JTextField(34);
    private final JCheckBox extensionlessCheckBox = new JCheckBox("Include extensionless files (alone selects only extensionless)");
    private final JTextField namesField = new JTextField(34);
    private final JTextField ownersField = new JTextField(34);
    private final JTextField modifiedAfterField = new JTextField(20);
    private final JTextField modifiedBeforeField = new JTextField(20);
    private final JTextField minBytesField = new JTextField(20);
    private final JTextField maxBytesField = new JTextField(20);
    private final JSpinner keepGroupsSpinner = new JSpinner(new SpinnerNumberModel(0, 0, Integer.MAX_VALUE, 1));
    private final JButton restoreButton = new JButton("Restore");
    private static final DateTimeFormatter FILTER_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
            .withResolverStyle(ResolverStyle.STRICT);

// ==================== Restore 页字段 ====================
// 还原三要素：归档文件、目标目录、口令（加密归档必填）。
    private final JTextField restoreArchiveField = new JTextField(34);
    private final JTextField restoreDestinationField = new JTextField(34);
    private final JPasswordField restorePassphraseField = new JPasswordField(20);

// ==================== Automation 页字段 ====================
// 定时周期（分钟）与实时防抖（秒）；两个布尔位记录自动化是否在运行。
    private final JSpinner scheduleMinutesSpinner = new JSpinner(new SpinnerNumberModel(30, 1, 24 * 60, 1));
    private final JSpinner watchDebounceSecondsSpinner = new JSpinner(new SpinnerNumberModel(5, 1, 600, 1));
    private final JButton scheduleButton = new JButton("Start schedule");
    private final JButton watchButton = new JButton("Start realtime backup");
    private volatile BackupOptions scheduledRequest;
    private volatile BackupOptions watchedRequest;

    private final JButton backupButton = new JButton("Back up now");
    private final JTextArea logArea = new JTextArea(10, 80);
    private final JLabel statusLabel = new JLabel("Ready");

    private final JTextField serverRootField = new JTextField(30);
    private final JSpinner serverPortSpinner = new JSpinner(new SpinnerNumberModel(8443, 1, 65535, 1));
    private final JButton serverToggleButton = new JButton("Start server");
    private final JTextField newUsernameField = new JTextField(16);
    private final JPasswordField newUserPasswordField = new JPasswordField(16);

    private final JTextField networkHostField = new JTextField("localhost", 14);
    private final JSpinner networkPortSpinner = new JSpinner(new SpinnerNumberModel(8443, 1, 65535, 1));
    private final JTextField networkUserField = new JTextField(14);
    private final JPasswordField networkPasswordField = new JPasswordField(16);
    private final JTextField networkSourceField = new JTextField(30);
    private final JTextField networkNameField = new JTextField("Backup", 16);
    private final JComboBox<CompressionType> networkCompressionCombo = new JComboBox<>(CompressionType.values());
    private final JCheckBox networkIncrementalCheckBox = new JCheckBox("Incremental", true);
    private final JCheckBox networkEncryptedCheckBox = new JCheckBox("Encrypt archive");
    private final JPasswordField networkPassphraseField = new JPasswordField(16);
    private final JSpinner networkKeepGroupsSpinner = new JSpinner(new SpinnerNumberModel(0, 0, Integer.MAX_VALUE, 1));
    private final JComboBox<String> snapshotsCombo = new JComboBox<>();
    private final JTextField networkRestoreDestinationField = new JTextField(30);
    private final JPasswordField networkRestorePassphraseField = new JPasswordField(16);
// ==================== Network 页字段 ====================
// 服务端：存储目录 + 端口 + 启停 + 添加账号（networkServer 非 null 即在运行）。
// 客户端：连接四要素 + 备份参数 + 快照下拉 + 还原/删除；connectedClient() 统一构造客户端。
    private final JButton networkBackupButton = new JButton("Back up to server");
    private final JButton networkListButton = new JButton("List snapshots");
    private final JButton networkRestoreButton = new JButton("Restore selected");
    private final JButton networkDeleteButton = new JButton("Delete selected group");
    private final JButton networkPingButton = new JButton("Test connection");
    private final JButton addUserButton = new JButton("Add user");
    private volatile com.backup.network.NetworkBackupServer networkServer;
    // 网络备份服务整个窗口只建一个：加密链的口令"记忆"存在服务实例里，若每次点击都 new 一个，
    // 勾选 Encrypt archive 的第二次备份就永远退化为全量（与本地 backupService 是单实例同理）。
    private final NetworkBackupService networkBackupService = new NetworkBackupService();

    /** 主窗口装配：页签 + 日志 + 关闭钩子（关闭窗口时停止调度器/监视器/网络服务器并释放闸门）。 */
    public BackupFrame() {
        super("Backup");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(850, 620));
        setSize(1040, 780);
        setLocationRelativeTo(null);
        getContentPane().setBackground(WINDOW_BACKGROUND);
        setLayout(new BorderLayout(0, 14));

// ==================== 装配 ====================
// 头部横幅 + 四标签页 + 底部日志；窗口关闭走 shutdown() 而非直接退出。
        add(createHeaderPanel(), BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
// 四个标签页：本地备份 / 还原 / 自动化 / 网络；各自独立表单，共享日志与状态徽标。
        tabs.addTab("Backup", scrollablePage(createBackupPanel()));
        tabs.addTab("Restore", scrollablePage(createRestorePanel()));
        tabs.addTab("Automation", scrollablePage(createAutomationPanel()));
        tabs.addTab("Network", scrollablePage(createNetworkPanel()));
        tabs.setFont(new Font("SansSerif", Font.BOLD, 13));
        tabs.setForeground(INK);
        tabs.setBackground(WINDOW_BACKGROUND);
        tabs.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        tabs.setTabLayoutPolicy(JTabbedPane.WRAP_TAB_LAYOUT);
        // 窄窗口时页签自动换行而不是截断。
        add(tabs, BorderLayout.CENTER);
        add(createLogPanel(), BorderLayout.SOUTH);

        // 关闭窗口 = DO_NOTHING_ON_CLOSE + 显式 shutdown：保证后台资源有序释放。
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdown();
                dispose();
            }
        });

        // 启动日志：证明窗口与日志系统就绪。
        log("Ready.");
        // 日志首行：演示时可用 Clear 清屏后从这里开始讲述。
    }

// ==================== 头部横幅 ====================
// 深蓝横幅：左侧 LogoMark + 品牌/副标题，右侧 SYSTEM STATUS 徽标（颜色由 updateStatus 驱动）。
    private JPanel createHeaderPanel() {
        JPanel header = new JPanel(new BorderLayout(16, 0));
        header.setBackground(new Color(15, 35, 63));
        header.setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));

        JPanel identity = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        identity.setOpaque(false);
        identity.add(new LogoMark());

        JPanel copy = new JPanel();
        copy.setOpaque(false);
        copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
        JLabel brand = new JLabel("BACKUP");
        brand.setFont(new Font("SansSerif", Font.BOLD, 18));
        brand.setForeground(Color.WHITE);
        JLabel subtitle = new JLabel("Private backup workspace");
        subtitle.setFont(BODY_FONT);
        subtitle.setForeground(new Color(186, 203, 226));
        copy.add(brand);
        copy.add(Box.createVerticalStrut(3));
        copy.add(subtitle);
        identity.add(copy);
        // 品牌区：Logo + 名称 + 副标题纵排。
        header.add(identity, BorderLayout.WEST);

        JPanel status = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        status.setOpaque(false);
        JLabel statusCaption = new JLabel("SYSTEM STATUS");
        statusCaption.setFont(new Font("SansSerif", Font.BOLD, 10));
        statusCaption.setForeground(new Color(164, 184, 211));
        styleStatusLabel();
        status.add(statusCaption);
        // 状态徽标：caption + 彩色标签，全局唯一（四个页面共享一个状态）。
        status.add(statusLabel);
        header.add(status, BorderLayout.EAST);
        return header;
    }

    private void styleStatusLabel() {
        statusLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        statusLabel.setForeground(Color.WHITE);
        statusLabel.setOpaque(true);
        statusLabel.setBackground(new Color(31, 100, 70));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(6, 11, 6, 11));
    }

    /** Backup 页：核心参数 + 可折叠筛选面板；按钮经任务闸门异步执行。 */
// ==================== Backup 页 ====================
// 表单：路径行带 Choose 按钮；筛选面板默认折叠（toggle 控制可见性），避免新用户被复杂度劝退。
    private JPanel createBackupPanel() {
        JPanel panel = pagePanel("BACKUP", "Create a protected snapshot", "Package and protect your files in a local backup archive.");
        JPanel form = formPanel("Backup settings");
        int row = 0;
        addPathRow(form, row++, "Source directory", sourceField, true);
        addPathRow(form, row++, "Destination directory", destinationField, true);
        addRow(form, row++, "Backup name", backupNameField);
        addRow(form, row++, "Compression", compressionCombo);
        addRow(form, row++, "", incrementalCheckBox);
        addRow(form, row++, "", encryptedCheckBox);
        addRow(form, row++, "Passphrase", passphraseField);
        addRow(form, row++, "", createFilterPanel());
        // 筛选面板整行插入（折叠态只占一个按钮的高度）。
        stylePrimaryButton(backupButton);
        // 提交按钮置于表单末行；点击即组装参数并进入闸门调度。
        addActionRow(form, row, backupButton);
        backupButton.addActionListener(event -> {
        // 本地备份按钮：参数组装失败就地弹窗（Invalid backup settings）。
            try {
                runBackupAsync("Manual", buildBackupOptionsFromFields());
            } catch (RuntimeException e) {
                showError("Invalid backup settings", e.getMessage());
            }
        });
        panel.add(form, BorderLayout.CENTER);
        return panel;
    }

    /** 筛选面板（默认折叠）：六维条件 + 规则说明；条件在 buildBackupOptionsFromFields 统一解析与校验。 */
// ==================== 筛选面板 ====================
// 六维条件一行一个；规则说明 JLabel 写清 OR/AND/排除优先/日期格式/端点包含等契约。
    private JPanel createFilterPanel() {
        JPanel container = new JPanel(new BorderLayout(0, 6));
        container.setOpaque(false);
        JButton toggle = new JButton("Show file filters");
        styleSecondaryButton(toggle);
        JPanel filters = new JPanel(new GridBagLayout());
        filters.setOpaque(false);
        int row = 0;
        addRow(filters, row++, "Include paths", includePathsField);
        addRow(filters, row++, "Exclude paths", excludePathsField);
        addRow(filters, row++, "Extensions", extensionsField);
        addRow(filters, row++, "", extensionlessCheckBox);
        addRow(filters, row++, "Names", namesField);
        addRow(filters, row++, "Owners", ownersField);
        addRow(filters, row++, "Modified at or after", modifiedAfterField);
        addRow(filters, row++, "Modified at or before", modifiedBeforeField);
        addRow(filters, row++, "Minimum bytes", minBytesField);
        addRow(filters, row++, "Maximum bytes", maxBytesField);
        addRow(filters, row, "Filter rules", new JLabel("<html>Leave blank for all files. Separate alternatives with ; — conditions intersect.<br>Paths use / and glob patterns, e.g. docs/**; names match the basename.<br>Owners are exact user/group names (case ignored), e.g. alice;root.<br>Extensions ignore case, e.g. txt;pdf. Excluded directories are not scanned.<br>Dates: yyyy-MM-dd HH:mm:ss, local time; dates and sizes include both boundaries.<br>Links are filtered by their own path, name, modification time and recorded size; targets are not read.</html>"));
        filters.setVisible(false);
        toggle.addActionListener(event -> {
            filters.setVisible(!filters.isVisible());
            toggle.setText(filters.isVisible() ? "Hide file filters" : "Show file filters");
            container.revalidate();
        });
        container.add(toggle, BorderLayout.NORTH);
        container.add(filters, BorderLayout.CENTER);
        return container;
    }

    /** 本地时间 yyyy-MM-dd HH:mm:ss → 纪元毫秒；不存在的时间（夏令时切换）显式报错而非猜测。 */
// ==================== 参数解析辅助 ====================
// 时间/字节的解析都集中在提交时刻完成：解析失败抛 IllegalArgumentException，
// 由调用方转为「Invalid backup settings」对话框——坏参数永不进入后台。
    private static Long parseFilterTime(JTextField field, String label) {
        String value = field.getText().strip();
        if (value.isEmpty()) return null;
        try {
            LocalDateTime local = LocalDateTime.parse(value, FILTER_TIME);
            var offsets = ZoneId.systemDefault().getRules().getValidOffsets(local);
            if (offsets.isEmpty()) throw new IllegalArgumentException("Time does not exist in local timezone");
            return local.toInstant(offsets.getFirst()).toEpochMilli();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(label + " must be a valid local yyyy-MM-dd HH:mm:ss date.", e);
        }
    }

    private static Long parseBytes(JTextField field, String label) {
        String value = field.getText().strip();
        if (value.isEmpty()) return null;
        try {
            long bytes = Long.parseLong(value);
            if (bytes < 0) throw new NumberFormatException();
            return bytes;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " must be a nonnegative whole byte count.", e);
        }
    }

    /** 忙时统一禁用手动按钮（备份/还原共用一个闸门，禁用粒度即全局）。 */
    private void setTaskControlsEnabled(boolean enabled) {
        backupButton.setEnabled(enabled);
        restoreButton.setEnabled(enabled);
    }

    /** 快照描述：组号/序号/父快照，日志中可追溯增量链。 */
    private static String snapshotDescription(BackupManifest manifest) {
        var snapshot = manifest.snapshot();
        return "Backup group " + snapshot.groupId() + ", sequence " + snapshot.sequence()
                + ", parent " + (snapshot.parentId() == null || snapshot.parentId().isBlank() ? "full baseline" : snapshot.parentId()) + ".";
    }

    /** Restore 页：归档、目标、口令 + 顺序说明（全量先行、增量按序、不同步删除）。 */
// ==================== Restore 页 ====================
// 三个输入 + 顺序说明（全量先行、增量按序、不同步删除——这是安全语义不是缺陷）。
    private JPanel createRestorePanel() {
        JPanel panel = pagePanel("RESTORE", "Bring a snapshot back", "Choose an archive and restore its contents into a clean destination.");
        JPanel form = formPanel("Restore archive");
        int row = 0;
        addPathRow(form, row++, "Archive file", restoreArchiveField, false);
        addPathRow(form, row++, "Restore destination", restoreDestinationField, true);
        addRow(form, row++, "Passphrase", restorePassphraseField);
        addRow(form, row++, "Restore order", new JLabel("<html>Restore full backup first, then increments in sequence into the same destination.<br>Deleted source files are not automatically removed. For an exact full snapshot, restore into an empty directory.<br>Read-only directories may require a manual permission adjustment before the next increment.</html>"));
        stylePrimaryButton(restoreButton);
        restoreButton.addActionListener(event -> {
        // 还原按钮：同样先过参数合法性，再进闸门。
            try {
                runRestoreAsync();
            } catch (RuntimeException e) {
                showError("Invalid restore settings", e.getMessage());
            }
        });
        // 还原按钮同样走闸门；口令只在本次 worker 生命周期内存在。
        addActionRow(form, row, restoreButton);
        panel.add(form, BorderLayout.CENTER);
        return panel;
    }

    /** Automation 页：保留组数、定时周期、实时防抖三个开关及其语义说明。 */
// ==================== Automation 页 ====================
// keepGroups（0=不淘汰）、定时分钟、防抖秒；启动按钮文案随运行态切换并换色。
    private JPanel createAutomationPanel() {
        JPanel panel = pagePanel("AUTOMATION", "Let Backup keep watch", "Run a recurring backup or react to changes in your source directory.");
        JPanel form = formPanel("Automation");
        int row = 0;
        addRow(form, row++, "Keep latest backup groups", keepGroupsSpinner);
        addRow(form, row++, "Retention", new JLabel("<html>0 disables cleanup. A group contains one full backup and its increments.<br>At most 10 snapshots per group; dependent archives are kept together.<br>Start schedule/watch again after changing backup settings to use a new options snapshot.</html>"));
        addRow(form, row++, "Schedule minutes", scheduleMinutesSpinner);
        scheduleButton.addActionListener(event -> toggleSchedule());
        styleSecondaryButton(scheduleButton);
        addActionRow(form, row++, scheduleButton);
        addRow(form, row++, "Watch debounce seconds", watchDebounceSecondsSpinner);
        watchButton.addActionListener(event -> toggleWatch());
        styleSecondaryButton(watchButton);
        addActionRow(form, row, watchButton);
        panel.add(form, BorderLayout.CENTER);
        return panel;
    }

    /**
     * Network 页：上半 Server（存储目录/端口/启停/添加账号），下半 Client
     * （连接、备份参数、快照列表、还原与删除组）。客户端复用同一套表单样式。
     */
// ==================== Network 页 ====================
// 上 Server 下 Client 两个表单卡；所有动作走 SwingWorker 或任务闸门，绝不阻塞 EDT。
    private JPanel createNetworkPanel() {
        JPanel panel = pagePanel("NETWORK", "Cloud backup on your terms",
                "Run a backup receiver for your accounts, or push encrypted snapshots to one.");

        JPanel serverForm = formPanel("Server (receiver)");
        int serverRow = 0;
        // 服务端三要素：数据落在哪里、监听哪个端口、启停开关（首次启动含证书生成，放后台线程）。
        addPathRow(serverForm, serverRow++, "Storage directory", serverRootField, true);
        addRow(serverForm, serverRow++, "Port", serverPortSpinner);
        serverToggleButton.addActionListener(event -> toggleNetworkServer());
        styleSecondaryButton(serverToggleButton);
        addActionRow(serverForm, serverRow++, serverToggleButton);
        // 账号管理：仅在服务器运行时可用；口令组件用后清零。
        addRow(serverForm, serverRow++, "New username", newUsernameField);
        addRow(serverForm, serverRow++, "New password", newUserPasswordField);
        addUserButton.addActionListener(event -> addNetworkUser());
        styleSmallButton(addUserButton);
        addActionRow(serverForm, serverRow, addUserButton);

        JPanel clientForm = formPanel("Client (cloud backup)");
        int row = 0;
        // 连接区：地址/端口 + 账号/口令分两行，连接自检按钮（一次 ping 验证 TLS 信任与账号）单独一行放在它们下面：先填再测。
        // FlowLayout 一行不会自动换行：单行控件过多会把整页撑到比窗口还宽，而页面的横向滚动条被禁用，
        // 多出的部分（Password、Test connection、Choose 按钮……）就被静默裁掉，所以每一行都要窄于默认窗口。
        JPanel hostRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        hostRow.setOpaque(false);
        hostRow.add(new JLabel("Host"));
        hostRow.add(networkHostField);
        hostRow.add(new JLabel("Port"));
        hostRow.add(networkPortSpinner);
        addRow(clientForm, row++, "Server", hostRow);
        JPanel accountRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        accountRow.setOpaque(false);
        accountRow.add(new JLabel("User"));
        accountRow.add(networkUserField);
        accountRow.add(new JLabel("Password"));
        accountRow.add(networkPasswordField);
        addRow(clientForm, row++, "Account", accountRow);
        networkPingButton.addActionListener(event -> testNetworkConnection());
        styleSmallButton(networkPingButton);
        addActionRow(clientForm, row++, networkPingButton);
        addPathRow(clientForm, row++, "Source directory", networkSourceField, true);
        // 备份参数区：与本地 Backup 页同语义（名称/压缩/增量/加密），快照进 NetworkBackupService；同样分两行。
        JPanel settingsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        settingsRow.setOpaque(false);
        settingsRow.add(new JLabel("Name"));
        settingsRow.add(networkNameField);
        settingsRow.add(new JLabel("Compression"));
        settingsRow.add(networkCompressionCombo);
        addRow(clientForm, row++, "Backup settings", settingsRow);
        JPanel protectionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        protectionRow.setOpaque(false);
        protectionRow.add(networkIncrementalCheckBox);
        protectionRow.add(networkEncryptedCheckBox);
        protectionRow.add(new JLabel("Passphrase"));
        protectionRow.add(networkPassphraseField);
        addRow(clientForm, row++, "", protectionRow);
        // 动作区：keepGroups 在提交时固定并传给服务器端淘汰。
        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actionRow.setOpaque(false);
        actionRow.add(new JLabel("Keep groups"));
        actionRow.add(networkKeepGroupsSpinner);
        networkBackupButton.addActionListener(event -> runNetworkBackup());
        stylePrimaryButton(networkBackupButton);
        actionRow.add(networkBackupButton);
        addRow(clientForm, row++, "Actions", actionRow);
        networkListButton.addActionListener(event -> runNetworkList());
        styleSmallButton(networkListButton);
        snapshotsCombo.setPreferredSize(new Dimension(420, 30));
        // 快照下拉固定宽度：归档名较长时不把整行撑爆。
        JPanel listRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        listRow.setOpaque(false);
        listRow.add(networkListButton);
        listRow.add(snapshotsCombo);
        // 列表行：List 按钮触发刷新，选中项供还原/删除使用。
        addRow(clientForm, row++, "Snapshots", listRow);
        // 还原区：目标目录 + 口令（加密归档）+ 还原/删除两个动作，均经任务闸门串行。
        addPathRow(clientForm, row++, "Restore destination", networkRestoreDestinationField, true);
        JPanel restoreRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        restoreRow.setOpaque(false);
        restoreRow.add(new JLabel("Passphrase"));
        restoreRow.add(networkRestorePassphraseField);
        networkRestoreButton.addActionListener(event -> runNetworkRestore());
        styleSmallButton(networkRestoreButton);
        restoreRow.add(networkRestoreButton);
        networkDeleteButton.addActionListener(event -> runNetworkDelete());
        styleSmallButton(networkDeleteButton);
        restoreRow.add(networkDeleteButton);
        // 删除与还原并排：都作用于当前选中的快照。
        addRow(clientForm, row, "Restore / delete", restoreRow);

        JPanel combined = new JPanel();
        combined.setOpaque(false);
        combined.setLayout(new BoxLayout(combined, BoxLayout.Y_AXIS));
        combined.add(serverForm);
        // 两个表单卡纵向堆叠，页内滚动查看。
        combined.add(Box.createVerticalStrut(12));
        combined.add(clientForm);
        // Server 在上 Client 在下：先有接收端才有客户端可连（也支持分机部署）。
        panel.add(combined, BorderLayout.NORTH);
        return panel;
    }

    /** 服务器启停（SwingWorker 后台执行——首次启动要跑 keytool 可能数秒）；状态与按钮文案同步切换。 */
// ---- 服务器启停：运行中再次点击 = 停止；首次启动含 keytool 证书生成（后台数秒）----
    private void toggleNetworkServer() {
        if (networkServer != null) {
            networkServer.close();
            networkServer = null;
            serverToggleButton.setText("Start server");
            styleSecondaryButton(serverToggleButton);
            log("Network server stopped.");
            return;
        }
        Path root = Path.of(serverRootField.getText().trim());
        int port = ((Number) serverPortSpinner.getValue()).intValue();
        if (serverRootField.getText().isBlank()) {
            showError("Invalid server settings", "A storage directory is required.");
            return;
        }
        serverToggleButton.setEnabled(false);
        new SwingWorker<com.backup.network.NetworkBackupServer, String>() {
        // 服务器启停放后台：首次启动要生成证书，不能卡 EDT。
            @Override protected com.backup.network.NetworkBackupServer doInBackground() throws Exception {
                return com.backup.network.NetworkBackupServer.start(port, root);
            }

            @Override protected void done() {
                serverToggleButton.setEnabled(true);
                try {
                    networkServer = get();
                    // 启动成功：记录实际端口（port=0 时由系统分配），按钮切为 Stop。
                    serverToggleButton.setText("Stop server");
                    styleWarningButton(serverToggleButton);
                    log("Network server listening on port " + networkServer.port() + " (TLS).");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    log("Network server failed to start: " + rootMessage(e));
                    showError("Network server failed", rootMessage(e));
                }
            }
        }.execute();
    }

    /** 向运行中的服务器添加账号；口令用后立即清零，不驻留表单组件。 */
// ---- 添加账号：需服务器已启动；口令数组用后立即清零 ----
    private void addNetworkUser() {
        if (networkServer == null) {
            showError("Server not running", "Start the server before adding accounts.");
            return;
        }
        try {
            char[] password = newUserPasswordField.getPassword();
            com.backup.network.NetworkUsers.ensureUser(networkServer.storageRoot(), newUsernameField.getText().trim(), password);
            Arrays.fill(password, '\0');
            newUserPasswordField.setText("");
            log("User added: " + newUsernameField.getText().trim());
        } catch (IOException | RuntimeException e) {
            showError("Could not add user", e.getMessage());
        }
    }

    /** 构造已认证的 HTTPS 客户端；本机起有服务器时自动钉定其密钥库作为信任根。 */
// ---- 客户端构造：本机有服务器时自动钉定其密钥库为信任根（localhost 零配置演示的关键）----
    private NetworkBackupClient connectedClient() throws IOException {
        int port = ((Number) networkPortSpinner.getValue()).intValue();
        return new NetworkBackupClient(networkHostField.getText().trim(), port, networkUserField.getText().trim(),
                networkPasswordField.getPassword(), networkServer == null ? null
                        : networkServer.storageRoot().resolve(com.backup.network.NetworkBackupServer.KEYSTORE_FILE),
                com.backup.network.NetworkBackupServer.KEYSTORE_PASSWORD);
    }

    /** 连接自检：ping 经 TLS+Basic 全链路，成功即证明证书信任与账号都配置正确。 */
// ---- 连接自检：一次 ping 验证「证书信任 + 账号密码」两件事 ----
    private void testNetworkConnection() {
        if (networkUserField.getText().isBlank()) {
            showError("Invalid connection", "A username is required.");
            return;
        }
        new SwingWorker<Properties, Void>() {
            @Override protected Properties doInBackground() throws Exception {
                try (NetworkBackupClient client = connectedClient()) {
                    return client.ping();
                }
            }

            @Override protected void done() {
                try {
                    get();
        // 自检成功即更新状态徽标为绿色，给用户即时正向反馈。
                    log("Connection OK: server answered as user '" + networkUserField.getText().trim() + "'.");
                    updateStatus("Connected", SUCCESS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    log("Connection failed: " + rootMessage(e));
                    updateStatus("Connection failed", DANGER);
                    showError("Connection failed", rootMessage(e));
                }
            }
        }.execute();
    }

    /** 网络备份提交：与本地备份共用任务闸门；参数快照 + keepGroups 一并固定。 */
// ---- 网络备份提交：与本地备份共用任务闸门（磁盘密集型操作全局串行）----
    private void runNetworkBackup() {
        try {
            BackupOptions options = buildNetworkBackupOptions();
            int keepGroups = ((Number) networkKeepGroupsSpinner.getValue()).intValue();
            if (!taskGate.submitManual(() -> startNetworkBackupWorker(options, keepGroups))) {
                log("Network backup unavailable while another backup or restore is running.");
                JOptionPane.showMessageDialog(this, "A backup or restore is running. Please wait for completion.");
            }
        } catch (RuntimeException e) {
            showError("Invalid network backup settings", e.getMessage());
        }
    }

// ---- 网络备份参数：destination 仅占位（网络模式不用本地目标），其余语义同本地 ----
    private BackupOptions buildNetworkBackupOptions() {
        return new BackupOptions(
                Path.of(networkSourceField.getText().trim()),
                Path.of(System.getProperty("java.io.tmpdir"), "backup-network-unused"),
                networkNameField.getText(),
                (CompressionType) networkCompressionCombo.getSelectedItem(),
                networkIncrementalCheckBox.isSelected(),
                networkEncryptedCheckBox.isSelected(),
                networkPassphraseField.getPassword(),
                BackupFilter.all(),
                0);
    }

// ---- 网络备份 worker：后台跑「拉基线→扫描→写归档→上传→提交」，done() 呈现结果 ----
    private void startNetworkBackupWorker(BackupOptions options, int keepGroups) {
        setTaskControlsEnabled(false);
        updateStatus("Backing up to server", PRIMARY);
        new SwingWorker<NetworkBackupService.NetworkBackupResult, String>() {
        // 网络备份 worker：后台「拉基线→扫描→打包→上传→提交」，过程日志流式回显。
            @Override protected NetworkBackupService.NetworkBackupResult doInBackground() throws Exception {
                try (NetworkBackupClient client = connectedClient()) {
                    publish("Network backup started (" + options.compressionType() + (options.encrypted() ? ", encrypted" : "") + ").");
                    return networkBackupService.backup(client, options, keepGroups);
                }
            }

            @Override protected void process(java.util.List<String> chunks) {
                chunks.forEach(BackupFrame.this::log);
            }

            @Override protected void done() {
                try {
                    NetworkBackupService.NetworkBackupResult result = get();
                    // 网络备份呈现：全量/增量标签 + 条目/字节/警告，与本地口径一致。
                    log("Network backup complete: %s (%s, %d archived entries, %,d bytes, %d warnings).".formatted(
                            result.archiveName(), result.full() ? "full" : "incremental",
                            result.archivedEntries(), result.bytes(), result.warnings().size()));
                    result.warnings().forEach(warning -> log("Warning: " + warning));
                    updateStatus(result.warnings().isEmpty() ? "Ready" : result.warnings().size() + " warnings",
                            result.warnings().isEmpty() ? SUCCESS : WARNING);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log("Network backup interrupted.");
                    updateStatus("Interrupted", WARNING);
                } catch (ExecutionException e) {
                    log("Network backup failed: " + rootMessage(e));
                    updateStatus("Network backup failed", DANGER);
                    showError("Network backup failed", rootMessage(e));
                } finally {
                    setTaskControlsEnabled(true);
                    taskGate.complete();
                }
            }
        }.execute();
    }

    /** 拉取并展示服务器快照列表（按创建时间倒序），下拉项带组号/序号/字节数。 */
// ---- 快照列表：只读操作不走闸门；下拉项携带组号/序号/字节数 ----
    private void runNetworkList() {
        new SwingWorker<java.util.List<NetworkBackupClient.RemoteSnapshot>, Void>() {
        // 列表刷新只读：不占用任务闸门（不影响正在进行的备份/还原）。
            @Override protected java.util.List<NetworkBackupClient.RemoteSnapshot> doInBackground() throws Exception {
                try (NetworkBackupClient client = connectedClient()) {
                    return client.listSnapshots();
                }
            }

            @Override protected void done() {
                try {
                    java.util.List<NetworkBackupClient.RemoteSnapshot> snapshots = get();
                    // 列表填充：displayName = 归档名 + 组前 8 位 + 序号 + 字节数（千分位）。
                    snapshotsCombo.removeAllItems();
                    for (NetworkBackupClient.RemoteSnapshot snapshot : snapshots) {
                        snapshotsCombo.addItem("%s  (%s #%d, %,d bytes)".formatted(snapshot.archiveName(),
                                snapshot.groupId().substring(0, 8), snapshot.sequence(), snapshot.bytes()));
                    }
                    log("Server lists " + snapshots.size() + " snapshot(s).");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    log("Listing snapshots failed: " + rootMessage(e));
                    showError("Listing snapshots failed", rootMessage(e));
                }
            }
        }.execute();
    }

    /** 下拉框只做展示；执行动作前按索引重查服务器最新快照，避免列表过期导致误操作。 */
// ---- 选中校验：按索引重查服务器最新列表，列表过期时要求刷新（防误操作他者归档）----
    private NetworkBackupClient.RemoteSnapshot selectedSnapshot() {
        int index = snapshotsCombo.getSelectedIndex();
        if (index < 0) throw new IllegalStateException("List snapshots first, then select one.");
        try {
            java.util.List<NetworkBackupClient.RemoteSnapshot> snapshots;
            // 下拉框只是上次列表的镜像；操作前按索引重查一次，保证动作基于服务器最新状态。
            try (NetworkBackupClient client = connectedClient()) {
                snapshots = client.listSnapshots();
            }
            if (index >= snapshots.size()) throw new IllegalStateException("The selected snapshot is no longer available; refresh the list.");
            return snapshots.get(index);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Server query failed: " + e.getMessage(), e);
        }
    }

    /** 网络还原：选中快照 → 下载 → 走本地 restoreDetailed，复用任务闸门与口令清零纪律。 */
// ---- 网络还原：目标目录必填 → 闸门 → 下载 + 本地还原 ----
    private void runNetworkRestore() {
        try {
            NetworkBackupClient.RemoteSnapshot snapshot = selectedSnapshot();
            Path destination = Path.of(networkRestoreDestinationField.getText().trim());
            if (networkRestoreDestinationField.getText().isBlank()) {
                showError("Invalid restore settings", "A restore destination is required.");
                return;
            }
            char[] passphrase = networkRestorePassphraseField.getPassword();
            if (!taskGate.submitManual(() -> startNetworkRestoreWorker(snapshot.archiveName(), destination, passphrase))) {
                log("Restore unavailable while another backup or restore is running.");
                JOptionPane.showMessageDialog(this, "A backup or restore is running. Please wait for completion.");
            }
        } catch (RuntimeException e) {
            showError("Restore unavailable", e.getMessage());
        }
    }

    private void startNetworkRestoreWorker(String archiveName, Path destination, char[] passphrase) {
        setTaskControlsEnabled(false);
        updateStatus("Restoring from server", PRIMARY);
        new SwingWorker<RestoreResult, String>() {
            @Override protected RestoreResult doInBackground() throws Exception {
                try (NetworkBackupClient client = connectedClient()) {
                    publish("Downloading " + archiveName + " ...");
                    return networkBackupService.restore(client, archiveName, destination, passphrase);
                }
            }

            @Override protected void process(java.util.List<String> chunks) {
                chunks.forEach(BackupFrame.this::log);
            }

            @Override protected void done() {
                try {
                    RestoreResult result = get();
                    log("Network restore complete: " + result.manifest().entries().size() + " manifest entries, "
                            + result.warnings().size() + " warnings.");
                    result.warnings().forEach(warning -> log("Warning: " + warning));
                    updateStatus(result.warnings().isEmpty() ? "Ready" : result.warnings().size() + " warnings",
                            result.warnings().isEmpty() ? SUCCESS : WARNING);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log("Network restore interrupted.");
                    updateStatus("Interrupted", WARNING);
                } catch (ExecutionException e) {
                    log("Network restore failed: " + rootMessage(e));
                    updateStatus("Network restore failed", DANGER);
                    showError("Network restore failed", rootMessage(e));
                } finally {
                    java.util.Arrays.fill(passphrase, '\0');
                    setTaskControlsEnabled(true);
                    taskGate.complete();
                }
            }
        }.execute();
    }

    /** 整组删除：二次确认对话框；服务器侧保护最新组，客户端刷新列表同步状态。 */
// ---- 整组删除：确认对话框 → 服务器删除（保护最新组）→ 成功后刷新列表 ----
    private void runNetworkDelete() {
        try {
            NetworkBackupClient.RemoteSnapshot snapshot = selectedSnapshot();
            int answer = JOptionPane.showConfirmDialog(this,
                    "Delete the whole backup group of " + snapshot.archiveName() + " from the server?",
                    "Delete group", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) return;
            new SwingWorker<Properties, Void>() {
                @Override protected Properties doInBackground() throws Exception {
                    try (NetworkBackupClient client = connectedClient()) {
                        return client.deleteGroup(snapshot.groupId());
                    }
                }

                @Override protected void done() {
                    try {
                        Properties response = get();
                        // 删除结果：删除条数 + 服务器警告逐条入日志。
                        log("Server deleted " + response.getProperty("deleted", "0") + " archive(s).");
                        for (int i = 0; ; i++) {
                            String warning = response.getProperty("warning." + i);
                            if (warning == null) break;
                            log("Warning: " + warning);
                        }
                        runNetworkList();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (ExecutionException e) {
                        log("Delete failed: " + rootMessage(e));
                        showError("Delete failed", rootMessage(e));
                    }
                }
            }.execute();
        } catch (RuntimeException e) {
            showError("Delete unavailable", e.getMessage());
        }
    }

    /** 活动日志面板：等宽深色控制台风格，只追加不覆盖，带清屏按钮。 */
// ==================== 活动日志 ====================
// 只追加不覆盖（时间戳前缀），评委可回溯整个演示过程；Clear 按钮仅供演示重置。
    private JPanel createLogPanel() {
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        logArea.setForeground(new Color(219, 234, 254));
        logArea.setBackground(CONSOLE);
        logArea.setCaretColor(new Color(147, 197, 253));
        logArea.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setBorder(BorderFactory.createLineBorder(new Color(30, 41, 59)));
        scrollPane.setPreferredSize(new Dimension(0, 165));
        JPanel wrapper = new JPanel(new BorderLayout(0, 7));
        wrapper.setOpaque(false);
        JPanel heading = new JPanel(new BorderLayout());
        heading.setOpaque(false);
        JLabel title = new JLabel("ACTIVITY");
        title.setFont(new Font("SansSerif", Font.BOLD, 11));
        title.setForeground(MUTED);
        JButton clearButton = new JButton("Clear");
        styleSmallButton(clearButton);
        clearButton.addActionListener(event -> logArea.setText(""));
        heading.add(title, BorderLayout.WEST);
        heading.add(clearButton, BorderLayout.EAST);
        wrapper.add(heading, BorderLayout.NORTH);
        wrapper.add(scrollPane, BorderLayout.CENTER);
        JPanel outer = new JPanel(new BorderLayout());
        outer.setOpaque(false);
        outer.setBorder(BorderFactory.createEmptyBorder(0, 22, 18, 22));
        outer.add(wrapper, BorderLayout.CENTER);
        return outer;
    }

    /** 表单容器：统一白底描边 + 内嵌标题边框，四页复用。 */
// ==================== 表单与页面骨架 ====================
// formPanel：白卡 + 标题边框；pagePanel：眉标/标题/描述三行头；scrollablePage：纵向滚动。
    private JPanel formPanel(String title) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(SURFACE);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createCompoundBorder(
                        BorderFactory.createEmptyBorder(10, 10, 10, 10),
                        BorderFactory.createTitledBorder(
                                BorderFactory.createEmptyBorder(), title, 0, 0,
                                LABEL_FONT, INK
                        )
                )
        ));
        return panel;
    }

    /** 页面骨架：眉标 + 标题 + 描述三行头，下方接表单。 */
    private JPanel pagePanel(String eyebrow, String title, String description) {
        JPanel page = new JPanel(new BorderLayout(0, 14));
        page.setBackground(WINDOW_BACKGROUND);
        page.setBorder(BorderFactory.createEmptyBorder(12, 12, 0, 12));

        JPanel intro = new JPanel();
        intro.setOpaque(false);
        intro.setLayout(new BoxLayout(intro, BoxLayout.Y_AXIS));
        JLabel eyebrowLabel = new JLabel(eyebrow);
        eyebrowLabel.setFont(new Font("SansSerif", Font.BOLD, 10));
        eyebrowLabel.setForeground(PRIMARY);
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(TITLE_FONT);
        titleLabel.setForeground(INK);
        JLabel descriptionLabel = new JLabel(description);
        descriptionLabel.setFont(BODY_FONT);
        descriptionLabel.setForeground(MUTED);
        intro.add(eyebrowLabel);
        intro.add(Box.createVerticalStrut(4));
        intro.add(titleLabel);
        intro.add(Box.createVerticalStrut(4));
        intro.add(descriptionLabel);
        page.add(intro, BorderLayout.NORTH);
        return page;
    }

    /** 页面包一层滚动：横向禁用（自适应窗口宽），纵向按需。 */
    private JScrollPane scrollablePage(JPanel page) {
        JScrollPane scrollPane = new JScrollPane(page);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.getViewport().setBackground(WINDOW_BACKGROUND);
        return scrollPane;
    }

    /** 路径输入行：输入框 + Choose 按钮（目录/文件选择器）。 */
// ==================== GridBag 行助手 ====================
// addRow：标签右对齐 + 控件横向填充；addPathRow：额外挂 Choose 按钮（目录/文件两种模式）。
    private void addPathRow(JPanel panel, int row, String label, JTextField field, boolean directoriesOnly) {
        JPanel container = new JPanel(new BorderLayout(6, 0));
        container.setOpaque(false);
        container.add(field, BorderLayout.CENTER);
        JButton browseButton = new JButton("Choose");
        styleSmallButton(browseButton);
        browseButton.addActionListener(event -> choosePath(field, directoriesOnly));
        container.add(browseButton, BorderLayout.EAST);
        addRow(panel, row, label, container);
    }

    private void addActionRow(JPanel panel, int row, JButton button) {
        GridBagConstraints constraints = constraints(1, row);
        constraints.anchor = GridBagConstraints.WEST;
        panel.add(button, constraints);
    }

    /** 两列表单行：右对齐标签 + 自适应宽控件，统一套用样式。 */
    private void addRow(JPanel panel, int row, String label, java.awt.Component component) {
        GridBagConstraints labelConstraints = constraints(0, row);
        labelConstraints.anchor = GridBagConstraints.EAST;
        JLabel labelComponent = new JLabel(label);
        labelComponent.setFont(LABEL_FONT);
        labelComponent.setForeground(MUTED);
        panel.add(labelComponent, labelConstraints);

        GridBagConstraints componentConstraints = constraints(1, row);
        componentConstraints.weightx = 1.0;
        componentConstraints.fill = GridBagConstraints.HORIZONTAL;
        styleFormComponent(component);
        panel.add(component, componentConstraints);
    }

    /** GridBag 单元格参数：统一内边距与对齐。 */
    private GridBagConstraints constraints(int column, int row) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = column;
        constraints.gridy = row;
        constraints.insets = new Insets(8, 8, 8, 8);
        constraints.anchor = GridBagConstraints.WEST;
        return constraints;
    }

    /** 文件/目录选择器，选中后回填到输入框。 */
    private void choosePath(JTextField field, boolean directoriesOnly) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(directoriesOnly ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            field.setText(chooser.getSelectedFile().toPath().toString());
        }
    }

    /** 把表单字段组装为不可变 BackupOptions：任何解析错误在此抛出，坏参数进不了后台。 */
// ==================== 参数组装与任务调度 ====================
// 本地备份提交：字段 → BackupOptions（解析失败即弹窗）→ runBackupAsync。
    private BackupOptions buildBackupOptionsFromFields() {
        BackupOptions options = new BackupOptions(
                Path.of(sourceField.getText().trim()),
                Path.of(destinationField.getText().trim()),
                backupNameField.getText(),
                (CompressionType) compressionCombo.getSelectedItem(),
                incrementalCheckBox.isSelected(),
                encryptedCheckBox.isSelected(),
                passphraseField.getPassword(),
                new BackupFilter(includePathsField.getText(), excludePathsField.getText(),
                        extensionsField.getText(), extensionlessCheckBox.isSelected(), namesField.getText(),
                        ownersField.getText(),
                        parseFilterTime(modifiedAfterField, "Earliest modified time"),
                        parseFilterTime(modifiedBeforeField, "Latest modified time"),
                        parseBytes(minBytesField, "Minimum size"), parseBytes(maxBytesField, "Maximum size")),
                ((Number) keepGroupsSpinner.getValue()).intValue()
        );
        return options;
    }

    /**
     * 备份提交的统一入口：手动请求忙时拒绝并提示；自动请求（Scheduled/Realtime）
     * 先校验"请求仍是当前生效的配置"（防止停止后残留回调以旧参数执行），再交给闸门。
     */
// label 区分 Manual/Scheduled/Realtime：自动请求需校验「仍是当前生效配置」才执行，
// 防止停止自动化后残留回调以旧参数悄悄跑备份。
    private void runBackupAsync(String label, BackupOptions request) {
        if (request == null) return;
        Runnable starter = () -> {
            if (!"Manual".equals(label)
                    && request != ("Scheduled".equals(label) ? scheduledRequest : watchedRequest)) {
                taskGate.complete();
                return;
            }
            startBackupWorker(label, request);
        };
        if ("Manual".equals(label)) {
            if (!taskGate.submitManual(starter)) {
                log("Backup unavailable while a backup or restore is running.");
                JOptionPane.showMessageDialog(this, "A backup or restore is running. Please wait for completion.");
            }
        } else {
            taskGate.submitAutomatic(request, starter);
        }
    }

    /** 备份 SwingWorker：doInBackgroud 跑服务调用，process 流式刷日志，done 里呈现结果/警告/失败并 complete 闸门。 */
// SwingWorker 三段：doInBackground（服务调用）/ process（流式日志）/ done（结果+警告+状态徽标），
// finally 必做两件事：恢复按钮可用、taskGate.complete() 释放闸门。
    private void startBackupWorker(String label, BackupOptions request) {
        setTaskControlsEnabled(false);
        updateStatus("Backing up", PRIMARY);
        new SwingWorker<BackupResult, String>() {
        // SwingWorker：后台执行 + process 回 EDT 刷日志 + done 统一收尾（恢复按钮/complete 闸门）。
            @Override
            protected BackupResult doInBackground() throws Exception {
                publish(label + " backup started.");
        // 起始日志经 process 回 EDT，与结果日志保持时间线一致。
                BackupResult result = backupService.backup(request);
                try {
                    BackupManifest manifest = BackupManifest.load(result.manifestPath());
        // 成功后回读清单展示快照组信息（读取失败不影响备份成功态）。
                    if (manifest.snapshot() != null) publish(snapshotDescription(manifest));
                } catch (IOException e) {
                    publish("Could not read snapshot details: " + e.getMessage());
                }
                return result;
            }

            @Override
            protected void process(java.util.List<String> chunks) {
                chunks.forEach(BackupFrame.this::log);
            }

            @Override
            protected void done() {
                try {
                    BackupResult result = get();
                    // 成功呈现：归档路径/条目数/字节数/警告数一条日志说清，警告逐条列出。
                    log("%s backup complete: %s (%d archived entries, %,d bytes, %d warnings).".formatted(
                            label,
                            result.archivePath(),
                            result.archivedEntries(),
                            result.archivedBytes(),
                            result.warnings().size()
                    ));
                    result.warnings().forEach(warning -> log("Warning: " + warning));
                    updateStatus(result.warnings().isEmpty() ? "Ready" : result.warnings().size() + " warnings",
                            result.warnings().isEmpty() ? SUCCESS : WARNING);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log(label + " backup interrupted.");
                    updateStatus("Interrupted", WARNING);
                } catch (ExecutionException e) {
                    log(label + " backup failed: " + rootMessage(e));
                    updateStatus("Backup failed", DANGER);
                    showError("Backup failed", rootMessage(e));
                } finally {
                    setTaskControlsEnabled(true);
                    taskGate.complete();
                }
            }
        }.execute();
    }

    /** 本地还原提交：读表单 → 闸门拒绝忙时请求 → 后台执行。 */
// 本地还原提交：路径非法在 Path.of 处即抛，归口到同一错误对话框。
    private void runRestoreAsync() {
        Path archive = Path.of(restoreArchiveField.getText().trim());
        Path destination = Path.of(restoreDestinationField.getText().trim());
        char[] passphrase = restorePassphraseField.getPassword();
        if (!taskGate.submitManual(() -> startRestoreWorker(archive, destination, passphrase))) {
            log("Restore unavailable while a backup or restore is running.");
            JOptionPane.showMessageDialog(this, "A backup or restore is running. Please wait for completion.");
        }
    }

    /** 还原 SwingWorker：完成后清零口令数组（无论成败），警告逐条入日志。 */
// 还原 worker：done() 里无论成败都把口令数组清零（安全纪律），再恢复 UI。
    private void startRestoreWorker(Path archive, Path destination, char[] passphrase) {
        setTaskControlsEnabled(false);
        updateStatus("Restoring", PRIMARY);
        new SwingWorker<RestoreResult, String>() {
            @Override
            protected RestoreResult doInBackground() throws Exception {
                publish("Restore started.");
                return backupService.restoreDetailed(archive, destination, passphrase);
            }

            @Override
            protected void process(java.util.List<String> chunks) {
                chunks.forEach(BackupFrame.this::log);
            }

            @Override
            protected void done() {
                try {
                    RestoreResult result = get();
                    BackupManifest manifest = result.manifest();
                    log("Restore complete: " + manifest.entries().size() + " manifest entries, " + result.warnings().size() + " warnings.");
                    if (manifest.snapshot() != null) log(snapshotDescription(manifest));
                    result.warnings().forEach(warning -> log("Warning: " + warning));
                    updateStatus(result.warnings().isEmpty() ? "Ready" : result.warnings().size() + " warnings",
                            result.warnings().isEmpty() ? SUCCESS : WARNING);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log("Restore interrupted.");
                    updateStatus("Interrupted", WARNING);
                } catch (ExecutionException e) {
                    log("Restore failed: " + rootMessage(e));
                    updateStatus("Restore failed", DANGER);
                    showError("Restore failed", rootMessage(e));
                } finally {
                    java.util.Arrays.fill(passphrase, '\0');
                    setTaskControlsEnabled(true);
                    taskGate.complete();
                }
            }
        }.execute();
    }

    /** 定时器开关：停止时取消其在闸门里的待执行请求；启动时快照当前表单参数（改参数需重启生效）。 */
// ==================== 自动化开关 ====================
// 停止路径：停引擎 + 取消闸门中该任务的待执行请求 + 按钮复位；
// 启动路径：固化参数快照（改参数需重启才生效，说明文字也在页面上交代了）。
    private void toggleSchedule() {
        if (scheduledBackupRunner.isRunning()) {
            scheduledBackupRunner.stop();
            BackupOptions stopped = scheduledRequest;
            scheduledRequest = null;
            if (stopped != null) taskGate.cancelAutomatic(stopped);
            scheduleButton.setText("Start schedule");
            styleSecondaryButton(scheduleButton);
            updateStatus("Ready", SUCCESS);
            log("Scheduled backup stopped.");
            return;
        }
        try {
            scheduledRequest = buildBackupOptionsFromFields();
        } catch (RuntimeException e) {
            showError("Invalid backup settings", e.getMessage());
            return;
        }
        long minutes = ((Number) scheduleMinutesSpinner.getValue()).longValue();
        BackupOptions request = scheduledRequest;
        scheduledBackupRunner.start(Duration.ofMinutes(minutes), () -> runBackupAsync("Scheduled", request));
        scheduleButton.setText("Stop schedule");
        styleWarningButton(scheduleButton);
        updateStatus("Scheduled", PRIMARY_DARK);
        log("Scheduled backup started every " + minutes + " minute(s).");
    }

    /** 实时监听开关：启动失败（如目录不存在）要回滚按钮态并弹窗，不能留下"假运行"状态。 */
// 实时监听启动失败（目录不存在等）必须回滚按钮态并弹窗，不允许假运行。
    private void toggleWatch() {
        if (watchBackupService.isRunning()) {
            watchBackupService.stop();
            BackupOptions stopped = watchedRequest;
            watchedRequest = null;
            if (stopped != null) taskGate.cancelAutomatic(stopped);
            watchButton.setText("Start realtime backup");
            styleSecondaryButton(watchButton);
            updateStatus("Ready", SUCCESS);
            log("Realtime backup stopped.");
            return;
        }
        try {
            watchedRequest = buildBackupOptionsFromFields();
            long seconds = ((Number) watchDebounceSecondsSpinner.getValue()).longValue();
            BackupOptions request = watchedRequest;
            watchBackupService.start(
                    request.sourceDirectory(),
                    Duration.ofSeconds(seconds),
                    () -> runBackupAsync("Realtime", request),
                    message -> SwingUtilities.invokeLater(() -> log(message))
            );
            watchButton.setText("Stop realtime backup");
            styleWarningButton(watchButton);
            updateStatus("Watching", PRIMARY_DARK);
            log("Realtime backup started.");
        } catch (IOException | RuntimeException e) {
            log("Realtime backup failed to start: " + e.getMessage());
            updateStatus("Watch failed", DANGER);
            showError("Realtime backup failed", e.getMessage());
        }
    }

    /** 从 ExecutionException 里解包最内层根因（SwingWorker 包装链），给用户看人话。 */
// ==================== 呈现辅助 ====================
// 解包 SwingWorker 异常链到最内层根因——用户要人话，不要堆栈。
    private String rootMessage(ExecutionException e) {
        Throwable cause = e.getCause();
        while (cause != null && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause == null ? e.getMessage() : cause.getMessage();
    }

    /** 模态错误对话框：标题=操作类别，正文=根因。 */
    private void showError(String title, String message) {
        JOptionPane.showMessageDialog(this, message, title, JOptionPane.ERROR_MESSAGE);
    }

    /** 追加一条带时间戳的日志并滚动到底部；所有操作结果都经此留痕。 */
// 日志：时间戳前缀 + 追加到底部； EDT 外线程严禁调用（worker 一律经 process 回 EDT）。
    private void log(String message) {
        logArea.append("[%s] %s%n".formatted(LocalDateTime.now().format(LOG_TIME), message));
        logArea.setCaretPosition(logArea.getDocument().getLength());
        // 光标置底：新日志始终可见（自动滚动）。
    }

    /** 状态徽标：绿=就绪/成功，蓝=进行中，黄=有警告，红=失败。 */
    private void updateStatus(String text, Color color) {
        statusLabel.setText(text);
        statusLabel.setBackground(color);
    }

    /** 关窗清理顺序：任务闸门（拒绝新任务）→ 定时器 → 监听器 → 网络服务器（含未完成上传清理）。 */
    private void shutdown() {
        taskGate.close();
        scheduledBackupRunner.close();
        watchBackupService.close();
        if (networkServer != null) {
            networkServer.close();
            networkServer = null;
        }
    }

    /** 递归样式化：按组件类型套字体/前景色/边框/高度，	JPanel 容器继续下钻。 */
// ==================== 样式族 ====================
// 递归样式化容器内所有控件；styleButton 是四个按钮变体的公共底座。
    private void styleFormComponent(Component component) {
// 输入框：白底蓝光标描边； JComboBox/JSpinner/JCheckBox/JPanel/JLabel 各自的统一外观。
        if (component instanceof JTextField textField) {
            styleTextField(textField);
            return;
        }
        if (component instanceof JComboBox<?> comboBox) {
            comboBox.setFont(BODY_FONT);
            comboBox.setForeground(INK);
            comboBox.setBackground(Color.WHITE);
            comboBox.setPreferredSize(new Dimension(comboBox.getPreferredSize().width, 34));
            return;
        }
        if (component instanceof JSpinner spinner) {
            spinner.setFont(BODY_FONT);
            spinner.setPreferredSize(new Dimension(spinner.getPreferredSize().width, 34));
            Component editor = spinner.getEditor();
            styleFormComponent(editor);
            return;
        }
        if (component instanceof JCheckBox checkBox) {
            checkBox.setFont(BODY_FONT);
            checkBox.setForeground(INK);
            checkBox.setBackground(SURFACE);
            checkBox.setFocusPainted(false);
            return;
        }
        if (component instanceof JPanel panel) {
            panel.setOpaque(false);
            for (Component child : panel.getComponents()) {
                styleFormComponent(child);
            }
            return;
        }
        if (component instanceof JLabel label) {
            label.setFont(LABEL_FONT);
            label.setForeground(MUTED);
        }
    }

    /** 输入框统一形态：白底 + 描边 + 蓝光标 + 34px 高。 */
    private void styleTextField(JTextField field) {
        field.setFont(BODY_FONT);
        field.setForeground(INK);
        field.setBackground(Color.WHITE);
        field.setCaretColor(PRIMARY);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(7, 9, 7, 9)
        ));
        field.setPreferredSize(new Dimension(field.getPreferredSize().width, 34));
    }

    /** 按钮样式族：主（蓝底白字）/次（白底蓝字）/警示（橙）/小（固定宽度）。 */
    private void stylePrimaryButton(JButton button) {
        styleButton(button, PRIMARY, Color.WHITE, PRIMARY_DARK);
    }

    private void styleSecondaryButton(JButton button) {
        styleButton(button, Color.WHITE, PRIMARY, BORDER);
    }

    private void styleWarningButton(JButton button) {
        styleButton(button, WARNING, Color.WHITE, WARNING.darker());
    }

    private void styleSmallButton(JButton button) {
        styleButton(button, Color.WHITE, PRIMARY, BORDER);
        button.setFont(new Font("SansSerif", Font.BOLD, 12));
        button.setPreferredSize(new Dimension(Math.max(button.getPreferredSize().width, 78), 32));
    }

    /** 按钮公共底座：字体/前景/背景/焦点/手型光标，四个变体只差配色。 */
    private void styleButton(JButton button, Color background, Color foreground, Color border) {
        button.setFont(new Font("SansSerif", Font.BOLD, 13));
        button.setForeground(foreground);
        button.setBackground(background);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border),
                BorderFactory.createEmptyBorder(8, 14, 8, 14)
        ));
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
    }

    /** 头部品牌图标：纯手绘圆角盾牌，无图片资源依赖。 */
// ==================== 品牌图标 ====================
// 纯 Graphics2D 手绘（盾牌 + 对勾），零图片资源；窗口缩放不糊。
    private static final class LogoMark extends JPanel {
        private LogoMark() {
            setOpaque(false);
            setPreferredSize(new Dimension(42, 42));
        }

        @Override
            /** 自绘盾牌图标：两根提手 + 圆角主体 + 对勾，分层用不同笔触与颜色。 */
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(96, 165, 250));
                g.fillRoundRect(2, 5, 38, 31, 12, 12);
                g.setColor(new Color(191, 219, 254));
                g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawLine(14, 11, 14, 4);
                g.drawLine(28, 11, 28, 4);
                g.setColor(new Color(15, 35, 63));
                g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawLine(13, 23, 20, 29);
                g.drawLine(20, 29, 30, 17);
            } finally {
                g.dispose();
            }
        }
    }
}

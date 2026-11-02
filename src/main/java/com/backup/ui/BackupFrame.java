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
import java.util.concurrent.ExecutionException;

public final class BackupFrame extends JFrame {
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

    private final BackupService backupService = new BackupService();
    private final ScheduledBackupRunner scheduledBackupRunner = new ScheduledBackupRunner();
    private final WatchBackupService watchBackupService = new WatchBackupService();
    private final BackupTaskGate taskGate = new BackupTaskGate(SwingUtilities::invokeLater);

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
    private final JTextField modifiedAfterField = new JTextField(20);
    private final JTextField modifiedBeforeField = new JTextField(20);
    private final JTextField minBytesField = new JTextField(20);
    private final JTextField maxBytesField = new JTextField(20);
    private final JSpinner keepGroupsSpinner = new JSpinner(new SpinnerNumberModel(0, 0, Integer.MAX_VALUE, 1));
    private final JButton restoreButton = new JButton("Restore");
    private static final DateTimeFormatter FILTER_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
            .withResolverStyle(ResolverStyle.STRICT);

    private final JTextField restoreArchiveField = new JTextField(34);
    private final JTextField restoreDestinationField = new JTextField(34);
    private final JPasswordField restorePassphraseField = new JPasswordField(20);

    private final JSpinner scheduleMinutesSpinner = new JSpinner(new SpinnerNumberModel(30, 1, 24 * 60, 1));
    private final JSpinner watchDebounceSecondsSpinner = new JSpinner(new SpinnerNumberModel(5, 1, 600, 1));
    private final JButton scheduleButton = new JButton("Start schedule");
    private final JButton watchButton = new JButton("Start realtime backup");
    private volatile BackupOptions scheduledRequest;
    private volatile BackupOptions watchedRequest;

    private final JButton backupButton = new JButton("Back up now");
    private final JTextArea logArea = new JTextArea(10, 80);
    private final JLabel statusLabel = new JLabel("Ready");

    public BackupFrame() {
        super("Backup");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(850, 620));
        setSize(1040, 780);
        setLocationRelativeTo(null);
        getContentPane().setBackground(WINDOW_BACKGROUND);
        setLayout(new BorderLayout(0, 14));

        add(createHeaderPanel(), BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Backup", scrollablePage(createBackupPanel()));
        tabs.addTab("Restore", scrollablePage(createRestorePanel()));
        tabs.addTab("Automation", scrollablePage(createAutomationPanel()));
        tabs.setFont(new Font("SansSerif", Font.BOLD, 13));
        tabs.setForeground(INK);
        tabs.setBackground(WINDOW_BACKGROUND);
        tabs.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        tabs.setTabLayoutPolicy(JTabbedPane.WRAP_TAB_LAYOUT);
        add(tabs, BorderLayout.CENTER);
        add(createLogPanel(), BorderLayout.SOUTH);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdown();
                dispose();
            }
        });

        log("Ready.");
    }

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
        header.add(identity, BorderLayout.WEST);

        JPanel status = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        status.setOpaque(false);
        JLabel statusCaption = new JLabel("SYSTEM STATUS");
        statusCaption.setFont(new Font("SansSerif", Font.BOLD, 10));
        statusCaption.setForeground(new Color(164, 184, 211));
        styleStatusLabel();
        status.add(statusCaption);
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
        stylePrimaryButton(backupButton);
        addActionRow(form, row, backupButton);
        backupButton.addActionListener(event -> {
            try {
                runBackupAsync("Manual", buildBackupOptionsFromFields());
            } catch (RuntimeException e) {
                showError("Invalid backup settings", e.getMessage());
            }
        });
        panel.add(form, BorderLayout.CENTER);
        return panel;
    }

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
        addRow(filters, row++, "Modified at or after", modifiedAfterField);
        addRow(filters, row++, "Modified at or before", modifiedBeforeField);
        addRow(filters, row++, "Minimum bytes", minBytesField);
        addRow(filters, row++, "Maximum bytes", maxBytesField);
        addRow(filters, row, "Filter rules", new JLabel("<html>Leave blank for all files. Separate alternatives with ; — conditions intersect.<br>Paths use / and glob patterns, e.g. docs/**; names match the basename.<br>Extensions ignore case, e.g. txt;pdf. Excluded directories are not scanned.<br>Dates: yyyy-MM-dd HH:mm:ss, local time; dates and sizes include both boundaries.<br>Links are filtered by their own path, name, modification time and recorded size; targets are not read.</html>"));
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

    private void setTaskControlsEnabled(boolean enabled) {
        backupButton.setEnabled(enabled);
        restoreButton.setEnabled(enabled);
    }

    private static String snapshotDescription(BackupManifest manifest) {
        var snapshot = manifest.snapshot();
        return "Backup group " + snapshot.groupId() + ", sequence " + snapshot.sequence()
                + ", parent " + (snapshot.parentId() == null || snapshot.parentId().isBlank() ? "full baseline" : snapshot.parentId()) + ".";
    }

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
            try {
                runRestoreAsync();
            } catch (RuntimeException e) {
                showError("Invalid restore settings", e.getMessage());
            }
        });
        addActionRow(form, row, restoreButton);
        panel.add(form, BorderLayout.CENTER);
        return panel;
    }

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

    private JScrollPane scrollablePage(JPanel page) {
        JScrollPane scrollPane = new JScrollPane(page);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.getViewport().setBackground(WINDOW_BACKGROUND);
        return scrollPane;
    }

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

    private GridBagConstraints constraints(int column, int row) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = column;
        constraints.gridy = row;
        constraints.insets = new Insets(8, 8, 8, 8);
        constraints.anchor = GridBagConstraints.WEST;
        return constraints;
    }

    private void choosePath(JTextField field, boolean directoriesOnly) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(directoriesOnly ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            field.setText(chooser.getSelectedFile().toPath().toString());
        }
    }

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
                        parseFilterTime(modifiedAfterField, "Earliest modified time"),
                        parseFilterTime(modifiedBeforeField, "Latest modified time"),
                        parseBytes(minBytesField, "Minimum size"), parseBytes(maxBytesField, "Maximum size")),
                ((Number) keepGroupsSpinner.getValue()).intValue()
        );
        return options;
    }

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

    private void startBackupWorker(String label, BackupOptions request) {
        setTaskControlsEnabled(false);
        updateStatus("Backing up", PRIMARY);
        new SwingWorker<BackupResult, String>() {
            @Override
            protected BackupResult doInBackground() throws Exception {
                publish(label + " backup started.");
                BackupResult result = backupService.backup(request);
                try {
                    BackupManifest manifest = BackupManifest.load(result.manifestPath());
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

    private void runRestoreAsync() {
        Path archive = Path.of(restoreArchiveField.getText().trim());
        Path destination = Path.of(restoreDestinationField.getText().trim());
        char[] passphrase = restorePassphraseField.getPassword();
        if (!taskGate.submitManual(() -> startRestoreWorker(archive, destination, passphrase))) {
            log("Restore unavailable while a backup or restore is running.");
            JOptionPane.showMessageDialog(this, "A backup or restore is running. Please wait for completion.");
        }
    }

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

    private String rootMessage(ExecutionException e) {
        Throwable cause = e.getCause();
        while (cause != null && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause == null ? e.getMessage() : cause.getMessage();
    }

    private void showError(String title, String message) {
        JOptionPane.showMessageDialog(this, message, title, JOptionPane.ERROR_MESSAGE);
    }

    private void log(String message) {
        logArea.append("[%s] %s%n".formatted(LocalDateTime.now().format(LOG_TIME), message));
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void updateStatus(String text, Color color) {
        statusLabel.setText(text);
        statusLabel.setBackground(color);
    }

    private void shutdown() {
        taskGate.close();
        scheduledBackupRunner.close();
        watchBackupService.close();
    }

    private void styleFormComponent(Component component) {
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

    private static final class LogoMark extends JPanel {
        private LogoMark() {
            setOpaque(false);
            setPreferredSize(new Dimension(42, 42));
        }

        @Override
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

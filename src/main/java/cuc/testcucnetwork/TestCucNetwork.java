
package cuc.testcucnetwork;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class TestCucNetwork {

    // ---- Config ----
    private static final int TCP_TIMEOUT_MS  = 2500;
    private static final int DNS_TIMEOUT_MS  = 2000;

    // ---- Model ----
    static class TestSpec {
        final String group;
        final String host;
        final int port;
        final String label;

        TestSpec(String group, String host, int port, String label) {
            this.group = group;
            this.host = host;
            this.port = port;
            this.label = label;
        }
    }

    enum Status { PENDING, RUNNING, OK, FAIL, SKIPPED }

    static class TestResult {
        Status status = Status.PENDING;
        String detail = "";
        long elapsedMs = 0;
        String resolvedIp = "";
    }

    static class Row {
        final TestSpec spec;
        final TestResult result = new TestResult();
        Row(TestSpec spec) { this.spec = spec; }
    }

    // ---- Swing Table Model ----
    static class TestsTableModel extends AbstractTableModel {
        private final List<Row> rows;
        private final String[] cols = {"Gruppo", "Test", "Host", "Porta", "Stato", "Tempo (ms)", "IP risolto", "Dettaglio"};

        TestsTableModel(List<Row> rows) { this.rows = rows; }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int column) { return cols[column]; }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Row r = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> r.spec.group;
                case 1 -> "TELNET(TCP)";
                case 2 -> r.spec.host;
                case 3 -> r.spec.port;
                case 4 -> r.result.status;
                case 5 -> r.result.elapsedMs;
                case 6 -> r.result.resolvedIp == null ? "" : r.result.resolvedIp;
                case 7 -> r.result.detail;
                default -> "";
            };
        }

        public Row getRow(int i) { return rows.get(i); }

        public void setStatus(int idx, Status st, String detail, long elapsed, String resolvedIp) {
            Row r = rows.get(idx);
            r.result.status = st;
            r.result.detail = detail;
            r.result.elapsedMs = elapsed;
            r.result.resolvedIp = resolvedIp == null ? "" : resolvedIp;
            fireTableRowsUpdated(idx, idx);
        }

        public void resetAll() {
            for (Row r : rows) {
                r.result.status = Status.PENDING;
                r.result.detail = "";
                r.result.elapsedMs = 0;
                r.result.resolvedIp = "";
            }
            fireTableDataChanged();
        }
    }

    // ---- App UI ----
    private final JFrame frame = new JFrame("Network Tests (TELNET/TCP only) - Swing");
    private final JTextArea logArea = new JTextArea(14, 80);
    private final JProgressBar progressBar = new JProgressBar();
    private final JButton runBtn = new JButton("Esegui test");
    private final JButton stopBtn = new JButton("Ferma");
    private final JButton saveBtn = new JButton("Salva log...");
    private final JLabel summaryLabel = new JLabel("Pronto.");

    private final List<Row> rows;
    private final TestsTableModel tableModel;
    private SwingWorker<Void, String> worker;
    private final AtomicBoolean stopFlag = new AtomicBoolean(false);

    public TestCucNetwork() {
        this.rows = buildRows();
        this.tableModel = new TestsTableModel(rows);
        initUI();
    }

    private void initUI() {
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout(10, 10));

        JTable table = new JTable(tableModel);
        table.setFillsViewportHeight(true);
        table.setRowHeight(22);

        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createTitledBorder("Elenco test"));

        // Log area
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        DefaultCaret caret = (DefaultCaret) logArea.getCaret();
        caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);

        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("Log"));

        // Controls
        JPanel top = new JPanel(new BorderLayout(8, 8));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(runBtn);
        buttons.add(stopBtn);
        buttons.add(saveBtn);

        stopBtn.setEnabled(false);

        progressBar.setStringPainted(true);
        progressBar.setMinimum(0);
        progressBar.setMaximum(rows.size());
        progressBar.setValue(0);

        JPanel statusPanel = new JPanel(new BorderLayout(8, 8));
        statusPanel.add(progressBar, BorderLayout.CENTER);
        statusPanel.add(summaryLabel, BorderLayout.SOUTH);

        top.add(buttons, BorderLayout.NORTH);
        top.add(statusPanel, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, logScroll);
        split.setResizeWeight(0.60);

        frame.add(top, BorderLayout.NORTH);
        frame.add(split, BorderLayout.CENTER);

        // Actions
        runBtn.addActionListener(e -> startTests());
        stopBtn.addActionListener(e -> stopTests());
        saveBtn.addActionListener(e -> saveLogToFile());

        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        appendLog("Avvio applicazione: " + now() + "\n");
    }

    private void startTests() {
        if (worker != null && !worker.isDone()) return;

        stopFlag.set(false);
        tableModel.resetAll();
        progressBar.setValue(0);
        progressBar.setString("0 / " + rows.size());
        logArea.setText("");
        appendLog("=== ESECUZIONE TEST TELNET(TCP): " + now() + " ===\n");

        runBtn.setEnabled(false);
        stopBtn.setEnabled(true);

        worker = new SwingWorker<>() {
            int ok = 0, fail = 0, skipped = 0;

            @Override
            protected Void doInBackground() {
                for (int i = 0; i < rows.size(); i++) {
                    if (stopFlag.get()) {
                        tableModel.setStatus(i, Status.SKIPPED, "Interrotto dall'utente", 0, "");
                        skipped++;
                        publish(linePrefix(i) + "SKIPPED (interrotto)\n");
                        continue;
                    }

                    Row r = tableModel.getRow(i);
                    tableModel.setStatus(i, Status.RUNNING, "In esecuzione...", 0, "");
                    publish(linePrefix(i) + "RUNNING TCP " + r.spec.host + ":" + r.spec.port + "\n");

                    long start = System.nanoTime();

                    // DNS resolve (best effort) before test
                    String resolvedIp = "";
                    try {
                        resolvedIp = resolveIp(r.spec.host, DNS_TIMEOUT_MS);
                    } catch (Exception ignored) {}

                    TestOutcome outcome;
                    try {
                        outcome = tcpConnect(r.spec.host, r.spec.port, TCP_TIMEOUT_MS);
                    } catch (Exception ex) {
                        outcome = new TestOutcome(false, "Eccezione: " + ex.getClass().getSimpleName() + " - " + ex.getMessage());
                    }

                    long elapsed = (System.nanoTime() - start) / 1_000_000;

                    if (outcome.ok) {
                        ok++;
                        tableModel.setStatus(i, Status.OK, outcome.detail, elapsed, resolvedIp);
                        publish(linePrefix(i) + "OK (" + elapsed + " ms) - " + outcome.detail +
                                (resolvedIp.isBlank() ? "" : " [IP=" + resolvedIp + "]") + "\n");
                    } else {
                        fail++;
                        tableModel.setStatus(i, Status.FAIL, outcome.detail, elapsed, resolvedIp);
                        publish(linePrefix(i) + "FAIL (" + elapsed + " ms) - " + outcome.detail +
                                (resolvedIp.isBlank() ? "" : " [IP=" + resolvedIp + "]") + "\n");
                    }

                    int done = i + 1;
                    SwingUtilities.invokeLater(() -> {
                        progressBar.setValue(done);
                        progressBar.setString(done + " / " + rows.size());
                        summaryLabel.setText("OK: " + ok + " | FAIL: " + fail + " | SKIPPED: " + skipped);
                    });
                }
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String s : chunks) appendLog(s);
            }

            @Override
            protected void done() {
                runBtn.setEnabled(true);
                stopBtn.setEnabled(false);
                appendLog("=== FINE TEST: " + now() + " ===\n");
                appendLog("RIEPILOGO -> OK: " + ok + " | FAIL: " + fail + " | SKIPPED: " + skipped + "\n");
            }
        };

        worker.execute();
    }

    private void stopTests() {
        stopFlag.set(true);
        appendLog("Richiesta interruzione... " + now() + "\n");
    }

    private void saveLogToFile() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Salva log");
        fc.setSelectedFile(new java.io.File("network_telnet_log_" +
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".txt"));
        int res = fc.showSaveDialog(frame);
        if (res == JFileChooser.APPROVE_OPTION) {
            Path p = fc.getSelectedFile().toPath();
            try {
                Files.writeString(p, buildFullLog(), StandardCharsets.UTF_8);
                JOptionPane.showMessageDialog(frame, "Log salvato in:\n" + p.toAbsolutePath(),
                        "OK", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(frame, "Errore salvataggio log:\n" + ex.getMessage(),
                        "Errore", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private String buildFullLog() {
        StringBuilder sb = new StringBuilder();
        sb.append("NETWORK TELNET(TCP) TEST LOG\n");
        sb.append("Generato: ").append(now()).append("\n\n");

        sb.append("RISULTATI:\n");
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            sb.append(String.format("%02d) [%s] TCP %s:%d -> %s (%d ms) IP=%s | %s%n",
                    i + 1,
                    r.spec.group,
                    r.spec.host,
                    r.spec.port,
                    r.result.status,
                    r.result.elapsedMs,
                    r.result.resolvedIp == null ? "" : r.result.resolvedIp,
                    r.result.detail == null ? "" : r.result.detail
            ));
        }

        sb.append("\nLOG DETTAGLIO:\n");
        sb.append(logArea.getText());
        return sb.toString();
    }

    private void appendLog(String s) {
        logArea.append(s);
    }

    private String linePrefix(int idx) {
        return String.format("%02d) ", idx + 1);
    }

    private static String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    // ---- Test execution ----
    static class TestOutcome {
        final boolean ok;
        final String detail;
        TestOutcome(boolean ok, String detail) {
            this.ok = ok;
            this.detail = detail;
        }
    }

    /**
     * TCP connect: equivalente al "telnet host port" per verifica connettività.
     */
    private static TestOutcome tcpConnect(String host, int port, int timeoutMs) {
        SocketAddress sa = new InetSocketAddress(host, port);
        try (Socket s = new Socket()) {
            s.connect(sa, timeoutMs);
            return new TestOutcome(true, "Connessione TCP OK");
        } catch (UnknownHostException ex) {
            return new TestOutcome(false, "DNS non risolve host");
        } catch (SocketTimeoutException ex) {
            return new TestOutcome(false, "Timeout connessione TCP");
        } catch (ConnectException ex) {
            return new TestOutcome(false, "Connessione rifiutata (porta chiusa?)");
        } catch (IOException ex) {
            return new TestOutcome(false, "Errore I/O TCP: " + ex.getMessage());
        }
    }

    /**
     * Resolve to IP with a short timeout (best-effort).
     */
    private static String resolveIp(String host, int timeoutMs) throws Exception {
        final String[] out = {""};
        Thread t = new Thread(() -> {
            try {
                InetAddress a = InetAddress.getByName(host);
                out[0] = a.getHostAddress();
            } catch (Exception ignored) {}
        }, "dns-resolve");
        t.setDaemon(true);
        t.start();
        t.join(timeoutMs);
        return out[0] == null ? "" : out[0];
    }

    // ---- Test list (solo TELNET/TCP) ----
    private static List<Row> buildRows() {
        List<TestSpec> specs = new ArrayList<>();

        // MQTT CUC--
        
        specs.add(new TestSpec("MQTT CUC", "10.200.0.216", 8883, "telnet 10.200.0.216 8883"));

        // SFTP CUC (Blacklist EMV)
        specs.add(new TestSpec("SFTP CUC (Blacklist EMV)", "10.200.0.10", 22, "telnet 10.200.0.10 22"));

        // WS CUC (QR CODES)
        specs.add(new TestSpec("WS CUC (QR CODES)", "10.200.0.35", 443, "telnet 10.200.0.35 443"));

        // POS (EMV GT)
        specs.add(new TestSpec("POS (EMV GT)", "193.178.207.212", 7005, "telnet 193.178.207.212 7005"));

        // POS (TEM)
        specs.add(new TestSpec("POS (TEM)", "35.195.97.84", 7034, "telnet 35.195.97.84 7034"));

        // SFTP REGIONE (TLIST)
        specs.add(new TestSpec("SFTP REGIONE (TLIST)", "sftp.itermob.regione.campania.it", 22,
                "telnet sftp.itermob.regione.campania.it 22"));

        List<Row> rows = new ArrayList<>();
        for (TestSpec s : specs) rows.add(new Row(s));
        return rows;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(TestCucNetwork::new);
    }
}

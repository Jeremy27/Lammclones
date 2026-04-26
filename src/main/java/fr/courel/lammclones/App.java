package fr.courel.lammclones;

import fr.courel.lammclones.io.Trasher;
import fr.courel.lammclones.scan.DuplicateGroup;
import fr.courel.lammclones.scan.DuplicateScanner;
import fr.courel.lammclones.scan.ScanListener;
import fr.courel.lammclones.scan.ScanResult;
import fr.courel.lammui.fx.component.LammButtonFx;
import fr.courel.lammui.fx.component.LammCardFx;
import fr.courel.lammui.fx.component.LammChromeFx;
import fr.courel.lammui.fx.component.LammProgressBarFx;
import fr.courel.lammui.fx.component.LammTreeFx;
import fr.courel.lammui.fx.component.LammTreeIcons;
import fr.courel.lammui.fx.theme.LammThemeFx;
import javafx.application.Application;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.geometry.Side;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.prefs.Preferences;

public class App extends Application {

    private static final String LABEL_SCAN = "Lancer l'analyse";
    private static final String LABEL_CANCEL = "Annuler";
    private static final String STRATEGY_NONE = "Aucune (sélection manuelle)";
    private static final String STRATEGY_OLDEST = "Tout sauf le plus ancien";
    private static final String STRATEGY_NEWEST = "Tout sauf le plus récent";
    private static final String STRATEGY_KEEP_ON_PREFIX = "Tout sauf celui sur ";

    private static final double DEFAULT_WIDTH = 860;
    private static final double DEFAULT_HEIGHT = 760;
    private static final String PREF_THEME = "theme";
    private static final String PREF_ACCENT = "accent";
    private static final String ACCENT_STEEL = "steel";
    private static final String ACCENT_EMERALD = "emerald";
    private static final String ACCENT_AMBER = "amber";
    private static final Preferences PREFS = Preferences.userNodeForPackage(App.class);

    private final List<File> selectedFolders = new ArrayList<>();
    private final BooleanProperty scanRunning = new SimpleBooleanProperty(false);
    private final IntegerProperty selectedCount = new SimpleIntegerProperty(0);
    private final LongProperty selectedBytes = new SimpleLongProperty(0);

    private final List<FileNode> allFileNodes = new ArrayList<>();
    private final Map<Path, FileNode> fileNodeByPath = new HashMap<>();
    private List<DuplicateGroup> currentGroups = List.of();

    private Stage stage;
    private ScanTask currentTask;

    private VBox sourceList;
    private LammButtonFx btnScan;
    private LammButtonFx btnDelete;
    private Label statusLabel;
    private LammProgressBarFx progress;
    private LammTreeFx<ResultNode> resultsTree;
    private LammCardFx resultsCard;
    private ComboBox<String> strategyCombo;

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;
        primaryStage.initStyle(StageStyle.UNDECORATED);

        var chrome = new LammChromeFx("clones — recherche de doublons");
        chrome.attachTo(primaryStage);

        var sourceCard = buildSourceCard();
        var scanCard = buildScanCard();
        resultsCard = buildResultsCard();

        var content = new VBox(16, sourceCard, scanCard, resultsCard);
        content.setPadding(new Insets(20));
        VBox.setVgrow(resultsCard, Priority.ALWAYS);
        VBox.setVgrow(content, Priority.ALWAYS);

        chrome.getChildren().add(content);

        var scene = new Scene(chrome, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        LammThemeFx.install(scene);

        applySavedPreferences(scene);
        chrome.addAction(buildSettingsButton(scene));

        stage.setOnCloseRequest(e -> {
            if (currentTask != null) currentTask.cancel();
        });

        stage.setTitle("Lammclones");
        stage.setScene(scene);
        stage.show();
    }

    private static void applySavedPreferences(javafx.scene.Scene scene) {
        String theme = PREFS.get(PREF_THEME, "LIGHT");
        LammThemeFx.setMode(scene, "DARK".equals(theme) ? LammThemeFx.Mode.DARK : LammThemeFx.Mode.LIGHT);
        String accent = PREFS.get(PREF_ACCENT, ACCENT_STEEL);
        applyAccent(scene, accent);
    }

    private static void applyAccent(javafx.scene.Scene scene, String accent) {
        var root = scene.getRoot();
        root.getStyleClass().removeIf(c -> c.startsWith("accent-"));
        if (accent != null && !ACCENT_STEEL.equals(accent)) {
            root.getStyleClass().add("accent-" + accent);
        }
        PREFS.put(PREF_ACCENT, accent == null ? ACCENT_STEEL : accent);
    }

    private void resetWindow() {
        stage.setMaximized(false);
        stage.setWidth(DEFAULT_WIDTH);
        stage.setHeight(DEFAULT_HEIGHT);
        var screen = Screen.getPrimary().getVisualBounds();
        stage.setX(screen.getMinX() + (screen.getWidth() - DEFAULT_WIDTH) / 2);
        stage.setY(screen.getMinY() + (screen.getHeight() - DEFAULT_HEIGHT) / 2);
    }

    private void showAbout() {
        var alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("À propos");
        alert.setHeaderText("Lammclones");
        alert.setContentText(
            "Version " + readVersion()
            + "\nRecherche de doublons sur disque"
            + "\n\n© 2026 Jeremy Courel");
        alert.initOwner(stage);
        styleDialog(alert);
        alert.showAndWait();
    }

    private void styleDialog(javafx.scene.control.Dialog<?> dialog) {
        var pane = dialog.getDialogPane();
        pane.setGraphic(null);
        pane.getStylesheets().setAll(stage.getScene().getStylesheets());
        var sceneRoot = stage.getScene().getRoot();
        for (var cls : sceneRoot.getStyleClass()) {
            if ("dark".equals(cls) || cls.startsWith("accent-")) {
                if (!pane.getStyleClass().contains(cls)) {
                    pane.getStyleClass().add(cls);
                }
            }
        }
    }

    private static String readVersion() {
        try (var in = App.class.getResourceAsStream("/version.txt")) {
            if (in == null) return "?";
            return new String(in.readAllBytes()).trim();
        } catch (IOException e) {
            return "?";
        }
    }

    private LammCardFx buildSourceCard() {
        var btnAdd = LammButtonFx.primary("+ Ajouter");
        btnAdd.disableProperty().bind(scanRunning);
        btnAdd.setOnAction(e -> onAddSource());

        sourceList = new VBox(6);
        sourceList.getChildren().add(emptyHint());

        var card = new LammCardFx();
        card.setSpacing(10);
        card.getChildren().addAll(
            cardTitleRow("Sources à analyser", btnAdd),
            cardSeparator(),
            sourceList
        );
        return card;
    }

    private LammCardFx buildScanCard() {
        btnScan = LammButtonFx.primary(LABEL_SCAN);
        btnScan.setDisable(true);
        btnScan.setOnAction(e -> onScanClicked());

        progress = new LammProgressBarFx(0);
        HBox.setHgrow(progress, Priority.ALWAYS);

        statusLabel = new Label("Sélectionne au moins un dossier source.");
        statusLabel.setMaxWidth(360);

        var row = new HBox(14, btnScan, progress, statusLabel);
        row.setAlignment(Pos.CENTER_LEFT);

        var card = new LammCardFx();
        card.setSpacing(0);
        card.getChildren().add(row);
        return card;
    }

    private LammCardFx buildResultsCard() {
        strategyCombo = new ComboBox<>();
        strategyCombo.setPrefWidth(260);
        strategyCombo.valueProperty().addListener((obs, ov, nv) -> applyStrategy(nv));

        btnDelete = LammButtonFx.accent("Supprimer");
        btnDelete.textProperty().bind(Bindings.createStringBinding(
            () -> {
                int n = selectedCount.get();
                if (n == 0) return "Supprimer";
                return "Supprimer " + n + (n == 1 ? " fichier" : " fichiers")
                    + " (" + humanBytes(selectedBytes.get()) + ")";
            },
            selectedCount, selectedBytes
        ));
        btnDelete.disableProperty().bind(selectedCount.isEqualTo(0).or(scanRunning));
        btnDelete.setOnAction(e -> onDeleteClicked());

        resultsTree = new LammTreeFx<>();
        resultsTree.setShowRoot(false);
        resultsTree.setCellFactory(tv -> new ResultCell());
        VBox.setVgrow(resultsTree, Priority.ALWAYS);

        var card = new LammCardFx();
        card.setSpacing(10);
        card.getChildren().addAll(
            cardTitleRow("Résultats",
                new Label("Pré-sélection :"), strategyCombo, btnDelete),
            cardSeparator(),
            resultsTree
        );
        card.setVisible(false);
        card.setManaged(false);
        return card;
    }

    private Node cardTitleRow(String title, Node... rightControls) {
        var titleLabel = new Label(title);
        titleLabel.getStyleClass().add("lamm-card-title");
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var children = new ArrayList<Node>();
        children.add(titleLabel);
        children.add(spacer);
        for (var c : rightControls) children.add(c);
        var row = new HBox(10, children.toArray(new Node[0]));
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Node cardSeparator() {
        var sep = new Region();
        sep.getStyleClass().add("lamm-card-separator");
        return sep;
    }

    private Label emptyHint() {
        return new Label("Aucune source sélectionnée.");
    }

    private Button buildSettingsButton(javafx.scene.Scene scene) {
        // Thème
        var lightItem = new RadioMenuItem("Mode clair");
        var darkItem = new RadioMenuItem("Mode sombre");
        var themeGroup = new ToggleGroup();
        lightItem.setToggleGroup(themeGroup);
        darkItem.setToggleGroup(themeGroup);
        lightItem.setOnAction(e -> {
            LammThemeFx.setMode(scene, LammThemeFx.Mode.LIGHT);
            PREFS.put(PREF_THEME, "LIGHT");
        });
        darkItem.setOnAction(e -> {
            LammThemeFx.setMode(scene, LammThemeFx.Mode.DARK);
            PREFS.put(PREF_THEME, "DARK");
        });

        // Accent
        var accentSteel = new RadioMenuItem("Bleu acier");
        var accentEmerald = new RadioMenuItem("Émeraude");
        var accentAmber = new RadioMenuItem("Ambre");
        var accentGroup = new ToggleGroup();
        accentSteel.setToggleGroup(accentGroup);
        accentEmerald.setToggleGroup(accentGroup);
        accentAmber.setToggleGroup(accentGroup);
        accentSteel.setOnAction(e -> applyAccent(scene, ACCENT_STEEL));
        accentEmerald.setOnAction(e -> applyAccent(scene, ACCENT_EMERALD));
        accentAmber.setOnAction(e -> applyAccent(scene, ACCENT_AMBER));
        var accentMenu = new Menu("Accent");
        accentMenu.getItems().addAll(accentSteel, accentEmerald, accentAmber);

        // Fenêtre
        var resetItem = new MenuItem("Réinitialiser la fenêtre");
        resetItem.setOnAction(e -> resetWindow());

        // À propos
        var aboutItem = new MenuItem("À propos…");
        aboutItem.setOnAction(e -> showAbout());

        var menu = new ContextMenu(
            lightItem, darkItem,
            new SeparatorMenuItem(),
            accentMenu,
            new SeparatorMenuItem(),
            resetItem,
            aboutItem
        );
        menu.getStyleClass().add("lamm-settings-menu");

        var btn = new Button();
        btn.getStyleClass().add("lamm-chrome-button");
        btn.setFocusTraversable(false);
        btn.setGraphic(LammChromeFx.settingsIcon());
        btn.setOnAction(e -> {
            // Sync radios à l'ouverture
            boolean dark = LammThemeFx.isDark();
            lightItem.setSelected(!dark);
            darkItem.setSelected(dark);
            String accent = PREFS.get(PREF_ACCENT, ACCENT_STEEL);
            accentSteel.setSelected(ACCENT_STEEL.equals(accent));
            accentEmerald.setSelected(ACCENT_EMERALD.equals(accent));
            accentAmber.setSelected(ACCENT_AMBER.equals(accent));
            menu.show(btn, Side.BOTTOM, 0, 4);
        });
        return btn;
    }

    private void onAddSource() {
        if (scanRunning.get()) return;
        var chooser = new DirectoryChooser();
        chooser.setTitle("Ajouter un dossier source");
        File chosen = chooser.showDialog(stage);
        if (chosen == null) return;
        if (selectedFolders.contains(chosen)) return;
        addSourceRow(chosen);
    }

    private void addSourceRow(File folder) {
        if (selectedFolders.isEmpty()) sourceList.getChildren().clear();
        selectedFolders.add(folder);
        sourceList.getChildren().add(buildSourceRow(folder));
        refreshScanState();
    }

    private Node buildSourceRow(File folder) {
        var label = new Label(folder.getAbsolutePath());
        label.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(label, Priority.ALWAYS);
        var btnRemove = new LammButtonFx("Retirer");
        btnRemove.disableProperty().bind(scanRunning);
        var row = new HBox(12, label, btnRemove);
        row.setAlignment(Pos.CENTER_LEFT);
        btnRemove.setOnAction(e -> {
            selectedFolders.remove(folder);
            sourceList.getChildren().remove(row);
            if (selectedFolders.isEmpty()) {
                sourceList.getChildren().add(emptyHint());
            }
            refreshScanState();
        });
        return row;
    }

    private void refreshScanState() {
        boolean empty = selectedFolders.isEmpty();
        btnScan.setDisable(empty);
        if (scanRunning.get()) return;
        if (empty) {
            statusLabel.setText("Sélectionne au moins un dossier source.");
        } else {
            int n = selectedFolders.size();
            statusLabel.setText(n + (n == 1 ? " source prête à analyser." : " sources prêtes à analyser."));
        }
        progress.setProgress(0);
        resultsCard.setVisible(false);
        resultsCard.setManaged(false);
        resultsTree.setRoot(null);
        clearTrackingState();
    }

    private void clearTrackingState() {
        allFileNodes.clear();
        fileNodeByPath.clear();
        currentGroups = List.of();
        selectedCount.set(0);
        selectedBytes.set(0);
    }

    private void onScanClicked() {
        if (currentTask != null && currentTask.isRunning()) {
            currentTask.cancel();
            return;
        }
        if (selectedFolders.isEmpty()) return;
        var roots = selectedFolders.stream().map(File::toPath).toList();
        startScan(roots);
    }

    private void startScan(List<Path> roots) {
        resultsCard.setVisible(false);
        resultsCard.setManaged(false);
        resultsTree.setRoot(null);
        clearTrackingState();

        var task = new ScanTask(roots);
        currentTask = task;
        scanRunning.set(true);

        progress.progressProperty().bind(task.progressProperty());
        statusLabel.textProperty().bind(task.messageProperty());
        btnScan.setText(LABEL_CANCEL);

        Runnable resetUi = () -> {
            progress.progressProperty().unbind();
            statusLabel.textProperty().unbind();
            btnScan.setText(LABEL_SCAN);
            currentTask = null;
            scanRunning.set(false);
        };

        task.setOnSucceeded(ev -> {
            resetUi.run();
            progress.setProgress(1);
            ScanResult r = task.getValue();
            currentGroups = new ArrayList<>(r.duplicates());
            statusLabel.setText(formatSummary(r));
            populateStrategyCombo();
            rebuildTreeFromCurrentGroups();
            resultsCard.setVisible(true);
            resultsCard.setManaged(true);
        });
        task.setOnCancelled(ev -> {
            resetUi.run();
            progress.setProgress(0);
            statusLabel.setText("Analyse annulée.");
        });
        task.setOnFailed(ev -> {
            resetUi.run();
            progress.setProgress(0);
            Throwable err = task.getException();
            statusLabel.setText("Erreur : " + (err == null ? "?" : err.getMessage()));
        });

        var thread = new Thread(task, "lammclones-scanner");
        thread.setDaemon(true);
        thread.start();
    }

    private void populateStrategyCombo() {
        var items = new ArrayList<String>();
        items.add(STRATEGY_NONE);
        items.add(STRATEGY_OLDEST);
        items.add(STRATEGY_NEWEST);
        for (var f : selectedFolders) {
            items.add(STRATEGY_KEEP_ON_PREFIX + f.getAbsolutePath());
        }
        strategyCombo.setItems(FXCollections.observableArrayList(items));
        strategyCombo.setValue(STRATEGY_NONE);
    }

    private void rebuildTreeFromCurrentGroups() {
        allFileNodes.clear();
        fileNodeByPath.clear();
        var root = new TreeItem<ResultNode>();
        for (var g : currentGroups) {
            if (g.files().size() < 2) continue;
            var groupItem = new TreeItem<ResultNode>(new GroupNode(g));
            groupItem.setExpanded(true);
            for (var p : g.files()) {
                var fn = new FileNode(p, g.sizeBytes(), readMtime(p));
                fn.selectedForDeletion.addListener(selectionChangeListener);
                allFileNodes.add(fn);
                fileNodeByPath.put(p, fn);
                groupItem.getChildren().add(new TreeItem<>(fn));
            }
            root.getChildren().add(groupItem);
        }
        resultsTree.setRoot(root);
        recomputeSelectionTotals();
    }

    private static long readMtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

    private final ChangeListener<Boolean> selectionChangeListener = (obs, ov, nv) -> recomputeSelectionTotals();

    private void recomputeSelectionTotals() {
        int count = 0;
        long bytes = 0;
        for (var fn : allFileNodes) {
            if (fn.selectedForDeletion.get()) {
                count++;
                bytes += fn.sizeBytes;
            }
        }
        selectedCount.set(count);
        selectedBytes.set(bytes);
    }

    private void applyStrategy(String strategy) {
        for (var fn : allFileNodes) fn.selectedForDeletion.set(false);
        if (strategy == null || STRATEGY_NONE.equals(strategy)) return;

        Function<List<FileNode>, FileNode> picker;
        if (STRATEGY_OLDEST.equals(strategy)) {
            picker = files -> files.stream().min((a, b) -> Long.compare(a.mtimeMillis, b.mtimeMillis)).orElse(null);
        } else if (STRATEGY_NEWEST.equals(strategy)) {
            picker = files -> files.stream().max((a, b) -> Long.compare(a.mtimeMillis, b.mtimeMillis)).orElse(null);
        } else if (strategy.startsWith(STRATEGY_KEEP_ON_PREFIX)) {
            Path keepRoot = Path.of(strategy.substring(STRATEGY_KEEP_ON_PREFIX.length())).toAbsolutePath().normalize();
            picker = files -> files.stream()
                .filter(f -> f.path.toAbsolutePath().normalize().startsWith(keepRoot))
                .findFirst().orElse(null);
        } else {
            return;
        }

        for (var g : currentGroups) {
            var nodes = g.files().stream().map(fileNodeByPath::get).filter(java.util.Objects::nonNull).toList();
            FileNode keeper = picker.apply(nodes);
            if (keeper == null) continue;
            for (var fn : nodes) {
                if (fn != keeper) fn.selectedForDeletion.set(true);
            }
        }
    }

    private void onDeleteClicked() {
        var toDelete = allFileNodes.stream().filter(fn -> fn.selectedForDeletion.get()).toList();
        if (toDelete.isEmpty()) return;
        if (!Trasher.isAvailable()) {
            showError("Corbeille indisponible",
                "Aucun mécanisme de corbeille trouvé.\n\n"
                + "Sous Linux, installe `gio` (paquet glib2) ou `trash-cli`.");
            return;
        }
        if (!confirmDeletion(toDelete)) return;

        var failures = new ArrayList<String>();
        var deletedPaths = new HashSet<Path>();
        for (var fn : toDelete) {
            try {
                if (Trasher.moveToTrash(fn.path.toFile())) {
                    deletedPaths.add(fn.path);
                } else {
                    failures.add(fn.path + " : opération refusée");
                }
            } catch (Exception ex) {
                failures.add(fn.path + " : " + ex.getMessage());
            }
        }

        currentGroups = currentGroups.stream()
            .map(g -> {
                var remaining = g.files().stream().filter(p -> !deletedPaths.contains(p)).toList();
                return remaining.size() < 2 ? null : new DuplicateGroup(g.sizeBytes(), remaining);
            })
            .filter(java.util.Objects::nonNull)
            .toList();
        rebuildTreeFromCurrentGroups();
        strategyCombo.setValue(STRATEGY_NONE);

        long freed = toDelete.stream().filter(fn -> deletedPaths.contains(fn.path)).mapToLong(fn -> fn.sizeBytes).sum();
        statusLabel.setText(String.format(
            "%d fichiers déplacés en corbeille (%s libérés). %d groupes restants.",
            deletedPaths.size(), humanBytes(freed), currentGroups.size()));

        if (!failures.isEmpty()) {
            showError("Suppressions échouées (" + failures.size() + ")",
                String.join("\n", failures.subList(0, Math.min(10, failures.size()))));
        }
    }

    private boolean confirmDeletion(List<FileNode> toDelete) {
        long bytes = toDelete.stream().mapToLong(fn -> fn.sizeBytes).sum();
        var preview = new StringBuilder();
        int previewMax = Math.min(5, toDelete.size());
        for (int i = 0; i < previewMax; i++) {
            preview.append("• ").append(toDelete.get(i).path).append('\n');
        }
        if (toDelete.size() > previewMax) {
            preview.append("… et ").append(toDelete.size() - previewMax).append(" autres.");
        }
        var alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Confirmer la suppression");
        alert.setHeaderText("Déplacer " + toDelete.size() + " fichier(s) vers la corbeille ?");
        alert.setContentText("Espace libéré : " + humanBytes(bytes) + "\n\n" + preview);
        alert.initOwner(stage);
        styleDialog(alert);
        var result = alert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }

    private void showError(String header, String content) {
        var alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Erreur");
        alert.setHeaderText(header);
        alert.setContentText(content);
        alert.initOwner(stage);
        styleDialog(alert);
        alert.showAndWait();
    }

    private static String formatSummary(ScanResult r) {
        return String.format(
            "%d fichiers scannés · %d groupes de doublons · %s récupérables · %.1fs",
            r.filesScanned(), r.duplicates().size(),
            humanBytes(r.wastedBytes()), r.durationMillis() / 1000.0);
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    public static void main(String[] args) {
        launch(args);
    }

    private sealed interface ResultNode permits GroupNode, FileNode {}

    private record GroupNode(DuplicateGroup group) implements ResultNode {
        @Override
        public String toString() {
            return humanBytes(group.sizeBytes())
                + " — " + group.files().size() + " copies ("
                + humanBytes(group.wastedBytes()) + " récupérables)";
        }
    }

    private static final class FileNode implements ResultNode {
        final Path path;
        final long sizeBytes;
        final long mtimeMillis;
        final BooleanProperty selectedForDeletion = new SimpleBooleanProperty(false);

        FileNode(Path path, long sizeBytes, long mtimeMillis) {
            this.path = path;
            this.sizeBytes = sizeBytes;
            this.mtimeMillis = mtimeMillis;
        }

        @Override
        public String toString() {
            return path.toString();
        }
    }

    private class ResultCell extends TreeCell<ResultNode> {

        private final CheckBox checkBox = new CheckBox();
        private FileNode boundNode;
        private TreeItem<ResultNode> trackedItem;
        private final ChangeListener<Boolean> expandedListener = (obs, ov, nv) -> refreshGraphic();

        ResultCell() {
            treeItemProperty().addListener((obs, oldItem, newItem) -> {
                if (trackedItem != null) {
                    trackedItem.expandedProperty().removeListener(expandedListener);
                }
                trackedItem = newItem;
                if (trackedItem != null) {
                    trackedItem.expandedProperty().addListener(expandedListener);
                }
            });
        }

        @Override
        protected void updateItem(ResultNode item, boolean empty) {
            super.updateItem(item, empty);
            if (boundNode != null) {
                checkBox.selectedProperty().unbindBidirectional(boundNode.selectedForDeletion);
                boundNode = null;
            }
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(item.toString());
            refreshGraphic();
        }

        private void refreshGraphic() {
            if (isEmpty() || getItem() == null) {
                setGraphic(null);
                return;
            }
            ResultNode item = getItem();
            if (item instanceof FileNode fn) {
                checkBox.selectedProperty().bindBidirectional(fn.selectedForDeletion);
                boundNode = fn;
                var icon = LammTreeIcons.file();
                var box = new HBox(8, checkBox, icon);
                box.setAlignment(Pos.CENTER_LEFT);
                setGraphic(box);
            } else {
                var ti = getTreeItem();
                Node icon = (ti != null && ti.isExpanded())
                    ? LammTreeIcons.folderOpen()
                    : LammTreeIcons.folder();
                setGraphic(icon);
            }
        }
    }

    private static class ScanTask extends Task<ScanResult> {
        private final List<Path> roots;

        ScanTask(List<Path> roots) {
            this.roots = roots;
        }

        @Override
        protected ScanResult call() {
            var scanner = new DuplicateScanner();
            return scanner.scan(roots, new ScanListener() {
                @Override
                public void onProgress(double fraction, String message) {
                    if (fraction < 0) {
                        updateProgress(-1, 1);
                    } else {
                        updateProgress(fraction, 1);
                    }
                    updateMessage(message);
                }

                @Override
                public boolean isCancelled() {
                    return ScanTask.this.isCancelled();
                }
            });
        }
    }
}

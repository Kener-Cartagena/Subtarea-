package com.subtareas;

import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyValue;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.*;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.util.StringConverter;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class App extends Application {

    private static final String ACC = "#22D3EE", ACC2 = "#0EA5C4", MUTE = "#6B7694", TXT = "#E6EAF5";
    private static final Map<String, String> PRI = Map.of(
            "Alta", "#F43F5E", "Media", "#F59E0B", "Baja", "#10B981");

    // Índices en el resultado de form(): 0 título · 1 duración · 2 inicio · 3 fecha objetivo · 4 margen
    //                                    · 5 prioridad · 6 objetivo de la fase · 7 resultado
    private static final String[][] PH = {
            {"Título", "entry"},
            {"Duración estimada (ej. 2 días)", "entry"},
            {"Fecha de inicio", "date"},
            {"Fecha objetivo", "date"},
            {"Margen (opcional)", "buffer"},
            {"Prioridad", "pri"},
            {"Objetivo de la fase", "entry"},
            {"Resultado esperado", "text"}};

    private static final double MIN_W_SIDE = 760;   // ancho mínimo con sidebar
    private static final double MIN_W_FREE = 520;   // ancho mínimo sin sidebar

    private static final DateTimeFormatter DMY_OUT = DateTimeFormatter.ofPattern("dd/MM/uuuu");
    private static final DateTimeFormatter DMY_IN =
            DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT);

    // pantalla de cierre (criterios / checklist): formato del texto
    private static final Pattern CHK = Pattern.compile("^[-*•]\\s*\\[( |x|X)\\]\\s*(.*)$");
    private static final Pattern NUM = Pattern.compile("^(\\d+)[.)]\\s+(.*)$");
    private static final Pattern BULLET = Pattern.compile("^[-*•–]\\s+(.*)$");
    private static final Pattern NOTE = Pattern.compile("(?i)^(resultado esperado|resultado|nota|importante)\\s*:\\s*(.*)$");

    private Stage stage;
    private VBox mainBox;      // columna central: inicio del ticket o pantalla de cierre
    private HBox homeHead;     // cabecera del inicio (para poder volver)
    private boolean closingOpen;
    private Integer tid;
    private final VBox listBox = new VBox(4);
    private final HBox headc = new HBox(6);
    private final VBox body = new VBox(10);
    private final FlowPane summaryBox = new FlowPane(10, 10);   // resumen del ticket (fijo, arriba)
    private final List<TaskRow> rows = new ArrayList<>();
    private ScrollPane bodyScroll;
    private VBox side;
    private Pane overlay;
    private Label ghost;
    private TaskRow dragRow, hlRow;
    // resumen animado + tarjeta flotante del nombre
    private Integer summaryTid;                                   // ticket cuyo resumen está dibujado
    private final DoubleProperty progFrac = new SimpleDoubleProperty(0);   // progreso mostrado (animado)
    private Timeline progAnim;
    private final Map<String, String> tileSigs = new HashMap<>();         // para animar solo lo que cambia
    private Label namePop;

    private Button importBtn, phaseBtn;   // botones del ticket que pasan a ícono si falta espacio
    private final Set<Integer> collapsed = new HashSet<>();   // fases ocultas
    private final Set<Integer> seen = new HashSet<>();        // fases ya vistas (solo las nuevas inician cerradas)

    // reordenar fases
    private final List<VBox> phaseCards = new ArrayList<>();  // tarjetas visibles, en orden; userData = id de fase
    private VBox dragCard;
    private Label phaseGhost;
    private Region dropLine;
    private int dropIdx = -1;
    private double lastSceneY;
    private Timeline autoScroll;

    // ---------------------------------------------------------------- inicio
    @Override
    public void start(Stage st) {
        this.stage = st;
        BorderPane app = new BorderPane();
        app.getStyleClass().add("root");

        // ---------- Sidebar ----------
        side = new VBox(10);
        side.getStyleClass().add("side");
        side.setPrefWidth(280);
        Label logo = new Label("✅ SUBTAREAS");
        logo.getStyleClass().add("logo");
        logo.setPadding(new Insets(24, 22, 0, 22));
        Label sub = new Label("Tareas específicas por ticket");
        sub.getStyleClass().add("subtitle");
        sub.setPadding(new Insets(0, 22, 0, 22));
        Button newTicket = btn("+  Nuevo ticket", this::newTicket, "primary");
        newTicket.setMaxWidth(Double.MAX_VALUE);
        VBox.setMargin(newTicket, new Insets(0, 18, 0, 18));
        ScrollPane listScroll = new ScrollPane(listBox);
        listScroll.setFitToWidth(true);
        listScroll.getStyleClass().add("clean-scroll");
        smoothScroll(listScroll, listBox);
        VBox.setVgrow(listScroll, Priority.ALWAYS);
        VBox.setMargin(listScroll, new Insets(0, 6, 10, 6));
        side.getChildren().addAll(logo, sub, newTicket, listScroll);

        // ---------- Main ----------
        HBox head = new HBox(14);
        head.setAlignment(Pos.CENTER_LEFT);
        head.setPadding(new Insets(0, 0, 14, 0));
        Button menu = btn("☰", this::toggleSide, "soft");
        headc.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(headc, Priority.ALWAYS);
        head.getChildren().addAll(menu, headc);
        headc.widthProperty().addListener((o, a, b) -> updateHeadMode());

        bodyScroll = new ScrollPane(body);
        bodyScroll.setFitToWidth(true);
        bodyScroll.getStyleClass().add("clean-scroll");
        body.setPadding(new Insets(2, 14, 20, 2));
        smoothScroll(bodyScroll, body);

        summaryBox.setPadding(new Insets(0, 14, 12, 2));
        homeHead = head;
        mainBox = new VBox(head, summaryBox, bodyScroll);
        VBox.setVgrow(bodyScroll, Priority.ALWAYS);
        mainBox.setPadding(new Insets(22, 28, 0, 28));

        app.setLeft(side);
        app.setCenter(mainBox);

        overlay = new Pane(app);
        app.prefWidthProperty().bind(overlay.widthProperty());
        app.prefHeightProperty().bind(overlay.heightProperty());

        Scene scene = new Scene(overlay, 1120, 720);
        scene.getStylesheets().add(css());
        st.setScene(scene);
        st.setMinWidth(MIN_W_SIDE);
        st.setMinHeight(480);
        st.setMaximized(true);   // arranca ocupando toda la pantalla
        st.setTitle("SubTareas");
        drawSide();
        drawMain();
        st.getIcons().add(new javafx.scene.image.Image(getClass().getResourceAsStream("/logo.png")));
        st.show();
    }

    /** Rueda del mouse más ágil y confiable en el ScrollPane. */
    private void smoothScroll(ScrollPane sp, Node content) {
        sp.addEventFilter(ScrollEvent.SCROLL, e -> {
            double extra = content.getBoundsInLocal().getHeight() - sp.getViewportBounds().getHeight();
            if (extra <= 0) return;
            double v = sp.getVvalue() - e.getDeltaY() * 1.6 / extra;
            sp.setVvalue(Math.max(0, Math.min(1, v)));
            e.consume();
        });
    }

    private static boolean onButton(Object target) {
        Node n = target instanceof Node ? (Node) target : null;
        while (n != null) {
            if (n instanceof ButtonBase) return true;
            n = n.getParent();
        }
        return false;
    }

    // ---------------------------------------------------------------- copiar
    private void copy(String text) {
        ClipboardContent cc = new ClipboardContent();
        cc.putString(text);
        Clipboard.getSystemClipboard().setContent(cc);
    }

    /** Click derecho sobre el nodo -> menú "Copiar". */
    private void attachCopy(Node n, String label, Supplier<String> text) {
        n.setOnContextMenuRequested(e -> {
            MenuItem mi = new MenuItem(label);
            mi.setOnAction(a -> copy(text.get()));
            ContextMenu m = new ContextMenu(mi);
            m.setAutoHide(true);
            m.show(n, e.getScreenX(), e.getScreenY());
            e.consume();
        });
    }

    private String phaseText(int pid) {
        Object[] p = Db.one("SELECT title,duration,priority,result,start_date,end_date,buffer_days,goal,ticket_id"
                + " FROM phases WHERE id=?", pid);
        StringBuilder sb = new StringBuilder((String) p[0]);
        if (p[7] != null && !((String) p[7]).isEmpty()) sb.append("\nObjetivo: ").append(p[7]);
        String dur = p[1] == null ? "" : (String) p[1], pri = p[2] == null ? "" : (String) p[2];
        if (!dur.isEmpty() || !pri.isEmpty())
            sb.append("\n").append(dur).append(!dur.isEmpty() && !pri.isEmpty() ? " · " : "").append(pri);
        PhaseDates pd = ticketDates(((Number) p[8]).intValue()).get(pid);   // con corrimientos aplicados
        if (pd != null && !pd.isEmpty()) sb.append("\n").append(pd.summary());
        if (p[3] != null && !((String) p[3]).isEmpty()) sb.append("\n→ ").append(p[3]);
        for (Object[] r : Db.rows("SELECT text FROM tasks WHERE phase_id=? ORDER BY pos,id", pid))
            sb.append("\n- ").append(r[0]);
        return sb.toString();
    }

    private String ticketText(int id) {
        StringBuilder sb = new StringBuilder((String) Db.one("SELECT name FROM tickets WHERE id=?", id)[0]);
        for (Object[] p : Db.rows("SELECT id FROM phases WHERE ticket_id=? ORDER BY pos,id", id))
            sb.append("\n\n").append(phaseText(((Number) p[0]).intValue()));
        return sb.toString();
    }

    /** Texto limpio de la fase para pegar en otro lado: nombre, objetivo, resultado y tareas. */
    private String phaseSummary(int pid) {
        Object[] p = Db.one("SELECT title,goal,result FROM phases WHERE id=?", pid);
        String title = (String) p[0];
        String goal = p[1] == null ? "" : ((String) p[1]).trim();
        String res = p[2] == null ? "" : ((String) p[2]).trim();
        List<Object[]> tasks = Db.rows("SELECT text FROM tasks WHERE phase_id=? ORDER BY pos,id", pid);

        StringBuilder sb = new StringBuilder();
        sb.append("📌 ").append(title).append("\n");
        sb.append("─".repeat(Math.max(12, Math.min(title.length() + 3, 44)))).append("\n");
        if (!goal.isEmpty())
            sb.append("\n🎯 Objetivo\n   ").append(goal.replace("\n", "\n   ")).append("\n");
        if (!res.isEmpty())
            sb.append("\n✅ Resultado esperado\n   ").append(res.replace("\n", "\n   ")).append("\n");
        sb.append("\n📋 Tareas (").append(tasks.size()).append(")\n");
        if (tasks.isEmpty()) sb.append("   — sin tareas —\n");
        int n = 1;
        for (Object[] r : tasks)
            sb.append("   ").append(n++).append(". ").append(((String) r[0]).replace("\n", "\n      ")).append("\n");
        return sb.toString().stripTrailing();
    }

    /** Ventana con la info completa de la fase, ordenada y lista para copiar. */
    private void showPhaseInfo(int pid) {
        String title = (String) Db.one("SELECT title FROM phases WHERE id=?", pid)[0];
        String text = phaseSummary(pid);

        Stage d = new Stage();
        d.initModality(Modality.APPLICATION_MODAL);
        d.initOwner(stage);
        d.setTitle("Fase · " + title);

        Label hint = new Label("Selecciona lo que quieras o copia todo con un clic.");
        hint.getStyleClass().add("field-label");

        TextArea ta = new TextArea(text);
        ta.setEditable(false);
        ta.setWrapText(true);
        ta.getStyleClass().add("preview-area");
        VBox.setVgrow(ta, Priority.ALWAYS);

        Button close = btn("Cerrar", d::close, "soft");
        Button copyBtn = btn("Copiar todo", () -> {}, "primary");
        copyBtn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(copyBtn, Priority.ALWAYS);
        copyBtn.setOnAction(e -> {
            copy(text);
            copyBtn.setText("✓ Copiado");
            PauseTransition pt = new PauseTransition(Duration.seconds(1.4));
            pt.setOnFinished(ev -> copyBtn.setText("Copiar todo"));
            pt.play();
        });
        HBox bar = new HBox(8, close, copyBtn);

        VBox box = new VBox(10, hint, ta, bar);
        box.getStyleClass().add("dialog");
        box.setPadding(new Insets(18, 24, 18, 24));

        Scene sc = new Scene(box, 560, 520);
        sc.getStylesheets().add(css());
        sc.setOnKeyPressed(ev -> { if (ev.getCode() == KeyCode.ESCAPE) d.close(); });
        d.setMinWidth(380);
        d.setMinHeight(320);
        d.setScene(sc);
        d.show();
        Platform.runLater(copyBtn::requestFocus);
    }

    // ---------------------------------------------------------------- widgets
    private Button btn(String text, Runnable cmd) {
        Button b = new Button(text);
        b.setMinWidth(Region.USE_PREF_SIZE);   // nunca se encoge: así no se convierte en "…"
        b.getStyleClass().addAll("btn", "btn-ghost");
        b.setOnAction(e -> cmd.run());
        return b;
    }

    private Button btn(String text, Runnable cmd, String kind) {
        Button b = btn(text, cmd);
        b.getStyleClass().remove("btn-ghost");
        b.getStyleClass().add("btn-" + kind);
        return b;
    }

    private boolean ask(String msg) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                msg + "\n\nEsta acción no se puede deshacer.", ButtonType.YES, ButtonType.NO);
        a.initOwner(stage);
        a.setTitle("Confirmar");
        a.setHeaderText(null);
        return a.showAndWait().filter(b -> b == ButtonType.YES).isPresent();
    }

    // ---------------------------------------------------------------- sidebar
    private void drawSide() {
        listBox.getChildren().clear();
        for (Object[] r : Db.rows("SELECT id,name FROM tickets ORDER BY id DESC")) {
            int i = ((Number) r[0]).intValue();
            String name = (String) r[1];
            Object[] c = Db.one("SELECT COALESCE(SUM(t.done),0), COUNT(t.id) FROM phases p"
                    + " LEFT JOIN tasks t ON t.phase_id=p.id WHERE p.ticket_id=?", i);
            long n = ((Number) c[0]).longValue(), t = ((Number) c[1]).longValue();
            Button b = btn(name + "\n" + n + "/" + t + " tareas", () -> select(i));
            b.getStyleClass().remove("btn-ghost");
            b.getStyleClass().add("ticket-item");
            if (Objects.equals(tid, i)) b.getStyleClass().add("selected");
            b.setMaxWidth(Double.MAX_VALUE);
            b.setAlignment(Pos.CENTER_LEFT);
            b.setWrapText(true);                          // nombre largo: pasa al renglón de abajo
            b.setMinWidth(0);                             // (btn() lo fija a su ancho; aquí debe poder ajustarse)
            b.setMinHeight(Region.USE_PREF_SIZE);
            listBox.getChildren().add(b);
        }
    }

    private void select(int i) {
        tid = i;
        if (closingOpen) {   // al elegir un ticket siempre se vuelve a su inicio
            closingOpen = false;
            mainBox.getChildren().setAll(homeHead, summaryBox, bodyScroll);
        }
        drawSide();
        drawMain();
    }

    // ---------------------------------------------------------------- main
    private static double btnWidth(String text) {
        Text t = new Text(text);
        t.setFont(Font.font("Segoe UI", 13.5));
        return t.getLayoutBounds().getWidth() + 34;   // texto + padding del botón + margen
    }

    /** Si no caben completos, "Importar texto" y "+ Fase" pasan a ícono (nunca a "…"). */
    private void updateHeadMode() {
        if (importBtn == null || phaseBtn == null) return;
        double w = headc.getWidth();
        if (w <= 0) return;
        double needed = 230                                   // espacio mínimo para el título del ticket
                + btnWidth("⇩  Importar texto") + btnWidth("+ Fase")
                + 2 * btnWidth("✎") + 5 * headc.getSpacing();
        boolean compact = w < needed;
        importBtn.setText(compact ? "⇩" : "⇩  Importar texto");
        phaseBtn.setText(compact ? "+" : "+ Fase");
        for (Button b : new Button[]{importBtn, phaseBtn}) {
            b.getStyleClass().remove("btn-compact");
            if (compact) b.getStyleClass().add("btn-compact");
        }
    }

    /** Tarjeta que muestra el nombre completo por encima de los botones, mientras el mouse esté sobre él. */
    private void showNamePop(Label src) {
        hideNamePop();
        Bounds b = sceneBounds(src);
        Label pop = new Label(src.getText());
        pop.getStyleClass().add("name-pop");
        pop.setWrapText(true);
        pop.setMouseTransparent(true);   // el mouse "atraviesa" la tarjeta: al salir del nombre desaparece
        double x = b.getMinX() - 14, y = b.getMinY() - 6;
        pop.setMaxWidth(Math.max(240, overlay.getWidth() - x - 24));
        pop.setLayoutX(x);
        pop.setLayoutY(y);
        pop.setOpacity(0);
        overlay.getChildren().add(pop);
        namePop = pop;
        Timeline in = new Timeline(new KeyFrame(Duration.millis(160),
                new KeyValue(pop.opacityProperty(), 1, Interpolator.EASE_OUT)));
        in.play();
    }

    private void hideNamePop() {
        if (namePop != null) overlay.getChildren().remove(namePop);
        namePop = null;
    }

    private void drawMain() {
        hideNamePop();
        importBtn = null;
        phaseBtn = null;
        headc.getChildren().clear();
        body.getChildren().clear();
        rows.clear();
        phaseCards.clear();
        if (tid == null) {
            Label l = new Label("Crea o selecciona un ticket\npara empezar");
            l.getStyleClass().add("empty-hint");
            body.getChildren().add(l);
            drawSummary();
            return;
        }
        String name = (String) Db.one("SELECT name FROM tickets WHERE id=?", tid)[0];
        Object[] c = Db.one("SELECT COALESCE(SUM(t.done),0), COUNT(t.id) FROM phases p"
                + " LEFT JOIN tasks t ON t.phase_id=p.id WHERE p.ticket_id=?", tid);
        VBox tt = new VBox(2);
        Label tl = new Label(name);
        tl.getStyleClass().add("head-title");
        tl.setMaxWidth(Region.USE_PREF_SIZE);   // la zona de hover es solo el nombre, no toda la columna
        tl.setOnMouseEntered(e -> showNamePop(tl));
        tl.setOnMouseExited(e -> hideNamePop());
        Label ts = new Label(((Number) c[0]).longValue() + " de "
                + ((Number) c[1]).longValue() + " tareas completadas");
        ts.getStyleClass().add("head-sub");
        tt.getChildren().addAll(tl, ts);
        tt.setMinWidth(60);
        final int ticketId = tid;
        attachCopy(tt, "Copiar ticket completo", () -> ticketText(ticketId));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        importBtn = btn("⇩  Importar texto", this::importDialog, "primary");
        phaseBtn = btn("+ Fase", this::newPhase, "soft");
        Tooltip.install(importBtn, new Tooltip("Importar texto"));
        Tooltip.install(phaseBtn, new Tooltip("Nueva fase"));
        headc.getChildren().addAll(tt, spacer, importBtn, phaseBtn,
                btn("✎", this::renameTicket),
                btn("✕", this::delTicket));
        updateHeadMode();

        // Cargar las fases
        List<Object[]> phases = Db.rows(
                "SELECT id,title,duration,priority,result,start_date,end_date,buffer_days,goal"
                        + " FROM phases WHERE ticket_id=? ORDER BY pos,id", tid);
        Map<Integer, PhaseDates> dm = ticketDates(tid);   // fechas efectivas (margen usado + corrimiento)

        // Las fases nuevas inician cerradas; las que ya conoces conservan su estado al redibujar
        for (Object[] p : phases) {
            int pid = ((Number) p[0]).intValue();
            if (seen.add(pid)) collapsed.add(pid);
        }

        for (Object[] p : phases) {
            phaseCard(((Number) p[0]).intValue(), (String) p[1], (String) p[2], (String) p[3], (String) p[4],
                    dm.get(((Number) p[0]).intValue()), p[8] == null ? "" : (String) p[8]);
        }
        drawSummary();
    }


    // ---------------------------------------------------------------- cierre (criterios, checklist, notas)
    private void toggleSide() {
        side.setVisible(!side.isVisible());
        side.setManaged(side.isVisible());
        stage.setMinWidth(side.isVisible() ? MIN_W_SIDE : MIN_W_FREE);
    }

    private String closingText(int ticketId) {
        Object[] r = Db.one("SELECT closing FROM tickets WHERE id=?", ticketId);
        return r == null || r[0] == null ? "" : (String) r[0];
    }

    /** {hechas, total} de las casillas "- [ ]" / "- [x]" del texto. */
    private static int[] closingStats(String raw) {
        int done = 0, total = 0;
        for (String l : raw.split("\n")) {
            Matcher m = CHK.matcher(l.strip());
            if (m.matches()) {
                total++;
                if (!m.group(1).equals(" ")) done++;
            }
        }
        return new int[]{done, total};
    }

    /** Tarjeta del resumen (mismo tamaño que las demás) que abre la pantalla de cierre. */
    private VBox closingTile() {
        String raw = closingText(tid);
        int[] st = closingStats(raw);
        String val, sub, col = null;
        if (raw.isBlank()) {
            val = "Abrir";
            sub = "agregar criterios y checklist";
        } else if (st[1] > 0) {
            val = st[0] + " de " + st[1];
            sub = "criterios cumplidos";
            if (st[0] == st[1]) col = "#10B981";
        } else {
            val = "Ver";
            sub = "notas de cierre";
        }
        VBox t = tile("Cierre  ›", val, sub, col);
        t.getStyleClass().add("stat-link");
        Tooltip.install(t, new Tooltip("Criterios de aceptación, checklist y cierre del ticket"));
        t.setOnMouseClicked(e -> showClosing(false));
        return t;
    }

    private void showHome() {
        if (!closingOpen) return;
        closingOpen = false;
        mainBox.getChildren().setAll(homeHead, summaryBox, bodyScroll);
        redraw();   // refresca el resumen (la tarjeta de Cierre) y conserva el scroll
    }

    private Label lbl(String s, String styleClass) {
        Label l = new Label(s);
        l.getStyleClass().add(styleClass);
        l.setWrapText(true);
        l.setMaxWidth(Double.MAX_VALUE);
        l.setMinHeight(Region.USE_PREF_SIZE);
        return l;
    }

    /** Pantalla de cierre del ticket, dentro de la misma ventana. edit=true muestra el editor de texto. */
    private void showClosing(boolean edit) {
        if (tid == null) return;
        hideNamePop();
        closingOpen = true;
        final int ticketId = tid;
        final String name = (String) Db.one("SELECT name FROM tickets WHERE id=?", ticketId)[0];
        final String raw = closingText(ticketId);
        final boolean empty = raw.isBlank();
        final boolean editing = edit || empty;

        Button menu = btn("☰", this::toggleSide, "soft");
        Button back = btn("←  Volver al inicio", this::showHome, "soft");
        Label title = new Label("Cierre · " + name);
        title.getStyleClass().add("head-title");
        Label sub = new Label();
        sub.getStyleClass().add("head-sub");
        VBox tt = new VBox(2, title, sub);
        tt.setMinWidth(60);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox head = new HBox(14, menu, back, tt, spacer);
        head.setAlignment(Pos.CENTER_LEFT);
        head.setPadding(new Insets(0, 0, 14, 0));

        VBox view = new VBox(0);
        VBox.setVgrow(view, Priority.ALWAYS);

        if (editing) {
            sub.setText("Editando");
            TextArea ta = new TextArea(raw);
            ta.getStyleClass().add("area");
            ta.setWrapText(true);
            VBox.setVgrow(ta, Priority.ALWAYS);
            Label hint = new Label("Pega o escribe aquí. Título: línea que empieza con emoji o #  ·  Casilla: - [ ] texto"
                    + "  ·  Paso: 1. texto  ·  Nota destacada: «Resultado esperado: …»");
            hint.getStyleClass().add("field-label");
            hint.setWrapText(true);
            hint.setPadding(new Insets(0, 0, 8, 0));
            Button save = btn("Guardar", () -> {
                Db.q("UPDATE tickets SET closing=? WHERE id=?", ta.getText().strip(), ticketId);
                if (ta.getText().isBlank()) showHome();
                else showClosing(false);
            }, "primary");
            Button cancel = btn("Cancelar", () -> { if (empty) showHome(); else showClosing(false); }, "soft");
            head.getChildren().addAll(cancel, save);
            if (empty) {
                Button ex = btn("Cargar ejemplo", () -> ta.setText(ClosingExample.TEXT));
                head.getChildren().add(head.getChildren().size() - 2, ex);
            }
            VBox pad = new VBox(0, hint, ta);
            VBox.setVgrow(pad, Priority.ALWAYS);
            pad.setPadding(new Insets(0, 14, 20, 2));
            view.getChildren().addAll(head, pad);
        } else {
            // barra de progreso con el mismo estilo (.track / .fill) que la del resumen
            DoubleProperty frac = new SimpleDoubleProperty(0);
            StackPane track = new StackPane();
            track.getStyleClass().add("track");
            Region fill = new Region();
            fill.getStyleClass().add("fill");
            fill.prefWidthProperty().bind(track.widthProperty().multiply(frac));
            StackPane.setAlignment(fill, Pos.CENTER_LEFT);
            track.getChildren().add(fill);
            VBox.setMargin(track, new Insets(0, 14, 14, 2));
            Runnable refresh = () -> {
                int[] st = closingStats(closingText(ticketId));
                boolean any = st[1] > 0;
                track.setVisible(any);
                track.setManaged(any);
                sub.setText(any ? st[0] + " de " + st[1] + " criterios cumplidos" : "Notas de cierre");
                new Timeline(new KeyFrame(Duration.millis(350),
                        new KeyValue(frac, any ? (double) st[0] / st[1] : 0, Interpolator.EASE_BOTH))).play();
            };
            refresh.run();

            Button copyBtn = btn("Copiar", () -> {}, "soft");
            copyBtn.setOnAction(e -> {
                copy(raw);
                copyBtn.setText("✓ Copiado");
                PauseTransition pt = new PauseTransition(Duration.seconds(1.4));
                pt.setOnFinished(ev -> copyBtn.setText("Copiar"));
                pt.play();
            });
            head.getChildren().addAll(copyBtn, btn("✎  Editar", () -> showClosing(true), "primary"));

            VBox content = new VBox(10);
            content.setPadding(new Insets(2, 14, 20, 2));
            renderClosing(content, raw, ticketId, refresh);
            ScrollPane sp = new ScrollPane(content);
            sp.setFitToWidth(true);
            sp.getStyleClass().add("clean-scroll");
            smoothScroll(sp, content);
            VBox.setVgrow(sp, Priority.ALWAYS);
            view.getChildren().addAll(head, track, sp);
        }
        mainBox.getChildren().setAll(view);
    }

    private static boolean isHeading(String s) {
        if (s.startsWith("#")) return true;
        int cp = s.codePointAt(0);
        return cp >= 0x2300 && !Character.isLetterOrDigit(cp);   // emoji / símbolo al inicio (flechas y viñetas no cuentan)
    }

    /** Una tarjeta del cierre: título, contador de casillas (n/m) y su contenido. */
    private static final class Sec {
        final VBox box = new VBox(8);
        final Label cnt = new Label();
        final List<CheckBox> checks = new ArrayList<>();

        Sec() {
            box.getStyleClass().add("card");
            box.setMaxWidth(Double.MAX_VALUE);
            cnt.getStyleClass().add("cnt");
        }

        void update() {
            long n = checks.stream().filter(CheckBox::isSelected).count();
            boolean any = !checks.isEmpty();
            cnt.setVisible(any);
            cnt.setManaged(any);
            cnt.setText(n + "/" + checks.size());
            cnt.getStyleClass().remove("cnt-completed");
            if (any && n == checks.size()) cnt.getStyleClass().add("cnt-completed");
        }
    }

    private Sec newSec(VBox content, List<Sec> all) {
        Sec s = new Sec();
        content.getChildren().add(s.box);
        all.add(s);
        return s;
    }

    /** Convierte el texto en tarjetas por sección: títulos, casillas, pasos, viñetas y notas. */
    private void renderClosing(VBox content, String raw, int ticketId, Runnable onChange) {
        String[] lines = raw.split("\n", -1);
        List<Sec> all = new ArrayList<>();
        Sec sec = null;
        boolean afterNum = false;   // el párrafo que sigue a un paso es su detalle
        for (int i = 0; i < lines.length; i++) {
            String s = lines[i].strip();
            if (s.isEmpty()) continue;
            if (isHeading(s)) {
                sec = newSec(content, all);
                Label h = lbl(s.replaceFirst("^#{1,3}\\s*", ""), "phase-title");
                HBox.setHgrow(h, Priority.ALWAYS);
                HBox row = new HBox(10, h, sec.cnt);
                row.setAlignment(Pos.CENTER_LEFT);
                sec.box.getChildren().add(row);
                afterNum = false;
                continue;
            }
            if (sec == null) sec = newSec(content, all);
            final Sec cur = sec;
            Matcher m;
            if ((m = CHK.matcher(s)).matches()) {
                final int idx = i;
                CheckBox cb = new CheckBox(m.group(2));
                cb.getStyleClass().addAll("task-check", "closing-check");
                cb.setWrapText(true);
                cb.setMaxWidth(Double.MAX_VALUE);
                cb.setMinHeight(Region.USE_PREF_SIZE);
                cb.setSelected(!m.group(1).equals(" "));
                cb.setOnAction(e -> {
                    toggleClosingLine(ticketId, idx, cb.isSelected());
                    cur.update();
                    onChange.run();
                });
                cur.checks.add(cb);
                cur.box.getChildren().add(cb);
                afterNum = false;
            } else if ((m = NUM.matcher(s)).matches()) {
                String t = m.group(2), headTxt = t, rest = "";
                int dot = t.indexOf(". ");
                if (dot > 0 && dot < 110) {
                    headTxt = t.substring(0, dot + 1);
                    rest = t.substring(dot + 2).trim();
                }
                Label num = new Label(m.group(1));
                num.getStyleClass().add("closing-num");
                VBox txt = new VBox(2, lbl(headTxt, "closing-step"));
                if (!rest.isEmpty()) txt.getChildren().add(lbl(rest, "closing-detail"));
                HBox.setHgrow(txt, Priority.ALWAYS);
                HBox row = new HBox(8, num, txt);
                row.setAlignment(Pos.TOP_LEFT);
                cur.box.getChildren().add(row);
                afterNum = true;
            } else if ((m = BULLET.matcher(s)).matches()) {
                Label dot = new Label("•");
                dot.getStyleClass().add("closing-dot");
                Label t = lbl(m.group(1), "closing-text");
                HBox.setHgrow(t, Priority.ALWAYS);
                HBox row = new HBox(8, dot, t);
                row.setAlignment(Pos.TOP_LEFT);
                cur.box.getChildren().add(row);
                afterNum = false;
            } else if ((m = NOTE.matcher(s)).matches()) {
                VBox box = new VBox(3, lbl(m.group(1), "closing-note-title"));
                if (!m.group(2).isEmpty()) box.getChildren().add(lbl(m.group(2), "closing-text"));
                box.getStyleClass().add("closing-note");
                cur.box.getChildren().add(box);
                afterNum = false;
            } else {
                Label p = lbl(s, afterNum ? "closing-detail" : "closing-text");
                if (afterNum) VBox.setMargin(p, new Insets(0, 0, 0, 32));
                cur.box.getChildren().add(p);
            }
        }
        for (Sec x : all) x.update();
    }

    /** Marca/desmarca la casilla de esa línea del texto guardado. */
    private void toggleClosingLine(int ticketId, int idx, boolean on) {
        String[] ls = closingText(ticketId).split("\n", -1);
        if (idx >= ls.length) return;
        ls[idx] = ls[idx].replaceFirst("\\[( |x|X)\\]", on ? "[x]" : "[ ]");
        Db.q("UPDATE tickets SET closing=? WHERE id=?", String.join("\n", ls), ticketId);
    }

    // ---------------------------------------------------------------- fechas efectivas
    /**
     * Fechas de cada fase del ticket, en el orden de la lista. El margen que se USA en una fase
     * corre, en cascada, las fechas de todas las que vienen después; el margen sin usar no mueve nada.
     */
    private Map<Integer, PhaseDates> ticketDates(int ticketId) {
        Map<Integer, PhaseDates> m = new LinkedHashMap<>();
        int shift = 0;
        for (Object[] p : Db.rows("SELECT id,start_date,end_date,buffer_days,buffer_used"
                + " FROM phases WHERE ticket_id=? ORDER BY pos,id", ticketId)) {
            PhaseDates pd = PhaseDates.of((String) p[1], (String) p[2], p[3], p[4], shift);
            m.put(((Number) p[0]).intValue(), pd);
            shift += pd.used;
        }
        return m;
    }

    // ---------------------------------------------------------------- resumen del ticket
    private VBox tile(String label, String value, String sub, String color) {
        Label l = new Label(label);
        l.getStyleClass().add("stat-label");
        Label v = new Label(value);
        v.getStyleClass().add("stat-value");
        if (color != null) v.setStyle("-fx-text-fill:" + color);
        VBox t = new VBox(1, l, v);
        t.getStyleClass().add("stat");
        if (sub != null && !sub.isEmpty()) {
            Label sl = new Label(sub);
            sl.getStyleClass().add("stat-sub");
            sl.setWrapText(true);
            t.getChildren().add(sl);
        }
        return t;
    }

    private void drawSummary() {
        summaryBox.getChildren().clear();
        List<Object[]> ph = tid == null ? List.of() : Db.rows(
                "SELECT p.id,p.title,COALESCE(SUM(t.done),0),COUNT(t.id) FROM phases p"
                        + " LEFT JOIN tasks t ON t.phase_id=p.id WHERE p.ticket_id=?"
                        + " GROUP BY p.id ORDER BY p.pos,p.id", tid);
        boolean show = tid != null;   // con ticket siempre hay resumen: así el botón de Cierre está a la mano
        summaryBox.setVisible(show);
        summaryBox.setManaged(show);
        if (!show) {
            summaryTid = null;
            return;
        }
        if (ph.isEmpty()) {           // ticket sin fases: solo el botón de Cierre
            summaryTid = null;
            summaryBox.getChildren().add(closingTile());
            return;
        }
        final boolean newTicket = !Objects.equals(summaryTid, tid);   // solo un ticket distinto repite la entrada
        summaryTid = tid;

        Map<Integer, PhaseDates> dm = ticketDates(tid);
        LocalDate today = LocalDate.now();
        long done = 0, total = 0;
        int phasesDone = 0, bufTotal = 0, bufUsed = 0;
        String current = null;
        LocalDate planMin = null, planMax = null, effMax = null, limMax = null;
        for (Object[] r : ph) {
            long n = ((Number) r[2]).longValue(), t = ((Number) r[3]).longValue();
            done += n;
            total += t;
            boolean complete = t > 0 && n == t;
            if (complete) phasesDone++;
            PhaseDates d = dm.get(((Number) r[0]).intValue());
            bufTotal += d.buffer;
            bufUsed += d.used;
            if (d.plannedStart != null && (planMin == null || d.plannedStart.isBefore(planMin)))
                planMin = d.plannedStart;
            if (d.plannedEnd != null && (planMax == null || d.plannedEnd.isAfter(planMax))) planMax = d.plannedEnd;
            if (d.end != null && (effMax == null || d.end.isAfter(effMax))) effMax = d.end;
            if (d.limit() != null && (limMax == null || d.limit().isAfter(limMax))) limMax = d.limit();
            if (current == null && !complete && d.start != null && d.end != null
                    && !today.isBefore(d.start) && !today.isAfter(d.end))
                current = (String) r[1];
        }

        // 1. progreso
        double pct = total == 0 ? 0 : (double) done / total;
        VBox prog = tile("Progreso", Math.round(pct * 100) + "%", done + " de " + total + " tareas", ACC);
        StackPane track = new StackPane();
        track.getStyleClass().add("track");
        Region fill = new Region();
        fill.getStyleClass().add("fill");
        // la barra y el % viajan suavemente hasta el nuevo valor (desde 0 si es otro ticket)
        if (progAnim != null) progAnim.stop();
        if (newTicket) progFrac.set(0);
        fill.prefWidthProperty().bind(track.widthProperty().multiply(progFrac));
        Label pv = (Label) prog.getChildren().get(1);
        pv.textProperty().bind(Bindings.createStringBinding(
                () -> Math.round(progFrac.get() * 100) + "%", progFrac));
        if (Math.abs(progFrac.get() - pct) > 1e-9) {
            progAnim = new Timeline(new KeyFrame(Duration.millis(newTicket ? 750 : 450),
                    new KeyValue(progFrac, pct, Interpolator.EASE_BOTH)));
            progAnim.setDelay(newTicket ? Duration.millis(140) : Duration.ZERO);
            progAnim.play();
        }
        StackPane.setAlignment(fill, Pos.CENTER_LEFT);
        track.getChildren().add(fill);
        VBox.setMargin(track, new Insets(4, 0, 0, 0));
        prog.getChildren().add(track);
        summaryBox.getChildren().add(prog);

        // 2. fases
        summaryBox.getChildren().add(tile("Fases", phasesDone + " de " + ph.size(),
                current != null ? "▶ Ahora: " + (current.length() > 24 ? current.substring(0, 24) + "…" : current)
                        : "completadas", null));

        // 3. días planeados (plan original, de la primera fecha de inicio a la última fecha objetivo)
        if (planMin != null && planMax != null && !planMax.isBefore(planMin)) {
            long span = ChronoUnit.DAYS.between(planMin, planMax) + 1;
            summaryBox.getChildren().add(tile("Días planeados", String.valueOf(span),
                    PhaseDates.shortDate(planMin) + " → " + PhaseDates.shortDate(planMax), null));
        }

        // 4. margen
        if (bufTotal > 0) {
            int left = bufTotal - bufUsed;
            summaryBox.getChildren().add(tile("Margen", PhaseDates.daysText(left),
                    bufUsed + " usado" + (bufUsed == 1 ? "" : "s") + " de " + bufTotal
                            + (left == 0 ? " · sin colchón" : " · disponible"),
                    bufUsed > 0 ? "#F59E0B" : null));
        }

        // 5. fecha final (efectiva) + 6. cuánto falta
        if (effMax != null) {
            String sub;
            if (bufUsed > 0) sub = "↷ corrida +" + PhaseDates.daysText(bufUsed) + " por margen usado";
            else if (limMax != null && limMax.isAfter(effMax)) sub = "con margen: " + PhaseDates.shortDate(limMax);
            else sub = "";
            summaryBox.getChildren().add(tile("Fecha final", PhaseDates.shortDate(effMax), sub,
                    bufUsed > 0 ? "#F59E0B" : null));

            String val, tsub, col = null;
            long left = ChronoUnit.DAYS.between(today, effMax);
            if (total > 0 && done == total) {
                val = "✓ Listo";
                tsub = "todas las tareas hechas";
                col = "#10B981";
            } else if (left > 0) {
                val = PhaseDates.daysText(left);
                tsub = limMax != null && limMax.isAfter(effMax)
                        ? "hasta el límite: " + PhaseDates.daysText(ChronoUnit.DAYS.between(today, limMax))
                        : "para la fecha final";
            } else if (left == 0) {
                val = "Hoy";
                tsub = "es la fecha final";
                col = "#F59E0B";
            } else {
                val = "Vencido";
                tsub = "hace " + PhaseDates.daysText(-left);
                col = "#F43F5E";
            }
            summaryBox.getChildren().add(tile("Faltan", val, tsub, col));
        }
        summaryBox.getChildren().add(closingTile());   // 7. botón a la pantalla de cierre
        animateSummary(newTicket);
    }

    /**
     * Movimiento sutil del resumen: al cambiar de ticket las tarjetas entran en cascada (aparecen y suben un
     * poco); mientras trabajas en el mismo ticket, solo se "enciende" suavemente la que cambió de valor.
     */
    private void animateSummary(boolean newTicket) {
        Map<String, String> now = new HashMap<>();
        int k = 0;
        for (Node n : summaryBox.getChildren()) {
            VBox t = (VBox) n;
            String label = ((Label) t.getChildren().get(0)).getText();
            StringBuilder sig = new StringBuilder(((Label) t.getChildren().get(1)).getText());
            if (t.getChildren().size() > 2 && t.getChildren().get(2) instanceof Label)
                sig.append('|').append(((Label) t.getChildren().get(2)).getText());
            now.put(label, sig.toString());

            if (newTicket) {
                t.setOpacity(0);
                t.setTranslateY(10);
                Timeline in = new Timeline(
                        new KeyFrame(Duration.ZERO,
                                new KeyValue(t.opacityProperty(), 0), new KeyValue(t.translateYProperty(), 10)),
                        new KeyFrame(Duration.millis(420),
                                new KeyValue(t.opacityProperty(), 1, Interpolator.EASE_OUT),
                                new KeyValue(t.translateYProperty(), 0, Interpolator.EASE_OUT)));
                in.setDelay(Duration.millis(70L * k));
                in.play();
            } else if (!label.equals("Progreso") && !sig.toString().equals(tileSigs.get(label))) {
                t.setOpacity(0.35);
                t.setTranslateY(4);
                Timeline pulse = new Timeline(
                        new KeyFrame(Duration.millis(380),
                                new KeyValue(t.opacityProperty(), 1, Interpolator.EASE_OUT),
                                new KeyValue(t.translateYProperty(), 0, Interpolator.EASE_OUT)));
                pulse.play();
            }
            k++;
        }
        tileSigs.clear();
        tileSigs.putAll(now);
    }

    /** Chip de margen con − / + para marcar cuántos días del margen ya se usaron. */
    private Node marginChip(int pid, PhaseDates d) {
        Label l = new Label(d.bufferText());
        Button minus = btn("−", () -> setBufferUsed(pid, d.used - 1));
        Button plus = btn("+", () -> setBufferUsed(pid, d.used + 1));
        for (Button b : List.of(minus, plus)) b.getStyleClass().add("mini");
        minus.setDisable(d.used <= 0);
        plus.setDisable(d.used >= d.buffer);
        Tooltip.install(minus, new Tooltip("Devolver un día de margen (las fechas siguientes regresan)"));
        Tooltip.install(plus, new Tooltip("Usé un día de margen: corre un día las fases siguientes"));
        HBox chip = new HBox(6, l, minus, plus);
        chip.setAlignment(Pos.CENTER_LEFT);
        chip.getStyleClass().addAll("chip", "chip-buffer");
        if (d.used > 0) chip.getStyleClass().add("chip-buffer-used");
        Tooltip.install(l, new Tooltip("Plan original: " + d.base().range()
                + (d.used > 0 ? "\nTermina el " + d.endText() + " por el margen usado" : "")));
        return chip;
    }

    private void setBufferUsed(int pid, int n) {
        Db.q("UPDATE phases SET buffer_used=? WHERE id=?", Math.max(0, Math.min(PhaseDates.MAX_BUFFER, n)), pid);
        redraw();   // recalcula las fechas de todas las fases y el resumen
    }

    private void redraw() {
        double y = bodyScroll.getVvalue();
        drawSide();
        drawMain();
        Platform.runLater(() -> bodyScroll.setVvalue(y));
    }

    private void phaseCard(int pid, String title, String dur, String pri, String res, PhaseDates dates,
                           String goal) {
        VBox card = new VBox(6);
        card.getStyleClass().add("card");
        card.setUserData(pid);
        card.setMaxWidth(Double.MAX_VALUE);

        HBox top = new HBox(8);
        top.setAlignment(Pos.CENTER_LEFT);
        top.getStyleClass().add("phase-head");
        top.setOnContextMenuRequested(e -> {
            int i = phaseCards.indexOf(card);
            MenuItem cp = new MenuItem("Copiar fase completa");
            cp.setOnAction(a -> copy(phaseText(pid)));
            MenuItem up = new MenuItem("Mover arriba");
            up.setDisable(i <= 0);
            up.setOnAction(a -> nudgePhase(pid, -1));
            MenuItem dn = new MenuItem("Mover abajo");
            dn.setDisable(i < 0 || i >= phaseCards.size() - 1);
            dn.setOnAction(a -> nudgePhase(pid, 1));
            ContextMenu m = new ContextMenu(cp, new SeparatorMenuItem(), up, dn);
            m.setAutoHide(true);
            m.show(top, e.getScreenX(), e.getScreenY());
            e.consume();
        });
        top.setCursor(javafx.scene.Cursor.HAND);

        // asa para reordenar: discreta en reposo, se ilumina al acercarse
        Label phaseGrip = new Label("⋮⋮");
        phaseGrip.getStyleClass().add("phase-grip");
        phaseGrip.setMinWidth(Region.USE_PREF_SIZE);
        Tooltip.install(phaseGrip, new Tooltip("Arrastra para reordenar la fase"));
        phaseGrip.setOnMousePressed(ev -> phaseDragStart(ev, card, title));
        phaseGrip.setOnMouseDragged(this::phaseDragMove);
        phaseGrip.setOnMouseReleased(this::phaseDragEnd);
        phaseGrip.setOnMouseClicked(Event::consume);   // soltar el asa no debe colapsar la fase

        Label arrow = new Label();
        arrow.getStyleClass().add("arrow");
        arrow.setMinWidth(Region.USE_PREF_SIZE);
        Label t = new Label(title);
        t.getStyleClass().add("phase-title");
        t.setWrapText(true);   // título largo: se parte en líneas, los íconos se quedan
        t.setMaxWidth(Double.MAX_VALUE);
        VBox titleBox = new VBox(1, t);
        if (!goal.isEmpty()) {
            Label gl = new Label("🎯 " + goal);
            gl.getStyleClass().add("goal");
            gl.setWrapText(true);
            gl.setMaxWidth(Double.MAX_VALUE);
            titleBox.getChildren().add(gl);
        }
        HBox.setHgrow(titleBox, Priority.ALWAYS);
        titleBox.setMaxWidth(Double.MAX_VALUE);
        titleBox.setMinWidth(0);
        Label cnt = new Label();
        cnt.getStyleClass().add("cnt");
        cnt.setMinWidth(Region.USE_PREF_SIZE);
        Button infoBtn = btn("📋", () -> showPhaseInfo(pid));
        Tooltip.install(infoBtn, new Tooltip("Ver y copiar la fase completa"));
        top.getChildren().addAll(phaseGrip, arrow, titleBox, cnt,
                infoBtn,
                btn("✎", () -> editPhase(pid)),
                btn("✕", () -> delPhase(pid)));

        FlowPane chips = new FlowPane(6, 6);
        if (pri != null && !pri.isEmpty()) {
            Label p = new Label("● " + pri);
            p.setStyle("-fx-text-fill:" + PRI.getOrDefault(pri, MUTE));
            p.getStyleClass().add("chip");
            chips.getChildren().add(p);
        }
        if (dur != null && !dur.isEmpty()) {
            Label d = new Label("◷ " + dur);
            d.getStyleClass().addAll("chip", "chip-mute");
            chips.getChildren().add(d);
        }
        if (!dates.isEmpty()) {
            Label dl = new Label("📅 " + dates.range());
            dl.getStyleClass().addAll("chip", "chip-date");
            if (dates.end != null)
                Tooltip.install(dl, new Tooltip("Fecha objetivo: " + dates.endText()
                        + (dates.hasShift() || dates.used > 0
                        ? "\nPlan original: " + dates.base().range() : "")));
            chips.getChildren().add(dl);
            if (dates.hasShift()) {
                Label sl = new Label(dates.shiftText());
                sl.getStyleClass().addAll("chip", "chip-buffer", "chip-buffer-used");
                Tooltip.install(sl, new Tooltip("Las fases anteriores usaron margen, así que esta fase se corrió "
                        + PhaseDates.daysText(dates.shift)));
                chips.getChildren().add(sl);
            }
            if (dates.hasBuffer()) chips.getChildren().add(marginChip(pid, dates));
        }

        StackPane track = new StackPane();
        track.getStyleClass().add("track");
        Region fill = new Region();
        fill.getStyleClass().add("fill");
        track.getChildren().add(fill);

        Runnable refresh = () -> {
            Object[] c2 = Db.one("SELECT COALESCE(SUM(done),0),COUNT(*) FROM tasks WHERE phase_id=?", pid);
            long n = ((Number) c2[0]).longValue(), tt = ((Number) c2[1]).longValue();
            fill.prefWidthProperty().unbind();
            fill.prefWidthProperty().bind(track.widthProperty().multiply(tt == 0 ? 0 : (double) n / tt));

            // --- TEXTO DE COMPLETADO / CONTEO ---
            if (tt > 0 && n == tt) {
                cnt.setText("✓ Completado");
                cnt.getStyleClass().add("cnt-completed"); // Opción para estilo especial CSS
            } else {
                cnt.setText(n + "/" + tt);
                cnt.getStyleClass().remove("cnt-completed");
            }
            drawSide();
        };

        VBox tasksBox = new VBox(2);
        for (Object[] r : Db.rows(
                "SELECT id,text,done FROM tasks WHERE phase_id=? ORDER BY pos,id", pid)) {
            int taskId = ((Number) r[0]).intValue();
            String text = (String) r[1];
            boolean done = ((Number) r[2]).intValue() == 1;

            TaskRow row = new TaskRow(taskId, pid);
            row.setAlignment(Pos.CENTER_LEFT);
            rows.add(row);
            attachCopy(row, "Copiar tarea", () -> text);

            Label grip = new Label("⋮⋮");
            grip.getStyleClass().add("grip");
            grip.setMinWidth(Region.USE_PREF_SIZE);
            grip.setOnMousePressed(ev -> dragStart(ev, row));
            grip.setOnMouseDragged(this::dragMove);
            grip.setOnMouseReleased(this::dragEnd);

            CheckBox cb = new CheckBox(text);
            cb.setSelected(done);
            cb.getStyleClass().add("task-check");
            cb.setWrapText(true);   // tarea larga: baja de línea, no empuja los íconos
            if (done) cb.setStyle("-fx-text-fill:" + MUTE);
            cb.setOnAction(ev -> {
                Db.q("UPDATE tasks SET done=? WHERE id=?", cb.isSelected() ? 1 : 0, taskId);
                cb.setStyle("-fx-text-fill:" + (cb.isSelected() ? MUTE : TXT));
                refresh.run();
                drawSummary();
            });

            Region sp = new Region();
            HBox.setHgrow(sp, Priority.ALWAYS);
            row.getChildren().addAll(grip, cb, sp,
                    btn("✎", () -> editTask(taskId)),
                    btn("✕", () -> delTask(taskId)));
            tasksBox.getChildren().add(row);
        }

        HBox foot = new HBox();
        foot.setPadding(new Insets(6, 0, 0, 0));
        foot.getChildren().add(btn("+ tarea", () -> newTask(pid)));

        VBox content = new VBox(6);
        content.getChildren().addAll(tasksBox, foot);
        card.getChildren().addAll(top, chips, track, content);
        if (res != null && !res.isEmpty()) {
            Label rl = new Label("→ " + res);
            rl.getStyleClass().add("result");
            rl.setWrapText(true);
            rl.setMaxWidth(Double.MAX_VALUE);
            attachCopy(rl, "Copiar resultado", () -> res);
            content.getChildren().add(rl);
        }

        Runnable apply = () -> {
            boolean c = collapsed.contains(pid);
            content.setVisible(!c);
            content.setManaged(!c);
            arrow.setText(c ? "▸" : "▾");
        };
        top.setOnMouseClicked(ev -> {
            if (onButton(ev.getTarget())) return;
            if (!collapsed.remove(pid)) collapsed.add(pid);
            apply.run();
        });
        apply.run();
        body.getChildren().add(card);
        phaseCards.add(card);
        refresh.run();
    }

    // ---------------------------------------------------------------- reordenar fases
    private static Bounds sceneBounds(Node n) {
        return n.localToScene(n.getBoundsInLocal());
    }

    private void phaseDragStart(MouseEvent e, VBox card, String title) {
        dragCard = card;
        lastSceneY = e.getSceneY();
        phaseGhost = new Label("⋮⋮  " + (title.length() > 50 ? title.substring(0, 50) + "…" : title));
        phaseGhost.getStyleClass().add("ghost");
        phaseGhost.setMouseTransparent(true);
        dropLine = new Region();
        dropLine.getStyleClass().add("drop-line");
        dropLine.setPrefHeight(3);
        dropLine.setMouseTransparent(true);
        dropLine.setVisible(false);
        overlay.getChildren().addAll(dropLine, phaseGhost);
        phaseGhost.setLayoutX(e.getSceneX() + 14);
        phaseGhost.setLayoutY(e.getSceneY() + 10);
        card.setOpacity(0.4);   // la tarjeta original queda "fantasma" mientras la mueves
        autoScroll = new Timeline(new KeyFrame(Duration.millis(16), ev -> edgeScroll()));
        autoScroll.setCycleCount(Animation.INDEFINITE);
        autoScroll.play();
    }

    private void phaseDragMove(MouseEvent e) {
        if (dragCard == null) return;
        lastSceneY = e.getSceneY();
        phaseGhost.setLayoutX(e.getSceneX() + 14);
        phaseGhost.setLayoutY(e.getSceneY() + 10);
        updateDropLine();
    }

    /** Calcula en qué hueco caería la fase y dibuja la línea guía (sin mover nada del layout). */
    private void updateDropLine() {
        if (dragCard == null || phaseCards.isEmpty()) return;
        int n = phaseCards.size();
        dropIdx = n;
        for (int i = 0; i < n; i++) {
            Bounds b = sceneBounds(phaseCards.get(i));
            if (lastSceneY < b.getMinY() + b.getHeight() / 2) {
                dropIdx = i;
                break;
            }
        }
        int from = phaseCards.indexOf(dragCard);
        boolean noop = dropIdx == from || dropIdx == from + 1;   // quedaría donde ya está
        Bounds ref = sceneBounds(phaseCards.get(Math.min(dropIdx, n - 1)));
        double y = dropIdx < n ? ref.getMinY() - 5 : ref.getMaxY() + 5;   // centro del hueco entre tarjetas
        dropLine.setPrefWidth(ref.getWidth());
        dropLine.setLayoutX(ref.getMinX());
        dropLine.setLayoutY(y - 1.5);
        dropLine.setVisible(!noop);
    }

    /** Si arrastras cerca del borde de la lista, se desplaza sola. */
    private void edgeScroll() {
        Bounds vb = sceneBounds(bodyScroll);
        double zone = 70, k = 0;
        if (lastSceneY < vb.getMinY() + zone) k = -(zone - Math.max(0, lastSceneY - vb.getMinY())) / zone;
        else if (lastSceneY > vb.getMaxY() - zone) k = (zone - Math.max(0, vb.getMaxY() - lastSceneY)) / zone;
        double extra = body.getBoundsInLocal().getHeight() - bodyScroll.getViewportBounds().getHeight();
        if (k == 0 || extra <= 0) return;
        k = Math.max(-1, Math.min(1, k));
        bodyScroll.setVvalue(Math.max(0, Math.min(1, bodyScroll.getVvalue() + k * 14 / extra)));
        updateDropLine();
    }

    private void phaseDragEnd(MouseEvent e) {
        if (dragCard == null) return;
        lastSceneY = e.getSceneY();
        updateDropLine();
        autoScroll.stop();
        overlay.getChildren().removeAll(dropLine, phaseGhost);
        dragCard.setOpacity(1);
        int from = phaseCards.indexOf(dragCard), to = dropIdx;
        dragCard = null;
        dropIdx = -1;
        if (from < 0 || to < 0 || to == from || to == from + 1) return;
        List<Integer> ids = phaseCards.stream().map(c -> (Integer) c.getUserData()).collect(Collectors.toList());
        Integer moved = ids.remove(from);
        ids.add(to > from ? to - 1 : to, moved);
        Db.setPhaseOrder(ids);
        redraw();
        flashPhase(moved);
    }

    /** Alternativa sin mouse: menú contextual > Mover arriba / abajo. */
    private void nudgePhase(int pid, int delta) {
        List<Integer> ids = phaseCards.stream().map(c -> (Integer) c.getUserData()).collect(Collectors.toList());
        int i = ids.indexOf(pid), j = i + delta;
        if (i < 0 || j < 0 || j >= ids.size()) return;
        Collections.swap(ids, i, j);
        Db.setPhaseOrder(ids);
        redraw();
        flashPhase(pid);
    }

    /** Destello breve en la fase recién movida, para confirmar dónde quedó. */
    private void flashPhase(int pid) {
        VBox c = phaseCards.stream().filter(x -> Objects.equals(x.getUserData(), pid)).findFirst().orElse(null);
        if (c == null) return;
        c.getStyleClass().add("phase-drop");
        PauseTransition p = new PauseTransition(Duration.millis(1300));
        p.setOnFinished(ev -> c.getStyleClass().remove("phase-drop"));
        p.play();
    }

    // ---------------------------------------------------------------- drag & drop (tareas)
    private TaskRow rowAt(double sceneY) {
        for (TaskRow r : rows) {
            if (collapsed.contains(r.pid)) continue;
            Bounds b = r.localToScene(r.getBoundsInLocal());
            if (b != null && sceneY >= b.getMinY() && sceneY < b.getMaxY()) return r;
        }
        return null;
    }

    private void dragStart(MouseEvent e, TaskRow row) {
        dragRow = row;
        String txt = (String) Db.one("SELECT text FROM tasks WHERE id=?", row.tid)[0];
        ghost = new Label("⋮⋮  " + txt);
        ghost.getStyleClass().add("ghost");
        ghost.setMouseTransparent(true);
        overlay.getChildren().add(ghost);
        ghost.setLayoutX(e.getSceneX() + 14);
        ghost.setLayoutY(e.getSceneY() + 10);
        row.setState("grab");
    }

    private void dragMove(MouseEvent e) {
        if (dragRow == null || ghost == null) return;
        ghost.setLayoutX(e.getSceneX() + 14);
        ghost.setLayoutY(e.getSceneY() + 10);
        TaskRow t = rowAt(e.getSceneY());
        if (t != hlRow) {
            if (hlRow != null) hlRow.setState(null);
            if (t != null && t.tid != dragRow.tid) t.setState("target");
            hlRow = t;
        }
    }

    private void dragEnd(MouseEvent e) {
        if (ghost != null) {
            overlay.getChildren().remove(ghost);
            ghost = null;
        }
        TaskRow target = rowAt(e.getSceneY());
        if (dragRow != null) dragRow.setState(null);
        if (hlRow != null) hlRow.setState(null);
        hlRow = null;
        if (dragRow == null || target == null || target.tid == dragRow.tid) {
            dragRow = null;
            return;
        }
        int moved = dragRow.tid;
        Bounds b = target.localToScene(target.getBoundsInLocal());
        boolean after = e.getSceneY() > b.getMinY() + b.getHeight() / 2;
        Db.moveTask(moved, target.pid, target.tid, after);
        dragRow = null;
        redraw();
        flash(moved);
    }

    private void flash(int tid) {
        TaskRow r = rows.stream().filter(x -> x.tid == tid).findFirst().orElse(null);
        if (r == null) return;

        r.setState("drop");

        PauseTransition p1 = new PauseTransition(Duration.millis(900));
        PauseTransition p2 = new PauseTransition(Duration.millis(900));

        p1.setOnFinished(ev -> {
            r.setState("soft");
            p2.play(); // Inicia la segunda pausa al terminar la primera
        });

        p2.setOnFinished(ev -> r.setState(null));

        p1.play();
    }

    // ---------------------------------------------------------------- formularios
    private String css() {
        return Objects.requireNonNull(getClass().getResource("/style.css")).toExternalForm();
    }

    private static StringConverter<LocalDate> dateConverter() {
        return new StringConverter<LocalDate>() {
            @Override
            public String toString(LocalDate d) {
                return d == null ? "" : DMY_OUT.format(d);
            }

            @Override
            public LocalDate fromString(String s) {
                if (s == null || s.isBlank()) return null;
                try {
                    return LocalDate.parse(s.trim(), DMY_IN);
                } catch (Exception e) {
                    return null;
                }
            }
        };
    }

    /**
     * Diálogo genérico. Tipos de campo: entry, text, big, pri, y (nuevos) date y buffer.
     * Los campos date/buffer consecutivos se agrupan en una sola fila para que el diálogo no crezca.
     * Devuelve los valores como texto; las fechas vienen en ISO (aaaa-MM-dd) o "" si están vacías.
     */
    @SuppressWarnings("unchecked")
    private String[] form(String title, String[][] fields, String[] init) {
        Stage d = new Stage();
        d.initModality(Modality.APPLICATION_MODAL);
        d.initOwner(stage);
        d.setTitle(title);
        VBox box = new VBox(6);
        box.getStyleClass().add("dialog");
        box.setPadding(new Insets(18, 24, 18, 24));
        List<Control> ws = new ArrayList<>();
        Label err = new Label();
        err.getStyleClass().add("form-error");
        err.setWrapText(true);
        err.visibleProperty().bind(err.textProperty().isNotEmpty());
        err.managedProperty().bind(err.visibleProperty());
        HBox dateRow = null;
        int blocks = 0;   // bloques verticales, para calcular el alto
        for (int i = 0; i < fields.length; i++) {
            Label l = new Label(fields[i][0]);
            l.getStyleClass().add("field-label");
            String kind = fields[i][1];
            String v = init != null && init[i] != null ? init[i] : "";
            Control w;
            if (kind.equals("pri")) {
                ChoiceBox<String> ch = new ChoiceBox<>();
                ch.getItems().addAll("Alta", "Media", "Baja");
                ch.setValue(v.isEmpty() ? "Media" : v);
                w = ch;
            } else if (kind.equals("buffer")) {
                ChoiceBox<Integer> ch = new ChoiceBox<Integer>();
                for (int n = 0; n <= PhaseDates.MAX_BUFFER; n++) ch.getItems().add(n);
                ch.setConverter(new StringConverter<Integer>() {
                    @Override
                    public String toString(Integer n) {
                        return n == null || n == 0 ? "Sin margen" : "+" + PhaseDates.daysText(n);
                    }

                    @Override
                    public Integer fromString(String s) {
                        return null;
                    }
                });
                ch.setValue(PhaseDates.clampBuffer(v));
                w = ch;
            } else if (kind.equals("date")) {
                DatePicker dp = new DatePicker(PhaseDates.parse(v));
                dp.setConverter(dateConverter());
                dp.setPromptText("dd/mm/aaaa");
                w = dp;
            } else if (kind.equals("text") || kind.equals("big")) {
                TextArea ta = new TextArea(v);
                ta.getStyleClass().add("area");
                ta.setPrefRowCount(kind.equals("big") ? 8 : 4);
                w = ta;
            } else {
                TextField tf = new TextField(v);
                tf.getStyleClass().add("field");
                if (i == 0) {
                    tf.setOnAction(ev -> save(d, ws, err));
                    Platform.runLater(tf::requestFocus);
                }
                w = tf;
            }
            ws.add(w);
            if (kind.equals("date") || kind.equals("buffer")) {
                if (dateRow == null) {
                    dateRow = new HBox(10);
                    box.getChildren().add(dateRow);
                    blocks++;
                }
                w.setMaxWidth(Double.MAX_VALUE);
                VBox cell = new VBox(6, l, w);
                cell.setPrefWidth(kind.equals("date") ? 150 : 120);
                HBox.setHgrow(cell, Priority.ALWAYS);
                dateRow.getChildren().add(cell);
            } else {
                box.getChildren().addAll(l, w);
                blocks++;
            }
        }
        box.getChildren().add(err);
        Button saveBtn = btn("Guardar", () -> save(d, ws, err), "primary");
        saveBtn.setMaxWidth(Double.MAX_VALUE);
        saveBtn.setPadding(new Insets(10));
        box.getChildren().add(saveBtn);
        Scene sc = new Scene(box, 500, 170 + blocks * 78);
        sc.getStylesheets().add(css());
        d.setScene(sc);
        d.showAndWait();
        Object data = d.getUserData();
        return data == null ? null : ((List<String>) data).toArray(new String[0]);
    }

    private void save(Stage d, List<Control> ws, Label err) {
        List<String> vals = new ArrayList<>();
        List<DatePicker> pickers = new ArrayList<>();
        List<LocalDate> dates = new ArrayList<>();
        boolean bad = false;
        err.setText("");
        for (Control w : ws) {
            w.getStyleClass().remove("invalid");
            if (w instanceof TextArea) vals.add(((TextArea) w).getText().trim());
            else if (w instanceof TextField) vals.add(((TextField) w).getText().trim());
            else if (w instanceof DatePicker) {
                DatePicker dp = (DatePicker) w;
                String txt = dp.getEditor().getText();
                LocalDate v = dp.getConverter().fromString(txt);   // lee lo escrito aunque no se haya confirmado
                if (txt != null && !txt.isBlank() && v == null) {
                    dp.getStyleClass().add("invalid");
                    err.setText("Fecha no válida. Usa el formato dd/mm/aaaa.");
                    bad = true;
                }
                dates.add(v);
                pickers.add(dp);
                vals.add(PhaseDates.iso(v));
            } else vals.add(((ChoiceBox<?>) w).getValue().toString());
        }
        // con dos fechas (inicio, objetivo) el objetivo no puede ser anterior al inicio
        if (!bad && pickers.size() == 2) {
            LocalDate a = dates.get(0), b = dates.get(1);
            if (a != null && b != null && b.isBefore(a)) {
                pickers.get(1).getStyleClass().add("invalid");
                err.setText("La fecha objetivo no puede ser anterior a la fecha de inicio.");
                bad = true;
            }
        }
        if (bad) return;
        if (!vals.get(0).isEmpty()) {
            d.setUserData(vals);
            d.close();
        }
    }

    // ---------------------------------------------------------------- acciones
    private void newTicket() {
        String[] f = form("Nuevo ticket", new String[][]{{"Nombre del ticket", "entry"}}, null);
        if (f != null) select(Db.insert("INSERT INTO tickets(name) VALUES(?)", f[0]));
    }

    private void renameTicket() {
        String old = (String) Db.one("SELECT name FROM tickets WHERE id=?", tid)[0];
        String[] f = form("Renombrar", new String[][]{{"Nombre", "entry"}}, new String[]{old});
        if (f != null) {
            Db.q("UPDATE tickets SET name=? WHERE id=?", f[0], tid);
            select(tid);
        }
    }

    private void delTicket() {
        String name = (String) Db.one("SELECT name FROM tickets WHERE id=?", tid)[0];
        if (ask("¿Eliminar el ticket «" + name + "» con todas sus fases y tareas?")) {
            Db.q("DELETE FROM tickets WHERE id=?", tid);
            tid = null;
            drawSide();
            drawMain();
        }
    }

    /** Duración final: la que escribió la persona; si la dejó vacía y hay rango de fechas, se calcula. */
    private static String duration(String[] f) {
        return f[1].isEmpty() ? PhaseDates.of(f[2], f[3], 0).autoDuration() : f[1];
    }

    /** Sin fecha objetivo no hay margen. */
    private static int buffer(String[] f) {
        return f[3].isEmpty() ? 0 : PhaseDates.clampBuffer(f[4]);
    }

    private void newPhase() {
        String[] f = form("Nueva fase", PH, null);
        if (f != null) {
            Db.addPhase(tid, f[0], duration(f), f[5], f[7], f[2], f[3], buffer(f), f[6]);
            drawMain();
        }
    }

    private void editPhase(int pid) {
        Object[] cur = Db.one("SELECT title,duration,priority,result,start_date,end_date,buffer_days,goal"
                + " FROM phases WHERE id=?", pid);
        PhaseDates old = PhaseDates.of((String) cur[4], (String) cur[5], cur[6]);
        String oldDur = cur[1] == null ? "" : (String) cur[1];
        String[] init = {(String) cur[0], oldDur, PhaseDates.iso(old.start), PhaseDates.iso(old.end),
                String.valueOf(old.buffer), (String) cur[2], cur[7] == null ? "" : (String) cur[7],
                (String) cur[3]};
        String[] f = form("Editar fase", PH, init);
        if (f != null) {
            // si la duración era la calculada por las fechas y no se tocó, se recalcula con las nuevas
            if (!oldDur.isEmpty() && oldDur.equals(old.autoDuration()) && f[1].equals(oldDur)) f[1] = "";
            Db.q("UPDATE phases SET title=?,duration=?,priority=?,result=?,start_date=?,end_date=?,buffer_days=?,"
                            + "goal=?,buffer_used=MIN(COALESCE(buffer_used,0),?) WHERE id=?",
                    f[0], duration(f), f[5], f[7], f[2], f[3], buffer(f), f[6], buffer(f), pid);
            drawMain();
        }
    }

    private void delPhase(int pid) {
        String t = (String) Db.one("SELECT title FROM phases WHERE id=?", pid)[0];
        if (ask("¿Eliminar la fase «" + (t.length() > 60 ? t.substring(0, 60) : t) + "» y todas sus tareas?")) {
            Db.q("DELETE FROM phases WHERE id=?", pid);
            redraw();
        }
    }

    private void newTask(int pid) {
        String[] f = form("Nuevas tareas",
                new String[][]{{"Una tarea por renglón (puedes pegar una lista)", "big"}}, null);
        if (f != null) {
            for (String t : Importer.linesToTasks(f[0])) Db.addTask(pid, t);
            redraw();
        }
    }

    private void editTask(int taskId) {
        String old = (String) Db.one("SELECT text FROM tasks WHERE id=?", taskId)[0];
        String[] f = form("Editar tarea", new String[][]{{"Tarea", "entry"}}, new String[]{old});
        if (f != null) {
            Db.q("UPDATE tasks SET text=? WHERE id=?", f[0], taskId);
            redraw();
        }
    }

    private void delTask(int taskId) {
        String t = (String) Db.one("SELECT text FROM tasks WHERE id=?", taskId)[0];
        if (ask("¿Eliminar la tarea «" + (t.length() > 70 ? t.substring(0, 70) : t) + "»?")) {
            Db.q("DELETE FROM tasks WHERE id=?", taskId);
            redraw();
        }
    }

    // ---------------------------------------------------------------- importar
    private void importDialog() {
        Stage d = new Stage();
        d.initModality(Modality.APPLICATION_MODAL);
        d.initOwner(stage);
        d.setTitle("Importar texto");
        VBox box = new VBox(10);
        box.getStyleClass().add("dialog");
        box.setPadding(new Insets(20, 24, 20, 24));
        Label title = new Label("Pega tu plan completo");
        title.getStyleClass().add("head-title");
        Label hint = new Label("Detecto fases, duración · prioridad, resultado y tareas "
                + "(numeradas, con viñeta o una por renglón).");
        hint.getStyleClass().add("head-sub");
        hint.setWrapText(true);
        TextArea area = new TextArea();
        area.getStyleClass().add("area");
        area.setPrefRowCount(13);
        VBox.setVgrow(area, Priority.ALWAYS);
        Label prev = new Label("Esperando texto…");
        prev.getStyleClass().add("head-sub");
        prev.setWrapText(true);

        area.textProperty().addListener((obs, o, n) -> {
            List<Importer.Ph> ph = Importer.parse(area.getText());
            if (ph.isEmpty()) {
                prev.setText("Esperando texto…");
                prev.getStyleClass().remove("preview-ok");
                prev.getStyleClass().add("head-sub");
                return;
            }
            int nt = ph.stream().mapToInt(p -> p.tasks.size()).sum();
            StringBuilder sb = new StringBuilder(String.format("✓ %d fase(s) · %d tarea(s) detectadas", ph.size(), nt));
            ph.stream().limit(4).forEach(p -> sb.append(String.format("\n   • %s — %d tareas · %s · %s",
                    p.title.length() > 46 ? p.title.substring(0, 46) : p.title,
                    p.tasks.size(), p.duration.isEmpty() ? "—" : p.duration, p.priority)));
            prev.setText(sb.toString());
            prev.getStyleClass().remove("head-sub");
            prev.getStyleClass().add("preview-ok");
        });

        Button go = btn("Importar  (Ctrl+Enter)", () -> {
            List<Importer.Ph> ph = Importer.parse(area.getText());
            if (ph.isEmpty()) return;
            for (Importer.Ph p : ph) {
                int pid = Db.addPhase(tid, p.title, p.duration, p.priority, p.result, "", "", 0, p.goal);
                for (String x : p.tasks) Db.addTask(pid, x);
            }
            d.close();
            drawSide();
            drawMain();
        }, "primary");
        go.setMaxWidth(Double.MAX_VALUE);
        go.setPadding(new Insets(11));
        box.getChildren().addAll(title, hint, area, prev, go);
        Scene sc = new Scene(box, 680, 600);
        sc.getStylesheets().add(css());
        sc.setOnKeyPressed(e -> {
            if (e.isControlDown() && e.getCode() == KeyCode.ENTER) go.fire();
        });
        d.setScene(sc);
        d.showAndWait();
    }

    // ---------------------------------------------------------------- row
    class TaskRow extends HBox {
        final int tid, pid;

        TaskRow(int tid, int pid) {
            super(6);
            this.tid = tid;
            this.pid = pid;
            getStyleClass().add("task-row");
        }

        void setState(String kind) {
            getStyleClass().removeAll("grab", "target", "drop", "soft");
            if (kind != null) getStyleClass().add(kind);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
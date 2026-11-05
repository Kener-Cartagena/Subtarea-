package com.subtareas;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/** Misma base de datos que la version Python: ~/SubTareas/data.db */
public class Db {
    private static final Connection c;

    static {
        try {
            String dir = System.getProperty("user.home") + File.separator + "SubTareas";
            new File(dir).mkdirs();
            c = DriverManager.getConnection("jdbc:sqlite:" + dir + File.separator + "data.db");
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA foreign_keys = ON");
                s.executeUpdate("CREATE TABLE IF NOT EXISTS tickets(id INTEGER PRIMARY KEY, name TEXT)");
                s.executeUpdate("CREATE TABLE IF NOT EXISTS phases(id INTEGER PRIMARY KEY,"
                        + " ticket_id INTEGER REFERENCES tickets(id) ON DELETE CASCADE,"
                        + " title TEXT, duration TEXT, priority TEXT, result TEXT,"
                        + " start_date TEXT DEFAULT '', end_date TEXT DEFAULT '',"
                        + " buffer_days INTEGER DEFAULT 0)");
                s.executeUpdate("CREATE TABLE IF NOT EXISTS tasks(id INTEGER PRIMARY KEY,"
                        + " phase_id INTEGER REFERENCES phases(id) ON DELETE CASCADE,"
                        + " text TEXT, done INTEGER DEFAULT 0, pos INTEGER DEFAULT 0)");
            }
            boolean hasPos = false;
            try (ResultSet rs = c.getMetaData().getColumns(null, null, "tasks", "pos")) {
                hasPos = rs.next();
            }
            if (!hasPos) {
                try (Statement s = c.createStatement()) {
                    s.executeUpdate("ALTER TABLE tasks ADD COLUMN pos INTEGER DEFAULT 0");
                    s.executeUpdate("UPDATE tasks SET pos=id");
                }
            }
            // fechas de fase (inicio, objetivo, margen): agrega las columnas a bases anteriores
            String[][] dateCols = {{"start_date", "TEXT DEFAULT ''"},
                    {"end_date", "TEXT DEFAULT ''"},
                    {"buffer_days", "INTEGER DEFAULT 0"},
                    {"buffer_used", "INTEGER DEFAULT 0"}, // días de margen ya usados (0..buffer_days)
                    {"goal", "TEXT DEFAULT ''"},          // objetivo de la fase
                    {"pos", "INTEGER DEFAULT 0"}};        // orden manual de las fases
            for (String[] col : dateCols) {
                boolean has;
                try (ResultSet rs = c.getMetaData().getColumns(null, null, "phases", col[0])) {
                    has = rs.next();
                }
                if (!has) {
                    try (Statement s = c.createStatement()) {
                        s.executeUpdate("ALTER TABLE phases ADD COLUMN " + col[0] + " " + col[1]);
                        // las fases que ya existían conservan el orden en que se crearon
                        if (col[0].equals("pos")) s.executeUpdate("UPDATE phases SET pos=id");
                    }
                }
            }
            // pantalla de cierre del ticket (criterios, checklist, notas): texto libre por ticket
            boolean hasClosing;
            try (ResultSet rs = c.getMetaData().getColumns(null, null, "tickets", "closing")) {
                hasClosing = rs.next();
            }
            if (!hasClosing) {
                try (Statement s = c.createStatement()) {
                    s.executeUpdate("ALTER TABLE tickets ADD COLUMN closing TEXT DEFAULT ''");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static void q(String sql, Object... a) {
        try (PreparedStatement p = c.prepareStatement(sql)) {
            for (int i = 0; i < a.length; i++) p.setObject(i + 1, a[i]);
            p.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static int insert(String sql, Object... a) {
        try (PreparedStatement p = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (int i = 0; i < a.length; i++) p.setObject(i + 1, a[i]);
            p.executeUpdate();
            try (ResultSet rs = p.getGeneratedKeys()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static List<Object[]> rows(String sql, Object... a) {
        List<Object[]> out = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement(sql)) {
            for (int i = 0; i < a.length; i++) p.setObject(i + 1, a[i]);
            try (ResultSet rs = p.executeQuery()) {
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Object[] r = new Object[n];
                    for (int i = 0; i < n; i++) r[i] = rs.getObject(i + 1);
                    out.add(r);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    public static Object[] one(String sql, Object... a) {
        List<Object[]> r = rows(sql, a);
        return r.isEmpty() ? null : r.get(0);
    }

    public static int addTask(int pid, String text) {
        Object[] m = one("SELECT COALESCE(MAX(pos),0)+1 FROM tasks WHERE phase_id=?", pid);
        int pos = ((Number) m[0]).intValue();
        return insert("INSERT INTO tasks(phase_id,text,pos) VALUES(?,?,?)", pid, text, pos);
    }

    /** Crea una fase al final de la lista del ticket. */
    public static int addPhase(int ticketId, String title, String duration, String priority, String result,
                               String start, String end, int buffer, String goal) {
        Object[] m = one("SELECT COALESCE(MAX(pos),0)+1 FROM phases WHERE ticket_id=?", ticketId);
        int pos = ((Number) m[0]).intValue();
        return insert("INSERT INTO phases(ticket_id,title,duration,priority,result,start_date,end_date,"
                + "buffer_days,goal,pos) VALUES(?,?,?,?,?,?,?,?,?,?)",
                ticketId, title, duration, priority, result, start, end, buffer, goal, pos);
    }

    /** Guarda el orden manual de las fases: ids en el orden en que deben aparecer. */
    public static void setPhaseOrder(List<Integer> ids) {
        for (int i = 0; i < ids.size(); i++) q("UPDATE phases SET pos=? WHERE id=?", i, ids.get(i));
    }

    /** Mueve tid a la fase pid, justo antes/despues de refTid. */
    public static void moveTask(int tid, int pid, int refTid, boolean after) {
        List<Integer> ids = new ArrayList<>();
        for (Object[] r : rows("SELECT id FROM tasks WHERE phase_id=? AND id!=? ORDER BY pos,id", pid, tid))
            ids.add(((Number) r[0]).intValue());
        ids.add(ids.indexOf(refTid) + (after ? 1 : 0), tid);
        q("UPDATE tasks SET phase_id=? WHERE id=?", pid, tid);
        for (int i = 0; i < ids.size(); i++) q("UPDATE tasks SET pos=? WHERE id=?", i, ids.get(i));
    }
}
package com.subtareas;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class Importer {
    private static final Pattern BUL = Pattern.compile("^(?:\\d+[.)]|[-*•–])\\s+");

    public static class Ph {
        public String title;
        public String duration = "";
        public String result = "";
        public String goal = "";
        public String priority = "Media";
        public List<String> tasks = new ArrayList<>();
    }

    /** Una tarea por renglon; quita numeracion o vinetas si las trae. */
    public static List<String> linesToTasks(String text) {
        List<String> out = new ArrayList<>();
        for (String l : text.split("\n")) {
            String s = l.trim();
            if (!s.isEmpty()) out.add(BUL.matcher(s).replaceFirst("").trim());
        }
        return out;
    }

    /** Detecta fases, 'duracion · prioridad', Resultado y tareas. */
    public static List<Ph> parse(String text) {
        List<Ph> phases = new ArrayList<>();
        Ph cur = null;
        for (String line : text.split("\n")) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            if (s.matches("(?i)fase\\b.*")) {
                cur = new Ph();
                cur.title = s;
                phases.add(cur);
            } else if (cur != null && s.contains("·") && !BUL.matcher(s).lookingAt()
                    && cur.duration.isEmpty() && cur.tasks.isEmpty()) {
                String[] parts = s.split("·", 2);
                cur.duration = parts[0].trim();
                String p = cap(parts[1].trim());
                cur.priority = p.equals("Alta") || p.equals("Media") || p.equals("Baja") ? p : "Media";
            } else if (cur != null && s.matches("(?i)objetivo\\s*:.*")) {
                cur.goal = s.substring(s.indexOf(':') + 1).trim();
            } else if (cur != null && s.toLowerCase().startsWith("resultado")) {
                cur.result = s.contains(":") ? s.substring(s.indexOf(':') + 1).trim() : s;
            } else {
                if (cur == null) {
                    cur = new Ph();
                    cur.title = "General";
                    phases.add(cur);
                }
                cur.tasks.add(BUL.matcher(s).replaceFirst("").trim());
            }
        }
        return phases;
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }
}
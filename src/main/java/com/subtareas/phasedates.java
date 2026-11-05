package com.subtareas;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Fechas de una fase: inicio, fecha objetivo y margen (buffer) opcional de hasta 2 días.
 * <p>
 * En la base de datos (columnas start_date, end_date, buffer_days y buffer_used de {@code phases},
 * creadas en {@link Db}) se guardan como texto ISO (aaaa-MM-dd), con "" cuando no hay fecha.
 * <p>
 * Lo guardado es SIEMPRE el plan original. Las fechas "efectivas" se calculan al vuelo:
 * <ul>
 *   <li>{@code used}: días de margen que ya se gastaron en ESTA fase (0..buffer). Empujan su fecha
 *       objetivo: objetivo 10 + 1 usado = termina el 11.</li>
 *   <li>{@code shift}: días que le llegan corridos por los márgenes usados en fases ANTERIORES
 *       (en el orden de la lista). Mueven inicio y fin.</li>
 *   <li>Margen sin usar = las fechas no se mueven. Quitar el uso = todo vuelve a su lugar.</li>
 * </ul>
 */
final class PhaseDates {

    static final int MAX_BUFFER = 2;
    private static final String[] MONTHS = {"enero", "febrero", "marzo", "abril", "mayo", "junio",
            "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"};

    final LocalDate start, end;                  // efectivas (con corrimiento y margen usado)
    final LocalDate plannedStart, plannedEnd;    // las del plan original
    final int buffer, used, shift;

    PhaseDates(LocalDate start, LocalDate end, int buffer) {
        this(start, end, buffer, 0, 0);
    }

    PhaseDates(LocalDate start, LocalDate end, int buffer, int used, int shift) {
        this.plannedStart = start;
        this.plannedEnd = end;
        this.buffer = end == null ? 0 : clampBuffer(buffer);   // sin objetivo no hay margen
        this.used = Math.max(0, Math.min(this.buffer, used));
        this.shift = Math.max(0, shift);
        this.start = start == null ? null : start.plusDays(this.shift);
        this.end = end == null ? null : end.plusDays(this.shift + this.used);
    }

    // ------------------------------------------------------------ conversión
    /** Plan original, sin corrimientos. Tolerante a null y a basura. */
    static PhaseDates of(String start, String end, Object buffer) {
        return of(start, end, buffer, 0, 0);
    }

    /** Con margen usado y corrimiento heredado de fases anteriores. */
    static PhaseDates of(String start, String end, Object buffer, Object used, int shift) {
        return new PhaseDates(parse(start), parse(end), clampBuffer(buffer), clampBuffer(used), shift);
    }

    static LocalDate parse(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return LocalDate.parse(iso.trim());
        } catch (Exception e) {
            return null;
        }
    }

    static String iso(LocalDate d) {
        return d == null ? "" : d.toString();
    }

    static int clampBuffer(Object o) {
        int n = 0;
        if (o instanceof Number) n = ((Number) o).intValue();
        else if (o != null) {
            try {
                n = Integer.parseInt(o.toString().trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return Math.max(0, Math.min(MAX_BUFFER, n));
    }

    // ------------------------------------------------------------ consulta
    boolean isEmpty() {
        return start == null && end == null;
    }

    boolean hasBuffer() {
        return buffer > 0;
    }

    boolean hasShift() {
        return shift > 0;
    }

    /** Días de margen que todavía no se han usado. */
    int remaining() {
        return buffer - used;
    }

    /** Plan original (sin corrimiento ni margen usado). */
    PhaseDates base() {
        return new PhaseDates(plannedStart, plannedEnd, buffer);
    }

    /** Último día posible = objetivo efectivo + margen que queda (= objetivo original + corrimiento + margen). */
    LocalDate limit() {
        return end == null ? null : end.plusDays(remaining());
    }

    /** Días corridos del rango efectivo, contando inicio y fin (5–10 = 6). 0 si falta alguna fecha. */
    long days() {
        if (start == null || end == null || end.isBefore(start)) return 0;
        return ChronoUnit.DAYS.between(start, end) + 1;
    }

    static String daysText(long n) {
        return n + (n == 1 ? " día" : " días");
    }

    /** Duración calculada a partir de las fechas, o "" si no hay rango completo. */
    String autoDuration() {
        long n = days();
        return n > 0 ? daysText(n) : "";
    }

    // ------------------------------------------------------------ texto
    private static boolean needYear(LocalDate d) {
        return d.getYear() != LocalDate.now().getYear();
    }

    private static String full(LocalDate d, boolean year) {
        return d.getDayOfMonth() + " de " + MONTHS[d.getMonthValue() - 1]
                + (year ? " de " + d.getYear() : "");
    }

    /** "12 de octubre" (con año solo si no es el actual). */
    static String dateText(LocalDate d) {
        return d == null ? "" : full(d, needYear(d));
    }

    /** "12 oct" (con año solo si no es el actual). */
    static String shortDate(LocalDate d) {
        if (d == null) return "";
        return d.getDayOfMonth() + " " + MONTHS[d.getMonthValue() - 1].substring(0, 3)
                + (needYear(d) ? " " + d.getYear() : "");
    }

    /** "5–10 de octubre", "28 de septiembre – 3 de octubre", "Desde el 5 de octubre"… */
    String range() {
        if (isEmpty()) return "";
        if (end == null) return "Desde el " + full(start, needYear(start));
        if (start == null) return "Hasta el " + full(end, needYear(end));
        boolean sameYear = start.getYear() == end.getYear();
        boolean yr = !sameYear || needYear(end);
        if (start.equals(end)) return full(end, yr);
        if (sameYear && start.getMonth() == end.getMonth())
            return start.getDayOfMonth() + "–" + full(end, yr);
        return full(start, !sameYear) + " – " + full(end, yr);
    }

    String endText() {
        return end == null ? "" : full(end, needYear(end));
    }

    String limitText() {
        return end == null ? "" : full(limit(), needYear(limit()));
    }

    /** Texto del chip de margen: "Margen 0/2 usado · hasta el 12 de octubre" / "Margen 1/2 usado → 11 de octubre". */
    String bufferText() {
        if (!hasBuffer()) return "";
        if (used == 0) return "Margen 0/" + buffer + " usado · hasta el " + limitText();
        return "Margen " + used + "/" + buffer + " usado → termina el " + endText();
    }

    /** "↷ corrida +2 días" cuando fases anteriores gastaron margen. */
    String shiftText() {
        return hasShift() ? "↷ corrida +" + daysText(shift) : "";
    }

    /** Una línea para copiar: "5–10 de octubre (usó 1 de 2 días de margen) [corrida +1 día]". */
    String summary() {
        if (isEmpty()) return "";
        String s = "📅 " + range();
        if (hasBuffer()) {
            if (used > 0)
                s += " (usó " + used + " de " + daysText(buffer) + " de margen"
                        + (remaining() > 0 ? ", límite " + limitText() : "") + ")";
            else
                s += " (+" + daysText(buffer) + " de margen, hasta el " + limitText() + ")";
        }
        if (hasShift()) s += " [corrida +" + daysText(shift) + "]";
        return s;
    }
}
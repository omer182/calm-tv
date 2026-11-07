package io.github.omer182.calmtv;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What's on the household's shared calendar later today and tomorrow, from a published iCalendar
 * link (iPhone: Calendar > Calendars > (i) > Public Calendar, then Share Link). Handles what
 * iCloud writes: TZID / UTC / all-day dates, and RRULE with FREQ, INTERVAL, UNTIL, COUNT,
 * weekly BYDAY and EXDATE. Moved single occurrences (RECURRENCE-ID) replace their originals.
 */
final class Agenda {
    static final class Event implements Comparable<Event> {
        final LocalDate day;
        final ZonedDateTime start; // null for all-day
        final String title;

        Event(LocalDate day, ZonedDateTime start, String title) {
            this.day = day;
            this.start = start;
            this.title = title;
        }

        boolean allDay() {
            return start == null;
        }

        @Override
        public int compareTo(Event o) {
            int c = day.compareTo(o.day);
            if (c != 0) return c;
            if (allDay() != o.allDay()) return allDay() ? -1 : 1;
            return allDay() ? title.compareTo(o.title) : start.compareTo(o.start);
        }
    }

    // A weekly event since 2015 is ~600 steps; daily since 2015 ~4000.
    private static final int MAX_STEPS = 6000;
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter BASIC_DATETIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

    final List<Event> events = new ArrayList<>();
    long fetchedAt;

    /** Events starting on [from, until), sorted. Blocking network call; null on any failure. */
    static Agenda fetch(String link, LocalDate from, LocalDate until) {
        if (link == null || link.isEmpty()) return null;
        HttpURLConnection conn = null;
        try {
            String url = link.replaceFirst("^webcals?://", "https://");
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            List<String> lines = new ArrayList<>();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                for (String line; (line = in.readLine()) != null; ) {
                    // Folded lines continue with a space or tab.
                    if (!lines.isEmpty() && !line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t')) {
                        int last = lines.size() - 1;
                        lines.set(last, lines.get(last) + line.substring(1));
                    } else {
                        lines.add(line);
                    }
                }
            }
            Agenda a = parse(lines, from, until);
            a.fetchedAt = System.currentTimeMillis();
            return a;
        } catch (Exception e) {
            android.util.Log.w("CalmAgenda", "calendar fetch failed: " + e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    static Agenda parse(List<String> lines, LocalDate from, LocalDate until) {
        ZoneId local = ZoneId.systemDefault();
        List<String[]> block = null;
        List<List<String[]>> vevents = new ArrayList<>();
        int depth = 0;
        for (String line : lines) {
            if (line.equals("BEGIN:VEVENT")) {
                block = new ArrayList<>();
                depth = 0;
            } else if (line.equals("END:VEVENT")) {
                if (block != null) vevents.add(block);
                block = null;
            } else if (block != null) {
                // Skip VALARM etc. inside the event.
                if (line.startsWith("BEGIN:")) depth++;
                else if (line.startsWith("END:")) depth--;
                else if (depth == 0) {
                    int colon = line.indexOf(':');
                    if (colon > 0) block.add(new String[]{line.substring(0, colon), line.substring(colon + 1)});
                }
            }
        }

        // Moved or cancelled single occurrences: "uid|original start" -> skip the original.
        Set<String> overridden = new HashSet<>();
        for (List<String[]> ev : vevents) {
            String uid = value(ev, "UID");
            String[] rid = prop(ev, "RECURRENCE-ID");
            if (uid != null && rid != null) overridden.add(uid + "|" + key(rid, local));
        }

        Agenda a = new Agenda();
        for (List<String[]> ev : vevents) {
            if ("CANCELLED".equalsIgnoreCase(value(ev, "STATUS"))) continue;
            String[] dtstart = prop(ev, "DTSTART");
            if (dtstart == null) continue;
            String title = unescape(value(ev, "SUMMARY"));
            if (title == null || title.trim().isEmpty()) title = "Busy";
            String uid = value(ev, "UID");
            boolean override = prop(ev, "RECURRENCE-ID") != null;
            boolean allDay = isDate(dtstart);

            Set<String> exdates = new HashSet<>();
            for (String[] p : ev) {
                if (!name(p[0]).equals("EXDATE")) continue;
                for (String v : p[1].split(",")) exdates.add(key(new String[]{p[0], v}, local));
            }

            try {
                if (allDay) {
                    LocalDate start = LocalDate.parse(dtstart[1].trim(), BASIC_DATE);
                    String[] dtend = prop(ev, "DTEND");
                    long days = 1;
                    if (dtend != null && isDate(dtend)) {
                        days = Math.max(1, java.time.temporal.ChronoUnit.DAYS.between(start,
                                LocalDate.parse(dtend[1].trim(), BASIC_DATE)));
                    }
                    for (ZonedDateTime occ : occurrences(start.atStartOfDay(local), value(ev, "RRULE"), until.atStartOfDay(local))) {
                        String k = occ.toLocalDate().format(BASIC_DATE);
                        if (exdates.contains(k) || (!override && overridden.contains(uid + "|" + k))) continue;
                        for (long d = 0; d < days; d++) {
                            LocalDate day = occ.toLocalDate().plusDays(d);
                            if (!day.isBefore(from) && day.isBefore(until)) a.events.add(new Event(day, null, title));
                        }
                    }
                } else {
                    ZonedDateTime start = dateTime(dtstart, local);
                    for (ZonedDateTime occ : occurrences(start, value(ev, "RRULE"), until.atStartOfDay(local))) {
                        String k = occ.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime().format(BASIC_DATETIME);
                        if (exdates.contains(k) || (!override && overridden.contains(uid + "|" + k))) continue;
                        ZonedDateTime here = occ.withZoneSameInstant(local);
                        LocalDate day = here.toLocalDate();
                        if (!day.isBefore(from) && day.isBefore(until)) a.events.add(new Event(day, here, title));
                    }
                }
            } catch (Exception e) {
                android.util.Log.w("CalmAgenda", "skipping event " + title + ": " + e);
            }
        }
        Collections.sort(a.events);
        return a;
    }

    /** Start times of a (maybe repeating) event, up to but not including {@code before}. */
    private static List<ZonedDateTime> occurrences(ZonedDateTime start, String rrule, ZonedDateTime before) {
        List<ZonedDateTime> out = new ArrayList<>();
        if (rrule == null) {
            if (start.isBefore(before)) out.add(start);
            return out;
        }
        String freq = "DAILY";
        int interval = 1;
        int count = -1;
        ZonedDateTime until = null;
        List<DayOfWeek> byDay = new ArrayList<>();
        for (String part : rrule.split(";")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            String k = part.substring(0, eq).toUpperCase(Locale.ROOT);
            String v = part.substring(eq + 1);
            switch (k) {
                case "FREQ": freq = v.toUpperCase(Locale.ROOT); break;
                case "INTERVAL": interval = Math.max(1, Integer.parseInt(v)); break;
                case "COUNT": count = Integer.parseInt(v); break;
                case "UNTIL":
                    until = v.length() == 8
                            ? LocalDate.parse(v, BASIC_DATE).plusDays(1).atStartOfDay(start.getZone())
                            : dateTime(new String[]{"UNTIL", v}, start.getZone());
                    break;
                case "BYDAY":
                    for (String d : v.split(",")) {
                        DayOfWeek dow = dayOfWeek(d.replaceAll("^[+-]?\\d+", ""));
                        if (dow != null) byDay.add(dow);
                    }
                    break;
                default:
            }
        }
        int n = 0;
        for (int step = 0; step < MAX_STEPS; step++) {
            List<ZonedDateTime> batch = new ArrayList<>();
            switch (freq) {
                case "WEEKLY": {
                    ZonedDateTime week = start.plusWeeks((long) step * interval);
                    if (byDay.isEmpty()) {
                        batch.add(week);
                    } else {
                        ZonedDateTime monday = week.minusDays(week.getDayOfWeek().getValue() - 1);
                        for (DayOfWeek d : DayOfWeek.values()) {
                            if (!byDay.contains(d)) continue;
                            ZonedDateTime t = monday.plusDays(d.getValue() - 1);
                            if (!t.isBefore(start)) batch.add(t);
                        }
                    }
                    break;
                }
                case "MONTHLY": batch.add(start.plusMonths((long) step * interval)); break;
                case "YEARLY": batch.add(start.plusYears((long) step * interval)); break;
                default: batch.add(start.plusDays((long) step * interval));
            }
            for (ZonedDateTime t : batch) {
                if (!t.isBefore(before) || (until != null && t.isAfter(until))) return out;
                if (count >= 0 && n >= count) return out;
                n++;
                out.add(t);
            }
        }
        return out;
    }

    private static ZonedDateTime dateTime(String[] p, ZoneId local) {
        String v = p[1].trim();
        if (v.endsWith("Z")) {
            return LocalDateTime.parse(v.substring(0, v.length() - 1), BASIC_DATETIME).atZone(ZoneOffset.UTC);
        }
        ZoneId zone = local;
        String tzid = param(p[0], "TZID");
        if (tzid != null) {
            try {
                zone = ZoneId.of(tzid);
            } catch (Exception ignored) {
            }
        }
        return LocalDateTime.parse(v, BASIC_DATETIME).atZone(zone);
    }

    /** Comparable key for an occurrence: the date for all-day values, else the UTC time. */
    private static String key(String[] p, ZoneId local) {
        if (isDate(p) || p[1].trim().length() == 8) return p[1].trim().substring(0, 8);
        return dateTime(p, local).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime().format(BASIC_DATETIME);
    }

    private static boolean isDate(String[] p) {
        return "DATE".equalsIgnoreCase(param(p[0], "VALUE"));
    }

    private static DayOfWeek dayOfWeek(String d) {
        switch (d.toUpperCase(Locale.ROOT)) {
            case "MO": return DayOfWeek.MONDAY;
            case "TU": return DayOfWeek.TUESDAY;
            case "WE": return DayOfWeek.WEDNESDAY;
            case "TH": return DayOfWeek.THURSDAY;
            case "FR": return DayOfWeek.FRIDAY;
            case "SA": return DayOfWeek.SATURDAY;
            case "SU": return DayOfWeek.SUNDAY;
            default: return null;
        }
    }

    private static String name(String head) {
        int semi = head.indexOf(';');
        return (semi < 0 ? head : head.substring(0, semi)).toUpperCase(Locale.ROOT);
    }

    private static String param(String head, String key) {
        for (String part : head.split(";")) {
            if (part.toUpperCase(Locale.ROOT).startsWith(key + "=")) {
                return part.substring(key.length() + 1).replace("\"", "");
            }
        }
        return null;
    }

    private static String[] prop(List<String[]> ev, String name) {
        for (String[] p : ev) if (name(p[0]).equals(name)) return p;
        return null;
    }

    private static String value(List<String[]> ev, String name) {
        String[] p = prop(ev, name);
        return p == null ? null : p[1];
    }

    private static String unescape(String s) {
        if (s == null) return null;
        return s.replace("\\n", " ").replace("\\N", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\");
    }
}

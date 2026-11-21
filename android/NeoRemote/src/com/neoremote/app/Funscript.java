package com.neoremote.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Скрипт синхронізації з відео у форматі .funscript:
 * {"actions":[{"at": мс, "pos": 0..100}, ...], "inverted": false}
 *
 * Скрипти пишуть для пристроїв, що рухаються вперед-назад. Для вібрації силу
 * беремо зі ШВИДКОСТІ руху (швидко = сильно), або, за бажанням, з позиції.
 */
final class Funscript {

    final String name;
    final int[] at;
    final int[] pos;

    private Funscript(String name, int[] at, int[] pos) {
        this.name = name;
        this.at = at;
        this.pos = pos;
    }

    static Funscript parse(String json, String name) throws Exception {
        JSONObject o = new JSONObject(json);
        boolean inverted = o.optBoolean("inverted", false);
        JSONArray a = o.getJSONArray("actions");
        List<int[]> list = new ArrayList<int[]>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject e = a.getJSONObject(i);
            int t = (int) Math.round(e.getDouble("at"));
            int p = (int) Math.round(e.getDouble("pos"));
            p = Math.max(0, Math.min(100, p));
            if (inverted) p = 100 - p;
            list.add(new int[]{t, p});
        }
        Collections.sort(list, new Comparator<int[]>() {
            @Override public int compare(int[] x, int[] y) { return x[0] < y[0] ? -1 : (x[0] == y[0] ? 0 : 1); }
        });
        if (list.size() < 2) throw new Exception("у скрипті менше двох точок");
        int[] at = new int[list.size()];
        int[] pos = new int[list.size()];
        for (int i = 0; i < at.length; i++) {
            at[i] = list.get(i)[0];
            pos[i] = list.get(i)[1];
        }
        return new Funscript(name, at, pos);
    }

    int count() { return at.length; }

    int durationMs() { return at[at.length - 1]; }

    /** Індекс останньої точки з at <= t, або -1. */
    private int indexAt(long t) {
        int lo = 0, hi = at.length - 1, r = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (at[mid] <= t) { r = mid; lo = mid + 1; } else hi = mid - 1;
        }
        return r;
    }

    /** Сила 0..100 у момент t (мс від початку відео). */
    int valueAt(long t, boolean bySpeed) {
        int i = indexAt(t);
        if (i < 0 || i >= at.length - 1) return 0;
        int dt = at[i + 1] - at[i];
        if (dt <= 0) return 0;
        if (bySpeed) {
            // одиниць позиції за секунду; ~400/с вважаємо максимумом
            double speed = Math.abs(pos[i + 1] - pos[i]) * 1000.0 / dt;
            return (int) Math.min(100, Math.round(speed / 4.0));
        }
        double k = (t - at[i]) / (double) dt;
        return (int) Math.round(pos[i] + (pos[i + 1] - pos[i]) * k);
    }
}

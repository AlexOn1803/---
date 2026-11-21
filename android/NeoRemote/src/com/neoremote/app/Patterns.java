package com.neoremote.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Режими вібрації. Кожен режим це послідовність значень 0..100 % з кроком 100 мс.
 * Відсоток потім множиться на загальну інтенсивність і перетворюється на рівень 0..3.
 */
public final class Patterns {

    public static final int STEP_MS = 100;

    public static final String[] NAMES = {
            "Пульс", "Хвиля", "Сходинки", "Серцебиття", "Швидкий пульс", "Повільна хвиля",
            "Зигзаг", "Наростання", "Тремоло", "Прибій", "Спуск"
    };

    public static final int COUNT = NAMES.length;

    private Patterns() { }

    private static final class B {
        final List<Integer> v = new ArrayList<Integer>();
        B hold(int pct, int ms) {
            for (int i = 0; i < Math.max(1, ms / STEP_MS); i++) v.add(pct);
            return this;
        }
        B ramp(int from, int to, int ms) {
            int n = Math.max(1, ms / STEP_MS);
            for (int i = 0; i < n; i++) v.add(from + (to - from) * i / Math.max(1, n - 1));
            return this;
        }
        int[] build() {
            int[] a = new int[v.size()];
            for (int i = 0; i < a.length; i++) a[i] = v.get(i);
            return a;
        }
    }

    public static int[] get(int index) {
        switch (index) {
            case 0: return new B().hold(100, 400).hold(0, 400).build();
            case 1: return new B().ramp(0, 100, 1000).ramp(100, 0, 1000).build();
            case 2: return new B().hold(33, 700).hold(66, 700).hold(100, 700).hold(0, 500).build();
            case 3: return new B().hold(100, 200).hold(0, 200).hold(100, 200).hold(0, 800).build();
            case 4: return new B().hold(100, 200).hold(0, 200).build();
            case 5: return new B().ramp(0, 100, 2500).ramp(100, 0, 2500).build();
            case 6: return new B().hold(33, 300).hold(100, 300).build();
            case 7: return new B().ramp(10, 100, 6000).hold(100, 1500).hold(0, 700).build();
            case 8: return new B().hold(66, 200).hold(100, 200).build();
            case 9: return new B().ramp(20, 100, 1200).hold(100, 400).ramp(100, 10, 1800)
                    .ramp(10, 70, 800).ramp(70, 10, 800).build();
            case 10: return new B().hold(100, 700).hold(66, 700).hold(33, 700).hold(0, 500).build();
            default: return new int[]{0};
        }
    }

    /** «Випадкова гра»: випадкові рівні випадкової довжини. */
    public static int[] random(Random rnd) {
        B b = new B();
        int total = 0;
        while (total < 20000) {
            int ms = 300 + rnd.nextInt(1500);
            int pct;
            int r = rnd.nextInt(10);
            if (r == 0) pct = 0;
            else if (r < 4) pct = 33;
            else if (r < 7) pct = 66;
            else pct = 100;
            if (rnd.nextInt(4) == 0) b.ramp(rnd.nextInt(40), pct, ms);
            else b.hold(pct, ms);
            total += ms;
        }
        return b.build();
    }

    /** Мініатюра режиму для кнопки: перші ~N точок. */
    public static int[] preview(int index, int points) {
        int[] p = get(index);
        int[] out = new int[points];
        int len = Math.max(p.length, 1);
        int reps = Math.max(1, (points * 3) / Math.max(len, 1));
        for (int i = 0; i < points; i++) {
            int src = (int) ((long) i * len * Math.min(reps, 3) / points) % len;
            out[i] = p[src];
        }
        return out;
    }
}

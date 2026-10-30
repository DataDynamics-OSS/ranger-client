package io.datadynamics.ranger.client.core;

/** Lenient "2.9.0", "2.9.0.7.3.1", "3.0.0-SNAPSHOT" version comparison. */
public final class Versions {
    private Versions() {
    }

    public static int compare(String a, String b) {
        int[] x = parse(a);
        int[] y = parse(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? x[i] : 0;
            int yi = i < y.length ? y[i] : 0;
            if (xi != yi) {
                return Integer.compare(xi, yi);
            }
        }
        return 0;
    }

    static int[] parse(String v) {
        String[] parts = v.split("-")[0].split("\\.");
        int[] out = new int[Math.max(parts.length, 3)];
        for (int i = 0; i < parts.length; i++) {
            String digits = parts[i].replaceAll("\\D", "");
            out[i] = digits.isEmpty() ? 0 : Integer.parseInt(digits);
        }
        return out;
    }
}

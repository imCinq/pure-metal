package io.github.imcinq.puremetal.bench;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;

/**
 * Opt-in GPU time per logical render pass (-Dpuremetal.passTiming=true, Metal 3 only). The native side gives every logical pass its
 * own encoder with stage-boundary samples and reports per "label WxH[+d]" totals; this class sums them over the measured frames and
 * prints the BENCH-PASS lines. Splitting disables encoder merging, so the totals are larger than an unsplit frame's.
 */
public final class PassTiming {
    public static final boolean ENABLED = Boolean.getBoolean("puremetal.passTiming");
    private static final int TOP = 25;
    /** Logical passes that could not be timed because a command buffer held more than the native limit. */
    public static long droppedPasses;
    /** Per key: count, vertex ns, fragment ns, span ns. */
    private static final Map<String, long[]> KEYS = new HashMap<>();

    private PassTiming() {}

    public static void clear() {
        KEYS.clear();
        droppedPasses = 0;
    }

    /** Adds native lines "name\tcount\tvertexNs\tfragmentNs\tspanNs". */
    public static void add(final String text) {
        for (String line : text.split("\n")) {
            String[] f = line.split("\t");
            if (f.length != 5) continue;
            try {
                long[] sum = KEYS.computeIfAbsent(f[0], k -> new long[4]);
                for (int i = 0; i < 4; i++) sum[i] += Long.parseLong(f[i + 1]);
            } catch (NumberFormatException ignored) {
                // a damaged line is skipped
            }
        }
    }

    /** Prints the summary and one BENCH-PASS line per pass (top 25 by span, the rest as "other"). */
    public static void report(final Logger log, final int frames, final long gpuNs, final long gpuFrames, final long blitNs) {
        if (!ENABLED || frames <= 0) return;
        if (KEYS.isEmpty()) {
            log.info("BENCH-PASS no per-pass samples (unsupported here, e.g. Metal 4, or no timed passes)");
            return;
        }
        List<Map.Entry<String, long[]>> rows = new ArrayList<>(KEYS.entrySet());
        rows.sort((a, b) -> Long.compare(b.getValue()[3], a.getValue()[3]));
        long vertex = 0, fragment = 0, span = 0, count = 0;
        for (long[] v : KEYS.values()) {
            count += v[0];
            vertex += v[1];
            fragment += v[2];
            span += v[3];
        }
        log.info("BENCH-PASS-SUMMARY mode=split splitGpuMsPerFrame={} passSpanSumMs/f={} vertexSumMs/f={} fragmentSumMs/f={} blitMs/f={} timedPasses/f={} droppedPasses/f={} keys={} (split mode: every logical pass is its own encoder, so totals are inflated against unsplit gpuMsPerFrame)",
            gpuFrames == 0 ? "n/a" : fmt(gpuNs / 1e6 / frames), fmt(span / 1e6 / frames), fmt(vertex / 1e6 / frames),
            fmt(fragment / 1e6 / frames), fmt(blitNs / 1e6 / frames), fmt((double)count / frames),
            fmt((double)droppedPasses / frames), KEYS.size());
        long[] other = new long[4];
        int otherKeys = 0;
        for (int i = 0; i < rows.size(); i++) {
            long[] v = rows.get(i).getValue();
            if (i >= TOP) {
                for (int k = 0; k < 4; k++) other[k] += v[k];
                otherKeys++;
                continue;
            }
            line(log, rows.get(i).getKey(), v, frames, span);
        }
        if (otherKeys > 0) line(log, "other (" + otherKeys + " more)", other, frames, span);
    }

    private static void line(final Logger log, final String label, final long[] v, final int frames, final long spanTotal) {
        log.info("BENCH-PASS label=\"{}\" count/f={} vertexMs/f={} fragmentMs/f={} totalMs/f={} percent={}",
            label, fmt((double)v[0] / frames), fmt(v[1] / 1e6 / frames), fmt(v[2] / 1e6 / frames), fmt(v[3] / 1e6 / frames),
            spanTotal == 0 ? "n/a" : fmt(100.0 * v[3] / spanTotal));
    }

    private static String fmt(final double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}

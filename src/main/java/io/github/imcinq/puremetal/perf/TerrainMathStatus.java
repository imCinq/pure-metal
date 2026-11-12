package io.github.imcinq.puremetal.perf;

import io.github.imcinq.puremetal.Lang;
import io.github.imcinq.puremetal.backend.FastTerrainMath;
import net.minecraft.network.chat.Component;

/** The plain-words state of terrain shader math ("Fast" or "Standard (reason)") for the F3 screen and Pure Metal's settings. */
public final class TerrainMathStatus {
    private TerrainMathStatus() {
    }

    /** "Terrain math: Fast" or "Terrain math: Standard (reason)". */
    public static Component line() {
        String off = FastTerrainMath.offReason();
        if (off != null) {
            return standard(off.equals("turned off") ? "puremetal.terrainMath.reason.off" : "puremetal.terrainMath.reason.macos");
        }
        boolean relaxed = false;
        for (TerrainShaderProof.FamilyState state : TerrainShaderProof.currentStates()) {
            if (state.requested()) {
                return standard(switch (state.why()) {
                    case PACK -> "puremetal.terrainMath.reason.pack";
                    case SODIUM_VERSION -> "puremetal.terrainMath.reason.sodium";
                    case OTHER -> "puremetal.terrainMath.reason.other";
                });
            }
            relaxed |= state.relaxedMembers() > 0;
        }
        return relaxed ? Lang.text("puremetal.terrainMath.fast") : standard("puremetal.terrainMath.reason.none");
    }

    private static Component standard(final String reasonKey) {
        return Lang.text("puremetal.terrainMath.standard", Lang.text(reasonKey));
    }

    /** The settings note followed by the math status line. */
    public static Component noteWithStatus() {
        return Component.empty().append(Lang.text("puremetal.options.note")).append(Component.literal("\n")).append(line());
    }
}

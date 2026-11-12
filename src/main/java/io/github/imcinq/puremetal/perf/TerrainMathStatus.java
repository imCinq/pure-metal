package io.github.imcinq.puremetal.perf;

import io.github.imcinq.puremetal.Lang;
import io.github.imcinq.puremetal.backend.FastTerrainMath;
import net.minecraft.network.chat.Component;

/** The plain-words state of terrain shader math ("On" or "Off (reason)") for the F3 screen and Pure Metal's settings. */
public final class TerrainMathStatus {
    private TerrainMathStatus() {
    }

    /** "Fast terrain: On" or "Fast terrain: Off (reason)". */
    public static Component line() {
        String off = FastTerrainMath.offReason();
        if (off != null) {
            return standard(off.equals("turned off") ? "puremetal.terrainMath.reason.off" : "puremetal.terrainMath.reason.macos");
        }
        boolean relaxed = false;
        for (TerrainShaderProof.FamilyState state : TerrainShaderProof.currentStates()) {
            // Only a published switch counts: until then every pipeline of the family still draws relaxed.
            if (state.strict()) {
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

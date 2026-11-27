package io.github.imcinq.puremetal.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

/** Client-only NeoForge mod entrypoint. Pure Metal registers its backend via RenderPearl mixins. */
@Mod(value = "puremetal", dist = Dist.CLIENT)
public final class PureMetalNeoForge {
    public PureMetalNeoForge() {
    }
}

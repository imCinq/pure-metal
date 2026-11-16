package io.github.imcinq.puremetal.mixin;

import net.minecraft.client.OptionInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Raw access to an option's value, without the update listeners (used to write the player's own values during a user benchmark save). */
@Mixin(OptionInstance.class)
public interface OptionInstanceAccessor {
    @Accessor("value")
    Object puremetal$getValue();

    @Accessor("value")
    void puremetal$setValue(Object value);
}

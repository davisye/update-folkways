package dev.davisye.folkways.update.mod;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** A separate client entry point keeps client classes out of the dedicated server loader. */
@Mod(value = "folkways_updater", dist = Dist.CLIENT)
public final class UpdaterClient {
    public UpdaterClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (ignored, parent) -> new UpdaterConfigScreen(parent));
    }
}

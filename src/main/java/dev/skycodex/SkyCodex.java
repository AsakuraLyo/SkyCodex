package dev.skycodex;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

@Mod(value = "skycodex", dist = Dist.CLIENT)
public final class SkyCodex {
    private final Controller controller = new Controller();
    private final KeyMapping enable = new KeyMapping("key.skycodex.enable", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, "key.categories.skycodex");
    private final KeyMapping stop = new KeyMapping("key.skycodex.stop", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F9, "key.categories.skycodex");

    public SkyCodex(IEventBus bus) {
        bus.addListener((RegisterKeyMappingsEvent e) -> { e.register(enable); e.register(stop); });
        // Before vanilla handles key bindings this tick, so a held use key cannot start eating a second item.
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Pre e) -> controller.preTick());
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> {
            while (stop.consumeClick()) controller.disarm("Stopped by F9");
            while (enable.consumeClick()) controller.toggleEnabled();
            if (DevHarness.ENABLED) DevHarness.tick(controller);
            controller.runPending();
            controller.tick();
        });
        try { new LocalServer(controller).start(); }
        catch (Exception e) { throw new IllegalStateException("SkyCodex could not open its localhost connection", e); }
    }
}

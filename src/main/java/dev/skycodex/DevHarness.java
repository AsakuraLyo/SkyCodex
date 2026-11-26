package dev.skycodex;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Development-only smoke-test support. Active only when the JVM runs with -Dskycodex.dev=true, which is set by the
 * Gradle runClient configuration and never by a normal launcher. It opens a test world, enables control without F8,
 * and allows the smoke test to run setup commands (give/fill/time) through the local API.
 */
final class DevHarness {
    static final boolean ENABLED = Boolean.getBoolean("skycodex.dev");
    private static final String WORLD = "SkyCodexSmoke";
    private static boolean worldRequested, enabledOnce;

    static void tick(Controller controller) {
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        // A fresh game directory first shows the accessibility/narrator onboarding screen instead of the title screen.
        if (mc.screen instanceof AccessibilityOnboardingScreen) {
            mc.options.onboardAccessibility = false; mc.options.save();
            mc.setScreen(new TitleScreen());
        }
        if (!worldRequested && mc.screen instanceof TitleScreen && mc.level == null) {
            worldRequested = true;
            if (mc.getLevelSource().levelExists(WORLD)) mc.createWorldOpenFlows().openWorld(WORLD, () -> mc.setScreen(new TitleScreen()));
            else {
                GameRules rules = new GameRules();
                rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
                rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
                LevelSettings settings = new LevelSettings(WORLD, GameType.SURVIVAL, false, Difficulty.EASY, true, rules, WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(20261124L, false, false), WorldPresets::createNormalWorldDimensions, new TitleScreen());
            }
        }
        if (!enabledOnce && mc.player != null && mc.level != null && mc.screen == null && mc.player.tickCount > 40) {
            enabledOnce = true;
            controller.toggleEnabled();
        }
    }
}

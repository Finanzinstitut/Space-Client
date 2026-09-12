package gg.spaceclient.module;

import gg.spaceclient.modules.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Central registry. Adding a module is one line here. */
public class ModuleManager {
    private final Map<String, Module> modules = new LinkedHashMap<>();

    public ModuleManager() {
        register(new FpsModule());
        register(new CpsModule());
        register(new CoordinatesModule());
        register(new PingModule());
        register(new ClockModule());
        register(new SpeedometerModule());
        register(new SessionModule());
        register(new KeystrokesModule());
        register(new MouseTrackerModule());
        register(new MemoryModule());
        register(new CompassModule());
        register(new TravelModule());
        register(new ZoomModule());
        register(new HitboxModule());
        register(new ChunkModule());
        register(new ServerInfoModule());
        register(new CrosshairInfoModule());
        register(new YawLockModule());
        register(new InputRateModule());
        register(new CoordsCopyModule());
        register(new WaveyCapeModule());
        register(new TpsModule());
        register(new ArmorModule());
        register(new MusicModule());
        register(new HitColorModule());
        register(new TwitchModule());
        register(new DirectionModule());
        register(new NetherPortalModule());
        register(new ConnectionModule());
        register(new SpacePlayersModule());
        register(new DayTimeModule());
        register(new InventoryModule());
        register(new SuppliesModule());
        register(new HealthModule());
        register(new DurabilityModule());
        register(new SaturationModule());
        register(new NutritionModule());
        register(new ServerModule());
        register(new ItemPhysicsModule());
        register(new FpsBoostModule());
        register(new ReachModule());
        register(new BlockHighlightModule());
        register(new EffectsModule());
    }

    private void register(Module module) {
        modules.put(module.getId(), module);
    }

    public Module get(String id) { return modules.get(id); }

    public List<Module> getAll() { return new ArrayList<>(modules.values()); }

    public List<HudModule> getHudModules() {
        List<HudModule> out = new ArrayList<>();
        for (Module m : modules.values()) {
            if (m instanceof HudModule hud) out.add(hud);
        }
        return out;
    }

    public void onTick() {
        for (Module m : modules.values()) {
            if (m.isEnabled()) m.onTick();
        }
    }
}

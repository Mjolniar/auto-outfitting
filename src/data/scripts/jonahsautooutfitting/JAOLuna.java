package data.scripts.jonahsautooutfitting;

import lunalib.lunaSettings.LunaSettings;
import lunalib.lunaSettings.LunaSettingsListener;

/** LunaLib link. Only touched when LunaLib is enabled, so the mod runs without it. */
public class JAOLuna implements LunaSettingsListener {
    static void register() {
        if (!LunaSettings.hasSettingsListenerOfClass(JAOLuna.class)) LunaSettings.addSettingsListener(new JAOLuna());
        // refit screen button "Order best weapons for fleet" (fleet production planner)
        if (lunalib.lunaRefit.LunaRefitManager.getFirstButtonOfClass(JAORefitButton.class) == null)
            lunalib.lunaRefit.LunaRefitManager.addRefitButton(new JAORefitButton());
    }

    static Boolean bool(String id) { return LunaSettings.getBoolean(JAOSettings.MOD, id); }
    static Integer integer(String id) { return LunaSettings.getInt(JAOSettings.MOD, id); }
    static Double dbl(String id) { return LunaSettings.getDouble(JAOSettings.MOD, id); }
    static String string(String id) { return LunaSettings.getString(JAOSettings.MOD, id); }

    /** Saving in LunaLib's menu applies the new values at once (the next autofit uses them). */
    @Override
    public void settingsChanged(String modId) {
        if (!JAOSettings.MOD.equals(modId)) return;
        if (JAOSettings.load()) { JAORatings.reset(); JAOGoals.refresh(); JAOModPlugin.publishStatus(); }
    }
}

package data.scripts.jonahsautooutfitting;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShieldAPI.ShieldType;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.WeaponAPI.AIHints;
import com.fs.starfarer.api.combat.WeaponAPI.DerivedWeaponStatsAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.ProjectileSpecAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.api.loading.WingRole;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Numbers for every weapon, wing and hullmod the loaded game has, whatever the mod list (docs/design.md 7b).
 * Built from the live specs the first time a fit needs them after a load, cached until the next load or settings
 * change. The data tables only add to items whose ids are loaded; other mods can add rows to them at the same paths
 * (merged by id), so nothing here is keyed to a particular mod.
 */
public class JAORatings {
    static final String EFFECTS = "data/config/jao_weapon_effects.csv", HULLMODS = "data/config/jao_hullmods.csv", RULES = "data/config/jao_rules.csv";

    private static final Map<String, JAOModel.W> weapons = new HashMap<>(), wings = new HashMap<>();
    private static Map<String, JSONObject> effects, hullmods;
    private static List<String[]> rules;
    /** Reference targets per size class: [size][0 shielded, 1 not]. */
    private static JAOModel.Target[][] refs;

    /** Drops the cache (after a load or a settings change); the next fit rebuilds it. */
    static void reset() {
        weapons.clear();
        wings.clear();
        effects = null;
        hullmods = null;
        rules = null;
        refs = null;
    }

    // ------------------------------------------------------------------ weapons

    static JAOModel.W weapon(WeaponSpecAPI spec) {
        JAOModel.W w = weapons.get(spec.getWeaponId());
        if (w == null) {
            w = makeWeapon(spec);
            weapons.put(spec.getWeaponId(), w);
        }
        return w;
    }

    private static JAOModel.W makeWeapon(WeaponSpecAPI spec) {
        JAOModel.W w = new JAOModel.W();
        w.id = spec.getWeaponId();
        w.name = spec.getWeaponName();
        w.size = spec.getSize() == null ? 0 : spec.getSize().ordinal();
        w.cls = cls(spec.getType());
        w.dmg = dmg(spec.getDamageType() == null ? null : spec.getDamageType().name());
        w.beam = spec.isBeam();
        w.soft = w.beam || spec.hasTag("damage_soft_flux");
        w.range = spec.getMaxRange();
        w.pd = spec.getAIHints().contains(AIHints.PD);
        w.pdOnly = spec.getAIHints().contains(AIHints.PD_ONLY);
        w.strike = spec.getAIHints().contains(AIHints.STRIKE);
        w.guided = spec.getAIHints().contains(AIHints.DO_NOT_AIM);
        w.guidedPoor = spec.getAIHints().contains(AIHints.GUIDED_POOR);
        w.fireWhenInefficient = spec.getAIHints().contains(AIHints.FIRE_WHEN_INEFFICIENT);
        w.spread = spec.getMaxSpread();
        Object proj = spec.getProjectileSpec();
        if (proj instanceof ProjectileSpecAPI) {
            try { w.projSpeed = ((ProjectileSpecAPI) proj).getMoveSpeed(null, null); } catch (Throwable ignored) { }
        }
        DerivedWeaponStatsAPI d = spec.getDerivedStats();
        if (d != null) {
            boolean finite = spec.usesAmmo() && spec.getAmmoPerSecond() <= 0f;
            w.dps = finite ? 0f : d.getSustainedDps();
            w.flux = finite ? 0f : d.getSustainedFluxPerSecond();
            w.burst = d.getBurstDamage();
            w.emp = d.getEmpPerSecond();
            w.hit = w.beam ? 0.5f * d.getDps() : d.getDamagePerShot();
            if (finite) w.pool = d.getDamagePerShot() * Math.max(1, spec.getMaxAmmo());
        }
        // rarity score: drop weight, tier, rare tags (only used with Prefer rare weapons)
        float r = Math.max(0f, 1f - Math.min(1f, spec.getRarity())) ;
        r = Math.max(r, spec.getTier() / 3f);
        for (String t : new String[]{"rare_bp", "restricted", "omega", "threat", "dweller"}) if (spec.hasTag(t)) r = 1f;
        w.rarity = Math.min(1f, r);
        w.interference = interference(w.id);
        applyEffects(w);
        return w;
    }

    /** Scripted effects from jao_weapon_effects.csv (when "Value special weapons" is on). */
    private static void applyEffects(JAOModel.W w) {
        JSONObject row = effects().get(w.id);
        if (row == null) return;
        w.ownedOnly = row.optBoolean("ownedOnly", false) || "TRUE".equalsIgnoreCase(row.optString("ownedOnly"));
        if (!JAOSettings.specialWeapons) return;
        float sm = num(row, "shieldDamageMult");
        if (sm > 0f) w.shieldMultExtra = sm;
        float shotsPerSec = w.hit > 0f && w.dps > 0f && !w.beam ? w.dps / w.hit : 1f;
        float armorExtra = num(row, "armorExtraPerHit");
        if (armorExtra > 0f) {
            w.armorExtraPerHit = armorExtra;
            // finite missiles spend their extra armor damage with their pool; guns per shot
            w.hitsPerSec = w.pool > 0f ? Math.max(0.05f, w.pool / Math.max(1f, w.hit) / Math.max(30f, JAOSettings.fightSeconds)) : shotsPerSec;
        }
        float arcDmg = num(row, "arcDamage"), chance = num(row, "arcChancePerHullHit"), perSec = num(row, "arcsPerSecond");
        float arcs = perSec > 0f ? perSec : chance * shotsPerSec;
        if (arcDmg > 0f && arcs > 0f) w.arcDps = arcDmg * arcs;
        float debuff = num(row, "targetShieldTakenMult");
        if (debuff > 0f) w.shieldDebuff = debuff;
        if ("TRUE".equalsIgnoreCase(row.optString("softFluxOnShields"))) w.soft = true;
    }

    // ------------------------------------------------------------------ fighter wings

    static JAOModel.W wing(FighterWingSpecAPI spec) {
        JAOModel.W w = wings.get(spec.getId());
        if (w == null) {
            w = makeWing(spec);
            wings.put(spec.getId(), w);
        }
        return w;
    }

    /**
     * A wing as one "weapon": its fighters' guns at 60% sortie uptime, their finite missiles once per refit cycle,
     * always in range (it flies to the target), no flux. Interceptors also count as point defense.
     */
    private static JAOModel.W makeWing(FighterWingSpecAPI spec) {
        JAOModel.W w = new JAOModel.W();
        w.id = spec.getId();
        w.wing = true;
        w.range = Math.max(1000f, spec.getRange());
        w.interceptor = spec.getRole() == WingRole.INTERCEPTOR;
        w.bomber = spec.getRole() == WingRole.BOMBER;
        int n = Math.max(1, spec.getNumFighters());
        float cycle = Math.max(10f, spec.getRefitTime()) + 20f;
        // share of the fight the wing is out fighting: quick-refit wings come back sooner (mechanics 7.2)
        float sortie = 0.75f - 0.25f * JAOModel.clamp((spec.getRefitTime() - 10f) / 20f, 0f, 1f);
        float dps = 0f, hitSum = 0f, best = -1f;
        ShipVariantAPI v = spec.getVariant();
        if (v != null) {
            for (String slot : v.getFittedWeaponSlots()) {
                WeaponSpecAPI ws = v.getWeaponSpec(slot);
                if (ws == null || ws.getDerivedStats() == null) continue;
                DerivedWeaponStatsAPI d = ws.getDerivedStats();
                boolean finite = ws.usesAmmo() && ws.getAmmoPerSecond() <= 0f;
                float wd = finite ? d.getDamagePerShot() * Math.max(1, ws.getMaxAmmo()) / cycle : d.getSustainedDps() * sortie;
                dps += wd;
                hitSum += wd * (ws.isBeam() ? 0.5f * d.getDps() : d.getDamagePerShot());
                if (wd > best) { best = wd; w.dmg = dmg(ws.getDamageType() == null ? null : ws.getDamageType().name()); }
            }
            w.name = v.getHullSpec() == null ? spec.getId() : v.getHullSpec().getHullName();
        }
        // tougher fighters live longer and keep shooting; flimsy ones die on the way in
        float tough = 0.8f;
        if (v != null && v.getHullSpec() != null) {
            float hp = v.getHullSpec().getHitpoints() + 2f * v.getHullSpec().getArmorRating();
            tough = JAOModel.clamp(0.6f + 0.4f * hp / 600f, 0.6f, 1.2f);
        }
        w.dps = dps * n * tough;
        w.hit = dps > 0f ? hitSum / dps : 0f;
        w.pd = w.interceptor;
        w.rarity = Math.min(1f, Math.max(Math.max(0f, 1f - Math.min(1f, spec.getRarity())), spec.getTier() / 3f));
        return w;
    }

    // ------------------------------------------------------------------ reference targets

    /** Medians of the loaded combat hulls per size, split into shielded and unshielded (phase counts as unshielded). */
    static JAOModel.Target[][] refs() {
        if (refs != null) return refs;
        List<List<float[]>> rows = new ArrayList<>();
        for (int i = 0; i < 4; i++) rows.add(new ArrayList<>());
        for (ShipHullSpecAPI h : Global.getSettings().getAllShipHullSpecs()) {
            int size = sizeIndex(h.getHullSize());
            int op;
            try { op = h.getOrdnancePoints(null); } catch (Throwable t) { op = 1; }
            if (size < 0 || op <= 0 || h.isCivilianNonCarrier()) continue;
            if (h.getHints().contains(ShipTypeHints.STATION) || h.getHints().contains(ShipTypeHints.MODULE)) continue;
            boolean shield = h.getShieldType() == ShieldType.FRONT || h.getShieldType() == ShieldType.OMNI;
            float eff = shield && h.getShieldSpec() != null ? h.getShieldSpec().getFluxPerDamageAbsorbed() : 0f;
            rows.get(size).add(new float[]{h.getArmorRating(), h.getHitpoints(), h.getFluxCapacity(), h.getFluxDissipation(), eff, shield ? 1f : 0f});
        }
        float[] threats = threats();
        refs = new JAOModel.Target[4][2];
        for (int s = 0; s < 4; s++) {
            List<float[]> all = rows.get(s);
            int shielded = 0;
            for (float[] r : all) if (r[5] > 0f) shielded++;
            float share = all.isEmpty() ? 0.8f : shielded / (float) all.size();
            for (int k = 0; k < 2; k++) {
                JAOModel.Target t = new JAOModel.Target();
                t.size = s;
                t.armor = median(all, 0, DEFAULT_ARMOR[s]);
                t.hull = median(all, 1, DEFAULT_HULL[s]);
                t.cap = median(all, 2, DEFAULT_HULL[s]);
                t.diss = median(all, 3, DEFAULT_HULL[s] / 16f);
                t.shield = k == 0;
                if (t.shield) {
                    List<float[]> sh = new ArrayList<>();
                    for (float[] r : all) if (r[5] > 0f) sh.add(r);
                    t.shieldEff = median(sh, 4, 0.8f);
                }
                t.weight = k == 0 ? share : 1f - share;
                t.threat = threats[s];
                refs[s][k] = t;
            }
        }
        return refs;
    }

    /** Fallback main-battery ranges per size (median of the stock goal loadouts in the 11/02 research dump). */
    static final float[] DEFAULT_THREAT = {660f, 800f, 1000f, 1250f};

    /**
     * Range each size class's main battery fights at in the loaded game: for every stock goal variant, the shortest
     * range in its heaviest non-missile, non-PD weapon group (as the ship AI picks it), with ITU or DTC added; the
     * median per hull size.
     */
    private static float[] threats() {
        float[] out = DEFAULT_THREAT.clone();
        try {
            List<List<Float>> per = new ArrayList<>();
            for (int i = 0; i < 4; i++) per.add(new ArrayList<>());
            float[] itu = {0.1f, 0.2f, 0.4f, 0.6f}, dtc = {0f, 0f, 0.35f, 0.5f};
            for (String id : Global.getSettings().getAllVariantIds()) {
                ShipVariantAPI v = Global.getSettings().getVariant(id);
                if (v == null || !v.isGoalVariant() || v.getHullSpec() == null) continue;
                int size = sizeIndex(v.getHullSpec().getHullSize());
                if (size < 0) continue;
                Map<String, float[]> groups = new HashMap<>();
                for (String slot : v.getFittedWeaponSlots()) {
                    WeaponSpecAPI w = v.getWeaponSpec(slot);
                    if (w == null || w.getType() == WeaponType.MISSILE || w.getType() == WeaponType.DECORATIVE
                            || w.getAIHints().contains(AIHints.PD) || w.getMaxRange() <= 0f) continue;
                    float wt = w.getSize() == null ? 1f : w.getSize().ordinal() == 0 ? 1f : w.getSize().ordinal() == 1 ? 3f : 7f;
                    float[] g = groups.computeIfAbsent(w.getWeaponId(), k -> new float[]{0f, w.getMaxRange()});
                    g[0] += wt;
                }
                float best = -1f, range = 0f;
                for (float[] g : groups.values()) if (g[0] > best) { best = g[0]; range = g[1]; }
                if (best < 0f) continue;
                float bonus = v.hasHullMod("targetingunit") ? itu[size] : v.hasHullMod("dedicated_targeting_core") ? dtc[size] : 0f;
                per.get(size).add(range * (1f + bonus));
            }
            for (int s = 0; s < 4; s++) {
                List<Float> l = per.get(s);
                if (l.size() < 5) continue;
                Collections.sort(l);
                out[s] = l.get(l.size() / 2);
            }
        } catch (Throwable t) {
            Global.getLogger(JAORatings.class).warn("Jonah's Auto Outfitting: couldn't measure enemy ranges; using defaults", t);
        }
        Global.getLogger(JAORatings.class).info("Jonah's Auto Outfitting: enemy main-battery ranges by size " + java.util.Arrays.toString(out));
        return out;
    }

    static final float[] DEFAULT_ARMOR = {300f, 500f, 1000f, 1500f}, DEFAULT_HULL = {1750f, 4500f, 8000f, 18000f};
    /** Who a ship of each size mostly fights: weights over frigate/destroyer/cruiser/capital targets. */
    static final float[][] TARGET_MIX = {{0.35f, 0.35f, 0.2f, 0.1f}, {0.25f, 0.35f, 0.25f, 0.15f}, {0.15f, 0.3f, 0.35f, 0.2f}, {0.1f, 0.2f, 0.35f, 0.35f}};

    /** Targets for a ship of this size and style. Anti-armor fights only the armored ones (cruisers and capitals). */
    static JAOModel.Target[] targets(int ownSize, JAOStyle style) {
        return targets(TARGET_MIX[Math.max(0, Math.min(3, ownSize))], style);
    }

    /** Targets weighted by mix (frigate, destroyer, cruiser, capital), e.g. a role's. */
    static JAOModel.Target[] targets(float[] mix, JAOStyle style) {
        JAOModel.Target[][] r = refs();
        List<JAOModel.Target> out = new ArrayList<>();
        for (int s = 0; s < 4; s++) {
            if (style == JAOStyle.ANTI_ARMOR && s < 2) continue;
            for (int k = 0; k < 2; k++) {
                JAOModel.Target t = r[s][k];
                if (t.weight <= 0f) continue;
                JAOModel.Target c = new JAOModel.Target();
                c.size = t.size; c.armor = t.armor; c.hull = t.hull; c.cap = t.cap; c.diss = t.diss; c.shield = t.shield;
                c.shieldEff = t.shieldEff;
                c.threat = t.threat;
                c.weight = mix[s] * t.weight;
                out.add(c);
            }
        }
        return out.toArray(new JAOModel.Target[0]);
    }

    // ------------------------------------------------------------------ tables

    static Map<String, JSONObject> effects() {
        if (effects == null) effects = merged("weaponId", EFFECTS);
        return effects;
    }

    static Map<String, JSONObject> hullmods() {
        if (hullmods == null) hullmods = merged("id", HULLMODS);
        return hullmods;
    }

    /** Rows of jao_rules.csv: {kind, id, value}. */
    static List<String[]> rules() {
        if (rules != null) return rules;
        rules = new ArrayList<>();
        try {
            JSONArray a = Global.getSettings().getMergedSpreadsheetDataForMod("kind", RULES, JAOSettings.MOD);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String kind = o.optString("kind", "").trim();
                if (kind.isEmpty() || kind.startsWith("#")) continue;
                rules.add(new String[]{kind, o.optString("id", "").trim(), o.optString("value", "").trim()});
            }
        } catch (Throwable t) {
            Global.getLogger(JAORatings.class).warn("Jonah's Auto Outfitting: can't read " + RULES, t);
        }
        return rules;
    }

    /** jao_rules interference rows: WEAK / MILD / STRONG = 20 / 40 / 80 dissipation per other interference weapon. */
    static float interference(String weaponId) {
        for (String[] r : rules()) {
            if (!r[0].equals("interference") || !glob(r[1], weaponId)) continue;
            return "STRONG".equalsIgnoreCase(r[2]) ? 80f : "MILD".equalsIgnoreCase(r[2]) ? 40f : 20f;
        }
        return 0f;
    }

    /** jao_rules "role" override for a hull (id or pattern; the base hull counts too). */
    static JAORole roleOverride(String hullId, String baseHullId) {
        for (String[] r : rules()) {
            if (!r[0].equals("role")) continue;
            if (glob(r[1], hullId) || (baseHullId != null && glob(r[1], baseHullId))) { JAORole role = JAORole.named(r[2]); if (role != null) return role; }
        }
        return null;
    }

    /** Ids (or prefix/suffix patterns with *) listed under kind. */
    static boolean ruleMatches(String kind, String id) {
        for (String[] r : rules()) if (r[0].equals(kind) && glob(r[1], id)) return true;
        return false;
    }

    static Set<String> skipSlots(String hullId, String baseHullId) {
        Set<String> out = new HashSet<>();
        for (String[] r : rules()) {
            if (!r[0].equals("skip_slots") || !(r[1].equals(hullId) || r[1].equals(baseHullId))) continue;
            for (String s : r[2].split(";")) if (!s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }

    static boolean glob(String pattern, String id) {
        if (pattern.isEmpty() || id == null) return false;
        if (!pattern.contains("*")) return pattern.equals(id);
        String[] parts = pattern.split("\\*", -1);
        int pos = 0;
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty()) continue;
            int at = id.indexOf(p, pos);
            if (at < 0 || (i == 0 && at != 0)) return false;
            pos = at + p.length();
        }
        return parts[parts.length - 1].isEmpty() || id.endsWith(parts[parts.length - 1]);
    }

    private static Map<String, JSONObject> merged(String idColumn, String path) {
        Map<String, JSONObject> m = new HashMap<>();
        try {
            JSONArray a = Global.getSettings().getMergedSpreadsheetDataForMod(idColumn, path, JAOSettings.MOD);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String id = o.optString(idColumn, "").trim();
                if (!id.isEmpty() && !id.startsWith("#")) m.put(id, o);
            }
        } catch (Throwable t) {
            Global.getLogger(JAORatings.class).warn("Jonah's Auto Outfitting: can't read " + path, t);
        }
        return Collections.unmodifiableMap(m);
    }

    // ------------------------------------------------------------------ helpers

    static int cls(WeaponType t) {
        if (t == WeaponType.ENERGY || t == WeaponType.SYNERGY) return JAOModel.ENE;
        if (t == WeaponType.MISSILE) return JAOModel.MIS;
        return JAOModel.BAL;
    }

    static int dmg(String type) {
        if ("KINETIC".equals(type)) return JAOModel.KIN;
        if ("HIGH_EXPLOSIVE".equals(type)) return JAOModel.HE;
        if ("FRAGMENTATION".equals(type)) return JAOModel.FRAG;
        return JAOModel.EN;
    }

    static int sizeIndex(HullSize s) {
        if (s == HullSize.FRIGATE) return 0;
        if (s == HullSize.DESTROYER) return 1;
        if (s == HullSize.CRUISER) return 2;
        if (s == HullSize.CAPITAL_SHIP) return 3;
        return -1;
    }

    static float num(JSONObject o, String key) {
        String v = o.optString(key, "").trim();
        if (v.isEmpty()) return 0f;
        try { return Float.parseFloat(v); } catch (NumberFormatException e) { return 0f; }
    }

    private static float median(List<float[]> rows, int col, float def) {
        if (rows.isEmpty()) return def;
        float[] v = new float[rows.size()];
        for (int i = 0; i < v.length; i++) v[i] = rows.get(i)[col];
        java.util.Arrays.sort(v);
        return v[v.length / 2];
    }
}

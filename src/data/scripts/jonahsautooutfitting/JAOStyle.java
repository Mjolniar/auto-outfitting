package data.scripts.jonahsautooutfitting;

/**
 * Build styles. Each weights the three layers a kill goes through: break the shield (overload), strip the armor,
 * then the hull (docs/design.md 7; research mechanics_report.md 8.7). Balanced minimises the whole kill time; the
 * specialists put most firepower into one layer but still have to finish the other two. Anti-armor is also scored
 * against the armored targets only (cruisers and capitals). Each weight set sums to 3.
 */
public enum JAOStyle {
    AUTO("Auto", 1f, 1f, 1f),
    BALANCED("Balanced", 1f, 1f, 1f),
    ANTI_SHIELD("Anti-shield", 2.1f, 0.45f, 0.45f),
    ANTI_ARMOR("Anti-armor", 0.45f, 1.65f, 0.9f),
    HULL_KILLER("Hull killer", 0.3f, 0.9f, 1.8f);

    public final String label;
    /** Weights on the shield, armor and hull phases of the kill time. */
    public final float shield, armor, hull;

    JAOStyle(String label, float shield, float armor, float hull) {
        this.label = label; this.shield = shield; this.armor = armor; this.hull = hull;
    }

    static JAOStyle named(String s) {
        for (JAOStyle st : values()) if (st.label.equalsIgnoreCase(s) || st.name().equalsIgnoreCase(s)) return st;
        return AUTO;
    }
}

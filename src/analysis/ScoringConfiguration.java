package analysis;

import domain.Nation;
import domain.Province;
import domain.UnitType;

import java.util.*;

/** Immutable, opt-in recommendation settings; legacy outcome weights are separate. */
public record ScoringConfiguration(double humanWeight,
                                   Map<Province, ProvinceValue> globalValues,
                                   Map<Nation, NationProfile> nationProfiles) {
    public static final double MAX_VALUE = 1_000_000;
    public static final int MAX_OBJECTIVES = 64;
    public static final int MAX_TARGETS = Province.values().length;
    public static final int MAX_HORIZON = 100;
    public static final double MAX_DECAY = 100;

    public ScoringConfiguration {
        bounded(humanWeight, "Human weight");
        if (humanWeight < 0)
            throw new IllegalArgumentException("Human weight must be non-negative");
        globalValues = values(globalValues);
        Objects.requireNonNull(nationProfiles, "nationProfiles");
        EnumMap<Nation, NationProfile> profiles = new EnumMap<>(Nation.class);
        for (var entry : nationProfiles.entrySet())
            profiles.put(Objects.requireNonNull(entry.getKey(), "nation"),
                    Objects.requireNonNull(entry.getValue(), "profile"));
        for (Nation nation : Nation.values())
            profiles.putIfAbsent(nation, new NationProfile(Map.of(), List.of()));
        nationProfiles = Collections.unmodifiableMap(profiles);
    }

    public record ProvinceValue(double value, Set<UnitType> unitTypes) {
        public ProvinceValue {
            bounded(value, "Province value");
            unitTypes = types(unitTypes);
        }
    }

    public record NationProfile(Map<Province, ProvinceValue> adjustments,
                                List<RegionalObjective> objectives) {
        public NationProfile {
            adjustments = values(adjustments);
            objectives = List.copyOf(objectives);
            if (objectives.size() > MAX_OBJECTIVES)
                throw new IllegalArgumentException("Too many regional objectives");
            Set<String> names = new HashSet<>();
            for (RegionalObjective objective : objectives)
                if (!names.add(objective.name()))
                    throw new IllegalArgumentException("Duplicate regional objective: " + objective.name());
            objectives = objectives.stream().sorted(Comparator.comparing(RegionalObjective::name)).toList();
        }
    }

    public record RegionalObjective(String name, Set<Province> targets, double priority,
                                    Set<UnitType> unitTypes, int horizon, double decay) {
        public RegionalObjective {
            Objects.requireNonNull(name, "name");
            name = name.strip();
            if (name.isEmpty() || name.length() > 120)
                throw new IllegalArgumentException("Objective name must contain 1–120 characters");
            Objects.requireNonNull(targets, "targets");
            if (targets.isEmpty() || targets.size() > MAX_TARGETS)
                throw new IllegalArgumentException("Invalid regional target count");
            // Fleets preserve exact coast targets; armies resolve targets to land territories.
            // Unreachable targets (including Swi) are allowed for explicit zero-potential diagnostics.
            targets = Collections.unmodifiableSet(EnumSet.copyOf(targets));
            for (Province target : targets)
                if (target.parent != null && targets.contains(target.parent))
                    throw new IllegalArgumentException("Region mixes parent and coast targets: " + target.parent);
            bounded(priority, "Regional priority");
            if (priority <= 0)
                throw new IllegalArgumentException("Regional priority must be positive");
            unitTypes = types(unitTypes);
            if (horizon < 0 || horizon > MAX_HORIZON)
                throw new IllegalArgumentException("Invalid regional horizon");
            if (!Double.isFinite(decay) || decay < 0 || decay > MAX_DECAY)
                throw new IllegalArgumentException("Invalid regional decay");
        }
    }

    public static ScoringConfiguration defaults() {
        return new ScoringConfiguration(0, Map.of(), Map.of());
    }

    /** A small illustrative preset, never automatically applied. */
    public static ScoringConfiguration example() {
        Set<UnitType> both = EnumSet.allOf(UnitType.class);
        Set<UnitType> fleets = Set.of(UnitType.FLEET);
        return new ScoringConfiguration(1,
                Map.of(Province.MAO, new ProvinceValue(1, fleets),
                        Province.ION, new ProvinceValue(1, fleets)),
                Map.of(Nation.GERMANY, new NationProfile(
                        Map.of(Province.Pru, new ProvinceValue(-1, both)),
                        List.of(new RegionalObjective("north", Set.of(Province.Bel, Province.Hol),
                                2, Set.of(UnitType.ARMY), 4, 0.5)))));
    }

    public NationProfile profile(Nation nation) {
        return nationProfiles.get(Objects.requireNonNull(nation, "nation"));
    }

    public double effectiveValue(Nation nation, UnitType type, Province province) {
        Objects.requireNonNull(type, "type");
        Province territory = Province.canonical(Objects.requireNonNull(province, "province"));
        ProvinceValue global = globalValues.get(territory);
        ProvinceValue local = profile(nation).adjustments().get(territory);
        return (global != null && global.unitTypes().contains(type) ? global.value() : 0)
                + (local != null && local.unitTypes().contains(type) ? local.value() : 0);
    }

    public boolean hasGeography() {
        return Arrays.stream(Nation.values()).anyMatch(this::hasGeography);
    }

    public boolean hasGeography(Nation nation) {
        return hasGeography(nation, EnumSet.allOf(UnitType.class));
    }

    public boolean hasGeography(Nation nation, Set<UnitType> unitTypes) {
        NationProfile profile = profile(nation);
        Set<UnitType> applicable = Set.copyOf(Objects.requireNonNull(unitTypes, "unitTypes"));
        if (profile.objectives().stream()
                .anyMatch(objective -> objective.unitTypes().stream().anyMatch(applicable::contains)))
            return true;
        for (Province province : Province.values())
            if (province.parent == null)
                for (UnitType type : applicable)
                    if (effectiveValue(nation, type, province) != 0)
                        return true;
        return false;
    }

    private static Set<UnitType> types(Set<UnitType> input) {
        Objects.requireNonNull(input, "unitTypes");
        if (input.isEmpty())
            throw new IllegalArgumentException("At least one unit type is required");
        return Collections.unmodifiableSet(EnumSet.copyOf(input));
    }

    private static Map<Province, ProvinceValue> values(Map<Province, ProvinceValue> input) {
        Objects.requireNonNull(input, "province values");
        if (input.size() > Province.values().length)
            throw new IllegalArgumentException("Too many province values");
        EnumMap<Province, ProvinceValue> copy = new EnumMap<>(Province.class);
        for (var entry : input.entrySet()) {
            Province canonical = Province.canonical(Objects.requireNonNull(entry.getKey(), "province"));
            if (copy.putIfAbsent(canonical, Objects.requireNonNull(entry.getValue(), "value")) != null)
                throw new IllegalArgumentException("Duplicate territory valuation: " + canonical);
        }
        return Collections.unmodifiableMap(copy);
    }

    private static void bounded(double value, String name) {
        if (!Double.isFinite(value) || Math.abs(value) > MAX_VALUE)
            throw new IllegalArgumentException(name + " must be finite and within ±" + MAX_VALUE);
    }
}

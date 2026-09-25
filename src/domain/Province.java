package domain;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public enum Province {


    Boh("Bohemia", false, false),
    Bud("Budapest", Geography.INLAND, true, Nation.AUSTRIA),
    Gal("Galicia", false, false),
    Tri("Trieste", Geography.COASTAL, true, Nation.AUSTRIA, 7, false, false),
    Tyr("Tyrolia", false, false),
    Vie("Vienna", Geography.INLAND, true, Nation.AUSTRIA),

    Cly("Clyde", Geography.COASTAL, false, null, 3, false, false),
    Edi("Edinburgh", Geography.COASTAL, true, Nation.ENGLAND, 2, false, false),
    Lvp("Liverpool", Geography.COASTAL, true, Nation.ENGLAND, 4 ,false, false),
    Lon("London", Geography.COASTAL, true, Nation.ENGLAND, 2, false, false),
    Wal("Wales", Geography.COASTAL, false, null, 3, false, false),
    Yor("Yorkshire", Geography.COASTAL, false, null, 1, false, false),

    Bre("Brest", Geography.COASTAL, true, Nation.FRANCE, 17, false, false),
    Bur("Burgundy", false, false),
    Gas("Gascony", Geography.COASTAL, false, null, 16, false, false),
    Mar("Marseilles", Geography.COASTAL, true, Nation.FRANCE, 14, false, false),
    Par("Paris", Geography.INLAND, true, Nation.FRANCE),
    Pic("Picardy", Geography.COASTAL, false, null, 18, false, false),

    Ber("Berlin", Geography.COASTAL, true, Nation.GERMANY, 22, false, false),
    Kie("Kiel", Geography.COASTAL, true, Nation.GERMANY, 21, false, false),
    Mun("Munich", Geography.INLAND, true, Nation.GERMANY),
    Pru("Prussia", Geography.COASTAL, false, null, 23, false, false),
    Ruh("Ruhr", false, false),
    Sil("Silesia", false, false),

    Apu("Apulia", Geography.COASTAL, false, null, 9, false, false),
    Nap("Naples", Geography.COASTAL, true, Nation.ITALY, 10, false, false),
    Pie("Piedmont", Geography.COASTAL, false, null, 13, false, false),
    Rom("Rome", Geography.COASTAL, true, Nation.ITALY, 11, false, false),
    Tus("Tuscany", Geography.COASTAL, false, null, 12, false, false),
    Ven("Venice", Geography.COASTAL, true, Nation.ITALY, 8, false, false),

    Lvn("Livonia", Geography.COASTAL, false, null, 24, false, false),
    Mos("Moscow", Geography.INLAND, true, Nation.RUSSIA),
    Sev("Sevastopol", Geography.COASTAL, true, Nation.RUSSIA, 6, false, false),
    Stp("St Petersburg", Geography.INLAND, true, Nation.RUSSIA, 25, false, false, null),
    // ^^ Not truly "Inland" ^^
    Ukr("Ukraine", false, false),
    War("Warsaw", Geography.INLAND, true, Nation.RUSSIA),

    Ank("Ankara", Geography.COASTAL, true, Nation.TURKEY, 4 , false, false),
    Arm("Armenia", Geography.COASTAL, false, null, 5, false, false),
    Con("Constantinople", Geography.COASTAL, true, Nation.TURKEY, 3, true, false, null),
    Smy("Smyrna", Geography.COASTAL, true, Nation.TURKEY, 2, false, false),
    Syr("Syria", Geography.COASTAL, false, null, 1, false, false),

    Alb("Albania", Geography.COASTAL, false, null, 6, false, false),
    Bel("Belgium", Geography.COASTAL, true, null, 19, false, false),
    Bul("Bulgaria", Geography.INLAND, true, null, 4, false, false, null),
    // ^^ Not truly "Inland" ^^
    Den("Denmark", Geography.COASTAL, true, null, 22, true, false, null),
    Fin("Finland", Geography.COASTAL, false, null, 24, false, false),
    Gre("Greece", Geography.COASTAL, true, null, 5, false, false),
    Hol("Holland", Geography.COASTAL, true, null, 20, false, false),
    Nwy("Norway", Geography.COASTAL, true, null, 24, false, false),
    Naf("North Africa", Geography.COASTAL, false, null, 2, false, false),
    Por("Portugal", Geography.COASTAL, true, null, 16, false, false),
    Rum("Rumania", Geography.COASTAL, true, null, 5, false, false),
    Ser("Serbia", false, true),
    Spa("Spain", Geography.INLAND, true, null, 15, false, false, null),
    // ^^ Not truly "Inland" ^^
    Swe("Sweden", Geography.COASTAL, true, null, 23, false, false),
    Tun("Tunis", Geography.COASTAL, true, null, 1, false, false),

    ADR("Adriatic Sea", true),
    AEG("Aegean Sea", true),
    BAL("Baltic Sea", true),
    BAR("Barents Sea", true),
    BLA("Black Sea", true),
    EAS("Eastern Mediterranean", true),
    ENG("English Channel", true),
    BOT("Gulf of Bothnia", true),
    LYO("Gulf of Lyon", true),
    HEL("Helgoland Bight", true),
    ION("Ionian Sea", true),
    IRI("Irish Sea", true),
    MAO("Mid-Atlantic Ocean", true),
    NAO("North Atlantic Ocean", true),
    NTH("North Sea", true),
    NWG("Norwegian Sea", true),
    SKA("Skagerrak", true),
    TYS("Tyrrhenian Sea", true),
    WES("Western Mediterranean", true),

    StpNC("St Petersburg(nc)", Geography.COASTAL, true, Nation.RUSSIA, 25, false, true, Province.Stp, "nc"),
    StpSC("St Petersburg(sc)", Geography.COASTAL, true, Nation.RUSSIA, 25, false, true, Province.Stp, "sc"),
    SpaNC("Spain(nc)", Geography.COASTAL, true, null, 15, false, true, Province.Spa, "nc"),
    SpaSC("Spain(sc)", Geography.COASTAL, true, null, 15, false, true, Province.Spa, "sc"),
    BulEC("Bulgaria(ec)", Geography.COASTAL, true, null, 4, false, true, Province.Bul, "ec"),
    BulSC("Bulgaria(sc)", Geography.COASTAL, true, null, 4, false, true, Province.Bul, "sc"),

    // The addition of the 'dummy' province Switzerland (`Province.Swi`), who borders nobody, is necessary because, for whatever reason, ...
    // ... the compiler hates the last entry in the `adjacencyMap`, and replaces its key with the null reference...
    // (It is also nice to simply have a dummy province value, for posterity)
    // (Future commits may choose to alter the identity of the dummy province, if (e.g.) a variant chooses to include a passable `Province.Swi` tile)
    Swi("Switzerland", false, false);


    public static final char SUFFIX_DELIM = '/';  // e.g. "Bul/ec"

    // `adjacencyMap` and `aliasesMap` autopopulate at runtime, once for every new Province constant
    private static Map<Province, Province[]>    adjacencyMap;
    private static Map<String, Province>        aliasesMap;  // TODO: Flesh out, add secondary names & common misspellings (in `populateAliasesMap()`)


    public final String     fullName;

    public boolean          supplyCenter;
    public Nation homeowner;

    public Geography        geography;

    public int              coastId;
    public CoastType        coastType;

    public Province         parent;  // Used for explicitly-split coasts (ATM)

    public String           suffix = "";  // e.g. "ec", blank by default


    // Full constructor - modern
    private Province(String fullName, Geography geography, boolean supplyCenter, Nation homeowner, int coastId, boolean canal, boolean splitCoast, Province parent) {

        populateAdjacencyMap();

        this.fullName = fullName;
        this.supplyCenter = supplyCenter;
        this.homeowner = homeowner;
        this.geography = geography;
        this.coastId = coastId;

        if (canal)
            coastType = CoastType.CANAL;
        else if (splitCoast)
            coastType = CoastType.SPLIT;
        else if (geography == Geography.COASTAL)
            coastType = CoastType.NORMAL;
        else
            coastType = CoastType.NONE;

        this.parent = parent;

        // Call `enforceStasis()` at the end of construction of all Province constants
        // --> (All other constructors call this one...)
        try {
            enforceStasis();
        } catch (IllegalStateException ex) {
            System.err.println(ex.toString());
        }

    }

    // Full constructor + suffix - modern
    private Province(String fullName, Geography geography, boolean supplyCenter, Nation homeowner, int coastId, boolean canal, boolean splitCoast, Province parent, String suffix) {

        this(fullName, geography, supplyCenter, homeowner, coastId, canal, splitCoast, parent);
        this.suffix = suffix;

    }

    // Mini-constructor + coast info - modern
    private Province(String fullName, Geography geography, boolean supplyCenter, Nation homeowner, int coastId, boolean canal, boolean splitCoast) {

        this(fullName, geography, supplyCenter, homeowner, coastId, canal, splitCoast, null);

    }

    // Mini-constructor - modern
    private Province(String fullName, Geography geography, boolean supplyCenter, Nation homeowner) {

        this(fullName, geography, supplyCenter, homeowner, -1, false, false, null);

    }


    // 3-bool constructor - deprecated
    private Province(String fullName, boolean coastal, boolean supplyCenter, boolean splitCoast) {  // Assumed land province, coastal (DETERMINISTIC)

        if (!coastal)
            throw new IllegalStateException("`Province.java`: Used coastal constructor for a non-coastal province");

        this(fullName, Geography.COASTAL, supplyCenter, null, -1, false, splitCoast, null);

    }

    // 2-bool constructor - deprecated
    private Province(String fullName, boolean coastal, boolean supplyCenter) {  // Assumed land province

        Geography geography;
        if (coastal)
            geography = Geography.COASTAL;
        else
            geography = Geography.INLAND;

        this(fullName, geography, supplyCenter, null, -1, false, false, null);

    }

    // 1-bool constructor - deprecated
    private Province(String fullName, boolean water) {  // Assumes no supply centers in water, DETERMINISTIC (value of `water` is irrelevant)

        if (!water)
            throw new IllegalStateException("`Province.java`: Used water constructor for a land province");

        this(fullName, Geography.WATER, false, null, -1, false, false, null);

    }


    /**
     * Determines whether 'this' Province is directly adjacent to another given Province
     * by consulting the static Adjacency Map
     *
     * @param pos1 'Other' province
     * @return Whether this Province is adjacent to Province `pos1`
     */
    public boolean isAdjacentTo(Province pos1) {

        return (Arrays.asList(adjacencyMap.get(this)).contains(pos1));

    }

    /**
     * Determines whether 'this' Province is adjacent to another given Province, <i>while ignoring <b>split coast rules</b></i><br>
     * The function is very lenient, and tries all combinations of the 2 Provinces & their parents<br><br>
     *
     * <b>NOTE:</b> Orders such as "F Tus S Tri - Ven" will still fail; only <i>split</i> coast rules are bypassed. e.g "F BOT S BAR - Stp/nc"<br><br>
     * <b>NOTE:</b> However, orders such as "F Spa/nc S LYO" will fail, but moves like "F LYO S Spa/nc" will succeed -- in this regard, preference is given to `pos1` over `this`
     *
     * @param pos1 'Other' Province
     * @return Whether this Province is adjacent to Province `pos1`, <i>ignoring split coast rules!!</i>
     */
    public boolean isAdjacentToIgnoreSplitCoast(Province pos1) {

        boolean genericAdjacency = this.isAdjacentTo(pos1);

        // If generic adjacency function passes (the coast(s) itself are adjacent), this func should pass immediately
        if (genericAdjacency)
            return true;

        if (this.coastType == CoastType.SPLIT && pos1.coastType == CoastType.SPLIT) {  // Both are split coasts (rare)

            if (this.parent != null && pos1.parent != null)
                return this.parent.isAdjacentTo(pos1.parent);
            else if (this.parent != null || pos1.parent != null)
                return this.parent.isAdjacentTo(pos1) || this.isAdjacentTo(pos1.parent);
            else
                return genericAdjacency;

        /*} else if (this.coastType == CoastType.SPLIT) {  // -> [`pos1.coastType` != SPLIT]

            // We omit this block b/c any relation from a unit in Spa/nc -> LYO is invalid, ...
            // ... but not necessarily the other way around. (i.e. default to generic adjacency)

            if (this.parent != null)
                return this.parent.isAdjacentTo(pos1);
            else
                return genericAdjacency;

        */

        } else if (pos1.coastType == CoastType.SPLIT) {  // -> [`this.coastType` != SPLIT]

            if (pos1.parent != null)
                return this.isAdjacentTo(pos1.parent);
            else
                return genericAdjacency;

        } else {

            // There are no split coasts involved, so just ret to generic adjacency function
            // --> (Should be `false`...)
            return genericAdjacency;

        }

    }

    public static boolean adjacentBySea(Province pos0, Province pos1) {

        // e.g. Mar & Spa/nc are considered "adjacent by sea"
        // This behavior results from calling `IATIgnoreSplitCoast(...)` instead of base `IAT`
        boolean genericAdjacency = pos0.isAdjacentToIgnoreSplitCoast(pos1);

        // Cannot be adjacent by sea if not also adjacent by land
        if (!genericAdjacency)
            return false;
        // Use generic adjacency (now `true`) if no coast-crawling is required
        else if (pos0.geography != Geography.COASTAL || pos1.geography != Geography.COASTAL)
            return true;
        else  // Handle coast-crawling -- coast IDs must be adjacent to sea neighbors
            return Math.abs(pos0.coastId - pos1.coastId) == 1;

    }

    public static boolean equalsIgnoreCoast(Province pos0, Province pos1) {

        if (pos0 == pos1)
            return true;

        if (pos0 == null || pos1 == null)
            return false;

        if (pos0.parent != null && pos1.parent != null)
            return pos0.parent == pos1.parent;
        else if (pos0.parent != null)
            return pos0.parent == pos1;
        else if (pos1.parent != null)
            return pos0 == pos1.parent;
        else
            return false;

    }


    public static Map<String, Province> populateAliasesMap() {

        aliasesMap = new HashMap<>();

        for (Province province : Province.values())
            aliasesMap.put(province.fullName, province);

        return aliasesMap;

    }

    private static Map<Province, Province[]> populateAdjacencyMap() {

        adjacencyMap = new HashMap<>();

        adjacencyMap.put(Province.Boh, new Province[]{Province.Mun, Province.Sil, Province.Gal, Province.Tyr, Province.Vie});
        adjacencyMap.put(Province.Bud, new Province[]{Province.Vie, Province.Gal, Province.Tri, Province.Ser, Province.Rum});
        adjacencyMap.put(Province.Gal, new Province[]{Province.Sil, Province.Boh, Province.Vie, Province.Bud, Province.Rum, Province.Ukr, Province.War});
        adjacencyMap.put(Province.Tri, new Province[]{Province.Tyr, Province.Ven, Province.ADR, Province.Alb, Province.Ser, Province.Bud, Province.Vie});
        adjacencyMap.put(Province.Tyr, new Province[]{Province.Mun, Province.Boh, Province.Vie, Province.Tri, Province.Ven, Province.Pie});
        adjacencyMap.put(Province.Vie, new Province[]{Province.Tyr, Province.Boh, Province.Gal, Province.Bud, Province.Tri});
        adjacencyMap.put(Province.Cly, new Province[]{Province.NAO, Province.NWG, Province.Edi, Province.Lvp});
        adjacencyMap.put(Province.Edi, new Province[]{Province.Cly, Province.NWG, Province.NTH, Province.Yor, Province.Lvp});
        adjacencyMap.put(Province.Lvp, new Province[]{Province.NAO, Province.Cly, Province.Edi, Province.Yor, Province.Wal, Province.IRI});
        adjacencyMap.put(Province.Lon, new Province[]{Province.Wal, Province.Yor, Province.NTH, Province.ENG});
        adjacencyMap.put(Province.Wal, new Province[]{Province.Lvp, Province.Yor, Province.Lon, Province.ENG, Province.IRI});
        adjacencyMap.put(Province.Yor, new Province[]{Province.Edi, Province.NTH, Province.Lon, Province.Wal, Province.Lvp});
        adjacencyMap.put(Province.Bre, new Province[]{Province.ENG, Province.Pic, Province.Par, Province.Gas, Province.MAO});
        adjacencyMap.put(Province.Bur, new Province[]{Province.Bel, Province.Ruh, Province.Mun, Province.Mar, Province.Gas, Province.Par, Province.Pic});
        adjacencyMap.put(Province.Gas, new Province[]{Province.Bre, Province.Par, Province.Bur, Province.Mar, Province.Spa, Province.SpaNC, Province.MAO});
        adjacencyMap.put(Province.Mar, new Province[]{Province.Bur, Province.Pie, Province.LYO, Province.Spa, Province.SpaSC, Province.Gas});
        adjacencyMap.put(Province.Par, new Province[]{Province.Bre, Province.Pic, Province.Bur, Province.Gas});
        adjacencyMap.put(Province.Pic, new Province[]{Province.ENG, Province.Bel, Province.Bur, Province.Par, Province.Bre});
        adjacencyMap.put(Province.Ber, new Province[]{Province.BAL, Province.Pru, Province.Sil, Province.Mun, Province.Kie});
        adjacencyMap.put(Province.Kie, new Province[]{Province.Den, Province.BAL, Province.Ber, Province.Mun, Province.Ruh, Province.Hol, Province.HEL});
        adjacencyMap.put(Province.Mun, new Province[]{Province.Kie, Province.Ber, Province.Sil, Province.Boh, Province.Tyr, Province.Bur, Province.Ruh});
        adjacencyMap.put(Province.Pru, new Province[]{Province.BAL, Province.Lvn, Province.War, Province.Sil, Province.Ber});
        adjacencyMap.put(Province.Ruh, new Province[]{Province.Hol, Province.Kie, Province.Mun, Province.Bur, Province.Bel});
        adjacencyMap.put(Province.Sil, new Province[]{Province.Ber, Province.Pru, Province.War, Province.Gal, Province.Boh, Province.Mun});
        adjacencyMap.put(Province.Apu, new Province[]{Province.ADR, Province.ION, Province.Nap, Province.Rom, Province.Ven});
        adjacencyMap.put(Province.Nap, new Province[]{Province.Apu, Province.ION, Province.TYS, Province.Rom});
        adjacencyMap.put(Province.Pie, new Province[]{Province.Tyr, Province.Ven, Province.Tus, Province.LYO, Province.Mar});
        adjacencyMap.put(Province.Rom, new Province[]{Province.Ven, Province.Apu, Province.Nap, Province.TYS, Province.Tus});
        adjacencyMap.put(Province.Tus, new Province[]{Province.Ven, Province.Rom, Province.TYS, Province.LYO, Province.Pie});
        adjacencyMap.put(Province.Ven, new Province[]{Province.Tyr, Province.Tri, Province.ADR, Province.Apu, Province.Rom, Province.Tus, Province.Pie});
        adjacencyMap.put(Province.Lvn, new Province[]{Province.BOT, Province.Stp, Province.StpSC, Province.Mos, Province.War, Province.Pru, Province.BAL});
        adjacencyMap.put(Province.Mos, new Province[]{Province.Stp, Province.Sev, Province.Ukr, Province.War, Province.Lvn});
        adjacencyMap.put(Province.Sev, new Province[]{Province.Mos, Province.Arm, Province.BLA, Province.Rum, Province.Ukr});
        adjacencyMap.put(Province.Stp, new Province[]{Province.BAR, Province.Mos, Province.Lvn, Province.BOT, Province.Fin, Province.Nwy});
        adjacencyMap.put(Province.Ukr, new Province[]{Province.Mos, Province.Sev, Province.Rum, Province.Gal, Province.War});
        adjacencyMap.put(Province.War, new Province[]{Province.Lvn, Province.Mos, Province.Ukr, Province.Gal, Province.Sil, Province.Pru});
        adjacencyMap.put(Province.Ank, new Province[]{Province.BLA, Province.Arm, Province.Smy, Province.Con});
        adjacencyMap.put(Province.Arm, new Province[]{Province.Sev, Province.Syr, Province.Smy, Province.Ank, Province.BLA});
        adjacencyMap.put(Province.Con, new Province[]{Province.BLA, Province.Ank, Province.Smy, Province.AEG, Province.Bul, Province.BulEC, Province.BulSC});
        adjacencyMap.put(Province.Smy, new Province[]{Province.Ank, Province.Arm, Province.Syr, Province.EAS, Province.AEG, Province.Con});
        adjacencyMap.put(Province.Syr, new Province[]{Province.Arm, Province.EAS, Province.Smy});
        adjacencyMap.put(Province.Alb, new Province[]{Province.Tri, Province.Ser, Province.Gre, Province.ION, Province.ADR});
        adjacencyMap.put(Province.Bel, new Province[]{Province.NTH, Province.Hol, Province.Ruh, Province.Bur, Province.Pic, Province.ENG});
        adjacencyMap.put(Province.Bul, new Province[]{Province.Rum, Province.BLA, Province.Con, Province.AEG, Province.Gre, Province.Ser});
        adjacencyMap.put(Province.Fin, new Province[]{Province.Nwy, Province.Stp, Province.StpSC, Province.BOT, Province.Swe});
        adjacencyMap.put(Province.Gre, new Province[]{Province.Ser, Province.Bul, Province.BulSC, Province.AEG, Province.ION, Province.Alb});
        adjacencyMap.put(Province.Hol, new Province[]{Province.NTH, Province.HEL, Province.Kie, Province.Ruh, Province.Bel});
        adjacencyMap.put(Province.Nwy, new Province[]{Province.NWG, Province.BAR, Province.Stp, Province.StpNC, Province.Fin, Province.Swe, Province.SKA, Province.NTH});
        adjacencyMap.put(Province.Naf, new Province[]{Province.WES, Province.Tun, Province.MAO});
        adjacencyMap.put(Province.Por, new Province[]{Province.Spa, Province.SpaNC, Province.SpaSC, Province.MAO});
        adjacencyMap.put(Province.Rum, new Province[]{Province.Ukr, Province.Sev, Province.BLA, Province.Bul, Province.BulEC, Province.Ser, Province.Bud, Province.Gal});
        adjacencyMap.put(Province.Ser, new Province[]{Province.Bud, Province.Rum, Province.Bul, Province.Gre, Province.Alb, Province.Tri});
        adjacencyMap.put(Province.Spa, new Province[]{Province.Gas, Province.Mar, Province.LYO, Province.WES, Province.MAO, Province.Por});
        adjacencyMap.put(Province.Swe, new Province[]{Province.Nwy, Province.Fin, Province.BOT, Province.BAL, Province.Den, Province.SKA});
        adjacencyMap.put(Province.Tun, new Province[]{Province.TYS, Province.ION, Province.Naf, Province.WES});
        adjacencyMap.put(Province.Den, new Province[]{Province.SKA, Province.Swe, Province.BAL, Province.Kie, Province.HEL, Province.NTH});
        adjacencyMap.put(Province.ADR, new Province[]{Province.Tri, Province.Alb, Province.ION, Province.Apu, Province.Ven});
        adjacencyMap.put(Province.AEG, new Province[]{Province.Bul, Province.BulSC, Province.Con, Province.Smy, Province.EAS, Province.ION, Province.Gre});
        adjacencyMap.put(Province.BAL, new Province[]{Province.Swe, Province.BOT, Province.Lvn, Province.Pru, Province.Ber, Province.Kie, Province.Den});
        adjacencyMap.put(Province.BAR, new Province[]{Province.NWG, Province.Nwy, Province.Stp, Province.StpNC});
        adjacencyMap.put(Province.BLA, new Province[]{Province.Sev, Province.Arm, Province.Ank, Province.Con, Province.Bul, Province.BulEC, Province.Rum});
        adjacencyMap.put(Province.EAS, new Province[]{Province.Smy, Province.Syr, Province.ION, Province.AEG});
        adjacencyMap.put(Province.ENG, new Province[]{Province.Lon, Province.NTH, Province.Bel, Province.Pic, Province.Bre, Province.MAO, Province.IRI, Province.Wal});
        adjacencyMap.put(Province.BOT, new Province[]{Province.Fin, Province.Stp, Province.StpSC, Province.Lvn, Province.BAL, Province.Swe});
        adjacencyMap.put(Province.LYO, new Province[]{Province.Mar, Province.Pie, Province.Tus, Province.TYS, Province.WES, Province.Spa, Province.SpaSC});
        adjacencyMap.put(Province.HEL, new Province[]{Province.NTH, Province.Den, Province.Kie, Province.Hol});
        adjacencyMap.put(Province.ION, new Province[]{Province.ADR, Province.Alb, Province.Gre, Province.AEG, Province.EAS, Province.Tun, Province.TYS, Province.Nap, Province.Apu});
        adjacencyMap.put(Province.IRI, new Province[]{Province.NAO, Province.Lvp, Province.Wal, Province.ENG, Province.MAO});
        adjacencyMap.put(Province.MAO, new Province[]{Province.NAO, Province.IRI, Province.ENG, Province.Bre, Province.Gas, Province.Spa, Province.SpaNC, Province.SpaSC, Province.Por, Province.WES, Province.Naf});
        adjacencyMap.put(Province.NAO, new Province[]{Province.NWG, Province.Cly, Province.Lvp, Province.IRI, Province.MAO});
        adjacencyMap.put(Province.NTH, new Province[]{Province.NWG, Province.Nwy, Province.SKA, Province.Den, Province.HEL, Province.Hol, Province.Bel, Province.ENG, Province.Lon, Province.Yor, Province.Edi});
        adjacencyMap.put(Province.NWG, new Province[]{Province.BAR, Province.Nwy, Province.NTH, Province.Edi, Province.Cly, Province.NAO});
        adjacencyMap.put(Province.SKA, new Province[]{Province.Nwy, Province.Swe, Province.Den, Province.NTH});
        adjacencyMap.put(Province.TYS, new Province[]{Province.Tus, Province.Rom, Province.Nap, Province.ION, Province.Tun, Province.WES, Province.LYO});
        adjacencyMap.put(Province.WES, new Province[]{Province.LYO, Province.TYS, Province.Tun, Province.Naf, Province.Spa, Province.SpaSC, Province.MAO});
        adjacencyMap.put(Province.StpNC, new Province[]{Province.BAR, Province.Nwy});
        adjacencyMap.put(Province.StpSC, new Province[]{Province.Fin, Province.Lvn, Province.BOT});
        adjacencyMap.put(Province.SpaNC, new Province[]{Province.MAO, Province.Gas, Province.Por});
        adjacencyMap.put(Province.SpaSC, new Province[]{Province.MAO, Province.Mar, Province.LYO, Province.WES, Province.Por});
        adjacencyMap.put(Province.BulEC, new Province[]{Province.Rum, Province.BLA, Province.Con});
        adjacencyMap.put(Province.BulSC, new Province[]{Province.Con, Province.AEG, Province.Gre});

        // The addition of Switzerland (`Province.Swi`) is necessary because, for whatever reason, ...
        // ... the compiler hates the last entry in the map, and replaces its key with the null reference...
        // (See the definition of `Province.Swi` for more info)
        adjacencyMap.put(Province.Swi, new Province[]{});

        return adjacencyMap;

    }


    //@Override
    private void enforceStasis() throws IllegalStateException {

        if (this.homeowner != null)
            this.supplyCenter = true;

        if (this.geography != Geography.COASTAL) {
            this.coastId = -1;
            this.coastType = CoastType.NONE;
            // Will need to remove the below line if we ever expand on the idea of Province hierarchy, beyond just split coasts
            this.parent = null;
        }

        if (this.geography == Geography.WATER) {  // No SCs in Water (ATM), water cannot be owned (ATM)
            this.supplyCenter = false;
            this.homeowner = null;
        }

        if (this.parent != null) {  // Province is split-Coastal
            //this.geography = Geography.COASTAL; (redundant)
            // Will need to remove this block if we ever expand on the idea of Province hierarchy, beyond just split coasts
            this.coastType = CoastType.SPLIT;
        }

        if (this.coastId == -1 && this.coastType == CoastType.NORMAL)  // Province is Coastal
            throw new IllegalStateException(String.format(
                    "`%s.%s:enforceStasis()`: CoastType is %s but coastId is %d, can/will lead to adjacency calculation issues",
                    this.getClass().getSimpleName(), this.toString(), this.coastType, this.coastId)
            );

    }

    public static Map<Province, Province[]> getAdjacencyMapCopy() {
        return new HashMap<>(adjacencyMap);
    }


    public static Province canonical(Province p) {
        return ( p.parent == null
                  ? p  // return p if non-split coast
                  : p.parent);  // return `p.parent` if split-coast
    }


    @Override
    public String toString() {

        if (suffix.isEmpty() && this.parent == null)
            return this.name();
        else if (suffix.isEmpty())  // Parent is non-null
            return this.parent.name();  // Does not consider the parent's suffix
        else if (this.parent != null)  // Suffix is non-empty
            return (this.parent.name() + SUFFIX_DELIM + this.suffix);
        else  // Parent is null, suffix is non-empty
            return (this.name() + SUFFIX_DELIM + this.suffix);

    }


}
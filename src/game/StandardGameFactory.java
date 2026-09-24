package game;

import domain.Nation;
import domain.Province;
import domain.UnitType;
import phase.UnitId;

import java.util.LinkedHashMap;
import java.util.Map;


/**
 * Creates standard Diplomacy games using the Spring 1901 starting position.
 */
public final class StandardGameFactory {


    private StandardGameFactory() {
    }


    /**
     * Creates the standard board at the beginning of Spring 1901 movement.
     */
    public static Game create1901() {

        Map<UnitId, Province> units = new LinkedHashMap<>();

        // Austria
        addUnit(units, Nation.AUSTRIA, UnitType.ARMY, Province.Bud);
        addUnit(units, Nation.AUSTRIA, UnitType.ARMY, Province.Vie);
        addUnit(units, Nation.AUSTRIA, UnitType.FLEET, Province.Tri);

        // England
        addUnit(units, Nation.ENGLAND, UnitType.FLEET, Province.Edi);
        addUnit(units, Nation.ENGLAND, UnitType.FLEET, Province.Lon);
        addUnit(units, Nation.ENGLAND, UnitType.ARMY, Province.Lvp);

        // France
        addUnit(units, Nation.FRANCE, UnitType.FLEET, Province.Bre);
        addUnit(units, Nation.FRANCE, UnitType.ARMY, Province.Par);
        addUnit(units, Nation.FRANCE, UnitType.ARMY, Province.Mar);

        // Germany
        addUnit(units, Nation.GERMANY, UnitType.FLEET, Province.Kie);
        addUnit(units, Nation.GERMANY, UnitType.ARMY, Province.Ber);
        addUnit(units, Nation.GERMANY, UnitType.ARMY, Province.Mun);

        // Italy
        addUnit(units, Nation.ITALY, UnitType.FLEET, Province.Nap);
        addUnit(units, Nation.ITALY, UnitType.ARMY, Province.Rom);
        addUnit(units, Nation.ITALY, UnitType.ARMY, Province.Ven);

        // Russia
        addUnit(units, Nation.RUSSIA, UnitType.FLEET, Province.StpSC);
        addUnit(units, Nation.RUSSIA, UnitType.ARMY, Province.Mos);
        addUnit(units, Nation.RUSSIA, UnitType.ARMY, Province.War);
        addUnit(units, Nation.RUSSIA, UnitType.FLEET, Province.Sev);

        // Turkey
        addUnit(units, Nation.TURKEY, UnitType.FLEET, Province.Ank);
        addUnit(units, Nation.TURKEY, UnitType.ARMY, Province.Con);
        addUnit(units, Nation.TURKEY, UnitType.ARMY, Province.Smy);

        Map<Province, Nation> supplyCenterOwners =
                new LinkedHashMap<>();

        addSupplyCenters(
                supplyCenterOwners,
                Nation.AUSTRIA,
                Province.Bud,
                Province.Tri,
                Province.Vie
        );

        addSupplyCenters(
                supplyCenterOwners,
                Nation.ENGLAND,
                Province.Edi,
                Province.Lon,
                Province.Lvp
        );

        addSupplyCenters(
                supplyCenterOwners,
                Nation.FRANCE,
                Province.Bre,
                Province.Mar,
                Province.Par
        );

        addSupplyCenters(
                supplyCenterOwners,
                Nation.GERMANY,
                Province.Ber,
                Province.Kie,
                Province.Mun
        );

        addSupplyCenters(
                supplyCenterOwners,
                Nation.ITALY,
                Province.Nap,
                Province.Rom,
                Province.Ven
        );

        addSupplyCenters(
                supplyCenterOwners,
                Nation.RUSSIA,
                Province.Mos,
                Province.Sev,
                Province.Stp,
                Province.War
        );

        addSupplyCenters(
                supplyCenterOwners,
                Nation.TURKEY,
                Province.Ank,
                Province.Con,
                Province.Smy
        );

        BoardState initialBoard = new BoardState(
                units,
                supplyCenterOwners
        );

        return new Game(1901, initialBoard);

    }

    @SuppressWarnings("deprecation")
    private static void addUnit(
            Map<UnitId, Province> units,
            Nation nation,
            UnitType unitType,
            Province location
    ) {
        /*
         * The compatibility constructor gives each standard starting unit a
         * stable deterministic identity. Winter-built units use UUIDs through
         * UnitId.newlyBuilt(...).
         */
        UnitId unit = new UnitId(
                nation,
                unitType,
                location
        );
        units.put(unit, location);
    }

    private static void addSupplyCenters(
            Map<Province, Nation> owners,
            Nation nation,
            Province... provinces
    ) {
        for (Province province : provinces) {
            owners.put(province, nation);
        }
    }

}
package parsing;

import adjudication.Order;
import domain.*;
import testing.AdjudicatorTestCase;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses Orders of the format found at the
 * <a href="https://webdiplomacy.net/doc/DATC_v3_0.html">DATC page</a> hosted on WebDip
 */
public class DATCParser implements TestCaseParser {

    public static final String TC_PREFIX = DATCParser.class.getName();

    protected int TC_ID = 1;


    public AdjudicatorTestCase parse(String source) {

        List<Order> orders = new ArrayList<>();

        String[] sourceLines = source.lines().toArray(String[]::new);
        Nation nation = null;
        for (String line : sourceLines) {

            if (line.isBlank())  // Blank line
                continue;

            line = line.strip();

            if (line.contains(":"))  // Line with a country name + colon char :: CHANGE NATION
                nation = Nation.valueOf(line.split(":")[0].toUpperCase());
            else { // Line with an elongated order descriptor :: PARSE ORDER

                String[] descriptorParts = line.split(" ");

                // Parse UNIT TYPE
                UnitType unitType = UnitType.valueOfMarker(descriptorParts[0].charAt(0));

                // Parse ORIGIN (pos0)
                Province origin;
                Map<String, Province> namesToAbbrsMap = Province.populateAliasesMap();
                String originStr;
                byte extraIndex = 0;
                if (namesToAbbrsMap.containsKey(descriptorParts[1]))  // e.g. "Clyde"
                    originStr = descriptorParts[1];
                else if (namesToAbbrsMap.containsKey(descriptorParts[1]+" "+descriptorParts[2])) {  // e.g. "Mid-Atlantic Ocean", "St Petersburg(nc)"
                    originStr = descriptorParts[1] + " " + descriptorParts[2];
                    extraIndex++;
                } else {  // e.g. "North Atlantic Ocean"
                    originStr = String.format("%s %s %s", descriptorParts[1], descriptorParts[2], descriptorParts[3]);
                    extraIndex += 2;
                }
                origin = Province.valueOf(namesToAbbrsMap.get(originStr).name());

                // Parse ORDER TYPE
                OrderType orderType;
                String orderTypeStr = descriptorParts[2+extraIndex];
                switch (orderTypeStr.charAt(0)) {
                    case '-' ->
                        orderType = OrderType.MOVE;
                    case 'S' ->
                        orderType = OrderType.SUPPORT;
                    case 'H' ->
                        orderType = OrderType.HOLD;
                    case 'C' ->
                        orderType = OrderType.CONVOY;
                    default ->
                        orderType = null;
                }

                // Parse pos1 and pos2
                Province pos1, pos2;
                if (orderType == OrderType.SUPPORT || orderType == OrderType.CONVOY) {
                    // Set pos1 (non-null), and pos2 (could be null)
                    boolean containsMove = line.split(orderType.name().substring(1).toLowerCase())[1].contains(" - ");
                    StringBuilder pos1Str = new StringBuilder();
                    if (!containsMove) {  // SUPPORT TO HOLD
                        for (int i = 4+extraIndex; i < descriptorParts.length; i++) {
                            pos1Str.append(descriptorParts[i]);
                            if (i != descriptorParts.length-1)
                                pos1Str.append(" ");
                        }
                        pos1 = Province.valueOf(namesToAbbrsMap.get(pos1Str.toString()).name());
                        pos2 = null;
                    } else {  // CONVOY / SUPPORT TO MOVE
                        int i = 4+extraIndex;
                        while (descriptorParts[i].charAt(0) != '-')
                            pos1Str.append(descriptorParts[i++]).append(" ");
                        StringBuilder pos2Str = new StringBuilder();
                        for (i = i+1; i < descriptorParts.length; i++) {
                            pos2Str.append(descriptorParts[i]);
                            if (i != descriptorParts.length-1)
                                pos2Str.append(" ");
                        }
                        pos1 = Province.valueOf(namesToAbbrsMap.get(pos1Str.toString().strip()).name());
                        pos2 = Province.valueOf(namesToAbbrsMap.get(pos2Str.toString().strip()).name());
                    }
                } else if (orderType == OrderType.MOVE) {
                    StringBuilder pos1Str = new StringBuilder();
                    for (int i = 3+extraIndex; i < descriptorParts.length; i++) {
                        pos1Str.append(descriptorParts[i]);
                        if (i != descriptorParts.length-1)
                            pos1Str.append(" ");
                    }
                    pos1 = Province.valueOf(namesToAbbrsMap.get(pos1Str.toString()).name());
                    pos2 = null;
                } else /*if (orderType == OrderType.HOLD)*/ {
                    pos1 = null;
                    pos2 = null;
                }

                orders.add(new Order(nation, unitType, origin, orderType, pos1, pos2));

            }

        }

        return new AdjudicatorTestCase(String.format("%s_%04d", TC_PREFIX, this.TC_ID++), orders);

    }

}

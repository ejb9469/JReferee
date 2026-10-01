package analysis;

import domain.Nation;
import io.catalog.SourceFingerprints;

import java.util.Objects;


public abstract class Identifiers {

    private static final String OPENING_CONTEXT =
            "standard\n1901-SPRING_MOVEMENT\n";


    private Identifiers() {  }


    public static String opening(Nation nation, String signature) {

        Objects.requireNonNull(nation, "nation");
        Objects.requireNonNull(signature, "signature");

        String scope = nation.name();
        String content = "opening-v1\n" + OPENING_CONTEXT + scope + "\n" + signature;

        return "OP1-" + scope + "-" + SourceFingerprints.sha256Hex(content);

    }

    public static String combination(String signature) {

        Objects.requireNonNull(signature, "signature");

        String content = "combination-v1\n" + OPENING_CONTEXT + "ALL\n" + signature;

        return "OC1-ALL-" + SourceFingerprints.sha256Hex(content);

    }

}
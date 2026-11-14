package agents.execution;

import agents.policy.GruMovementPolicy;
import agents.policy.LinearMovementPolicy;
import agents.policy.MovementPolicy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Immutable movement models shared between decisions.<br><br>
 *
 * The frozen human anchor is independent of the proposal policy.
 * Prepared positions belong to individual generation calls, not this class.
 */
public final class AgentModels {


    // Models and identities \\

    private final MovementPolicy            anchor;
    private final Optional<MovementPolicy>  proposal;
    private final Map<String, String>       identities;


    // Constructors \\

    /**
     * Accepts immutable caller-supplied policies.
     * These policies have no verified artifact identities.
     */
    public AgentModels(
            MovementPolicy anchor,
            Optional<MovementPolicy> proposal
    ) {
        this(anchor, proposal, Map.of());
    }

    private AgentModels(
            MovementPolicy anchor,
            Optional<MovementPolicy> proposal,
            Map<String, String> identities
    ) {
        this.anchor = Objects.requireNonNull(anchor, "anchor");
        this.proposal = Objects.requireNonNull(proposal, "proposal");
        this.identities = Collections.unmodifiableMap(
                new TreeMap<>(identities));
    }


    // Loading \\

    /**
     * Loads a frozen linear anchor and an optional Java GRU export once.
     * Model readers validate their existing schemas and checksums.
     */
    public static AgentModels load(
            Path anchorPath,
            Optional<Path> gruPath
    ) throws IOException {

        Objects.requireNonNull(anchorPath, "anchorPath");
        Objects.requireNonNull(gruPath, "gruPath");

        String anchorHash = hash(anchorPath);
        LinearMovementPolicy anchor = LinearMovementPolicy.load(anchorPath);

        if (!anchorHash.equals(hash(anchorPath)))
            throw new IOException(
                    "Anchor model changed while loading: " + anchorPath);

        Map<String, String> identities = new TreeMap<>();
        identities.put("anchor", anchorHash);

        Optional<MovementPolicy> proposal = Optional.empty();

        if (gruPath.isPresent()) {

            GruMovementPolicy gru = GruMovementPolicy.load(
                    gruPath.orElseThrow());

            identities.put("gru", gru.sha256());
            identities.put("checkpoint", gru.checkpointSha256());
            identities.put("dataset", gru.datasetIndexSha256());

            proposal = Optional.of(gru);

        }

        return new AgentModels(anchor, proposal, identities);

    }

    private static String hash(Path path) throws IOException {

        try (DigestInputStream input = new DigestInputStream(
                Files.newInputStream(path),
                MessageDigest.getInstance("SHA-256")
        )) {

            input.transferTo(OutputStream.nullOutputStream());

            return HexFormat.of().formatHex(
                    input.getMessageDigest().digest());

        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }

    }


    // Accessors \\

    public MovementPolicy anchor() {
        return this.anchor;
    }

    public Optional<MovementPolicy> proposal() {
        return this.proposal;
    }

    public Map<String, String> identities() {
        return this.identities;
    }

    @Override
    public String toString() {

        if (this.identities.isEmpty())
            return "caller-supplied movement policies (unverified identity)";

        return this.identities.toString();

    }

}
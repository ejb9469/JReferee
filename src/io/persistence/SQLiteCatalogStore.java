package io.persistence;

import io.catalog.CatalogAnalysis;
import io.catalog.CatalogGame;
import io.catalog.CatalogGameId;
import io.catalog.CatalogMetadata;
import io.catalog.CatalogStore;
import io.catalog.GameSource;
import io.catalog.GameSourceReference;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;


/**
 * SQLite-backed persistent {@link CatalogStore}.
 *
 * <p>One catalog entry is uniquely identified by its local UUID and by its
 * provider source identity: {@code source_type + external_key}. DiploBN games
 * therefore remain deduplicated by their extracted GameID rather than by the
 * raw URL entered into a manifest.</p>
 *
 * <p>This store retains complete canonical source payloads, local metadata,
 * ordered tags, and the most recently recorded adjudication analysis.</p>
 */
public class SQLiteCatalogStore
        implements CatalogStore {


    private static final String SQLITE_DRIVER =
            "org.sqlite.JDBC";


    private final Path databasePath;
    private final Connection connection;

    private boolean closed;


    /**
     * Opens or creates a SQLite catalog database at the given file path.
     */
    public SQLiteCatalogStore(
            Path databasePath
    ) {

        this.databasePath = Objects.requireNonNull(
                databasePath,
                "databasePath").toAbsolutePath().normalize();

        prepareDatabaseDirectory();

        try {
            Class.forName(SQLITE_DRIVER);

            this.connection = DriverManager.getConnection(
                    "jdbc:sqlite:" + this.databasePath);

            this.closed = false;

            initializeSchema();

        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException(
                    "SQLite JDBC driver was not found. "
                            + "Add sqlite-jdbc to the project lib.",
                    exception);
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Unable to open catalog database: "
                            + this.databasePath,
                    exception);
        }

    }


    @Override
    public CatalogGame upsert(
            CatalogGame game
    ) {

        requireOpen();

        Objects.requireNonNull(game, "game");

        boolean originalAutoCommit = autoCommit();

        try {
            connection.setAutoCommit(false);

            upsertGame(game);
            replaceTags(game);

            connection.commit();

            return game;

        } catch (SQLException exception) {
            rollback();

            throw new IllegalStateException(
                    "Unable to save catalog game: "
                            + game.id(),
                    exception);
        } finally {
            restoreAutoCommit(originalAutoCommit);
        }

    }

    @Override
    public Optional<CatalogGame> findById(
            CatalogGameId id
    ) {

        requireOpen();

        Objects.requireNonNull(id, "id");

        String sql = """
                SELECT *
                FROM catalog_game
                WHERE catalog_id = ?
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql))
        {
            statement.setString(
                    1,
                    id.value().toString());

            try (ResultSet result = statement.executeQuery()) {
                if (!result.next())
                    return Optional.empty();

                return Optional.of(readGame(result));
            }

        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Unable to find catalog game: " + id,
                    exception);
        }

    }

    @Override
    public Optional<CatalogGame> findBySource(
            GameSource source,
            String externalKey
    ) {

        requireOpen();

        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(externalKey, "externalKey");

        String sql = """
                SELECT *
                FROM catalog_game
                WHERE source_type = ?
                AND external_key = ?
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql))
        {
            statement.setString(
                    1,
                    source.name());

            statement.setString(
                    2,
                    externalKey);

            try (ResultSet result = statement.executeQuery()) {
                if (!result.next())
                    return Optional.empty();

                return Optional.of(readGame(result));
            }

        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Unable to find catalog source: "
                            + source
                            + "/"
                            + externalKey,
                    exception);
        }

    }

    @Override
    public List<CatalogGame> findAll() {

        requireOpen();

        String sql = """
                SELECT *
                FROM catalog_game
                ORDER BY first_imported_at,
                catalog_id
                """;

        return queryGames(sql);

    }

    @Override
    public List<CatalogGame> findByTag(
            String tag
    ) {

        requireOpen();

        Objects.requireNonNull(tag, "tag");

        String sql = """
                SELECT game.*
                FROM catalog_game AS game
                INNER JOIN catalog_tag AS tag
                ON tag.catalog_id = game.catalog_id
                WHERE tag.tag = ?
                ORDER BY game.first_imported_at,
                game.catalog_id
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql))
        {
            statement.setString(1, tag);

            return queryGames(statement);

        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Unable to find catalog games with tag: "
                            + tag,
                    exception);
        }

    }

    @Override
    public List<CatalogGame> findWithDefiniteMismatches() {

        requireOpen();

        String sql = """
                SELECT *
                FROM catalog_game
                WHERE definite_mismatch_count > 0
                ORDER BY definite_mismatch_count DESC,
                first_imported_at,
                catalog_id
                """;

        return queryGames(sql);

    }

    @Override
    public void delete(
            CatalogGameId id
    ) {

        requireOpen();

        Objects.requireNonNull(id, "id");

        String sql = """
                DELETE FROM catalog_game
                WHERE catalog_id = ?
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql))
        {
            statement.setString(
                    1,
                    id.value().toString());

            statement.executeUpdate();

        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Unable to delete catalog game: " + id,
                    exception);
        }

    }

    @Override
    public void close() {

        if (closed)
            return;

        try {
            connection.close();
            closed = true;
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Unable to close catalog database: "
                            + databasePath,
                    exception);
        }

    }


    private void prepareDatabaseDirectory() {

        Path parent = databasePath.getParent();

        if (parent == null)
            return;

        try {
            Files.createDirectories(parent);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to create catalog directory: "
                            + parent,
                    exception);
        }

    }

    private void initializeSchema()
            throws SQLException {

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS catalog_game (
                        catalog_id TEXT PRIMARY KEY,
                        source_type TEXT NOT NULL,
                        external_key TEXT NOT NULL,
                        canonical_uri TEXT NOT NULL,
                        submitted_uri TEXT,

                        title TEXT,
                        competition TEXT,
                        notes TEXT,

                        source_payload TEXT NOT NULL,
                        source_payload_sha256 TEXT NOT NULL,
                        source_phase_count INTEGER NOT NULL,

                        analyzer TEXT,
                        analyzer_revision TEXT,
                        analyzed_at TEXT,
                        compared_order_count INTEGER,
                        matching_order_count INTEGER,
                        compatibility_difference_count INTEGER,
                        definite_mismatch_count INTEGER,

                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        first_imported_at TEXT NOT NULL,
                        last_refreshed_at TEXT NOT NULL,

                        UNIQUE (source_type, external_key)
                    )
                    """);

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS catalog_tag (
                        catalog_id TEXT NOT NULL,
                        tag TEXT NOT NULL,
                        tag_order INTEGER NOT NULL,

                        PRIMARY KEY (catalog_id, tag),

                        FOREIGN KEY (catalog_id)
                        REFERENCES catalog_game(catalog_id)
                        ON DELETE CASCADE
                    )
                    """);

            statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS
                    idx_catalog_game_source
                    ON catalog_game(source_type, external_key)
                    """);

            statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS
                    idx_catalog_tag_name
                    ON catalog_tag(tag)
                    """);

            statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS
                    idx_catalog_game_mismatch
                    ON catalog_game(definite_mismatch_count)
                    """);
        }

    }

    private void upsertGame(
            CatalogGame game
    ) throws SQLException {

        String sql = """
                INSERT INTO catalog_game (
                    catalog_id,
                    source_type,
                    external_key,
                    canonical_uri,
                    submitted_uri,
                    title,
                    competition,
                    notes,
                    source_payload,
                    source_payload_sha256,
                    source_phase_count,
                    analyzer,
                    analyzer_revision,
                    analyzed_at,
                    compared_order_count,
                    matching_order_count,
                    compatibility_difference_count,
                    definite_mismatch_count,
                    created_at,
                    updated_at,
                    first_imported_at,
                    last_refreshed_at
                )
                VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                )
                ON CONFLICT(source_type, external_key)
                  DO UPDATE SET
                      catalog_id = excluded.catalog_id,
                      canonical_uri = excluded.canonical_uri,
                      submitted_uri = excluded.submitted_uri,
                      title = excluded.title,
                      competition = excluded.competition,
                      notes = excluded.notes,
                      source_payload = excluded.source_payload,
                      source_payload_sha256 = excluded.source_payload_sha256,
                      source_phase_count = excluded.source_phase_count,
                      analyzer = excluded.analyzer,
                      analyzer_revision = excluded.analyzer_revision,
                      analyzed_at = excluded.analyzed_at,
                      compared_order_count = excluded.compared_order_count,
                      matching_order_count = excluded.matching_order_count,
                      compatibility_difference_count =
                          excluded.compatibility_difference_count,
                      definite_mismatch_count =
                          excluded.definite_mismatch_count,
                      created_at = excluded.created_at,
                      updated_at = excluded.updated_at,
                      first_imported_at = excluded.first_imported_at,
                      last_refreshed_at = excluded.last_refreshed_at
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql))
        {
            bindGame(statement, game);

            statement.executeUpdate();
        }

    }

    private void replaceTags(
            CatalogGame game
    ) throws SQLException {

        String deleteSql = """
                DELETE FROM catalog_tag
                WHERE catalog_id = ?
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(deleteSql))
        {
            statement.setString(
                    1,
                    game.id().value().toString());

            statement.executeUpdate();
        }

        if (game.metadata().tags().isEmpty())
            return;

        String insertSql = """
                INSERT INTO catalog_tag (
                    catalog_id,
                    tag,
                    tag_order
                )
                VALUES (?, ?, ?)
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(insertSql))
        {
            for (int index = 0;
                 index < game.metadata().tags().size();
                 index++) {

                statement.setString(
                        1,
                        game.id().value().toString());

                statement.setString(
                        2,
                        game.metadata().tags().get(index));

                statement.setInt(
                        3,
                        index);

                statement.addBatch();
            }

            statement.executeBatch();
        }

    }

    private void bindGame(
            PreparedStatement statement,
            CatalogGame game
    ) throws SQLException {

        GameSourceReference source = game.source();

        statement.setString(
                1,
                game.id().value().toString());

        statement.setString(
                2,
                source.source().name());

        statement.setString(
                3,
                source.externalKey());

        statement.setString(
                4,
                source.canonicalUri().toString());

        setNullableString(
                statement,
                5,
                source.submittedUri() == null
                        ? null
                        : source.submittedUri().toString());

        setNullableString(
                statement,
                6,
                game.metadata().title());

        setNullableString(
                statement,
                7,
                game.metadata().competition());

        setNullableString(
                statement,
                8,
                game.metadata().notes());

        statement.setString(
                9,
                game.sourcePayload());

        statement.setString(
                10,
                game.sourcePayloadSha256());

        statement.setInt(
                11,
                game.sourcePhaseCount());

        bindAnalysis(
                statement,
                12,
                game.latestAnalysis());

        statement.setString(
                19,
                game.createdAt().toString());

        statement.setString(
                20,
                game.updatedAt().toString());

        statement.setString(
                21,
                game.metadata().firstImportedAt().toString());

        statement.setString(
                22,
                game.metadata().lastRefreshedAt().toString());

    }

    private static void bindAnalysis(
            PreparedStatement statement,
            int firstParameter,
            CatalogAnalysis analysis
    ) throws SQLException {

        if (analysis == null) {
            for (int index = firstParameter;
                 index < firstParameter + 7;
                 index++)
                statement.setObject(index, null);

            return;
        }

        statement.setString(
                firstParameter,
                analysis.analyzer());

        statement.setString(
                firstParameter + 1,
                analysis.analyzerRevision());

        statement.setString(
                firstParameter + 2,
                analysis.analyzedAt().toString());

        statement.setInt(
                firstParameter + 3,
                analysis.comparedOrderCount());

        statement.setInt(
                firstParameter + 4,
                analysis.matchingOrderCount());

        statement.setInt(
                firstParameter + 5,
                analysis.compatibilityDifferenceCount());

        statement.setInt(
                firstParameter + 6,
                analysis.definiteMismatchCount());

    }

    private List<CatalogGame> queryGames(
            String sql
    ) {

        try (PreparedStatement statement =
                     connection.prepareStatement(sql))
        {
            return queryGames(statement);

        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Unable to query catalog games",
                    exception);
        }

    }

    private List<CatalogGame> queryGames(
            PreparedStatement statement
    ) throws SQLException {

        List<CatalogGame> games = new ArrayList<>();

        try (ResultSet result = statement.executeQuery()) {
            while (result.next())
                games.add(readGame(result));
        }

        return List.copyOf(games);

    }

    private CatalogGame readGame(
            ResultSet result
    ) throws SQLException {

        CatalogGameId id = new CatalogGameId(
                UUID.fromString(
                        result.getString("catalog_id")));

        GameSourceReference source = new GameSourceReference(
                GameSource.valueOf(
                        result.getString("source_type")),
                result.getString("external_key"),
                URI.create(
                        result.getString("canonical_uri")),
                nullableUri(
                        result.getString("submitted_uri"))
        );

        CatalogMetadata metadata = new CatalogMetadata(
                result.getString("title"),
                result.getString("competition"),
                result.getString("notes"),
                tagsOf(id),
                instantOf(
                        result.getString("first_imported_at"),
                        "first_imported_at"),
                instantOf(
                        result.getString("last_refreshed_at"),
                        "last_refreshed_at")
        );

        return new CatalogGame(
                id,
                source,
                metadata,
                result.getString("source_payload"),
                result.getString("source_payload_sha256"),
                result.getInt("source_phase_count"),
                analysisOf(result),
                instantOf(
                        result.getString("created_at"),
                        "created_at"),
                instantOf(
                        result.getString("updated_at"),
                        "updated_at")
        );

    }

    private List<String> tagsOf(
            CatalogGameId id
    ) throws SQLException {

        String sql = """
                SELECT tag
                FROM catalog_tag
                WHERE catalog_id = ?
                ORDER BY tag_order
                """;

        List<String> tags = new ArrayList<>();

        try (PreparedStatement statement =
                     connection.prepareStatement(sql))
        {
            statement.setString(
                    1,
                    id.value().toString());

            try (ResultSet result = statement.executeQuery()) {
                while (result.next())
                    tags.add(result.getString("tag"));
            }
        }

        return List.copyOf(tags);

    }

    private static CatalogAnalysis analysisOf(
            ResultSet result
    ) throws SQLException {

        String analyzer = result.getString("analyzer");

        if (analyzer == null)
            return null;

        return new CatalogAnalysis(
                analyzer,
                result.getString("analyzer_revision"),
                instantOf(
                        result.getString("analyzed_at"),
                        "analyzed_at"),
                result.getInt("compared_order_count"),
                result.getInt("matching_order_count"),
                result.getInt("compatibility_difference_count"),
                result.getInt("definite_mismatch_count")
        );

    }

    private static URI nullableUri(
            String value
    ) {
        return value == null
                ? null
                : URI.create(value);
    }

    private static Instant instantOf(
            String value,
            String column
    ) {

        if (value == null)
            throw new IllegalStateException(
                    "Catalog database contains null "
                            + column);

        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Catalog database contains invalid "
                            + column
                            + ": "
                            + value,
                    exception);
        }

    }

    private static void setNullableString(
            PreparedStatement statement,
            int parameter,
            String value
    ) throws SQLException {

        if (value == null)
            statement.setObject(parameter, null);
        else
            statement.setString(parameter, value);

    }

    private boolean autoCommit() {

        try {
            return connection.getAutoCommit();
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Unable to inspect catalog transaction state",
                    exception);
        }

    }

    private void rollback() {

        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Preserve the original persistence failure.
        }

    }

    private void restoreAutoCommit(
            boolean originalAutoCommit
    ) {

        try {
            connection.setAutoCommit(originalAutoCommit);
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Unable to restore catalog transaction state",
                    exception);
        }

    }

    private void requireOpen() {

        if (closed)
            throw new IllegalStateException(
                    "Catalog store is closed");

    }

}
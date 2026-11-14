package _app;

import agents.encoding.EncodingPurpose;
import io.training.JsonlValueDataset;
import io.training.ValueDataset;
import io.training.ValueRewardContract;

import java.nio.file.Path;
import java.util.*;

/**
 * Full readback of a completed development value export.
 * This checks dataset integrity, not historical adjudication correctness.
 */
public final class ValueDatasetCheckApp {

    private ValueDatasetCheckApp() { }

    public static void main(String[] args) throws Exception {

        if (args.length != 1)
            throw new IllegalArgumentException(
                    "Usage: ValueDatasetCheckApp <value-export-directory>");

        JsonlValueDataset dataset = new JsonlValueDataset(Path.of(args[0]));
        Map<String, Long> sources = new TreeMap<>();
        Map<String, Long> endings = new TreeMap<>();

        for (EncodingPurpose split : List.of(EncodingPurpose.TRAIN, EncodingPurpose.VALIDATION)) {

            long games = 0, positions = 0;

            try (ValueDataset.Cursor cursor = dataset.open(split)) {

                ValueDataset.GameExample game;

                while ((game = cursor.next()) != null) {

                    if (game.assess(ValueRewardContract.defaults()).rejection().isPresent())
                        throw new IllegalStateException("Rejected outcome entered the dataset");

                    games++;
                    positions += game.positions().size();
                    sources.merge(game.source(), 1L, Long::sum);
                    endings.merge(game.outcome().termination().name(), 1L, Long::sum);

                }

                if (games != dataset.games(split) || positions != dataset.positions(split)
                        || cursor.statistics().sha256().isEmpty())
                    throw new IllegalStateException("Incomplete dataset verification");

                System.out.println(split + ": " + cursor.statistics());

            }

        }

        System.out.println("Sources: " + sources);
        System.out.println("Endings: " + endings);
        System.out.println("Reward contract: " + ValueRewardContract.VERSION);
        System.out.println("Dataset fingerprint: " + dataset.fingerprint());
        System.out.println("ValueDatasetCheckApp passed. TEST was not opened.");

    }

}
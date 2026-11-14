package _app;

import io.training.JsonlValueDataset;
import io.training.NeuralValueCache;

import java.nio.file.Path;

/**
 * Converts a verified development value dataset without opening TEST.
 */
public final class NeuralValueCacheApp {

    private NeuralValueCacheApp() { }

    public static void main(String[] args) throws Exception {

        if (args.length != 2)
            throw new IllegalArgumentException(
                    "Usage: NeuralValueCacheApp <value-dataset> <new-cache-directory>");

        JsonlValueDataset dataset = new JsonlValueDataset(Path.of(args[0]));
        String manifest = NeuralValueCache.write(
                dataset, Path.of(args[1]), System.out::println);

        System.out.print(manifest);
        System.out.println("TRAIN and VALIDATION converted. TEST was not opened.");
        System.out.println("No model has been trained or evaluated.");

    }

}
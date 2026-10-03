package _app;

import analysis.CoordinationMode;
import analysis.PreferenceAblation;

/** No database or held-out corpus is opened implicitly. */
public final class PreferenceAblationApp {
    private PreferenceAblationApp() { }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Opt-in only: --synthetic-validation");
            System.out.println("Corpus validation and human reference-order matching: unmeasured.");
            System.out.println("For real VALIDATION use PreferenceAblation.evaluate with fixed positions,"
                    + " training-only evidence, explicit scenarios and optional reference orders.");
            return;
        }
        if (args.length != 1 || !args[0].equals("--synthetic-validation"))
            throw new IllegalArgumentException("Supported flag: --synthetic-validation;"
                    + " database loading is not enabled; use the fixed VALIDATION fixture API");
        System.out.println("Synthetic illustration only; no corpus loaded; human agreement unmeasured.");
        PreferenceAblation.print(PreferenceAblation.evaluate(
                PreferenceAblation.syntheticValidation(), PreferenceAblation.syntheticConfiguration(),
                new PreferenceAblation.Limits(1, 1, CoordinationMode.STRICT)), System.out);
    }
}

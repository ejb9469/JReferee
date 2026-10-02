Map<UnitId, RoutePrediction> predictions = new LinkedHashMap<>();

for (UnitId unit : currentBoard.locations().keySet()) {

        if (unit.owner() != nation)
        continue;

RoutePrediction prediction = preferences.predict(
        rulesetId,
        currentMoment,
        currentBoard,
        unit,
        histories.getOrDefault(unit, List.of()),
        3);

    predictions.put(unit, prediction);

}

JointOrders search = new JointOrders(4, 32);

List<OrderPlan> candidates =
        search.generate(currentBoard, nation, predictions);

for (OrderPlan candidate : candidates) {

MovementResult outcome = search.evaluate(
        currentBoard,
        candidate,
        explicitOpponentScenario,
        configuredMovementProcessor);

// Compare outcomes here; no "best strategy" evaluator is assumed yet.
}
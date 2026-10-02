UnitRoutes routes = new UnitRoutes();

for (GameRecord game : trainingGames)
        routes.add(game);

List<String> prefix = routes.birthPrefix(exampleGame.id(), exampleUnit);

// Most common first orders for this nation/type/birth location/year/ruleset.
Map<String, Long> choices = routes.next(prefix);

// Only continuations observed at least twice.
Map<String, Long> repeatedChoices = routes.next(prefix, 2);

// These archives stop here; they do not demonstrate death or a next decision.
long unfinished = routes.unfinishedAt(prefix);
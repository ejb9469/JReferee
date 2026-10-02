UnitRoutes routes = new UnitRoutes(selectedOpening);

// selectedOpening can also be an OpeningCombination.
for (GameRecord game : trainingGames)
        routes.add(game);
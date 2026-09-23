Game game = new Game(1901, initialBoard);

GameRecordBuilder archive = new GameRecordBuilder(
        UUID.randomUUID(),
        "jreferee-standard-v1",
        game
);

archive.resolveMovement(
        springOrders,
        new Moment(
                1901,
        GamePhase.SPRING_MOVEMENT,
        clock.instant()
        )
);

GameRecord record = archive.build();
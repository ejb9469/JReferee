package domain;

public enum OrderType {

    // thus far [ @ 11-04-26 ] we deliberately have avoided including an `OrderType.NONE` OrderType.
        // that isn't to say that we won't find a use for it in the future, just that we should only implement it...
        // if absolutely necessary (and only sparingly), ...
        // because `NONE` is an ugly sentinel that introduces imprecision & overhead in the program
    MOVE,
    HOLD,
    SUPPORT,
    CONVOY,
    RETREAT,
    DESTROY,
    BUILD,
    WAIVE

}
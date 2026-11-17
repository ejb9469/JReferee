export const PHASES = Object.freeze([
    "SPRING_MOVEMENT", "SPRING_RETREAT", "FALL_MOVEMENT", "FALL_RETREAT", "WINTER_ADJUSTMENT"
]);

export function tick(frame) {
    const phase = PHASES.indexOf(frame.phase);
    if (!Number.isInteger(frame.year) || phase < 0)
        throw new Error("Invalid recorded game moment.");
    return frame.year * PHASES.length + phase;
}

export function timeline(games) {
    const moments = new Map();
    for (const game of games) {
        if (!game.frames.length || !game.frames.at(-1).final)
            throw new Error("Replay has no final position.");
        let previous = -Infinity;
        for (const frame of game.frames) {
            const value = tick(frame);
            if (value <= previous)
                throw new Error("Replay moments are not strictly increasing.");
            previous = value;
            moments.set(value, { year: frame.year, phase: frame.phase });
        }
    }
    return [...moments.entries()].sort(([a], [b]) => a - b).map(([, moment]) => moment);
}

export function locate(game, moment, after = false) {
    const value = tick(moment);
    const index = game.frames.findIndex(frame => tick(frame) === value);
    if (index >= 0) {
        const frame = game.frames[index];
        const boardFrame = after && !frame.final ? game.frames[index + 1] : frame;
        return { status: frame.final ? "FINAL" : after ? "AFTER" : "BEFORE", frame, boardFrame };
    }
    const last = game.frames.at(-1);
    return value > tick(last)
        ? { status: "ENDED", frame: last, boardFrame: last }
        : { status: "ABSENT", frame: null, boardFrame: null };
}

export function series(game, power, metric) {
    if (!["centers", "scores"].includes(metric))
        throw new Error("Unknown recorded score metric.");
    return game.frames.map(frame => {
        const value = frame[metric][power];
        if (!Number.isFinite(value) || value < 0 || value > (metric === "scores" ? 1 : 34))
            throw new Error("Invalid recorded score.");
        return { time: tick(frame), value, year: frame.year, phase: frame.phase };
    });
}
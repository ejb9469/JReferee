"use strict";

import { BoardMap } from "./board-map.js";

const $ = selector => document.querySelector(selector);
const form = $("#editor");

const POSITION_FIELDS = new Set(["year", "phase", "units", "centers"]);
const MOVEMENT_PHASES = new Set(["SPRING_MOVEMENT", "FALL_MOVEMENT"]);
const ORDER_TYPES = new Set(["HOLD", "MOVE", "SUPPORT", "CONVOY"]);
const PARENTS = {
    StpNC: "Stp", StpSC: "Stp",
    SpaNC: "Spa", SpaSC: "Spa",
    BulEC: "Bul", BulSC: "Bul"
};

let bootstrap;
let map;
let result = null;
let filtered = [];
let selectedIndex = -1;
let busy = false;
let generationVersion = 0;
let generationController = null;
let displayedBoard = null;
let boardLabel = "";
let positionDirty = false;
let resultContext = null;

let importedGame = null;
let importedPhase = null;
let fileReadVersion = 0;
let scoringReadVersion = 0;
let profileNation = null;

function profileField(kind, nation) {
    return form.elements.namedItem(`${kind}.${nation}`);
}

function saveProfile() {
    if (!profileNation)
        return;
    profileField("adjustments", profileNation).value = $("#profile-adjustments").value;
    profileField("regions", profileNation).value = $("#profile-regions").value;
}

function loadProfile() {
    saveProfile();
    profileNation = $("#nation").value;
    $("#profile-nation").textContent = profileNation;
    $("#profile-adjustments").value = profileField("adjustments", profileNation).value;
    $("#profile-regions").value = profileField("regions", profileNation).value;
}

function profileDocument() {
    saveProfile();
    return {
        format: "jreferee-scoring-profiles", version: 1,
        humanWeight: form.elements.namedItem("humanWeight").value,
        globalValues: form.elements.namedItem("globalValues").value,
        nationProfiles: Object.fromEntries(Object.keys(bootstrap.colors).map(nation =>
            [nation, { adjustments: profileField("adjustments", nation).value,
                regions: profileField("regions", nation).value }]))
    };
}

function applyProfiles(value) {
    const nations = Object.keys(bootstrap.colors);
    if (!value || value.format !== "jreferee-scoring-profiles" || value.version !== 1
        || typeof value.globalValues !== "string" || value.globalValues.length > 8000
        || typeof value.humanWeight !== "string" || value.humanWeight.trim() === ""
        || !Number.isFinite(Number(value.humanWeight))
        || Number(value.humanWeight) < 0 || Number(value.humanWeight) > 1000
        || !value.nationProfiles || typeof value.nationProfiles !== "object"
        || Object.keys(value.nationProfiles).length !== nations.length
        || nations.some(nation => !Object.hasOwn(value.nationProfiles, nation)
            || typeof value.nationProfiles[nation]?.adjustments !== "string"
            || typeof value.nationProfiles[nation]?.regions !== "string"
            || value.nationProfiles[nation].adjustments.length > 8000
            || value.nationProfiles[nation].regions.length > 8000))
        throw new Error("Expected bounded scoring profiles JSON containing exactly all seven countries.");
    form.elements.namedItem("humanWeight").value = value.humanWeight;
    form.elements.namedItem("globalValues").value = value.globalValues;
    for (const nation of nations) {
        profileField("adjustments", nation).value = value.nationProfiles[nation].adjustments;
        profileField("regions", nation).value = value.nationProfiles[nation].regions;
    }
    profileNation = null;
    loadProfile();
    invalidate({ target: {} });
    $("#profile-status").textContent = "Profiles loaded. Java validates grammar and scoring budgets on Generate.";
}

function emptyProfiles() {
    return { format: "jreferee-scoring-profiles", version: 1, humanWeight: "0", globalValues: "",
        nationProfiles: Object.fromEntries(Object.keys(bootstrap.colors)
            .map(nation => [nation, { adjustments: "", regions: "" }])) };
}

function setupProfiles() {
    for (const nation of Object.keys(bootstrap.colors))
        for (const kind of ["adjustments", "regions"]) {
            const field = document.createElement("input");
            field.type = "hidden";
            field.name = `${kind}.${nation}`;
            $("#profile-fields").append(field);
        }
    $("#nation").addEventListener("change", () => {
        loadProfile();
        form.elements.namedItem("objectives").value = "";
        $("#profile-status").textContent = "Country profile loaded; country-specific snapshot objectives cleared.";
    });
    for (const id of ["profile-adjustments", "profile-regions"])
        $(`#${id}`).addEventListener("input", saveProfile);
    $("#clear-profile").addEventListener("click", () => {
        $("#profile-adjustments").value = "";
        $("#profile-regions").value = "";
        saveProfile();
        invalidate({ target: {} });
    });
    $("#clear-snapshot").addEventListener("click", () => {
        form.elements.namedItem("objectives").value = "";
        invalidate({ target: {} });
    });
    $("#reset-scoring").addEventListener("click", () => applyProfiles(emptyProfiles()));
    $("#example-scoring").addEventListener("click", () => {
        const example = emptyProfiles();
        example.humanWeight = "1";
        example.globalValues = "MAO=1/FLEET\nION=1/FLEET";
        example.nationProfiles.GERMANY.adjustments = "Pru=-1/ARMY,FLEET";
        example.nationProfiles.GERMANY.regions = "north Bel,Hol 2 ARMY 4 0.5";
        applyProfiles(example);
        $("#profile-status").textContent = "Untuned example loaded, not calibrated advice. Edit or reset to disable.";
    });
    $("#export-scoring").addEventListener("click", () => {
        const url = URL.createObjectURL(new Blob([JSON.stringify(profileDocument(), null, 2)],
            { type: "application/json" }));
        const link = document.createElement("a");
        link.href = url;
        link.download = "jreferee-scoring-profiles.json";
        link.click();
        URL.revokeObjectURL(url);
    });
    $("#scoring-file").addEventListener("change", async () => {
        const version = ++scoringReadVersion;
        const file = $("#scoring-file").files[0];
        if (!file) return;
        try {
            if (file.size > 32768) throw new Error("Scoring profiles exceed 32 KiB.");
            const value = JSON.parse(await file.text());
            if (version !== scoringReadVersion || busy) return;
            applyProfiles(value);
        } catch (error) {
            if (version === scoringReadVersion)
                $("#profile-status").textContent = "Import failed: " + error.message;
        }
    });
}

async function responseJson(response) {
    const value = await response.json();
    if (!response.ok)
        throw new Error(value.error ?? `Request failed: ${response.status}`);
    return value;
}

async function start() {
    bootstrap = await fetch("/api/bootstrap").then(responseJson);

    map = await BoardMap.create(
        $("#map-host"), $("#province-details"), $("#map-warning"));

    for (const nation of Object.keys(bootstrap.colors)) {
        const option = document.createElement("option");
        option.value = nation;
        option.textContent = nation;
        $("#nation").append(option);
    }

    setupProfiles();
    restoreStartingPosition();
    loadProfile();

    form.addEventListener("input", invalidate);
    form.addEventListener("change", invalidate);
    form.addEventListener("submit", generate);
    $("#cancel-generation").addEventListener("click", invalidate);

    $("#reset-position").addEventListener("click", restoreStartingPosition);
    $("#search").addEventListener("input", renderList);
    $("#previous").addEventListener("click", () => navigate(-1));
    $("#next").addEventListener("click", () => navigate(1));

    $("#game-file").addEventListener("change", loadGameFile);
    $("#import-phase-button").addEventListener("click", importSelectedPhase);
    $("#show-historical").addEventListener("click", showHistorical);
    $("#scenario-selector").addEventListener("change", renderInspector);
    $("#dependency-set").addEventListener("change", renderInspector);
    $("#show-dependencies").addEventListener("change", renderInspector);

    $("#fields").disabled = false;
    $("#import-fields").disabled = false;
}

function clearResults() {
    result = null;
    filtered = [];
    selectedIndex = -1;

    $("#opening-list").replaceChildren();
    $("#diagnostic-list").replaceChildren();
    $("#diagnostic-summary").textContent = "Prediction evidence";
    $("#list-summary").textContent = "";
    $("#list-title").textContent = "Ranked plans";
    $("#selection-title").textContent = "Position";
    $("#plan-metrics").textContent = "";
    $("#plan-dependencies").replaceChildren();
    $("#plan-inspector").hidden = true;
    for (const selector of ["#result-context", "#scoring-context", "#bias-summary",
        "#scenario-metrics", "#requirement-details", "#human-summary", "#frozen-scoring"])
        $(selector).textContent = "";
    for (const selector of ["#scenario-orders", "#structured-dependencies",
        "#dependency-legend", "#plan-findings", "#plan-comparisons", "#human-units",
        "#province-shaping", "#regional-shaping"])
        $(selector).replaceChildren();
    $("#show-dependencies").checked = false;
    $("#scenario-selector").replaceChildren();
    $("#dependency-set").replaceChildren();
    resultContext = null;
    $("#coordination-summary").textContent = "No current generation.";
    $("#coordination-list").replaceChildren();
    $("#position").textContent = "0 / 0";

    $("#previous").disabled = true;
    $("#next").disabled = true;
    $("#search").disabled = true;
    $("#search").value = "";
}

function invalidate(event) {
    if (event.target?.id !== "scoring-file")
        ++scoringReadVersion;
    cancelGeneration();

    clearResults();
    $("#error").textContent = "";

    if (POSITION_FIELDS.has(event.target.name)) {
        positionDirty = true;
        importedPhase = null;
        $("#import-status").textContent =
            "Position edited. Historical comparison detached; reimport a phase to restore it.";
    }

    refreshHistorical();

    showRetainedBoard();

    $("#summary").textContent =
        "Position or settings changed. Press Generate to evaluate this form.";
}

function showRetainedBoard() {
    if (!displayedBoard)
        return;
    map.show(displayedBoard, bootstrap.colors, $("#nation").value);
    $("#selection-title").textContent = boardLabel;
    $("#board-status").textContent = positionDirty
        ? "Last validated/displayed position retained. Position edits are unvalidated and are NOT shown on this map."
        : "Map shows the loaded position. No pending position edits; generation settings may differ.";
}

function rememberBoard(board, label) {
    displayedBoard = board;
    boardLabel = label;
    positionDirty = false;
    showRetainedBoard();
}

function restoreStartingPosition() {
    ++scoringReadVersion;
    cancelGeneration();

    for (const [name, value] of Object.entries(bootstrap.defaults))
        form.elements.namedItem(name).value = value;
    form.elements.namedItem("compareAlternatives").checked = false;
    if (profileNation)
        loadProfile();

    importedPhase = null;
    clearResults();
    refreshHistorical();

    $("#error").textContent = "";
    rememberBoard(bootstrap.board, "Starting position — Spring 1901");
    $("#summary").textContent =
        `${bootstrap.trainingGames} training games; ${bootstrap.ruleset}. `
        + "Starting position loaded. Press Generate.";

    if (importedGame)
        $("#import-status").textContent =
            "Starting position restored. The loaded game remains available in the phase selector.";
}

async function loadGameFile() {
    if (busy)
        return;

    const version = ++fileReadVersion;
    const file = $("#game-file").files[0];

    importedGame = null;
    importedPhase = null;
    form.elements.namedItem("objectives").value = "";

    $("#import-phase").replaceChildren();
    $("#import-phase").disabled = true;
    $("#import-phase-button").disabled = true;

    clearResults();
    refreshHistorical();
    showRetainedBoard();

    if (!file) {
        $("#import-status").textContent = "No game file selected.";
        return;
    }

    try {
        if (file.size > 5 * 1024 * 1024)
            throw new Error("Game export exceeds the 5 MiB limit.");

        $("#import-status").textContent = "Reading game export…";

        const parsed = JSON.parse(await file.text());

        // Ignore an earlier file read if another file was selected meanwhile.
        if (version !== fileReadVersion)
            return;

        validateExport(parsed);
        importedGame = parsed;

        for (const [index, phase] of parsed.phases.entries()) {
            const option = document.createElement("option");
            option.value = String(index);
            option.textContent =
                `${phase.board.year} ${phase.board.phase.replaceAll("_", " ")}`
                + ` · snapshot ${phase.snapshot}`
                + ` · ${phase.sourceStatus}`
                + ` · ${phase.orders.length} orders`;
            $("#import-phase").append(option);
        }

        $("#import-phase").disabled = false;
        $("#import-phase-button").disabled = false;
        $("#import-status").textContent =
            `${parsed.gameLabel || "DiploBN game"}: `
            + `${parsed.phases.length} movement snapshots loaded. Select one and import it.`;
        $("#summary").textContent = "Game loaded; no phase imported yet.";

    } catch (error) {
        if (version !== fileReadVersion)
            return;

        console.error(error);
        importedGame = null;
        $("#import-status").textContent = "Import failed: " + error.message;
        $("#summary").textContent =
            "No imported position. You may still edit the position form manually.";
    }
}

function validateExport(value) {
    const fail = message => {
        throw new Error("Invalid game export: " + message);
    };

    if (!value || value.format !== "jreferee-strategy-game" || value.version !== 1)
        fail("unsupported format or version.");

    if (!Array.isArray(value.phases) ||
        value.phases.length === 0 || value.phases.length > 512)
        fail("expected 1–512 movement snapshots.");

    const nations = new Set(Object.keys(bootstrap.colors));
    const snapshots = new Set();
    const province = item => typeof item === "string"
        && /^[A-Za-z]{3}(?:NC|SC|EC)?$/.test(item);

    for (const phase of value.phases) {
        if (!phase || !Number.isInteger(phase.snapshot) ||
            phase.snapshot < 1 || snapshots.has(phase.snapshot))
            fail("invalid or repeated snapshot number.");

        snapshots.add(phase.snapshot);

        const board = phase.board;

        if (!board || !Number.isInteger(board.year) ||
            board.year < 1901 || board.year > 9999 ||
            !MOVEMENT_PHASES.has(board.phase))
            fail("invalid movement moment.");

        if (typeof phase.sourceStatus !== "string")
            fail("missing source status.");

        if (!Array.isArray(board.units) || board.units.length > 75 ||
            !board.supplyCenterOwners ||
            typeof board.supplyCenterOwners !== "object" ||
            Array.isArray(board.supplyCenterOwners))
            fail("invalid board.");

        const territories = new Set();
        const issuers = new Map();

        for (const unit of board.units) {
            if (!unit || !nations.has(unit.nation) ||
                !["ARMY", "FLEET"].includes(unit.type) ||
                !province(unit.province))
                fail("invalid unit.");

            const territory = PARENTS[unit.province] ?? unit.province;

            if (territories.has(territory))
                fail("multiple units occupy " + territory);

            territories.add(territory);
            issuers.set(unit.province, unit);
        }

        for (const [location, owner] of Object.entries(board.supplyCenterOwners))
            if (!province(location) || !nations.has(owner))
                fail("invalid center ownership.");

        if (!Array.isArray(phase.orders) ||
            phase.orders.length > board.units.length)
            fail("invalid order list.");

        const ordered = new Set();

        for (const order of phase.orders) {
            if (!order || !ORDER_TYPES.has(order.type) ||
                typeof order.text !== "string" || order.text.length > 1000 ||
                !province(order.origin) ||
                (order.target !== null && !province(order.target)) ||
                (order.auxiliaryTarget !== null && !province(order.auxiliaryTarget)))
                fail("invalid movement order.");

            const issuer = issuers.get(order.origin);

            if (!issuer || issuer.nation !== order.nation ||
                ordered.has(order.origin))
                fail("unknown, mismatched, or repeated order issuer.");

            if (order.type !== "HOLD" && order.target === null)
                fail("non-hold order has no target.");

            ordered.add(order.origin);
        }

        if (!Number.isInteger(phase.missingSubmissionCount) ||
            phase.missingSubmissionCount !== board.units.length - phase.orders.length)
            fail("submission count does not match the board.");
    }

    // Exact province/geography validity is checked again by the Java endpoint
    // when the imported editor contents are submitted for generation.
}

function importSelectedPhase() {
    if (busy || !importedGame)
        return;

    const index = Number($("#import-phase").value);
    const phase = importedGame.phases[index];

    if (!phase)
        return;

    importedPhase = phase;

    form.elements.namedItem("year").value = phase.board.year;
    form.elements.namedItem("phase").value = phase.board.phase;

    form.elements.namedItem("units").value = phase.board.units
        .map(unit => `${unit.nation} ${unit.type} ${unit.province}`)
        .join("\n");

    form.elements.namedItem("centers").value =
        Object.entries(phase.board.supplyCenterOwners)
            .map(([location, owner]) => `${location} ${owner}`)
            .join("\n");

    // Do not carry position-specific objectives into a different imported phase.
    form.elements.namedItem("objectives").value = "";

    const activeNations = new Set(phase.board.units.map(unit => unit.nation));

    if (!activeNations.has($("#nation").value) && activeNations.size)
        $("#nation").value = activeNations.values().next().value;
    ++scoringReadVersion;
    loadProfile();

    clearResults();
    $("#error").textContent = "";
    refreshHistorical();

    rememberBoard(phase.board, importedPositionTitle());
    $("#board-status").textContent =
        "Imported source snapshot displayed immediately. Exact geography is checked during generation.";

    $("#import-status").textContent =
        `${importedGame.gameLabel || "DiploBN game"} — `
        + `snapshot ${phase.snapshot} imported. `
        + `${phase.orders.length} historical orders; `
        + `${phase.missingSubmissionCount} missing submissions. `
        + "Objectives cleared; scoring and search settings retained.";

    $("#summary").textContent =
        "Imported source position. Review country/objectives, then press Generate. "
        + "Historical orders are not used for generation.";
}

function importedPositionTitle() {
    if (!importedPhase)
        return "Position";

    return `Imported position — ${importedPhase.board.phase.replaceAll("_", " ")} `
        + `${importedPhase.board.year} — snapshot ${importedPhase.snapshot}`;
}

function refreshHistorical() {
    $("#historical-orders").replaceChildren();

    if (!importedPhase) {
        $("#historical-summary").textContent = "No imported phase attached to this position.";
        $("#show-historical").disabled = true;
        return;
    }

    const nation = $("#nation").value;
    const orders = importedPhase.orders.filter(order => order.nation === nation);
    const units = importedPhase.board.units.filter(unit => unit.nation === nation);

    $("#historical-summary").textContent =
        `${importedGame?.gameLabel || "DiploBN game"}; `
        + `${importedPhase.board.year} ${importedPhase.board.phase}; `
        + `source status ${importedPhase.sourceStatus}. `
        + `${nation}: ${orders.length} submitted orders for ${units.length} units. `
        + "Source data, not locally replay-verified; raw historical orders are unpenalized.";

    for (const order of orders) {
        const item = document.createElement("li");
        item.textContent = order.text;
        $("#historical-orders").append(item);
    }

    if (!orders.length) {
        const item = document.createElement("li");
        item.textContent = "No recorded movement submissions for this country.";
        $("#historical-orders").append(item);
    }

    $("#show-historical").disabled = busy;
}

function showHistorical() {
    if (busy || !importedPhase)
        return;

    const nation = $("#nation").value;
    const orders = importedPhase.orders.filter(order => order.nation === nation);

    for (const button of $("#opening-list").querySelectorAll("button"))
        button.setAttribute("aria-pressed", "false");

    $("#selection-title").textContent =
        `HISTORICAL SUBMISSIONS — ${nation} — `
        + `${importedPhase.board.phase.replaceAll("_", " ")} `
        + importedPhase.board.year;

    $("#plan-metrics").textContent =
        "Raw historical submissions — unpenalized comparison overlay only. "
        + "Click a ranked plan to return to generated recommendations.";
    $("#plan-dependencies").replaceChildren();
    $("#plan-inspector").hidden = true;

    map.show(importedPhase.board, bootstrap.colors, nation, { nation, orders });
}

async function generate(event) {
    event.preventDefault();

    if (busy || !form.reportValidity())
        return;
    ++scoringReadVersion;

    // Only the editor's named position/configuration controls are submitted.
    // Imported source orders and metadata are outside this form.
    const body = new URLSearchParams();
    saveProfile();

    for (const [key, value] of new FormData(form))
        body.append(key, String(value));
    body.set("compareAlternatives", String(form.elements.namedItem("compareAlternatives").checked));
    const context = Object.fromEntries(body);
    const source = importedPhase ? {
        label: "Imported source snapshot",
        snapshot: importedPhase.snapshot,
        status: importedPhase.sourceStatus
    } : { label: "Editor position; no imported source attached" };

    const version = ++generationVersion;
    const controller = new AbortController();
    generationController = controller;
    setGenerating(true);

    clearResults();
    showRetainedBoard();
    $("#error").textContent = "";
    $("#summary").textContent = "Generating and evaluating movement plans…";

    try {
        const response = await fetch("/api/generate", {
            method: "POST",
            headers: {
                "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
                "X-Strategy-Token": bootstrap.token
            },
            body,
            signal: controller.signal
        }).then(responseJson);

        if (version !== generationVersion)
            return;
        result = response;
        resultContext = freezeContext({
            ...(response.context ?? context), source,
            pruning: {
                omittedChoices: response.coordination?.omittedChoices,
                geographyGuided: response.coordination?.geographyGuided,
                replenishedChoices: response.coordination?.replenishedChoices,
                guidanceChoicesUnexamined: response.coordination?.guidanceChoicesUnexamined,
                beamTruncated: response.coordination?.beamTruncated,
                dependencySearchTruncated: response.coordination?.dependencySearchTruncated
            }
        });
        rememberBoard(result.board,
            `Validated position — ${result.board.phase.replaceAll("_", " ")} ${result.board.year}`);

        $("#summary").textContent = result.settings;
        $("#search").disabled = result.plans.length === 0;

        $("#diagnostic-summary").textContent =
            `Prediction evidence (${result.diagnostics.length})`;

        for (const message of result.diagnostics) {
            const item = document.createElement("li");
            item.textContent = message;
            $("#diagnostic-list").append(item);
        }

        const coordination = result.coordination;
        $("#coordination-summary").textContent =
            `${coordination.mode}: ${coordination.expandedCandidates} expanded; `
            + `${coordination.rejectedCandidates} rejected; `
            + `${coordination.omittedChoices} choices omitted; `
            + `geography guided: ${coordination.geographyGuided ? "yes" : "no"}; `
            + `${coordination.replenishedChoices ?? 0} positional choices replenished; `
            + `${coordination.guidanceChoicesUnexamined ?? 0} guidance choices unexamined; `
            + `historical guidance scan cap ${coordination.geographicChoiceLimit ?? "unavailable"}/unit; `
            + `beam truncated: ${coordination.beamTruncated ? "yes" : "no"}; `
            + `${coordination.dependencyAssignmentsExamined} foreign-assignment nodes `
            + `(limit ${coordination.foreignAssignmentLimit} per check); `
            + `dependency search truncated: ${coordination.dependencySearchTruncated ? "yes" : "no"}.`;
        for (const reason of coordination.reasons) {
            const item = document.createElement("li");
            item.textContent = reason;
            $("#coordination-list").append(item);
        }

        renderList();

    } catch (error) {
        if (version !== generationVersion)
            return;
        console.error(error);
        clearResults();
        showRetainedBoard();
        $("#error").textContent = error.message;
        $("#summary").textContent =
            "Generation failed. No previous recommendation is being displayed.";
    } finally {
        if (version === generationVersion) {
            generationController = null;
            setGenerating(false);
        }
    }
}

function setGenerating(value) {
    busy = value;
    $("#fields").disabled = value;
    $("#import-fields").disabled = value;
    $("#cancel-generation").disabled = !value;
    $("#editor").setAttribute("aria-busy", String(value));
    refreshHistorical();
}

function cancelGeneration() {
    ++generationVersion;
    generationController?.abort();
    generationController = null;
    if (busy)
        setGenerating(false);
}

function renderList() {
    if (!result)
        return;

    const query = $("#search").value.trim().toLowerCase();

    filtered = result.plans.filter(plan =>
        plan.orders.some(order => order.text.toLowerCase().includes(query)));

    $("#list-title").textContent = `${result.nation} — ranked plans`;
    $("#list-summary").textContent =
        `${result.status}: ${result.diagnostic} `
        + `${filtered.length}/${result.plans.length} displayed; `
        + `${result.candidateCount} candidates; ${result.scenarioCount} scenarios.`;

    $("#opening-list").replaceChildren();

    for (const [index, plan] of filtered.entries()) {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "opening";
        button.setAttribute("aria-pressed", "false");

        const heading = document.createElement("span");
        heading.className = "opening-count";
        heading.textContent =
            `Rank ${plan.rank} · ${scoreSummary(plan)} · `
            + `mean ${number(plan.mean)} · worst ${number(plan.worst)}`
            + ` · ${rankExplanation(plan)}`
            + ((plan.dependencies ?? []).length ? " · CONDITIONAL" : "");
        button.append(heading);

        for (const order of plan.orders) {
            const line = document.createElement("span");
            line.className = "order";
            line.textContent = order.text + (offendingIncoming(plan, order) ? " · FLAGGED INCOMING MOVE" : "");
            line.style.setProperty("--nation-color", bootstrap.colors[result.nation]);
            line.classList.toggle("germany", result.nation === "GERMANY");
            button.append(line);
        }

        button.addEventListener("click", () => select(index));
        $("#opening-list").append(button);
    }

    if (!filtered.length) {
        const message = document.createElement("p");
        message.textContent = result.plans.length
            ? "No plans match the search."
            : "No recommendation. Inspect coordination diagnostics and prediction evidence.";
        $("#opening-list").append(message);
    }

    select(filtered.length ? 0 : -1);
}

function select(index) {
    selectedIndex = index;

    for (const [position, button] of
        $("#opening-list").querySelectorAll("button").entries())
        button.setAttribute("aria-pressed", String(position === index));

    $("#previous").disabled = index <= 0;
    $("#next").disabled = index < 0 || index >= filtered.length - 1;
    $("#position").textContent =
        `${index < 0 ? 0 : index + 1} / ${filtered.length}`;

    const plan = index < 0 ? null : filtered[index];
    const moment =
        `${result.board.phase.replaceAll("_", " ")} ${result.board.year}`;

    $("#selection-title").textContent = plan
        ? `${result.nation} — candidate rank ${plan.rank} — ${moment}`
        : `${result.nation} — ${moment}`;

    $("#plan-metrics").textContent = plan
        ? `${scoreSummary(plan)}; ${rankExplanation(plan)}; mean ${number(plan.mean)}; `
          + `worst ${number(plan.worst)}; log preference ${number(plan.logPreference)}.`
        : "";

    $("#plan-dependencies").replaceChildren();
    if (plan) {
        const dependencies = (plan.dependencies ?? []).length
            ? plan.dependencies.map(message => "External dependency: " + message)
            : [result.coordination.mode === "RAW"
                ? "Raw mode: coordination and external dependencies are not checked."
                : "No external dependencies reported."];
        for (const message of dependencies) {
            const item = document.createElement("li");
            item.textContent = message;
            $("#plan-dependencies").append(item);
        }
    }

    map.show(result.board, bootstrap.colors, result.nation, plan);
    setupInspector(plan);
}

function navigate(offset) {
    const next = selectedIndex + offset;

    if (next < 0 || next >= filtered.length)
        return;

    select(next);
    $("#opening-list").querySelectorAll("button")[next]
        .scrollIntoView({ block: "nearest" });
}

function appendText(host, tag, text, className = "") {
    const element = document.createElement(tag);
    element.textContent = text;
    element.className = className;
    host.append(element);
    return element;
}

function option(host, value, text) {
    const element = appendText(host, "option", text);
    element.value = String(value);
}

function freezeContext(value) {
    if (value && typeof value === "object") {
        for (const child of Object.values(value))
            freezeContext(child);
        Object.freeze(value);
    }
    return value;
}

function scenarioDetails(plan) {
    return plan.scenarios ?? plan.scenarioResults ?? [];
}

function dependencyAlternatives(plan) {
    return Array.isArray(plan.dependencySets)
        ? plan.dependencySets : plan.dependencySets?.alternatives ?? [];
}

function requirementText(requirement) {
    const order = requirement.order ?? requirement;
    if (order.text)
        return order.text;
    const unit = `${order.nation} ${order.unitType ?? "unit"} ${order.origin}`;
    if (order.constraint === "STATIONARY")
        return `${unit} must remain stationary (hold, support, or convoy; not move)`;
    return `${unit} ${order.type}`
        + (order.target ? ` ${order.target}` : "")
        + (order.auxiliaryTarget ? ` → ${order.auxiliaryTarget}` : "");
}

function requirementSatisfied(requirement, opponentOrders) {
    if (!Array.isArray(opponentOrders))
        return undefined;
    const expected = requirement.order ?? requirement;
    return opponentOrders.some(order => {
        const sameUnit = expected.unit && order.unit
            ? expected.unit === order.unit
            : expected.origin === order.origin
                && (requirement.nation ?? expected.nation) === order.nation;
        if (!sameUnit)
            return false;
        if ((requirement.constraint ?? expected.constraint) === "STATIONARY")
            return ["HOLD", "SUPPORT", "CONVOY"].includes(order.type);
        return expected.type === order.type
            && (expected.target ?? null) === (order.target ?? null)
            && (expected.auxiliaryTarget ?? null) === (order.auxiliaryTarget ?? null);
    });
}

function individualRequirementStatus(requirement, opponentOrders) {
    const present = requirementSatisfied(requirement, opponentOrders);
    return present === undefined ? "individual requirement satisfaction unavailable"
        : `individual requirement ${present ? "PRESENT" : "ABSENT"} in selected sampled opponent orders`;
}

function setupInspector(plan) {
    $("#plan-inspector").hidden = !plan;
    $("#scenario-selector").replaceChildren();
    $("#dependency-set").replaceChildren();
    $("#requirement-details").textContent = "";
    $("#show-dependencies").checked = false;
    if (!plan)
        return;
    const scenarios = scenarioDetails(plan);
    for (const [index, scenario] of scenarios.entries())
        option($("#scenario-selector"), index,
            `${scenario.name ?? `Scenario ${(scenario.scenarioIndex ?? index) + 1}`} · score ${number(scenario.score)}`);
    if (!scenarios.length)
        option($("#scenario-selector"), "", "Per-scenario details unavailable");
    $("#scenario-selector").disabled = !scenarios.length;
    const sets = dependencyAlternatives(plan);
    for (const [index, set] of sets.entries())
        option($("#dependency-set"), index,
            `Set ${index + 1} — ${(set.requirements ?? []).length} conjunctive requirements`);
    if (!sets.length)
        option($("#dependency-set"), "", "No structured external requirements reported");
    $("#dependency-set").disabled = !sets.length;
    $("#show-dependencies").disabled = !sets.length;
    renderInspector();
}

function renderInspector() {
    const plan = filtered[selectedIndex];
    if (!result || !plan || $("#plan-inspector").hidden)
        return;
    const context = resultContext;
    const limits = context.limits ?? context;
    $("#result-context").textContent =
        `Result-time context: ${result.nation}; ${result.board.year} ${result.board.phase}; `
        + `policy ${context.policy}; coordination ${context.coordinationMode}; `
        + `minimum observations ${limits.minimumObservations ?? limits.minimum}; `
        + `choices/unit ${limits.choicesPerUnit ?? limits.choices}; `
        + `national-plan limit ${limits.nationalPlanLimit ?? limits.plans}; `
        + `opponent-scenario limit ${limits.opponentScenarioLimit ?? limits.scenarios}; `
        + `evaluation budget ${limits.evaluationBudget ?? "unavailable"}; `
        + `foreign-assignment limit ${limits.foreignAssignmentLimit ?? "unavailable"}; `
        + `request comparison limit ${limits.comparisonLimit ?? "unavailable"}; `
        + `comparison scenario-evaluation budget ${limits.comparisonScenarioEvaluationBudget ?? "unavailable"}; `
        + `omitted choices ${context.pruning.omittedChoices ?? "unavailable"}; `
        + `geography guided ${context.pruning.geographyGuided ?? false}; `
        + `positional choices replenished ${context.pruning.replenishedChoices ?? 0}; `
        + `guidance choices unexamined ${context.pruning.guidanceChoicesUnexamined ?? 0}; `
        + `historical guidance scan cap ${limits.geographicChoiceLimit ?? "unavailable"}/unit; `
        + `beam truncated ${context.pruning.beamTruncated ?? "unavailable"}; `
        + `dependency search truncated ${context.pruning.dependencySearchTruncated ?? "unavailable"}; `
        + (context.positionOnly === true ? "position-only evidence; no route histories; "
            : context.positionOnly === false ? "history-based evidence; " : "evidence history mode unavailable; ")
        + `ruleset ${context.ruleset ?? bootstrap.ruleset}. `
        + `Source: ${context.source.label}`
        + (context.source.snapshot !== undefined
            ? `; snapshot ${context.source.snapshot}; status ${context.source.status}` : "")
        + ". Source metadata is display-only, not training input.";
    if (context.pruning.geographyGuided)
        $("#result-context").textContent +=
            " Guided search retains historical top K UNION positional top K (at most 2K/unit); "
            + "the beam remains bounded and tactical collisions remain primary ordering. "
            + "Destination estimates guide search only; final shaping uses actual adjudication.";
    const objectives = typeof context.objectives === "string"
        ? context.objectives.trim()
        : Object.entries(context.objectives ?? {}).map(([province, value]) => `${province} ${value}`).join("; ");
    $("#scoring-context").textContent =
        `Scoring: center weight ${context.centerWeight}; dislodgement penalty ${context.dislodgementPenalty}; `
        + `caution ${context.caution}; tactical bias weight ${context.tacticalBiasWeight}; `
        + `objectives: ${objectives || "none"}. `
        + `${result.scenarioCount} sampled scenarios have equal weight`
        + (result.scenarioCount ? ` (1/${result.scenarioCount})` : "") + "; not calibrated probabilities. "
        + (context.formula ?? "base=(1-caution)*mean+caution*worst; adjusted=base-weight*offendingMoveCount");
    $("#bias-summary").textContent = `${scoreSummary(plan)}. ${rankExplanation(plan)}. `
        + (plan.penaltyWeight === 0 ? "Bias disabled exactly; diagnostic warnings may remain. "
            : "Heuristic ranking adjustment only, not an adjudicated outcome change. ")
        + (context.coordinationMode === "RAW"
            ? "Raw generation is not reordered; the evaluator still applies the result-time weight. "
            : Number(context.tacticalBiasWeight) > 0
            ? "Any positive weight enables count-first coordinated search with bounded "
                + "max(256, beam width) grouping; penalty magnitude scales with weight. "
            : "Zero weight retains legacy search ordering. ")
        + "Coordination validity is separate from this heuristic; mean, worst, and scenario components remain raw.";
    const human = plan.humanPreference;
    $("#human-summary").textContent =
        `Raw mean/worst/combined ${number(plan.mean)} / ${number(plan.worst)} / ${number(plan.baseScore)}; `
        + `shaped mean/worst/combined ${number(plan.shapedMean ?? plan.mean)} / `
        + `${number(plan.shapedWorst ?? plan.worst)} / ${number(plan.shapedScore ?? plan.baseScore)}. `
        + `Human average log frequency ${number(human?.score)}; available ${human?.available ?? false}; `
        + `${human?.diagnostic ?? "Evidence details unavailable."} `
        + `Final = shaped ${number(plan.shapedScore ?? plan.baseScore)} − tactical ${number(plan.penaltyTotal)} `
        + `+ human ${number(plan.humanWeight)} × ${number(human?.score)} `
        + `(${number(plan.humanContribution)}) = ${number(plan.score)}.`;
    $("#human-units").replaceChildren();
    for (const unit of human?.units ?? [])
        appendText($("#human-units"), "li",
            `${unit.order?.text ?? `${unit.identity.nation} ${unit.identity.unitType} ${unit.identity.origin}`}: `
            + `${unit.selectedCount}/${unit.denominator} FULL selected counts (before top-K); `
            + `basis ${unit.basis}; log ${number(unit.logFrequency)}; available ${unit.available}. ${unit.diagnostic}`);
    $("#frozen-scoring").textContent = "Frozen result-time scoring configuration (all countries):\n"
        + JSON.stringify(plan.scoringConfiguration ?? context.scoringConfiguration ?? {}, null, 2);
    const scenario = scenarioDetails(plan)[Number($("#scenario-selector").value)];
    $("#scenario-metrics").textContent = scenario
        ? `Scenario score ${number(scenario.score)}; center-position component `
          + `${number(scenario.centerPositionDelta ?? scenario.centerComponent)}; `
          + `dislodgement penalty ${number(scenario.dislodgementPenalty ?? scenario.dislodgementComponent)}; `
          + `objective component ${number(scenario.objectiveDelta ?? scenario.objectiveComponent)}`
          + (scenario.dislodgedUnits !== undefined ? `; dislodged units ${scenario.dislodgedUnits}` : "")
          + `; province Δ ${number(scenario.provinceContribution)}; regional Δ ${number(scenario.regionalContribution)}; `
          + `augmented = raw + province + regional = ${number(scenario.augmentedScore)}.`
        : "Per-scenario score and objective components unavailable in this response.";
    renderShaping(plan, scenario);
    $("#scenario-orders").replaceChildren();
    const sampled = (result.scenarios ?? []).find(item => item.index === scenario?.scenarioIndex)
        ?? (result.scenarios ?? [])[scenario?.scenarioIndex];
    const opponentOrders = scenario?.opponentOrders ?? sampled?.orders;
    for (const order of opponentOrders ?? [])
        appendText($("#scenario-orders"), "li", order.text);
    if (!sampled && !scenario?.opponentOrders)
        appendText($("#scenario-orders"), "li", "Sampled opponent orders unavailable.");
    const setIndex = Number($("#dependency-set").value);
    const set = dependencyAlternatives(plan)[setIndex];
    const requirements = (set?.requirements ?? []).map(item => ({ ...item, text: requirementText(item) }));
    $("#structured-dependencies").replaceChildren();
    $("#dependency-legend").replaceChildren();
    $("#requirement-details").textContent = "";
    const satisfied = set?.satisfyingScenarios?.includes(scenario?.name)
        ?? scenario?.satisfiedDependencySets?.includes(setIndex);
    for (const [index, requirement] of requirements.entries()) {
        const order = requirement.order ?? requirement;
        appendText($("#structured-dependencies"), "li",
            `${index + 1}. ${order.text} — assumption; `
            + individualRequirementStatus(requirement, opponentOrders) + "; "
            + (scenario && satisfied !== undefined ? (satisfied ? "selected conjunction satisfied" : "selected conjunction not satisfied")
                + " in this sampled scenario" : "scenario satisfaction unavailable")
            + "; unconfirmed foreign order.");
        for (const affected of requirement.affectedFriendlyOrders ?? [])
            appendText($("#structured-dependencies"), "li", `Affected friendly order: ${affected.text}`);
    }
    for (const nation of new Set(requirements.map(item => item.nation ?? item.order?.nation))) {
        const item = appendText($("#dependency-legend"), "li", `${nation}: dashed-square requirements`);
        const swatch = document.createElement("span");
        swatch.className = "owner-swatch";
        swatch.style.borderColor = bootstrap.colors[nation] ?? "#172033";
        swatch.setAttribute("aria-hidden", "true");
        item.prepend(swatch);
    }
    if (!requirements.length)
        appendText($("#structured-dependencies"), "li",
            plan.dependencySets?.checked === false
                ? "External requirements were not checked. Raw combinations are not confirmed coordination."
                : (plan.dependencies ?? []).length
                ? "Legacy dependency prose only; structured requirement map unavailable."
                : "No external assumptions reported.");
    if (plan.dependencySets?.truncated)
        appendText($("#structured-dependencies"), "li", "Dependency search was truncated; reported sets are incomplete.");
    if (plan.dependencySets?.scenarioCount !== undefined)
        appendText($("#structured-dependencies"), "li",
            `At least one alternative satisfied in ${plan.dependencySets.satisfiedCount}/${plan.dependencySets.scenarioCount} `
            + "sampled scenarios; this is scenario coverage, not confirmed cooperation.");
    map.showRequirements($("#show-dependencies").checked ? requirements : [], bootstrap.colors,
        (requirement, index) => {
            $("#requirement-details").textContent =
                `Requirement ${index + 1}: ${requirementText(requirement)}. `
                + individualRequirementStatus(requirement, opponentOrders) + ". "
                + "Assumed foreign order, not a confirmed promise.";
        });
    $("#plan-findings").replaceChildren();
    for (const finding of plan.findings ?? []) {
        appendText($("#plan-findings"), "li",
            `Warning: ${finding.explanation ?? finding.message ?? finding}`
            + (finding.evaluatedStatus ? ` Comparison status: ${finding.evaluatedStatus}.` : ""),
            "finding-warning");
        for (const order of finding.orders ?? [])
            appendText($("#plan-findings"), "li",
                `${order.type === "MOVE" ? "Flagged incoming move" : "Related stationary order"}: ${order.text}`);
    }
    if (!(plan.findings ?? []).length)
        appendText($("#plan-findings"), "li",
            plan.findings ? "No self-dependency warnings reported."
                : "Self-dependency warning analysis unavailable in this response.");
    renderComparisons(plan);
}

function renderShaping(plan, scenario) {
    $("#province-shaping").replaceChildren();
    $("#regional-shaping").replaceChildren();
    for (const unit of scenario?.shaping?.units ?? [])
        appendText($("#province-shaping"), "li",
            `${unit.identity.nation} ${unit.identity.unitType}: ${unit.before} → ${unit.after ?? "dislodged"}; `
            + `effective province value ${number(unit.beforeValue)} → ${number(unit.afterValue)}; `
            + `contribution ${number(unit.contribution)}; surviving ${unit.surviving}. `
            + (!unit.surviving ? "Lost units contribute zero on both sides; losses are penalized separately." : ""));
    const profile = (plan.scoringConfiguration ?? resultContext.scoringConfiguration)
        ?.nationProfiles?.[result.nation];
    for (const goal of scenario?.shaping?.objectives ?? []) {
        const definition = profile?.objectives?.find(item => item.name === goal.name);
        appendText($("#regional-shaping"), "li",
            `${goal.name}: targets ${definition?.targets?.join(", ") ?? "unavailable"}; `
            + `types ${definition?.unitTypes?.join(", ") ?? "unavailable"}; priority ${number(definition?.priority)}; `
            + `horizon ${definition?.horizon ?? "unavailable"}; decay ${number(definition?.decay)}; `
            + `best-surviving-unit potential ${number(goal.beforePotential)} → ${number(goal.afterPotential)}; `
            + `bounded contribution ${number(goal.contribution)} (within ±priority).`);
        for (const unit of goal.units)
            appendText($("#regional-shaping"), "li",
                `${unit.identity.unitType} ${unit.identity.origin}: static movement distance `
                + `${unit.beforeDistance} → ${unit.afterDistance} (−1 = unreachable/lost); `
                + `potential ${number(unit.beforePotential)} → ${number(unit.afterPotential)}. `
                + "No convoy or opponent-cooperation assumptions; zero outside horizon.");
    }
}

function renderComparisons(plan) {
    const host = $("#plan-comparisons");
    host.replaceChildren();
    if (String(resultContext.compareAlternatives) !== "true") {
        appendText(host, "p", "Comparison not requested. Opt in before generating to compare alternatives.");
        return;
    }
    if (!(plan.comparisons ?? []).length) {
        appendText(host, "p", "Alternative comparisons unavailable: no evidence-backed alternatives reported.");
        return;
    }
    for (const [index, comparison] of plan.comparisons.entries()) {
        const section = document.createElement("section");
        host.append(section);
        appendText(section, "h5", `Alternative ${index + 1}`);
        if (comparison.available === false || (comparison.status && comparison.status !== "EVALUATED")) {
            appendText(section, "p", `Unavailable${comparison.status ? ` (${comparison.status})` : ""}: `
                + (comparison.diagnostic ?? comparison.reason ?? "No evidence-backed comparison."));
            continue;
        }
        appendText(section, "p", comparison.diagnostic ?? comparison.reason
            ?? "Evidence-backed alternative; same sampled scenarios.");
        if (comparison.provenance)
            appendText(section, "p", `Evidence: ${comparison.provenance.basis}; `
                + `${comparison.provenance.observations} observations; ${comparison.provenance.orderCount} order occurrences.`);
        appendText(section, "h6", "Original orders");
        const original = document.createElement("ul");
        section.append(original);
        for (const order of comparison.originalOrders ?? plan.orders)
            appendText(original, "li", order.text ?? order);
        appendText(section, "h6", "Alternative orders");
        const alternative = document.createElement("ul");
        section.append(alternative);
        for (const order of comparison.alternativeOrders ?? [])
            appendText(alternative, "li", order.text ?? order);
        const originalMean = comparison.originalMean ?? plan.mean;
        const originalWorst = comparison.originalWorst ?? plan.worst;
        appendText(section, "p",
            `For these exact sampled scenarios only: mean ${number(originalMean)} → `
            + `${number(comparison.alternativeMean ?? originalMean + comparison.meanDelta)} `
            + `(Δ ${number(comparison.meanDelta)}, ${deltaRelation(comparison.meanDelta)}); worst: ${number(originalWorst)} → `
            + `${number(comparison.alternativeWorst ?? originalWorst + comparison.worstDelta)} `
            + `(Δ ${number(comparison.worstDelta)}, ${deltaRelation(comparison.worstDelta)}); `
            + `raw outcome/base score Δ ${number(comparison.scoreDelta)} (${deltaRelation(comparison.scoreDelta)}); `
            + `positional/shaping Δ ${number(comparison.positionalDelta)}; human contribution Δ ${number(comparison.humanDelta)}; `
            + `penalty Δ ${number(comparison.penaltyDelta)} (alternative − original); `
            + `bias-adjusted score Δ ${number(comparison.adjustedScoreDelta)} `
            + `(${deltaRelation(comparison.adjustedScoreDelta)}). `
            + "A heuristic gain is not an adjudicated outcome improvement. "
            + "This does not establish universal superiority.");
        const scroll = document.createElement("div");
        scroll.className = "comparison-table-scroll";
        section.append(scroll);
        const table = document.createElement("table");
        scroll.append(table);
        appendText(table, "caption", "Per-scenario score comparison; Δ = alternative − original");
        const head = document.createElement("thead");
        table.append(head);
        const row = document.createElement("tr");
        head.append(row);
        for (const text of ["Scenario", "Original", "Alternative", "Delta", "Relation"]) {
            const cell = appendText(row, "th", text);
            cell.scope = "col";
        }
        const body = document.createElement("tbody");
        table.append(body);
        for (const scenario of comparison.scenarioDeltas ?? comparison.scenarios ?? []) {
            const row = document.createElement("tr");
            body.append(row);
            for (const text of [scenario.name ?? String(scenario.scenarioIndex + 1),
                number(scenario.original ?? scenario.originalScore),
                number(scenario.alternative ?? scenario.alternativeScore), number(scenario.delta),
                ["BETTER", "EQUAL", "WORSE"].includes(scenario.relation)
                    ? scenario.relation : deltaRelation(scenario.delta)])
                appendText(row, "td", text);
        }
    }
}

function deltaRelation(value) {
    if (value === null || value === undefined || !Number.isFinite(Number(value)))
        return "unavailable";
    return Number(value) > 0 ? "BETTER" : Number(value) < 0 ? "WORSE" : "EQUAL";
}

function scoreSummary(plan) {
    return `Adjusted ${number(plan.score)} · base ${number(plan.baseScore)} · `
        + `shaped ${number(plan.shapedScore ?? plan.baseScore)} · human ${number(plan.humanContribution)} · `
        + `offending moves ${plan.offendingMoveCount ?? "unavailable"} · `
        + `weight ${number(plan.penaltyWeight)} · penalty total ${number(plan.penaltyTotal)}`;
}

function rankExplanation(plan) {
    if (plan.baseRank === undefined)
        return "Base rank unavailable";
    return plan.baseRank === plan.rank
        ? `Base rank ${plan.baseRank} unchanged after shaping/preferences/bias`
        : `Base rank ${plan.baseRank} → adjusted rank ${plan.rank}: `
            + "raw outcome plus positional shaping and human preference minus tactical bias reorders candidates";
}

function offendingIncoming(plan, order) {
    return order.type === "MOVE" && (plan.findings ?? []).some(finding =>
        (finding.orders ?? []).some(flagged =>
            flagged.type === "MOVE" && flagged.origin === order.origin
            && flagged.target === order.target));
}

function number(value) {
    return value !== null && value !== undefined && Number.isFinite(Number(value))
        ? Number(value).toFixed(3) : "unavailable";
}

start().catch(error => {
    console.error(error);
    $("#error").textContent = error.message;
    $("#summary").textContent = "Unable to initialize strategy browser.";
});
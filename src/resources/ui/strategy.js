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

let importedGame = null;
let importedPhase = null;
let fileReadVersion = 0;

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

    restoreStartingPosition();

    form.addEventListener("input", invalidate);
    form.addEventListener("change", invalidate);
    form.addEventListener("submit", generate);

    $("#reset-position").addEventListener("click", restoreStartingPosition);
    $("#search").addEventListener("input", renderList);
    $("#previous").addEventListener("click", () => navigate(-1));
    $("#next").addEventListener("click", () => navigate(1));

    $("#game-file").addEventListener("change", loadGameFile);
    $("#import-phase-button").addEventListener("click", importSelectedPhase);
    $("#show-historical").addEventListener("click", showHistorical);

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
    $("#position").textContent = "0 / 0";

    $("#previous").disabled = true;
    $("#next").disabled = true;
    $("#search").disabled = true;
    $("#search").value = "";
}

function invalidate(event) {
    if (busy)
        return;

    clearResults();
    $("#error").textContent = "";

    if (POSITION_FIELDS.has(event.target.name)) {
        importedPhase = null;
        $("#import-status").textContent =
            "Position edited. Historical comparison detached; reimport a phase to restore it.";
    }

    refreshHistorical();

    if (importedPhase) {
        map.show(importedPhase.board, bootstrap.colors, $("#nation").value);
        $("#selection-title").textContent = importedPositionTitle();
    } else {
        map.clear();
    }

    $("#summary").textContent =
        "Position or settings changed. Press Generate to evaluate this form.";
}

function restoreStartingPosition() {
    if (busy)
        return;

    for (const [name, value] of Object.entries(bootstrap.defaults))
        form.elements.namedItem(name).value = value;

    importedPhase = null;
    clearResults();
    refreshHistorical();

    $("#error").textContent = "";
    map.show(bootstrap.board, bootstrap.colors, $("#nation").value);
    $("#selection-title").textContent = "Starting position — Spring 1901";
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

    $("#import-phase").replaceChildren();
    $("#import-phase").disabled = true;
    $("#import-phase-button").disabled = true;

    clearResults();
    refreshHistorical();
    map.clear();

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

    clearResults();
    $("#error").textContent = "";
    refreshHistorical();

    map.show(phase.board, bootstrap.colors, $("#nation").value);
    $("#selection-title").textContent = importedPositionTitle();

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
        + "Source data, not locally replay-verified.";

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
        "Comparison overlay only. Click a ranked plan to return to generated recommendations.";

    map.show(importedPhase.board, bootstrap.colors, nation, { nation, orders });
}

async function generate(event) {
    event.preventDefault();

    if (busy || !form.reportValidity())
        return;

    // Only the editor's named position/configuration controls are submitted.
    // Imported source orders and metadata are outside this form.
    const body = new URLSearchParams();

    for (const [key, value] of new FormData(form))
        body.append(key, String(value));

    busy = true;
    $("#fields").disabled = true;
    $("#import-fields").disabled = true;
    $("#show-historical").disabled = true;
    $("#editor").setAttribute("aria-busy", "true");

    clearResults();
    map.clear();
    $("#error").textContent = "";
    $("#summary").textContent = "Generating and evaluating movement plans…";

    try {
        result = await fetch("/api/generate", {
            method: "POST",
            headers: {
                "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
                "X-Strategy-Token": bootstrap.token
            },
            body
        }).then(responseJson);

        $("#summary").textContent = result.settings;
        $("#search").disabled = result.plans.length === 0;

        $("#diagnostic-summary").textContent =
            `Prediction evidence (${result.diagnostics.length})`;

        for (const message of result.diagnostics) {
            const item = document.createElement("li");
            item.textContent = message;
            $("#diagnostic-list").append(item);
        }

        renderList();

    } catch (error) {
        console.error(error);
        clearResults();
        map.clear();
        $("#error").textContent = error.message;
        $("#summary").textContent =
            "Generation failed. No previous recommendation is being displayed.";
    } finally {
        busy = false;
        $("#fields").disabled = false;
        $("#import-fields").disabled = false;
        $("#editor").setAttribute("aria-busy", "false");
        refreshHistorical();
    }
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
            `Rank ${plan.rank} · score ${number(plan.score)} · `
            + `mean ${number(plan.mean)} · worst ${number(plan.worst)}`;
        button.append(heading);

        for (const order of plan.orders) {
            const line = document.createElement("span");
            line.className = "order";
            line.textContent = order.text;
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
            : "No recommendation. Inspect the prediction evidence.";
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
        ? `Score ${number(plan.score)}; mean ${number(plan.mean)}; `
          + `worst ${number(plan.worst)}; log preference ${number(plan.logPreference)}.`
        : "";

    map.show(result.board, bootstrap.colors, result.nation, plan);
}

function navigate(offset) {
    const next = selectedIndex + offset;

    if (next < 0 || next >= filtered.length)
        return;

    select(next);
    $("#opening-list").querySelectorAll("button")[next]
        .scrollIntoView({ block: "nearest" });
}

function number(value) {
    return Number(value).toFixed(3);
}

start().catch(error => {
    console.error(error);
    $("#error").textContent = error.message;
    $("#summary").textContent = "Unable to initialize strategy browser.";
});
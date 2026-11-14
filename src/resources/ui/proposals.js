import { MovementMap } from "./movement-map.js";

const $ = selector => document.querySelector(selector);
const labels = { LINEAR_POLICY: "Linear policy", GRU_POLICY: "GRU policy", ANCHOR: "Linear anchor", SEARCH: "Search probability" };
let catalog, map, result;
const position = () => catalog.positions.find(value => value.id === $("#board-position").value);
const numeric = value => Number(value);
const log = value => numeric(value) === -Infinity ? "-Infinity" : numeric(value).toFixed(6);
const probability = value => {
    const p = Math.exp(numeric(value));
    return p === 0 && numeric(value) !== -Infinity ? "underflow (see log P)" : p.toExponential(4);
};

async function json(url, options) {
    const response = await fetch(url, options);
    const body = await response.json();
    if (!response.ok) throw new Error(body.error || `Request failed (${response.status})`);
    return body;
}

function showPosition() {
    result = null;
    $("#ranking").disabled = $("#top").disabled = $("#download").disabled = true;
    $("#opening-list").replaceChildren();
    $("#list-summary").textContent = "";
    $("#metadata").textContent = JSON.stringify(catalog.models, null, 2);
    const current = position(), board = current.board;
    $("#selection-title").textContent = `${current.label} / ${$("#power").value}`;
    $("#position-details").textContent = `${board.phase} ${board.year} / source communication: ${current.communication ?? "UNKNOWN"}`;
    map.show(board, $("#power").value);
    $("#summary").textContent = "Ready. Choose budgets and press Inspect.";
}

function changePosition() {
    const previous = $("#power").value;
    $("#power").replaceChildren(...position().powers.map(power => new Option(power, power)));
    if (position().powers.includes(previous)) $("#power").value = previous;
    showPosition();
}

function renderRanking() {
    if (!result || !$("#top").reportValidity()) return;
    const inspection = result.inspection;
    const indices = inspection.rankings[$("#ranking").value].slice(0, Number($("#top").value));
    const shown = indices.map(index => inspection.proposals[index]);
    const anchorMass = shown.reduce((sum, value) => sum + value.anchorPoolProbability, 0);
    const searchMass = inspection.options.search ? shown.reduce((sum, value) => sum + value.search.probability, 0) : null;
    $("#list-summary").textContent = `${shown.length} shown / ${inspection.proposals.length} retained / ${inspection.options.candidates} requested. `
        + `Shown anchor-pool mass: ${(100 * anchorMass).toFixed(2)}%.`
        + (searchMass === null ? "" : ` Shown search mass: ${(100 * searchMass).toFixed(2)}%.`);
    const generation = inspection.generation[inspection.power];
    if (generation)
        $("#list-summary").textContent += ` Selected: ${generation.learnedSelected} learned, ${generation.heuristicSelected} plausible. `
            + `Construction: ${generation.attempts} attempts, ${generation.deadEnds} dead ends, ${generation.duplicates} duplicates.`;
    $("#summary").textContent = "Inspection ready. Ranked by " + labels[$("#ranking").value]
        + ". Ranking and Top X reuse this pool. Model weights were not changed.";
    $("#opening-list").replaceChildren();
    for (const [rank, proposal] of shown.entries()) {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "opening";
        button.dataset.index = proposal.index;
        button.setAttribute("aria-pressed", "false");
        const title = document.createElement("strong");
        title.textContent = `${rank + 1}. Candidate ${proposal.index + 1} / ${proposal.source}`;
        const scores = document.createElement("span");
        scores.className = "scores";
        scores.textContent = `Linear P=${probability(proposal.logLinear)}, log P=${log(proposal.logLinear)}; `
            + `GRU P=${probability(proposal.logGru)}, log P=${log(proposal.logGru)}; `
            + `anchor log P=${log(proposal.logAnchor)}, pool P=${proposal.anchorPoolProbability.toFixed(6)}. `
            + (proposal.search ? `Search P=${proposal.search.probability.toFixed(6)}, Q=${proposal.search.meanValue.toFixed(6)}, `
                + `observations=${proposal.search.samples}.` : "Search not run.");
        button.append(title, scores);
        const counts = proposal.coordination;
        const coordination = document.createElement("span");
        coordination.className = counts.mismatchedSupports || counts.mismatchedConvoys ? "mismatch" : "scores";
        coordination.textContent = `Own support mismatches ${counts.mismatchedSupports}/${counts.ownSupports}; `
            + `own convoy mismatches ${counts.mismatchedConvoys}/${counts.ownConvoys}.`;
        button.append(coordination);
        for (const [index, order] of proposal.orders.entries()) {
            const line = document.createElement("span");
            line.className = "order";
            line.style.setProperty("--nation-color", catalog.colors[inspection.power]);
            line.classList.toggle("germany", inspection.power === "GERMANY");
            line.textContent = order.text;
            button.append(line);
            for (const finding of proposal.findings.filter(value => value.orderIndex === index)) {
                const explanation = document.createElement("span");
                explanation.className = "finding " + finding.kind.toLowerCase();
                explanation.textContent = `${finding.kind}: ${finding.message}`;
                button.append(explanation);
            }
        }
        if (!proposal.findings.length) {
            const note = document.createElement("span");
            note.className = "scores";
            note.textContent = "No structural findings; these orders may still fail against opposing orders.";
            button.append(note);
        }
        if (!proposal.orders.length) button.append(document.createTextNode("Empty action: no units."));
        button.addEventListener("click", () => select(proposal));
        $("#opening-list").append(button);
    }
    select(shown[0]);
}

function select(proposal) {
    for (const button of $("#opening-list").querySelectorAll("button"))
        button.setAttribute("aria-pressed", String(Number(button.dataset.index) === proposal.index));
    const inspection = result.inspection;
    $("#selection-title").textContent = `${result.position.label} / ${inspection.power} / candidate ${proposal.index + 1}`;
    map.show(inspection.board, inspection.power, proposal.orders);
}

async function inspect(event) {
    event.preventDefault();
    showPosition();
    $("#request-controls").disabled = true;
    $("#summary").textContent = "Generating proposals" + ($("#search").checked ? " and searching all living powers..." : "...");
    document.body.setAttribute("aria-busy", "true");
    try {
        const request = { position: position().id, power: $("#power").value, method: $("#method").value, search: $("#search").checked };
        for (const name of ["candidates", "iterations", "rollouts", "rolloutCandidates"]) request[name] = Number($("#" + name).value);
        for (const name of ["poolSeed", "searchSeed", "rolloutSeed"]) request[name] = $("#" + name).value;
        result = await json("/api/inspect", {
            method: "POST", headers: { "Content-Type": "application/json", "X-JReferee-Inspect": "1" }, body: JSON.stringify(request)
        });
        $("#ranking").replaceChildren(...Object.keys(result.inspection.rankings).map(key => new Option(labels[key], key)));
        $("#ranking").value = result.inspection.options.search ? "SEARCH" : "GRU_POLICY";
        $("#top").max = result.inspection.options.candidates;
        $("#top").value = Math.max(1, Math.min(Number($("#top").value) || 5, result.inspection.options.candidates));
        $("#ranking").disabled = $("#top").disabled = $("#download").disabled = false;
        const { models, options, searchSettings, work, poolSizes, generation } = result.inspection;
        $("#metadata").textContent = JSON.stringify({ models, options, searchSettings, work, poolSizes, generation }, null, 2);
        renderRanking();
    } catch (error) {
        showPosition();
        console.error(error);
        $("#summary").textContent = "Inspection failed: " + error.message;
    } finally {
        $("#request-controls").disabled = false;
        document.body.setAttribute("aria-busy", "false");
    }
}

async function start() {
    catalog = await json("/api/positions");
    map = await MovementMap.create($("#map-host"), $("#province-details"), $("#map-warning"), catalog.colors, true);
    $("#board-position").replaceChildren(...catalog.positions.map(value => new Option(value.label, value.id)));
    $("#board-position").addEventListener("change", changePosition);
    $("#request-controls").addEventListener("change", event => { if (event.target.id !== "board-position") showPosition(); });
    $("#request").addEventListener("submit", inspect);
    $("#ranking").addEventListener("change", renderRanking);
    $("#top").addEventListener("change", renderRanking);
    $("#download").addEventListener("click", () => {
        const blob = new Blob([JSON.stringify({ ...result, displayed: { ranking: $("#ranking").value, count: Number($("#top").value) } }, null, 2)],
            { type: "application/json" });
        const url = URL.createObjectURL(blob), link = document.createElement("a");
        link.href = url; link.download = "proposal-inspection.json"; link.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
    });
    $("#request-controls").disabled = false;
    changePosition();
}

start().catch(error => {
    console.error(error);
    $("#summary").textContent = "Unable to start proposal viewer: " + error.message;
});
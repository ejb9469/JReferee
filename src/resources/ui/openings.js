import { MovementMap } from "./movement-map.js";

let catalog;
let map;
let filtered = [];
let selectedIndex = -1;

const $ = selector => document.querySelector(selector);
const nationSelect = $("#nation");
const searchInput = $("#search");
const openingList = $("#opening-list");

async function startBrowser() {
    const response = await fetch("/api/openings");
    if (!response.ok)
        throw new Error(`Request failed: ${response.status} ${response.statusText}`);

    catalog = await response.json();

    if (!catalog || !Array.isArray(catalog.openings)
            || !catalog.board || !catalog.colors)
        throw new Error("The server returned an invalid opening catalog.");

    map = await MovementMap.create(
        $("#map-host"), $("#province-details"), $("#map-warning"), catalog.colors);

    for (const nation of Object.keys(catalog.colors))
        nationSelect.add(new Option(nation, nation));

    if (catalog.openings.length)
        nationSelect.value = catalog.openings[0].nation;

    nationSelect.disabled = false;
    searchInput.disabled = false;

    nationSelect.addEventListener("change", filterOpenings);
    searchInput.addEventListener("input", filterOpenings);
    $("#previous").addEventListener("click", () => navigate(-1));
    $("#next").addEventListener("click", () => navigate(1));

    document.addEventListener("keydown", event => {
        if (event.target instanceof Element
                && event.target.closest(
                    "input, select, textarea, button, [contenteditable]"))
            return;

        if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
            event.preventDefault();
            navigate(event.key === "ArrowLeft" ? -1 : 1);
        }
    });

    $("#summary").textContent =
        `${catalog.scanned} games scanned · ${catalog.represented} represented · `
        + `${catalog.skipped} skipped · ${catalog.nationalOpenings} complete national openings`;

    $("#diagnostic-summary").textContent =
        `Import diagnostics (${catalog.diagnostics.length})`;

    for (const message of catalog.diagnostics) {
        const item = document.createElement("li");
        item.textContent = message;
        $("#diagnostic-list").append(item);
    }

    filterOpenings();
}

function filterOpenings() {
    const nation = nationSelect.value;
    const query = searchInput.value.trim().toLowerCase();
    const national = catalog.openings.filter(opening => opening.nation === nation);
    const total = national.reduce((sum, opening) => sum + opening.count, 0);

    filtered = national.filter(opening =>
        opening.orders.some(order => order.text.toLowerCase().includes(query)));

    filtered.sort((a, b) =>
        b.count - a.count
        || (a.signature < b.signature ? -1 : a.signature > b.signature ? 1 : 0));

    $("#list-title").textContent = nation;
    applyNationColor($("#list-title"), nation);
    $("#list-title").classList.add("country-name");

    $("#list-summary").textContent =
        `${filtered.length} of ${national.length} openings · ${total} complete submissions`;

    openingList.replaceChildren();

    for (const [index, opening] of filtered.entries()) {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "opening";
        button.setAttribute("aria-pressed", "false");

        const count = document.createElement("span");
        count.className = "opening-count";
        count.textContent =
            `${opening.count} game(s) · ${(100 * opening.count / total).toFixed(1)}%`;
        button.append(count);

        for (const order of opening.orders) {
            const line = document.createElement("span");
            line.className = "order";
            line.textContent = order.text;
            applyNationColor(line, nation);
            button.append(line);
        }

        button.addEventListener("click", () => selectOpening(index));
        openingList.append(button);
    }

    if (!filtered.length) {
        const empty = document.createElement("p");
        empty.textContent = "No matching openings.";
        openingList.append(empty);
    }

    selectOpening(filtered.length ? 0 : -1);
}

function selectOpening(index) {
    selectedIndex = index;

    for (const [position, button] of openingList.querySelectorAll("button").entries())
        button.setAttribute("aria-pressed", String(position === index));

    $("#previous").disabled = index <= 0;
    $("#next").disabled = index < 0 || index >= filtered.length - 1;
    $("#position").textContent = `${index < 0 ? 0 : index + 1} / ${filtered.length}`;
    $("#selection-title").textContent = index < 0
        ? "No opening selected"
        : `${filtered[index].nation} — Submitted orders — Spring 1901`;

    map.show(catalog.board, nationSelect.value,
        index < 0 ? [] : filtered[index].orders);
}

function navigate(offset) {
    const next = selectedIndex + offset;
    if (next < 0 || next >= filtered.length) return;

    selectOpening(next);
    openingList.querySelectorAll("button")[next].scrollIntoView({
        block: "nearest",
        behavior: "smooth"
    });
}

function applyNationColor(element, nation) {
    element.style.setProperty("--nation-color", catalog.colors[nation]);
    element.classList.toggle("germany", nation === "GERMANY");
}

startBrowser().catch(error => {
    console.error(error);
    $("#summary").textContent = "Unable to load openings: " + error.message;
});
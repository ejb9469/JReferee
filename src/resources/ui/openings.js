"use strict";

import { BoardMap } from "./board-map.js";

const $ = selector => document.querySelector(selector);

let catalog;
let map;
let filtered = [];
let selectedIndex = -1;

async function start() {
    const response = await fetch("/api/openings");
    if (!response.ok)
        throw new Error(`Catalog request failed: ${response.status}`);

    catalog = await response.json();

    if (!Array.isArray(catalog.openings) || !catalog.board || !catalog.colors)
        throw new Error("Invalid opening catalog.");

    map = await BoardMap.create(
        $("#map-host"), $("#province-details"), $("#map-warning"));

    for (const nation of Object.keys(catalog.colors)) {
        const option = document.createElement("option");
        option.value = nation;
        option.textContent = nation;
        $("#nation").append(option);
    }

    if (catalog.openings.length)
        $("#nation").value = catalog.openings[0].nation;

    $("#nation").disabled = false;
    $("#search").disabled = false;
    $("#hide-singletons").disabled = false;

    $("#nation").addEventListener("change", filterOpenings);
    $("#search").addEventListener("input", filterOpenings);
    $("#hide-singletons").addEventListener("change", filterOpenings);
    $("#previous").addEventListener("click", () => navigate(-1));
    $("#next").addEventListener("click", () => navigate(1));

    document.addEventListener("keydown", event => {
        if (event.target instanceof Element &&
            event.target.closest(
                "input, select, textarea, button, summary, svg, [contenteditable]"))
            return;

        if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
            event.preventDefault();
            navigate(event.key === "ArrowLeft" ? -1 : 1);
        }
    });

    $("#summary").textContent =
        `${catalog.scanned} games scanned · ${catalog.represented} represented · `
        + `${catalog.skipped} skipped · ${catalog.nationalOpenings} complete national openings`;

    const diagnostics = catalog.diagnostics ?? [];
    $("#diagnostic-summary").textContent =
        `Import diagnostics (${diagnostics.length})`;

    for (const message of diagnostics) {
        const item = document.createElement("li");
        item.textContent = message;
        $("#diagnostic-list").append(item);
    }

    filterOpenings();
}

function filterOpenings() {
    const nation = $("#nation").value;
    const query = $("#search").value.trim().toLowerCase();
    const hideSingletons = $("#hide-singletons").checked;

    const national = catalog.openings.filter(item => item.nation === nation);

    // Deliberately independent of search and singleton filtering.
    const totalSubmissions =
        national.reduce((sum, item) => sum + item.count, 0);

    const singletons = national.filter(item => item.count === 1).length;

    filtered = national.filter(item =>
        (!hideSingletons || item.count !== 1) &&
        item.orders.some(order => order.text.toLowerCase().includes(query)));

    filtered.sort((a, b) =>
        b.count - a.count ||
        (a.signature < b.signature ? -1 : a.signature > b.signature ? 1 : 0));

    $("#list-title").textContent = nation;
    colorize($("#list-title"), nation);
    $("#list-title").classList.add("country-name");

    $("#list-summary").textContent =
        `${filtered.length} of ${national.length} openings · `
        + `${totalSubmissions} total submissions`
        + (hideSingletons ? ` · ${singletons} single-entry openings excluded` : "")
        + ". Percentages use all country submissions.";

    $("#opening-list").replaceChildren();

    for (const [index, opening] of filtered.entries()) {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "opening";
        button.setAttribute("aria-pressed", "false");

        const count = document.createElement("span");
        count.className = "opening-count";
        const percentage = totalSubmissions
            ? (100 * opening.count / totalSubmissions).toFixed(1)
            : "0.0";
        count.textContent = `${opening.count} game(s) · ${percentage}%`;
        button.append(count);

        for (const order of opening.orders) {
            const line = document.createElement("span");
            line.className = "order";
            line.textContent = order.text;
            colorize(line, nation);
            button.append(line);
        }

        button.addEventListener("click", () => select(index));
        $("#opening-list").append(button);
    }

    if (!filtered.length) {
        const message = document.createElement("p");
        message.textContent = "No openings match the current filters.";
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

    const opening = index < 0 ? null : filtered[index];
    $("#selection-title").textContent = opening
        ? `${opening.nation} — Submitted orders — Spring 1901`
        : "No opening selected";

    map.show(catalog.board, catalog.colors, $("#nation").value, opening);
}

function navigate(offset) {
    const next = selectedIndex + offset;
    if (next < 0 || next >= filtered.length)
        return;

    select(next);
    $("#opening-list").querySelectorAll("button")[next]
        .scrollIntoView({ block: "nearest" });
}

function colorize(element, nation) {
    element.style.setProperty("--nation-color", catalog.colors[nation]);
    element.classList.toggle("germany", nation === "GERMANY");
}

start().catch(error => {
    console.error(error);
    $("#summary").textContent = "Unable to load openings: " + error.message;
});
const SVG_NS = "http://www.w3.org/2000/svg";

/*
 * Split-coast locations are valid JReferee unit locations, but the supplied
 * map has one visible region each for St Petersburg, Spain, and Bulgaria.
 *
 * These values are offsets in the SVG viewBox coordinate system. Tune them
 * visually in the browser or replace them with explicit anchor coordinates.
 */
const COAST_OFFSETS = {
  stpnc: { dx: 10, dy: -7 },
  stpsc: { dx: 8, dy: 9 },
  spanc: { dx: -5, dy: -3 },
  spasc: { dx: 5, dy: 4 },
  bulec: { dx: 4, dy: 1 },
  bulsc: { dx: -3, dy: 4 }
};

const NATION_COLORS = {
  AUSTRIA: "#c48f85",
  ENGLAND: "#7562b8",
  FRANCE: "#4b84cc",
  GERMANY: "#6f6f6f",
  ITALY: "#4f9a62",
  RUSSIA: "#a87e9f",
  TURKEY: "#d7ae4a"
};

let board = null;
let svgRoot = null;
let selectedProvince = null;

/*
 * Expected API shape:
 *
 * {
 *   "year": 1901,
 *   "phase": "SPRING_MOVEMENT",
 *   "units": [
 *     { "nation": "ENGLAND", "type": "FLEET", "province": "Edi" }
 *   ],
 *   "supplyCenterOwners": {
 *     "Edi": "ENGLAND"
 *   }
 * }
 */
async function startViewer() {
  const [svgText, snapshot] = await Promise.all([
    fetch("/ui/maps/standard.svg").then(requireOk).then(response => response.text()),
    fetch("/api/board").then(requireOk).then(response => response.json())
  ]);

  board = indexSnapshot(snapshot);

  const host = document.querySelector("#map-host");
  host.innerHTML = svgText;

  svgRoot = host.querySelector("svg");
  svgRoot.classList.add("board-svg");

  installProvinceInteractions();
  renderUnits();
  renderTurnLabel();
  clearSelection();

  document.querySelector("#reset-selection")
    .addEventListener("click", clearSelection);

  document.addEventListener("keydown", event => {
    if (event.key === "Escape") {
      clearSelection();
    }
  });
}

function requireOk(response) {
  if (!response.ok) {
    throw new Error(
      `Request failed: ${response.status} ${response.statusText}`
    );
  }

  return response;
}

function indexSnapshot(snapshot) {
  return {
    ...snapshot,
    unitsByLocation: new Map(
      snapshot.units.map(unit => [keyOf(unit.province), unit])
    )
  };
}

/*
 * JReferee uses mixed-case land codes and uppercase sea codes. SVG IDs are
 * lowercase. Normalizing to lowercase makes both formats compatible.
 */
function keyOf(province) {
  return province.toLowerCase();
}

/*
 * A click on any coast-specific location selects the visible parent region.
 */
function visibleProvinceKey(province) {
  const key = keyOf(province);

  const parents = {
    stpnc: "stp",
    stpsc: "stp",
    spanc: "spa",
    spasc: "spa",
    bulec: "bul",
    bulsc: "bul"
  };

  return parents[key] ?? key;
}

function installProvinceInteractions() {
  const provincePaths = svgRoot.querySelectorAll("#provinces > path[id]");

  for (const path of provincePaths) {
    /*
     * Skip the non-province black decorative path between France and Germany.
     * It has no matching JReferee Province value.
     */
    if (path.id === "path4603") {
      continue;
    }

    path.classList.add("province");
    path.setAttribute("tabindex", "0");
    path.setAttribute("role", "button");
    path.setAttribute("aria-label", path.id.toUpperCase());

    path.addEventListener("click", () => selectProvince(path.id));

    path.addEventListener("keydown", event => {
      if (event.key === "Enter" || event.key === " ") {
        event.preventDefault();
        selectProvince(path.id);
      }
    });

    path.addEventListener("pointerenter", () => {
      showTooltip(path.id);
    });

    path.addEventListener("pointermove", moveTooltip);
    path.addEventListener("pointerleave", hideTooltip);
  }
}

function selectProvince(province) {
  selectedProvince = visibleProvinceKey(province);

  for (const path of svgRoot.querySelectorAll("#provinces > path[id]")) {
    path.classList.toggle(
      "is-selected",
      path.id === selectedProvince
    );
  }

  renderInspector(selectedProvince);
}

function clearSelection() {
  selectedProvince = null;

  if (svgRoot) {
    for (const path of svgRoot.querySelectorAll(".province")) {
      path.classList.remove("is-selected");
    }
  }

  document.querySelector("#selection-empty").hidden = false;
  document.querySelector("#province-details").hidden = true;
}

function renderTurnLabel() {
  document.querySelector("#turn-label").textContent =
    `${board.phase.replaceAll("_", " ")} ${board.year}`;
}

function renderInspector(provinceKey) {
  const matchingUnits = board.units
    .filter(unit => visibleProvinceKey(unit.province) === provinceKey);

  const owner = board.supplyCenterOwners[
    enumStyleProvince(provinceKey)
  ] ?? "Not a supply center";

  document.querySelector("#selection-empty").hidden = true;
  document.querySelector("#province-details").hidden = false;

  document.querySelector("#detail-province").textContent =
    enumStyleProvince(provinceKey);

  document.querySelector("#detail-owner").textContent = owner;

  document.querySelector("#detail-unit").textContent =
    matchingUnits.length === 0
      ? "No unit"
      : matchingUnits
          .map(unit => `${unit.nation} ${unit.type} (${unit.province})`)
          .join(", ");
}

/*
 * For a polished viewer, return display names from the Java API:
 * "Paris", "North Sea", "St Petersburg", etc.
 *
 * This fallback is only for a basic prototype.
 */
function enumStyleProvince(provinceKey) {
  const seaSpaces = new Set([
    "adr", "aeg", "bal", "bar", "bla", "bot", "eas", "eng",
    "hel", "ion", "iri", "lyo", "mao", "nao", "nth", "nwg",
    "ska", "tys", "wes"
  ]);

  if (seaSpaces.has(provinceKey)) {
    return provinceKey.toUpperCase();
  }

  return provinceKey.charAt(0).toUpperCase() + provinceKey.slice(1);
}

function renderUnits() {
  const unitLayer = svgRoot.querySelector("#units");

  if (!unitLayer) {
    throw new Error("standard.svg is missing the #units layer.");
  }

  unitLayer.replaceChildren();

  for (const unit of board.units) {
    const token = createUnitToken(unit);
    unitLayer.append(token);
  }
}

function createUnitToken(unit) {
  const exactLocation = keyOf(unit.province);
  const visibleLocation = visibleProvinceKey(unit.province);
  const provincePath = svgRoot.querySelector(
    `#provinces > path#${CSS.escape(visibleLocation)}`
  );

  if (!provincePath) {
    console.warn(`No SVG province path for unit at ${unit.province}.`);
    return document.createDocumentFragment();
  }

  const anchor = unitAnchor(provincePath, exactLocation);
  const group = document.createElementNS(SVG_NS, "g");

  group.classList.add("unit-token");
  group.dataset.province = unit.province;
  group.setAttribute("transform", `translate(${anchor.x} ${anchor.y})`);
  group.setAttribute(
    "aria-label",
    `${unit.nation} ${unit.type} in ${unit.province}`
  );

  const body = document.createElementNS(SVG_NS, "circle");
  body.setAttribute("r", "2.6");
  body.setAttribute("fill", NATION_COLORS[unit.nation] ?? "#ffffff");

  const label = document.createElementNS(SVG_NS, "text");
  label.setAttribute("text-anchor", "middle");
  label.setAttribute("dominant-baseline", "central");
  label.setAttribute("font-size", "2.3");
  label.setAttribute("font-weight", "bold");
  label.setAttribute("fill", "#ffffff");
  label.setAttribute("pointer-events", "none");
  label.textContent = unit.type === "ARMY" ? "A" : "F";

  group.append(body, label);

  group.addEventListener("click", event => {
    event.stopPropagation();
    selectProvince(unit.province);
  });

  group.addEventListener("pointerenter", () => {
    showTooltip(
      `${unit.province}: ${unit.nation} ${unit.type}`
    );
  });

  group.addEventListener("pointermove", moveTooltip);
  group.addEventListener("pointerleave", hideTooltip);

  return group;
}

function unitAnchor(provincePath, exactLocation) {
  const box = provincePath.getBBox();
  const offset = COAST_OFFSETS[exactLocation] ?? { dx: 0, dy: 0 };

  return {
    x: box.x + (box.width / 2) + offset.dx,
    y: box.y + (box.height / 2) + offset.dy
  };
}

function showTooltip(message) {
  const tooltip = document.querySelector("#tooltip");
  tooltip.textContent = message;
  tooltip.hidden = false;
}

function moveTooltip(event) {
  const tooltip = document.querySelector("#tooltip");
  tooltip.style.left = `${event.clientX + 12}px`;
  tooltip.style.top = `${event.clientY + 12}px`;
}

function hideTooltip() {
  document.querySelector("#tooltip").hidden = true;
}

startViewer().catch(error => {
  console.error(error);

  const turnLabel = document.querySelector("#turn-label");
  if (turnLabel) {
    turnLabel.textContent = "Unable to load board";
  }
});
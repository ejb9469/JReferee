const SVG_NS = "http://www.w3.org/2000/svg";

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

const NEUTRAL_SUPPLY_CENTER_COLOR = "#f8fafc";
const SUPPLY_CENTER_OUTLINE_COLOR = "#111827";
const SUPPLY_CENTER_DOT_COLOR = "#ffffff";

/*
 * Existing Inkscape marker IDs in standard.svg's #layer5.
 * Values use the canonical JReferee Province name returned by /api/history.
 */
const SUPPLY_CENTER_INDICATORS = {
  "g5246": "Bel",
  "g5246-3": "Hol",
  "g5246-6": "Kie",
  "g5246-9": "Ber",
  "g5246-0": "Mun",
  "g5246-8": "Vie",
  "g5246-02": "Bud",
  "g5246-92": "Tri",
  "g5246-36": "Ser",
  "g5246-93": "Bul",
  "g5246-7": "Rum",
  "g5246-03": "Con",
  "g5246-63": "Ank",
  "g5246-5": "Smy",
  "g5246-56": "Sev",
  "g5246-74": "Mos",
  "g5246-84": "Stp",
  "g5246-2": "War",
  "g5246-926": "Ven",
  "g5246-04": "Rom",
  "g5246-72": "Nap",
  "g5246-1": "Tun",
  "g5246-94": "Mar",
  "g5246-77": "Spa",
  "g5246-776": "Por",
  "g5246-639": "Par",
  "g5246-939": "Bre",
  "g5246-50": "Lon",
  "g5246-561": "Lvp",
  "g5246-4": "Edi",
  "g5246-044": "Den",
  "g5246-17": "Swe",
  "g5246-178": "Nwy",
  "g5246-85": "Gre"
};

let board = null;
let svgRoot = null;
let selectedProvince = null;

let history = [];
let historyIndex = 0;

async function startViewer() {
  const [svgText, historyResponse] = await Promise.all([
    fetch("/ui/maps/standard.svg")
      .then(requireOk)
      .then(response => response.text()),

    fetch("/api/history")
      .then(requireOk)
      .then(response => response.json())
  ]);

  validateHistory(historyResponse);

  history = historyResponse.snapshots;
  historyIndex = history.length - 1;

  const host = document.querySelector("#map-host");
  host.innerHTML = svgText;

  svgRoot = host.querySelector("svg");

  if (!svgRoot) {
    throw new Error("standard.svg did not contain an SVG root element.");
  }

  svgRoot.classList.add("board-svg");

  installProvinceInteractions();
  installReplayControls();
  renderHistoryPosition();
}

function requireOk(response) {
  if (!response.ok) {
    throw new Error(
      `Request failed: ${response.status} ${response.statusText}`
    );
  }

  return response;
}

function validateHistory(historyResponse) {
  if (!historyResponse
      || !Array.isArray(historyResponse.snapshots)
      || historyResponse.snapshots.length === 0) {
    throw new Error(
      "The server returned no replayable board snapshots."
    );
  }

  for (const entry of historyResponse.snapshots) {
    if (!entry
        || typeof entry.label !== "string"
        || !entry.snapshot) {
      throw new Error(
        "The server returned a malformed board-history entry."
      );
    }
  }
}

function installReplayControls() {
  document.querySelector("#first-turn")
    .addEventListener("click", showFirstPosition);

  document.querySelector("#previous-turn")
    .addEventListener("click", () => moveHistory(-1));

  document.querySelector("#next-turn")
    .addEventListener("click", () => moveHistory(1));

  document.querySelector("#last-turn")
    .addEventListener("click", showLatestPosition);

  document.querySelector("#reset-selection")
    .addEventListener("click", clearSelection);

  document.addEventListener("keydown", event => {
    if (event.target.matches("input, textarea, select")) {
      return;
    }

    switch (event.key) {
      case "ArrowLeft":
        event.preventDefault();
        moveHistory(-1);
        break;

      case "ArrowRight":
        event.preventDefault();
        moveHistory(1);
        break;

      case "Home":
        event.preventDefault();
        showFirstPosition();
        break;

      case "End":
        event.preventDefault();
        showLatestPosition();
        break;

      case "Escape":
        clearSelection();
        break;

      default:
        break;
    }
  });
}

function showFirstPosition() {
  if (historyIndex === 0) {
    return;
  }

  historyIndex = 0;
  renderHistoryPosition();
}

function showLatestPosition() {
  const latestIndex = history.length - 1;

  if (historyIndex === latestIndex) {
    return;
  }

  historyIndex = latestIndex;
  renderHistoryPosition();
}

function moveHistory(offset) {
  const nextIndex = historyIndex + offset;

  if (nextIndex < 0 || nextIndex >= history.length) {
    return;
  }

  historyIndex = nextIndex;
  renderHistoryPosition();
}

function renderHistoryPosition() {
  const entry = history[historyIndex];

  board = indexSnapshot(entry.snapshot);

  renderTurnLabel(entry.label);
  renderSupplyCenterOwners();
  renderUnits();
  clearSelection();
  updateReplayControls();
}

function updateReplayControls() {
  const isFirst = historyIndex === 0;
  const isLatest = historyIndex === history.length - 1;

  document.querySelector("#first-turn").disabled = isFirst;
  document.querySelector("#previous-turn").disabled = isFirst;
  document.querySelector("#next-turn").disabled = isLatest;
  document.querySelector("#last-turn").disabled = isLatest;
}

function indexSnapshot(snapshot) {
  return {
    ...snapshot,
    unitsByLocation: new Map(
      snapshot.units.map(unit => [keyOf(unit.province), unit])
    )
  };
}

function keyOf(province) {
  return province.toLowerCase();
}

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
     * Black decorative shape between France and Germany. It has no matching
     * JReferee Province enum member.
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
      showTooltip(path.id.toUpperCase());
    });

    path.addEventListener("pointermove", moveTooltip);
    path.addEventListener("pointerleave", hideTooltip);
  }
}

function renderTurnLabel(label) {
  document.querySelector("#turn-label").textContent = label;
}

function renderSupplyCenterOwners() {
  const indicatorLayer = svgRoot.querySelector("#layer5");

  if (!indicatorLayer) {
    console.warn("standard.svg is missing #layer5.");
    return;
  }

  for (const [indicatorId, province] of Object.entries(
    SUPPLY_CENTER_INDICATORS
  )) {
    const indicator = svgRoot.querySelector(
      `#${CSS.escape(indicatorId)}`
    );

    if (!indicator) {
      console.warn(
        `Missing supply-center SVG indicator ${indicatorId} for ${province}.`
      );
      continue;
    }

    const circles = indicator.querySelectorAll("circle");

    if (circles.length < 2) {
      console.warn(
        `Supply-center indicator ${indicatorId} does not contain two circles.`
      );
      continue;
    }

    const owner = board.supplyCenterOwners[province] ?? null;
    const ownerColor = owner
      ? NATION_COLORS[owner]
      : NEUTRAL_SUPPLY_CENTER_COLOR;

    const ring = circles[0];
    const dot = circles[1];

    /*
     * Inkscape wrote inline style attributes into standard.svg, so use
     * style-property setters rather than presentation attributes.
     */
    ring.style.setProperty(
      "fill",
      ownerColor ?? NEUTRAL_SUPPLY_CENTER_COLOR
    );
    ring.style.setProperty("stroke", SUPPLY_CENTER_OUTLINE_COLOR);

    dot.style.setProperty("fill", SUPPLY_CENTER_DOT_COLOR);
    dot.style.setProperty("stroke", "none");

    indicator.dataset.province = province;
    indicator.dataset.owner = owner ?? "NEUTRAL";
  }
}

function renderUnits() {
  const unitLayer = svgRoot.querySelector("#units");

  if (!unitLayer) {
    throw new Error("standard.svg is missing the #units layer.");
  }

  unitLayer.replaceChildren();

  for (const unit of board.units) {
    unitLayer.append(createUnitToken(unit));
  }
}

function createUnitToken(unit) {
  const exactLocation = keyOf(unit.province);
  const visibleLocation = visibleProvinceKey(unit.province);

  const provincePath = svgRoot.querySelector(
    `#provinces > path#${CSS.escape(visibleLocation)}`
  );

  if (!provincePath) {
    console.warn(`No SVG province path for ${unit.province}.`);
    return document.createDocumentFragment();
  }

  const anchor = unitAnchor(provincePath, exactLocation);
  const group = document.createElementNS(SVG_NS, "g");

  group.classList.add("unit-token");
  group.dataset.province = unit.province;
  group.setAttribute(
    "transform",
    `translate(${anchor.x} ${anchor.y})`
  );
  group.setAttribute(
    "aria-label",
    `${unit.nation} ${unit.type} in ${unit.province}`
  );

  const body = document.createElementNS(SVG_NS, "circle");
  body.setAttribute("r", "2.6");
  body.setAttribute(
    "fill",
    NATION_COLORS[unit.nation] ?? "#ffffff"
  );

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

function renderInspector(provinceKey) {
  const matchingUnits = board.units.filter(unit => {
    return visibleProvinceKey(unit.province) === provinceKey;
  });

  const province = enumStyleProvince(provinceKey);
  const owner = board.supplyCenterOwners[province]
    ?? "Not a supply center";

  document.querySelector("#selection-empty").hidden = true;
  document.querySelector("#province-details").hidden = false;

  document.querySelector("#detail-province").textContent = province;
  document.querySelector("#detail-owner").textContent = owner;

  document.querySelector("#detail-unit").textContent =
    matchingUnits.length === 0
      ? "No unit"
      : matchingUnits
          .map(unit => {
            return `${unit.nation} ${unit.type} (${unit.province})`;
          })
          .join(", ");
}

function enumStyleProvince(provinceKey) {
  const seaSpaces = new Set([
    "adr", "aeg", "bal", "bar", "bla", "bot", "eas", "eng",
    "hel", "ion", "iri", "lyo", "mao", "nao", "nth", "nwg",
    "ska", "tys", "wes"
  ]);

  if (seaSpaces.has(provinceKey)) {
    return provinceKey.toUpperCase();
  }

  return provinceKey.charAt(0).toUpperCase()
    + provinceKey.slice(1);
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
    turnLabel.textContent = "Unable to load board history";
  }
});
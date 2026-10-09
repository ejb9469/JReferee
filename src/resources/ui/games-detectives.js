"use strict";

/*
 * Independent presentation component.
 * The game browser supplies the selected snapshot's investigation.
 * Filtering never changes the underlying coverage or invokes detectives.
 */
const DetectivePanel = (() => {

    const kindsGroup = document.querySelector("#detectives-kinds");
    const kindsHost = document.querySelector("#detectives-kind-options");
    const selectAll = document.querySelector("#detectives-select-all");
    const clearSelection = document.querySelector("#detectives-clear-selection");
    const selectCompleteness =
        document.querySelector("#detectives-completeness");

    const status = document.querySelector("#detectives-status");
    const evidence = document.querySelector("#detectives-evidence");
    const findingsHost = document.querySelector("#detectives-findings");
    const coverageBody = document.querySelector("#detectives-coverage-body");
    const coverageSummary =
        document.querySelector("#detectives-coverage-summary");

    let report = null;
    let country = "";
    let inspect = null;
    const selectedKinds = new Set();

    const labels = {
        EVALUATED_WITH_FINDINGS: "Evaluated — findings",
        EVALUATED_WITH_NO_FINDINGS: "Evaluated — no findings",
        SKIPPED_MISSING_EVIDENCE: "Skipped — missing evidence",
        UNSUPPORTED_UNIMPLEMENTED: "Not implemented / not registered"
    };

    kindsHost.addEventListener("change", event => {
        const checkbox = event.target;

        if (checkbox.type !== "checkbox")
            return;

        if (checkbox.checked)
            selectedKinds.add(checkbox.value);
        else
            selectedKinds.delete(checkbox.value);

        renderFindings();
    });
    selectAll.addEventListener("click", () => selectKinds(true));
    clearSelection.addEventListener("click", () => selectKinds(false));
    selectCompleteness.addEventListener("change", renderFindings);


    // Public interface \\

    function reset(message = "Select a game.") {

        report = null;
        country = "";
        inspect = null;

        selectedKinds.clear();
        kindsHost.replaceChildren();
        kindsGroup.disabled = true;
        selectCompleteness.value = "";
        selectCompleteness.disabled = true;

        status.textContent = message;
        evidence.textContent = "";
        findingsHost.replaceChildren();
        coverageBody.replaceChildren();
        coverageSummary.textContent = "No investigation coverage available.";
        document.querySelector("#detectives-coverage").open = false;

    }

    function show(investigation, selectedCountry, inspectProvince) {

        const changed = investigation !== report;

        if (changed)
            reset();

        report = investigation || null;
        country = selectedCountry;
        inspect = inspectProvince;

        if (!report) {
            status.textContent = "No investigation report was supplied.";
            return;
        }

        if (report.status !== "EVALUATED") {
            status.textContent =
                `${report.status}: ${report.message || "Snapshot not investigated."}`;
            return;
        }

        if (changed) {

            for (const entry of report.coverage) {
                if (!entry.implemented)
                    continue;

                const label = document.createElement("label");
                const checkbox = document.createElement("input");
                checkbox.type = "checkbox";
                checkbox.value = entry.kind;
                checkbox.checked = true;
                selectedKinds.add(entry.kind);

                label.append(checkbox,
                    element("span", entry.kind.replaceAll("_", " ")));
                kindsHost.append(label);
            }

            renderCoverage();

        }

        kindsGroup.disabled = false;
        selectCompleteness.disabled = false;

        const unknown = report.unknownUnits;

        evidence.textContent =
            `${report.knownOrders}/${report.activeUnits} unit orders known. `
            + (unknown.length
                ? `Unknown: ${unknown.join("; ")}. `
                : "No unknown unit orders in this snapshot. ")
            + `Context: ${report.contextId}.`;

        renderFindings();

    }


    // Finding selection \\

    function selectKinds(checked) {

        selectedKinds.clear();

        for (const checkbox of kindsHost.querySelectorAll("input")) {
            checkbox.checked = checked;
            if (checked)
                selectedKinds.add(checkbox.value);
        }

        renderFindings();

    }

    function renderFindings() {

        findingsHost.replaceChildren();

        if (!report || report.status !== "EVALUATED")
            return;

        const completeness = selectCompleteness.value;

        const selected = report.findings.filter(finding =>
            selectedKinds.has(finding.kind)
            && (!country || finding.participants.some(
                participant => participant.nation === country))
            && (!completeness
                || finding.complete === (completeness === "complete")));

        status.textContent =
            `${selected.length} of ${report.findings.length} finding(s) displayed`
            + (country ? ` involving ${country}` : "")
            + ". Coverage remains whole-snapshot.";

        if (selected.length === 0) {

            const message = selectedKinds.size === 0
                ? "No kinds selected. Select one or more registered kinds to display findings."
                : report.findings.length === 0
                    ? "Evaluated detectives found no patterns in the known submissions. "
                        + "Unimplemented kinds were not tested."
                    : "No findings match the display filters.";

            findingsHost.append(element("p", message));
            return;

        }

        for (const finding of selected) {

            const card = document.createElement("article");
            card.className = "detective-finding";
            card.classList.toggle("incomplete", !finding.complete);
            card.classList.toggle(
                "diagnostic", finding.interpretation === "DIAGNOSTIC");

            card.append(element("h3",
                `${finding.kind.replaceAll("_", " ")} — ${finding.focus}`));

            card.append(element("p",
                `${finding.complete ? "Complete" : "Incomplete"} local pattern · `
                + finding.interpretation.replaceAll("_", " ")));

            const focus = element("button", `Inspect ${finding.focus} on map`);
            focus.type = "button";

            focus.addEventListener("click", () => {

                if (!inspect)
                    return;

                inspect(finding.focus);

                const map = document.querySelector("#map-host");
                map.scrollIntoView({ block: "nearest" });

            });

            card.append(focus);

            const participants = document.createElement("ul");

            for (const participant of finding.participants) {

                const submission = participant.order
                    ? `${participant.order} [${participant.provenance}]`
                    : `${participant.unit} — order unknown`;

                participants.append(element("li",
                    `${participant.role.replaceAll("_", " ")}: ${submission}`));

            }

            card.append(participants);

            if (finding.missingOrders.length) {

                const missing = element("p",
                    "Missing prerequisites: "
                        + finding.missingOrders.join("; "));

                missing.className = "detective-missing";
                card.append(missing);

            }

            const definition = document.createElement("details");
            definition.append(element("summary", "Definition and versions"));
            definition.append(element("p", finding.semantics));

            definition.append(element("p",
                `Detective: ${finding.detectiveVersion} · `
                + `Definition: ${finding.definitionVersion}`));

            card.append(definition);
            findingsHost.append(card);

        }

    }


    // Unfiltered coverage \\

    function renderCoverage() {

        coverageBody.replaceChildren();

        const registered = report.coverage.filter(entry => entry.implemented);
        const evaluated = registered.filter(entry =>
            entry.status === "EVALUATED_WITH_FINDINGS"
            || entry.status === "EVALUATED_WITH_NO_FINDINGS");

        const unsupported = report.coverage.filter(entry =>
            entry.status === "UNSUPPORTED_UNIMPLEMENTED").length;

        const skipped = report.coverage.filter(entry =>
            entry.status === "SKIPPED_MISSING_EVIDENCE").length;

        coverageSummary.textContent =
            `${registered.length} registered · ${evaluated.length} evaluated · `
            + `${skipped} skipped · ${unsupported} unsupported. `
            + `Scope: ${report.coverageScope}. `
            + "Counts below are before all display filters.";

        for (const entry of report.coverage) {

            const row = document.createElement("tr");

            row.append(element("td", entry.kind.replaceAll("_", " ")));
            row.append(element("td", labels[entry.status] || entry.status));

            const wasEvaluated =
                entry.status === "EVALUATED_WITH_FINDINGS"
                || entry.status === "EVALUATED_WITH_NO_FINDINGS";

            row.append(element("td",
                wasEvaluated ? String(entry.findingCount) : "Not evaluated"));

            row.append(element("td",
                entry.missingEvidence.length
                    ? entry.missingEvidence.join(", ")
                    : "—"));

            coverageBody.append(row);

        }

    }


    // Safe DOM construction \\

    function element(tag, text) {

        const node = document.createElement(tag);
        node.textContent = text;

        return node;

    }

    return Object.freeze({ reset, show });

})();
"use strict";

const form = document.querySelector("#controls");
const requestState = document.querySelector("#request-state");
const strategyGrid = document.querySelector("#strategy-grid");
const comparisonList = document.querySelector("#comparison-list");
const traceBody = document.querySelector("#trace-body");
const canvas = document.querySelector("#retry-chart");

let response = null;
let selectedPolicy = "alter_hot_ref";
let requestSequence = 0;
let debounceTimer = null;

const outputFormatters = {
  workers: (value) => value,
  operations_per_worker: (value) => integer(value),
  hot_refs: (value) => value,
  critical_work_us: (value) => `${value} µs`,
  side_effect_percent: (value) => `${value}%`,
  shards: (value) => value,
  batch_size: (value) => value,
  observation_seconds: (value) => `${value}s`,
  seed: (value) => value,
};

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function integer(value) {
  return Intl.NumberFormat("en").format(value);
}

function compact(value) {
  return Intl.NumberFormat("en", {
    notation: "compact",
    maximumFractionDigits: 1,
  }).format(value);
}

function milliseconds(value) {
  return value < 1000 ? `${Number(value).toFixed(value < 10 ? 1 : 0)} ms`
    : `${(value / 1000).toFixed(2)} s`;
}

function microseconds(value) {
  return value < 1000 ? `${integer(value)} µs` : `${(value / 1000).toFixed(2)} ms`;
}

function updateOutputs() {
  for (const input of form.elements) {
    if (!(input instanceof HTMLInputElement)) continue;
    const output = form.querySelector(`[data-for="${input.name}"]`);
    if (output) {
      output.value = outputFormatters[input.name]?.(Number(input.value)) ?? input.value;
    }
  }
}

function selectedStrategy() {
  return response?.strategies.find((strategy) => strategy.policy === selectedPolicy)
    ?? response?.strategies[0];
}

function renderHero() {
  const alter = response.strategies.find((strategy) => strategy.policy === "alter_hot_ref");
  const metrics = alter.metrics;
  document.querySelector("#hero-commits").textContent = integer(metrics.transactionsCommitted);
  document.querySelector("#hero-retries").textContent = integer(metrics.retries);
  document.querySelector("#hero-effects").textContent = integer(metrics.duplicateSideEffects);

  const firstTransaction = alter.events.filter(
    (event) => event.transactionId === alter.events[0].transactionId,
  );
  document.querySelector("#hero-attempts").innerHTML = firstTransaction.map((event) => `
    <div>
      <span>attempt ${event.attempt}${event.sideEffect ? " · effect fired" : ""}</span>
      <b class="${event.outcome}">${escapeHtml(event.outcome)}</b>
    </div>
  `).join("");
}

function renderStrategies() {
  strategyGrid.innerHTML = response.strategies.map((strategy, index) => {
    const metrics = strategy.metrics;
    const selected = strategy.policy === selectedPolicy;
    return `
      <button
        class="strategy-card ${selected ? "selected" : ""}"
        data-policy="${escapeHtml(strategy.policy)}"
        aria-pressed="${selected}"
        style="--strategy:${escapeHtml(strategy.color)}"
      >
        <span class="card-number">${String(index + 1).padStart(2, "0")}</span>
        <span class="card-kicker">${escapeHtml(strategy.kicker)}</span>
        <strong>
          ${escapeHtml(strategy.name)}
          ${strategy.recommended ? "<small>lowest waste here</small>" : ""}
        </strong>
        <span class="card-description">${escapeHtml(strategy.description)}</span>
        <span class="card-stats">
          <span><em>executions</em><b>${integer(metrics.attempts)}</b></span>
          <span><em>retries</em><b>${integer(metrics.retries)}</b></span>
          <span><em>efficiency</em><b>${metrics.commitEfficiencyPercent}%</b></span>
        </span>
        <span class="card-tradeoff">${escapeHtml(strategy.tradeoff)}</span>
      </button>
    `;
  }).join("");

  strategyGrid.querySelectorAll("[data-policy]").forEach((button) => {
    button.addEventListener("click", () => {
      selectedPolicy = button.dataset.policy;
      renderStrategies();
      renderSelected();
    });
  });
}

function renderComparison() {
  const maximumAttempts = Math.max(...response.strategies.map(
    (strategy) => strategy.metrics.attempts,
  ));
  comparisonList.innerHTML = response.strategies.map((strategy) => {
    const metrics = strategy.metrics;
    const attemptWidth = Math.max(2, metrics.attempts / maximumAttempts * 100);
    const commitWidth = Math.max(2, metrics.transactionsCommitted / maximumAttempts * 100);
    return `
      <button data-comparison-policy="${escapeHtml(strategy.policy)}" style="--strategy:${escapeHtml(strategy.color)}">
        <span class="comparison-name">
          <i></i>
          <strong>${escapeHtml(strategy.name)}</strong>
          <small>${escapeHtml(strategy.semantics)}</small>
        </span>
        <span class="comparison-bars">
          <i class="attempt-bar" style="width:${attemptWidth}%">
            <b>${integer(metrics.attempts)} attempts</b>
          </i>
          <i class="commit-bar" style="width:${commitWidth}%">
            <b>${integer(metrics.transactionsCommitted)} commits</b>
          </i>
        </span>
        <span class="comparison-value">
          <b>${milliseconds(metrics.wastedWorkMs)}</b>
          <small>wasted work</small>
        </span>
      </button>
    `;
  }).join("");

  comparisonList.querySelectorAll("button").forEach((button) => {
    button.addEventListener("click", () => {
      selectedPolicy = button.dataset.comparisonPolicy;
      renderStrategies();
      renderSelected();
      document.querySelector(".signal-layout").scrollIntoView({
        behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth",
        block: "start",
      });
    });
  });
}

function renderTrace(strategy) {
  document.querySelector("#trace-policy").textContent = strategy.name;
  traceBody.innerHTML = strategy.events.map((event) => `
    <tr>
      <td><strong>${escapeHtml(event.transactionId)}</strong></td>
      <td>w${String(event.worker).padStart(2, "0")}</td>
      <td>${event.logicalOperation}</td>
      <td><span class="attempt-pill">#${event.attempt}</span></td>
      <td><code>${escapeHtml(event.refKey)}</code></td>
      <td>${microseconds(event.durationUs)}</td>
      <td><span class="outcome ${escapeHtml(event.outcome)}">${escapeHtml(event.outcome)}</span></td>
      <td class="${event.sideEffect && event.outcome === "retry" ? "effect-danger" : ""}">
        ${event.sideEffect ? escapeHtml(event.sideEffectPhase) : "—"}
      </td>
      <td><code>${event.commitId ? escapeHtml(event.commitId) : "—"}</code></td>
    </tr>
  `).join("");
}

function prepareCanvas() {
  const bounds = canvas.getBoundingClientRect();
  const ratio = window.devicePixelRatio || 1;
  canvas.width = Math.max(1, Math.round(bounds.width * ratio));
  canvas.height = Math.max(1, Math.round(bounds.height * ratio));
  const context = canvas.getContext("2d");
  context.scale(ratio, ratio);
  return { context, width: bounds.width, height: bounds.height };
}

function drawSeries(context, points, x, y, field, color, width) {
  context.beginPath();
  points.forEach((point, index) => {
    const pointX = x(point.timeMs);
    const pointY = y(point[field]);
    if (index === 0) context.moveTo(pointX, pointY);
    else context.lineTo(pointX, pointY);
  });
  context.strokeStyle = color;
  context.lineWidth = width;
  context.stroke();
}

function drawChart(strategy) {
  const { context, width, height } = prepareCanvas();
  const points = strategy.timeline;
  const inset = { top: 22, right: 18, bottom: 35, left: 57 };
  const plotWidth = Math.max(1, width - inset.left - inset.right);
  const plotHeight = Math.max(1, height - inset.top - inset.bottom);
  const maximumTime = Math.max(...points.map((point) => point.timeMs), 1);
  const maximumRate = Math.max(
    1,
    ...points.map((point) => Math.max(point.commitsPerSecond, point.retriesPerSecond)),
  );
  const x = (time) => inset.left + time / maximumTime * plotWidth;
  const y = (rate) => inset.top + plotHeight - rate / maximumRate * plotHeight;

  context.clearRect(0, 0, width, height);
  context.font = "10px ui-monospace, monospace";
  context.textBaseline = "middle";

  [0, 0.25, 0.5, 0.75, 1].forEach((fraction) => {
    const pointY = inset.top + plotHeight - fraction * plotHeight;
    context.beginPath();
    context.strokeStyle = "rgba(15,20,17,.13)";
    context.moveTo(inset.left, pointY);
    context.lineTo(width - inset.right, pointY);
    context.stroke();
    context.fillStyle = "#687269";
    context.textAlign = "right";
    context.fillText(compact(maximumRate * fraction), inset.left - 9, pointY);
  });

  context.beginPath();
  context.moveTo(x(points[0].timeMs), inset.top + plotHeight);
  points.forEach((point) => context.lineTo(x(point.timeMs), y(point.retriesPerSecond)));
  context.lineTo(x(points.at(-1).timeMs), inset.top + plotHeight);
  context.closePath();
  context.fillStyle = "rgba(255,92,122,.2)";
  context.fill();

  drawSeries(context, points, x, y, "retriesPerSecond", "#ff3d68", 3);
  drawSeries(context, points, x, y, "commitsPerSecond", "#158f75", 3);

  [0, 0.25, 0.5, 0.75, 1].forEach((fraction) => {
    const pointX = inset.left + fraction * plotWidth;
    context.fillStyle = "#687269";
    context.textAlign = fraction === 0 ? "left" : fraction === 1 ? "right" : "center";
    context.fillText(`${Math.round(maximumTime / 1000 * fraction)}s`, pointX, height - 12);
  });
}

function renderSelected() {
  const strategy = selectedStrategy();
  const metrics = strategy.metrics;
  document.querySelector("#chart-title").textContent = strategy.name;
  document.querySelector("#selected-efficiency").textContent =
    `${metrics.commitEfficiencyPercent}%`;
  document.querySelector("#selected-waste").textContent = milliseconds(metrics.wastedWorkMs);
  document.querySelector("#selected-p99").textContent = microseconds(metrics.p99TransactionUs);
  document.querySelector("#selected-throughput").textContent =
    `${compact(metrics.logicalThroughputPerSecond)} ops/s`;
  document.querySelector("#selected-effects").textContent =
    integer(metrics.duplicateSideEffects);
  document.querySelector("#selected-semantics").textContent =
    `${strategy.semantics} · ${strategy.tradeoff}`;
  requestState.textContent =
    `${integer(metrics.attempts)} attempts · ${integer(metrics.transactionsCommitted)} commits`;
  renderTrace(strategy);
  drawChart(strategy);
}

function renderAll() {
  renderHero();
  renderStrategies();
  renderComparison();
  renderSelected();
}

async function loadSimulation() {
  const sequence = ++requestSequence;
  requestState.textContent = "running model…";
  requestState.classList.remove("error");
  try {
    const query = new URLSearchParams(new FormData(form));
    const result = await fetch(`/api/simulate?${query}`, {
      headers: { Accept: "application/json" },
    });
    if (!result.ok) throw new Error(`HTTP ${result.status}`);
    const payload = await result.json();
    if (sequence !== requestSequence) return;
    response = payload;
    renderAll();
  } catch (error) {
    if (sequence !== requestSequence) return;
    requestState.textContent = `model unavailable · ${error.message}`;
    requestState.classList.add("error");
  }
}

form.addEventListener("input", () => {
  updateOutputs();
  clearTimeout(debounceTimer);
  debounceTimer = setTimeout(loadSimulation, 130);
});

form.addEventListener("reset", () => {
  setTimeout(() => {
    updateOutputs();
    loadSimulation();
  }, 0);
});

new ResizeObserver(() => {
  if (response) drawChart(selectedStrategy());
}).observe(canvas);

updateOutputs();
loadSimulation();

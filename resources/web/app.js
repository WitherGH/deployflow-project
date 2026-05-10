const state = {
  tasks: [],
  selected: new Set(),
  mode: 'improved',
  lastResult: null
};

const palette = ['#8b5cf6', '#06b6d4', '#10b981', '#f59e0b', '#f43f5e', '#6366f1', '#14b8a6', '#ec4899'];
const TRACE_LIMIT = 160;
const STATIC_CATALOG = [
  task('feature-flags', 'Feature Flags', 'Platform', 'low', 15, [], ['config-store', 'edge-cache'], ['config', 'safe-rollout']),
  task('identity-api', 'Identity API', 'Platform', 'high', 40, ['feature-flags'], ['identity-db', 'redis-cluster'], ['auth', 'customer-facing']),
  task('payments-gateway', 'Payments Gateway', 'Payments', 'critical', 55, ['identity-api'], ['payments-db', 'redis-cluster', 'pci-vault'], ['money-flow', 'pci']),
  task('checkout-web', 'Checkout Web', 'Commerce', 'high', 35, ['payments-gateway', 'inventory-service'], ['edge-cache', 'checkout-cdn'], ['frontend', 'conversion']),
  task('inventory-service', 'Inventory Service', 'Commerce', 'medium', 30, ['feature-flags'], ['inventory-db', 'warehouse-queue'], ['stock', 'warehouse']),
  task('orders-api', 'Orders API', 'Commerce', 'medium', 28, ['inventory-service'], ['orders-db', 'warehouse-queue'], ['orders', 'backend']),
  task('notifications-worker', 'Notifications Worker', 'Messaging', 'low', 20, ['orders-api'], ['email-provider', 'events-bus'], ['async', 'email']),
  task('search-indexer', 'Search Indexer', 'Data', 'medium', 45, ['inventory-service'], ['search-cluster', 'events-bus'], ['batch', 'search']),
  task('analytics-pipeline', 'Analytics Pipeline', 'Data', 'medium', 60, ['orders-api'], ['warehouse-db', 'events-bus'], ['etl', 'dashboards']),
  task('mobile-api', 'Mobile API', 'Experience', 'high', 38, ['identity-api', 'orders-api'], ['mobile-edge', 'redis-cluster'], ['mobile', 'customer-facing']),
  task('support-portal', 'Support Portal', 'Experience', 'low', 18, ['identity-api'], ['support-cdn', 'config-store'], ['internal', 'portal']),
  task('billing-reports', 'Billing Reports', 'Finance Ops', 'low', 25, ['payments-gateway'], ['warehouse-db', 'billing-store'], ['reporting', 'finance'])
];

const el = (id) => document.getElementById(id);

window.addEventListener('DOMContentLoaded', () => {
  bindEvents();
  checkHealth();
  loadCatalog();
});

function bindEvents() {
  el('planButton').addEventListener('click', generatePlan);
  el('planTopButton').addEventListener('click', generatePlan);
  el('loadCatalogButton').addEventListener('click', loadCatalog);
  el('searchInput').addEventListener('input', renderServiceGrid);
  el('selectAllButton').addEventListener('click', () => {
    state.selected = new Set(state.tasks.map(task => task.id));
    renderAll();
    showToast('All deployments selected.');
  });
  el('clearButton').addEventListener('click', () => {
    state.selected.clear();
    renderAll();
  });
  el('selectCoreButton').addEventListener('click', () => {
    const core = ['feature-flags', 'identity-api', 'payments-gateway', 'checkout-web', 'inventory-service', 'orders-api', 'mobile-api'];
    state.selected = new Set(state.tasks.filter(task => core.includes(task.id)).map(task => task.id));
    renderAll();
    showToast('Core customer path selected.');
  });

  el('toggleComposerButton').addEventListener('click', () => {
    el('composer').hidden = !el('composer').hidden;
  });
  el('cancelComposerButton').addEventListener('click', () => {
    el('composer').reset();
    el('composer').hidden = true;
  });
  el('composer').addEventListener('submit', addCustomTask);

  el('maxWindows').addEventListener('input', event => {
    el('maxWindowsValue').textContent = event.target.value;
  });
  document.querySelectorAll('.mode').forEach(button => {
    button.addEventListener('click', () => {
      state.mode = button.dataset.mode;
      document.querySelectorAll('.mode').forEach(item => item.classList.remove('active'));
      button.classList.add('active');
    });
  });
}

async function checkHealth() {
  try {
    const response = await fetch('/api/health');
    if (!response.ok) throw new Error('health failed');
    const data = await response.json();
    el('healthPill').innerHTML = `<span class="pulse"></span> ${escapeHtml(data.product)} ready`;
  } catch {
    el('healthPill').innerHTML = '<span class="pulse"></span> Static planner ready';
  }
}

async function loadCatalog() {
  setBusy(true);
  try {
    const response = await fetch('/api/catalog');
    if (!response.ok) throw new Error('Catalog request failed');
    const data = await response.json();
    state.tasks = data.tasks || [];
    state.selected = new Set(state.tasks.slice(0, 9).map(task => task.id));
    renderAll();
    await generatePlan(false);
  } catch (error) {
    state.tasks = cloneTasks(STATIC_CATALOG);
    state.selected = new Set(state.tasks.slice(0, 9).map(task => task.id));
    renderAll();
    await generatePlan(false);
    showToast('Static demo catalog loaded.');
  } finally {
    setBusy(false);
  }
}

function renderAll() {
  renderServiceGrid();
  renderMetrics();
}

function renderMetrics() {
  const selected = selectedTasks();
  const teams = new Set(selected.map(task => task.team));
  const highestRisk = selected.reduce((current, task) => Math.max(current, riskWeight(task.risk)), 0);
  el('selectedMetric').textContent = selected.length;
  el('teamsMetric').textContent = teams.size;
  el('riskMetric').textContent = highestRisk >= 4 ? 'Critical' : highestRisk >= 3 ? 'High' : highestRisk >= 2 ? 'Medium' : highestRisk >= 1 ? 'Low' : '—';
}

function renderServiceGrid() {
  const query = el('searchInput').value.trim().toLowerCase();
  const tasks = state.tasks.filter(task => !query || searchBlob(task).includes(query));
  el('serviceGrid').innerHTML = tasks.map(task => renderServiceCard(task)).join('') || `<div class="empty-state">No services match this search.</div>`;
  document.querySelectorAll('[data-toggle-service]').forEach(input => {
    input.addEventListener('change', () => {
      const id = input.dataset.toggleService;
      if (input.checked) state.selected.add(id); else state.selected.delete(id);
      renderAll();
    });
  });
}

function renderServiceCard(task) {
  const checked = state.selected.has(task.id);
  const deps = task.dependsOn?.length ? `Waits for ${task.dependsOn.join(', ')}` : 'No release dependency';
  return `
    <article class="service-card ${checked ? 'selected' : ''}">
      <div class="card-top">
        <div>
          <h3>${escapeHtml(task.service)}</h3>
          <div class="card-subtitle">${escapeHtml(task.team)} · ${escapeHtml(task.environment)} · ${task.durationMinutes} min</div>
        </div>
        <label class="check-wrap" aria-label="Select ${escapeHtml(task.service)}">
          <input type="checkbox" ${checked ? 'checked' : ''} data-toggle-service="${escapeHtml(task.id)}">
        </label>
      </div>
      <div class="card-meta">
        <span class="risk-chip ${escapeHtml(task.risk)}">${escapeHtml(labelRisk(task.risk))}</span>
        ${(task.tags || []).slice(0, 2).map(tag => `<span class="chip">${escapeHtml(tag)}</span>`).join('')}
      </div>
      <div class="resource-list">${(task.resources || []).slice(0, 4).map(resource => `<span>${escapeHtml(resource)}</span>`).join('')}</div>
      <div class="depends">${escapeHtml(deps)}</div>
    </article>
  `;
}

function addCustomTask(event) {
  event.preventDefault();
  const service = el('customService').value.trim();
  const team = el('customTeam').value.trim();
  if (!service || !team) return;
  const task = {
    id: slug(service) + '-' + Math.floor(Math.random() * 1000),
    service,
    team,
    environment: 'production',
    risk: el('customRisk').value,
    durationMinutes: Number(el('customDuration').value || 30),
    dependsOn: splitList(el('customDepends').value),
    resources: splitList(el('customResources').value),
    tags: ['custom']
  };
  state.tasks.unshift(task);
  state.selected.add(task.id);
  el('composer').reset();
  el('composer').hidden = true;
  renderAll();
  showToast(`${service} added to today’s board.`);
}

async function generatePlan(scroll = true) {
  const tasks = selectedTasks().map(task => ({ ...task, selected: true }));
  if (!tasks.length) {
    showToast('Select at least one deployment.');
    return;
  }
  setBusy(true);
  try {
    const payload = {
      tasks,
      maxWindows: Number(el('maxWindows').value),
      startTime: el('startTime').value || '09:30',
      windowMinutes: Number(el('windowMinutes').value || 45),
      algorithm: state.mode
    };
    const response = await fetch('/api/plan', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });
    const result = await readPlanResponse(response, payload);
    state.lastResult = result;
    renderResult(result);
    if (scroll) el('resultShell').scrollIntoView({ behavior: 'smooth', block: 'start' });
  } catch (error) {
    try {
      const payload = {
        tasks,
        maxWindows: Number(el('maxWindows').value),
        startTime: el('startTime').value || '09:30',
        windowMinutes: Number(el('windowMinutes').value || 45),
        algorithm: state.mode
      };
      const result = planLocally(payload);
      state.lastResult = result;
      renderResult(result);
      if (scroll) el('resultShell').scrollIntoView({ behavior: 'smooth', block: 'start' });
      showToast('Static planner used for this hosted demo.');
    } catch (fallbackError) {
      showToast(fallbackError.message || error.message || 'Could not generate plan.');
    }
  } finally {
    setBusy(false);
  }
}

function renderResult(result) {
  el('resultShell').hidden = false;
  renderOverview(result);
  renderTimeline(result);
  renderGraph(result);
  renderRunbook(result);
  renderInsights(result);
}

function renderOverview(result) {
  const metrics = result.metrics || {};
  const summary = result.summary || {};
  el('resultOverview').innerHTML = `
    <div class="overview-card main ${result.solved ? '' : 'error'}">
      <small>${result.solved ? 'Plan generated' : 'Planner blocked'}</small>
      <strong>${escapeHtml(summary.headline || result.message)}</strong>
      <p>${escapeHtml(summary.nextAction || result.message)}</p>
    </div>
    <div class="overview-card"><small>Windows</small><strong>${result.windowCountUsed || 0}/${result.maxWindows}</strong><p>used today</p></div>
    <div class="overview-card"><small>Conflicts</small><strong>${metrics.conflictEdges ?? 0}</strong><p>separated</p></div>
    <div class="overview-card"><small>Calls</small><strong>${formatNumber(metrics.recursiveCalls || 0)}</strong><p>recursive</p></div>
    <div class="overview-card"><small>Runtime</small><strong>${metrics.elapsedMs ?? 0}ms</strong><p>${escapeHtml(result.algorithm || '')}</p></div>
  `;
}

function renderTimeline(result) {
  if (!result.solved) {
    el('timeline').innerHTML = `<div class="empty-state">${escapeHtml(result.message)}<br>${(result.suggestions || []).map(escapeHtml).join('<br>')}</div>`;
    return;
  }
  el('timeline').innerHTML = `<div class="timeline-list">${(result.windows || []).map(window => `
    <article class="window-card ${escapeHtml(window.tone)}">
      <div class="window-head">
        <div>
          <strong>${escapeHtml(window.name)}</strong><br>
          <span>${escapeHtml(window.start)} – ${escapeHtml(window.end)}</span>
        </div>
        <span class="window-chip">${window.parallelCount} lane${window.parallelCount === 1 ? '' : 's'}</span>
      </div>
      <div class="deployments">
        ${(window.deployments || []).map(task => `
          <div class="deployment-pill">
            <div>
              <strong>${escapeHtml(task.service)}</strong>
              <small>${escapeHtml(task.team)} · ${escapeHtml(task.sequenceHint || '')}</small>
            </div>
            <span class="risk-chip ${escapeHtml(task.risk)}">${escapeHtml(labelRisk(task.risk))}</span>
          </div>
        `).join('') || '<div class="empty-state">Buffer window</div>'}
      </div>
    </article>
  `).join('')}</div>`;
}

function renderGraph(result) {
  const graph = result.graph || { nodes: [], edges: [] };
  const nodes = graph.nodes || [];
  const edges = graph.edges || [];
  if (!nodes.length) {
    el('graphWrap').innerHTML = '<div class="empty-state">Select services to see a conflict graph.</div>';
    return;
  }

  const width = 560;
  const height = 410;
  const centerX = width / 2;
  const centerY = height / 2;
  const radius = Math.min(width, height) * 0.37;
  const positions = new Map();
  nodes.forEach((node, index) => {
    const angle = (Math.PI * 2 * index) / nodes.length - Math.PI / 2;
    positions.set(node.id, {
      x: centerX + Math.cos(angle) * radius,
      y: centerY + Math.sin(angle) * radius
    });
  });

  const edgeMarkup = edges.map(edge => {
    const from = positions.get(edge.from);
    const to = positions.get(edge.to);
    if (!from || !to) return '';
    const reason = (edge.reasons || []).map(item => item.detail).join(' ');
    return `<line class="graph-edge" x1="${from.x}" y1="${from.y}" x2="${to.x}" y2="${to.y}"><title>${escapeHtml(reason)}</title></line>`;
  }).join('');

  const nodeMarkup = nodes.map(node => {
    const point = positions.get(node.id);
    const color = palette[Math.max(0, (Number(node.color || 1) - 1) % palette.length)];
    const label = shortLabel(node.service);
    return `
      <g class="graph-node">
        <circle cx="${point.x}" cy="${point.y}" r="22" fill="${color}" opacity="0.96"><title>${escapeHtml(node.service)} · Window ${node.color || '?'}</title></circle>
        <circle cx="${point.x}" cy="${point.y}" r="27" fill="none" stroke="rgba(255,255,255,0.18)" />
        <text class="graph-label" x="${point.x}" y="${point.y + 4}" text-anchor="middle">${escapeHtml(label)}</text>
      </g>
    `;
  }).join('');

  el('graphWrap').innerHTML = `
    <svg class="conflict-svg" viewBox="0 0 ${width} ${height}" role="img" aria-label="Deployment conflict graph">
      <defs>
        <radialGradient id="graphGlow" cx="50%" cy="50%" r="50%">
          <stop offset="0%" stop-color="rgba(139,92,246,0.18)" />
          <stop offset="100%" stop-color="rgba(139,92,246,0)" />
        </radialGradient>
      </defs>
      <rect width="${width}" height="${height}" rx="26" fill="url(#graphGlow)" />
      ${edgeMarkup}
      ${nodeMarkup}
    </svg>
    <p class="graph-caption">Nodes are deployments. Lines mean the services should not share a release window. Node color shows the assigned window.</p>
  `;
}

function renderRunbook(result) {
  if (!result.solved) {
    el('runbook').innerHTML = '<div class="empty-state">Runbook appears after a feasible plan is found.</div>';
    return;
  }
  el('runbook').innerHTML = `<div class="runbook-list">${(result.runbook || []).map(item => `
    <div class="runbook-item">
      <div class="step-number">${item.step}</div>
      <div>
        <strong>Window ${item.window}: ${escapeHtml(item.service)}</strong>
        <p>${escapeHtml(item.team)} · ${escapeHtml(item.risk)} risk<br>${escapeHtml(item.action)}</p>
      </div>
    </div>
  `).join('')}</div>`;
}

function renderInsights(result) {
  const metrics = result.metrics || {};
  const conflicts = result.conflicts || [];
  const trace = result.trace || [];
  const warnings = result.warnings || [];
  el('insights').innerHTML = `
    <div class="insight-block">
      <div class="insight-grid">
        <div class="insight-stat"><small>Graph density</small><strong>${metrics.density ?? 0}</strong></div>
        <div class="insight-stat"><small>Safety checks</small><strong>${formatNumber(metrics.safetyChecks || 0)}</strong></div>
        <div class="insight-stat"><small>Backtracks</small><strong>${formatNumber(metrics.backtracks || 0)}</strong></div>
        <div class="insight-stat"><small>Forward checks</small><strong>${formatNumber(metrics.forwardChecks || 0)}</strong></div>
      </div>
    </div>
    ${warnings.length ? `<div class="insight-block"><strong>Warnings</strong><div class="conflict-list">${warnings.map(warning => `<div class="conflict-item">${escapeHtml(warning)}</div>`).join('')}</div></div>` : ''}
    <div class="insight-block">
      <strong>Top conflicts</strong>
      <div class="conflict-list">${conflicts.slice(0, 12).map(conflict => `
        <div class="conflict-item"><b>${escapeHtml(conflict.fromService)}</b> ↔ <b>${escapeHtml(conflict.toService)}</b><br>${escapeHtml((conflict.reasons || []).map(reason => reason.detail).join(' '))}</div>
      `).join('') || '<div class="conflict-item">No conflicts in the selected queue.</div>'}</div>
    </div>
    <div class="insight-block">
      <strong>Algorithm trace</strong>
      <div class="trace-list">${trace.slice(0, 40).map(event => `<div class="trace-item">${escapeHtml(event)}</div>`).join('') || '<div class="trace-item">No trace events.</div>'}</div>
    </div>
  `;
}

async function readPlanResponse(response, payload) {
  const contentType = response.headers.get('content-type') || '';
  if (response.ok && contentType.includes('application/json')) {
    return response.json();
  }
  if (response.status === 404 || response.status === 405 || !contentType.includes('application/json')) {
    return planLocally(payload);
  }
  const result = await response.json();
  throw new Error(result.detail || result.error || 'Planner failed');
}

function planLocally(payload) {
  const startedAt = performance.now();
  const options = {
    maxWindows: Math.max(1, Math.min(8, Number(payload.maxWindows || 4))),
    startTime: payload.startTime || '09:30',
    windowMinutes: Math.max(15, Math.min(180, Number(payload.windowMinutes || 45))),
    mode: String(payload.algorithm || 'improved').toLowerCase()
  };
  const tasks = deduplicateTasks((payload.tasks || []).map(normalizeTask));
  const graph = buildLocalGraph(tasks);
  const metrics = {
    algorithm: options.mode === 'classic' ? 'Classical deployment backtracking' : 'Improved MRV + degree backtracking',
    recursiveCalls: 0,
    backtracks: 0,
    safetyChecks: 0,
    forwardChecks: 0,
    colorsTried: 0,
    vertexCount: graph.tasks.length,
    conflictEdges: countConflictEdges(graph),
    precedenceRules: countPrecedenceRules(graph)
  };
  const trace = [];
  const solved = solveLocalDeployment(graph, options.maxWindows, options.mode, metrics, trace);
  metrics.elapsedMs = Math.round((performance.now() - startedAt) * 100) / 100;
  metrics.density = graph.tasks.length <= 1
    ? 0
    : Math.round((2 * metrics.conflictEdges / (graph.tasks.length * (graph.tasks.length - 1))) * 1000) / 1000;

  const response = {
    product: 'DeployFlow',
    solved: solved.ok,
    message: solved.ok ? 'Deployment plan generated.' : 'No safe deployment plan exists within the selected windows.',
    algorithm: metrics.algorithm,
    windowCountUsed: solved.ok ? solved.colorCount : 0,
    maxWindows: options.maxWindows,
    graph: graphToMap(graph, solved.colors),
    conflicts: buildLocalConflictList(graph),
    metrics,
    trace,
    warnings: findLocalWarnings(graph)
  };

  if (solved.ok) {
    response.windows = buildLocalWindows(graph, solved.colors, solved.colorCount, options);
    response.runbook = buildLocalRunbook(graph, solved.colors, solved.colorCount);
    response.summary = buildLocalSummary(graph, solved.colorCount);
  } else {
    response.windows = [];
    response.runbook = [];
    response.summary = {
      headline: 'No safe plan with current windows',
      nextAction: 'Increase the number of windows or move one high-risk deployment to another day.'
    };
    response.suggestions = [
      'Add one more deployment window and run the planner again.',
      'Remove one critical update from today’s batch.',
      'Check whether same-team deployments can be handled by a backup owner.',
      'Split database migrations from service rollouts.'
    ];
  }
  return response;
}

function solveLocalDeployment(graph, maxColors, mode, metrics, trace) {
  if (!graph.tasks.length) {
    return { ok: true, colors: [], colorCount: 0 };
  }
  for (let colorCount = 1; colorCount <= maxColors; colorCount++) {
    const colors = Array(graph.tasks.length).fill(0);
    metrics.colorsTried = colorCount;
    addTrace(trace, `Trying plan with ${colorCount} release window${colorCount === 1 ? '' : 's'}.`);
    const ok = mode === 'classic'
      ? colorByFixedOrder(0, graph, colors, colorCount, metrics, trace)
      : colorByHeuristicOrder(graph, colors, colorCount, metrics, trace);
    if (ok) {
      return { ok: true, colors, colorCount };
    }
  }
  return { ok: false, colors: Array(graph.tasks.length).fill(0), colorCount: maxColors };
}

function colorByFixedOrder(position, graph, colors, colorCount, metrics, trace) {
  metrics.recursiveCalls++;
  if (position === colors.length) return true;
  const vertex = position;
  for (let color = 1; color <= colorCount; color++) {
    if (isDeploymentSafe(vertex, color, graph, colors, metrics)) {
      colors[vertex] = color;
      addTrace(trace, `${labelTask(graph, vertex)} → Window ${color}.`);
      if (colorByFixedOrder(position + 1, graph, colors, colorCount, metrics, trace)) return true;
      colors[vertex] = 0;
      metrics.backtracks++;
      addTrace(trace, `Backtrack: ${labelTask(graph, vertex)} removed from Window ${color}.`);
    }
  }
  return false;
}

function colorByHeuristicOrder(graph, colors, colorCount, metrics, trace) {
  metrics.recursiveCalls++;
  const vertex = selectMostConstrainedVertex(graph, colors, colorCount, metrics);
  if (vertex === -1) return true;
  for (const color of orderColorsByLeastConstrainingValue(vertex, graph, colors, colorCount, metrics)) {
    if (isDeploymentSafe(vertex, color, graph, colors, metrics)) {
      colors[vertex] = color;
      addTrace(trace, `${labelTask(graph, vertex)} → Window ${color} selected by heuristic.`);
      if (hasFutureOptions(graph, colors, colorCount, metrics)
          && colorByHeuristicOrder(graph, colors, colorCount, metrics, trace)) {
        return true;
      }
      colors[vertex] = 0;
      metrics.backtracks++;
      addTrace(trace, `Backtrack: ${labelTask(graph, vertex)} cannot stay in Window ${color}.`);
    }
  }
  return false;
}

function selectMostConstrainedVertex(graph, colors, colorCount, metrics) {
  let bestVertex = -1;
  let bestOptionCount = Infinity;
  let bestDegree = -1;
  let bestRisk = -1;
  for (let vertex = 0; vertex < colors.length; vertex++) {
    if (colors[vertex] !== 0) continue;
    let options = 0;
    for (let color = 1; color <= colorCount; color++) {
      if (isDeploymentSafe(vertex, color, graph, colors, metrics)) options++;
    }
    const degree = degreeOf(graph, vertex);
    const risk = riskWeight(graph.tasks[vertex].risk);
    if (options < bestOptionCount
        || (options === bestOptionCount && degree > bestDegree)
        || (options === bestOptionCount && degree === bestDegree && risk > bestRisk)) {
      bestVertex = vertex;
      bestOptionCount = options;
      bestDegree = degree;
      bestRisk = risk;
    }
  }
  return bestVertex;
}

function orderColorsByLeastConstrainingValue(vertex, graph, colors, colorCount, metrics) {
  const candidates = [];
  for (let color = 1; color <= colorCount; color++) {
    if (isDeploymentSafe(vertex, color, graph, colors, metrics)) candidates.push(color);
  }
  return candidates.sort((a, b) => constraintScore(vertex, a, graph, colors, colorCount, metrics)
    - constraintScore(vertex, b, graph, colors, colorCount, metrics));
}

function constraintScore(vertex, candidateColor, graph, colors, colorCount, metrics) {
  const copy = [...colors];
  copy[vertex] = candidateColor;
  let score = 0;
  for (let other = 0; other < copy.length; other++) {
    if (copy[other] !== 0 || (!graph.conflicts[vertex][other] && !graph.before[vertex][other] && !graph.before[other][vertex])) continue;
    let options = 0;
    for (let color = 1; color <= colorCount; color++) {
      if (isDeploymentSafe(other, color, graph, copy, metrics)) options++;
    }
    score -= options;
  }
  return score;
}

function hasFutureOptions(graph, colors, colorCount, metrics) {
  for (let vertex = 0; vertex < colors.length; vertex++) {
    if (colors[vertex] !== 0) continue;
    metrics.forwardChecks++;
    let hasOption = false;
    for (let color = 1; color <= colorCount; color++) {
      if (isDeploymentSafe(vertex, color, graph, colors, metrics)) {
        hasOption = true;
        break;
      }
    }
    if (!hasOption) return false;
  }
  return true;
}

function isDeploymentSafe(vertex, candidateColor, graph, colors, metrics) {
  metrics.safetyChecks++;
  for (let other = 0; other < colors.length; other++) {
    if (other === vertex || colors[other] === 0) continue;
    if (graph.conflicts[vertex][other] && colors[other] === candidateColor) return false;
    if (graph.before[vertex][other] && candidateColor >= colors[other]) return false;
    if (graph.before[other][vertex] && colors[other] >= candidateColor) return false;
  }
  return true;
}

function buildLocalGraph(rawTasks) {
  const tasks = deduplicateTasks(rawTasks);
  const conflicts = matrix(tasks.length);
  const before = matrix(tasks.length);
  const reasons = new Map();
  for (let i = 0; i < tasks.length; i++) {
    for (let j = i + 1; j < tasks.length; j++) {
      const a = tasks[i];
      const b = tasks[j];
      const pairReasons = [];
      if (sameText(a.team, b.team)) {
        pairReasons.push({ type: 'team', detail: `Both deployments are owned by ${a.team}.` });
      }
      const sharedResources = sharedValues(a.resources, b.resources);
      if (sharedResources.length) {
        pairReasons.push({ type: 'resource', detail: `Shared resource: ${sharedResources.join(', ')}.` });
      }
      if (dependsOn(a, b)) {
        pairReasons.push({ type: 'dependency', detail: `${a.service} depends on ${b.service}.` });
        before[j][i] = true;
      }
      if (dependsOn(b, a)) {
        pairReasons.push({ type: 'dependency', detail: `${b.service} depends on ${a.service}.` });
        before[i][j] = true;
      }
      if (riskWeight(a.risk) >= 3 && riskWeight(b.risk) >= 3) {
        pairReasons.push({ type: 'risk', detail: 'Two high-blast-radius releases should not run in parallel.' });
      }
      if (pairReasons.length) {
        conflicts[i][j] = true;
        conflicts[j][i] = true;
        reasons.set(edgeKey(i, j), pairReasons);
      }
    }
  }
  return { tasks, conflicts, before, reasons };
}

function buildLocalWindows(graph, colors, colorCount, options) {
  const windows = [];
  const start = parseTimeMinutes(options.startTime);
  for (let color = 1; color <= colorCount; color++) {
    const deployments = [];
    for (let i = 0; i < graph.tasks.length; i++) {
      if (colors[i] === color) {
        deployments.push({
          ...graph.tasks[i],
          riskLabel: labelRisk(graph.tasks[i].risk),
          riskWeight: riskWeight(graph.tasks[i].risk),
          window: color,
          sequenceHint: sequenceHint(graph.tasks[i])
        });
      }
    }
    deployments.sort((a, b) => riskWeight(b.risk) - riskWeight(a.risk)
      || String(a.team).localeCompare(String(b.team))
      || String(a.service).localeCompare(String(b.service)));
    const windowStart = start + (color - 1) * options.windowMinutes;
    const windowEnd = windowStart + options.windowMinutes;
    windows.push({
      index: color,
      name: `Window ${color}`,
      start: formatTime(windowStart),
      end: formatTime(windowEnd),
      deployments,
      loadMinutes: deployments.reduce((max, item) => Math.max(max, Number(item.durationMinutes || 0)), 0),
      parallelCount: deployments.length,
      tone: windowTone(deployments)
    });
  }
  return windows;
}

function buildLocalRunbook(graph, colors, colorCount) {
  const runbook = [];
  let step = 1;
  for (let color = 1; color <= colorCount; color++) {
    const indexes = graph.tasks.map((_, index) => index).filter(index => colors[index] === color);
    indexes.sort((a, b) => riskWeight(graph.tasks[b].risk) - riskWeight(graph.tasks[a].risk)
      || graph.tasks[a].service.localeCompare(graph.tasks[b].service));
    for (const index of indexes) {
      const item = graph.tasks[index];
      runbook.push({
        step: step++,
        window: color,
        service: item.service,
        team: item.team,
        risk: labelRisk(item.risk),
        action: actionFor(item)
      });
    }
  }
  return runbook;
}

function buildLocalSummary(graph, colorCount) {
  const teams = new Set(graph.tasks.map(item => item.team));
  const critical = graph.tasks.filter(item => item.risk === 'critical').length;
  const high = graph.tasks.filter(item => item.risk === 'high').length;
  return {
    headline: `${colorCount} safe release window${colorCount === 1 ? '' : 's'} generated`,
    deployments: graph.tasks.length,
    teams: teams.size,
    critical,
    high,
    conflicts: countConflictEdges(graph),
    nextAction: 'Review the timeline, then share the runbook with owners.'
  };
}

function graphToMap(graph, colors) {
  const nodes = graph.tasks.map((item, index) => ({
    ...item,
    riskLabel: labelRisk(item.risk),
    riskWeight: riskWeight(item.risk),
    index,
    color: colors[index] || 0,
    degree: degreeOf(graph, index)
  }));
  const edges = [];
  for (let i = 0; i < graph.tasks.length; i++) {
    for (let j = i + 1; j < graph.tasks.length; j++) {
      if (graph.conflicts[i][j]) {
        edges.push({
          from: graph.tasks[i].id,
          to: graph.tasks[j].id,
          fromIndex: i,
          toIndex: j,
          reasons: graph.reasons.get(edgeKey(i, j)) || []
        });
      }
    }
  }
  return { nodes, edges };
}

function buildLocalConflictList(graph) {
  const conflicts = [];
  for (let i = 0; i < graph.tasks.length; i++) {
    for (let j = i + 1; j < graph.tasks.length; j++) {
      if (graph.conflicts[i][j]) {
        conflicts.push({
          from: graph.tasks[i].id,
          fromService: graph.tasks[i].service,
          to: graph.tasks[j].id,
          toService: graph.tasks[j].service,
          reasons: graph.reasons.get(edgeKey(i, j)) || []
        });
      }
    }
  }
  return conflicts;
}

function findLocalWarnings(graph) {
  const warnings = [];
  if (hasPrecedenceCycle(graph)) warnings.push('Dependency cycle detected. The planner will reject impossible chronological orders.');
  if (countConflictEdges(graph) > graph.tasks.length * 2 && graph.tasks.length > 4) {
    warnings.push('This is a dense deployment day. Expect more windows or move non-urgent releases.');
  }
  return warnings;
}

function hasPrecedenceCycle(graph) {
  const indegree = Array(graph.tasks.length).fill(0);
  for (let i = 0; i < graph.tasks.length; i++) {
    for (let j = 0; j < graph.tasks.length; j++) {
      if (graph.before[i][j]) indegree[j]++;
    }
  }
  const queue = indegree.map((value, index) => value === 0 ? index : -1).filter(index => index !== -1);
  let visited = 0;
  while (queue.length) {
    const current = queue.shift();
    visited++;
    for (let next = 0; next < graph.tasks.length; next++) {
      if (graph.before[current][next]) {
        indegree[next]--;
        if (indegree[next] === 0) queue.push(next);
      }
    }
  }
  return visited !== graph.tasks.length;
}

function selectedTasks() {
  return state.tasks.filter(task => state.selected.has(task.id));
}

function setBusy(busy) {
  document.querySelectorAll('#planButton, #planTopButton, #loadCatalogButton').forEach(button => {
    button.disabled = busy;
  });
  el('planButton').classList.toggle('loading', busy);
}

function showToast(message) {
  const toast = el('toast');
  toast.textContent = message;
  toast.classList.add('show');
  clearTimeout(showToast.timer);
  showToast.timer = setTimeout(() => toast.classList.remove('show'), 2500);
}

function searchBlob(task) {
  return [task.service, task.team, task.environment, task.risk, ...(task.resources || []), ...(task.dependsOn || []), ...(task.tags || [])]
    .join(' ')
    .toLowerCase();
}

function splitList(value) {
  return value.split(',').map(item => item.trim()).filter(Boolean);
}

function slug(value) {
  return value.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/(^-|-$)/g, '') || 'service';
}

function riskWeight(risk) {
  return { low: 1, medium: 2, high: 3, critical: 4 }[String(risk).toLowerCase()] || 2;
}

function labelRisk(risk) {
  const value = String(risk || 'medium').toLowerCase();
  return value.charAt(0).toUpperCase() + value.slice(1);
}

function task(id, service, team, risk, durationMinutes, dependsOn, resources, tags) {
  return normalizeTask({
    id,
    service,
    team,
    environment: 'production',
    risk,
    durationMinutes,
    dependsOn,
    resources,
    tags
  });
}

function cloneTasks(tasks) {
  return tasks.map(item => ({
    ...item,
    dependsOn: [...(item.dependsOn || [])],
    resources: [...(item.resources || [])],
    tags: [...(item.tags || [])]
  }));
}

function normalizeTask(item) {
  const service = String(item.service || 'Unnamed service').trim() || 'Unnamed service';
  const id = slug(item.id || service);
  const risk = String(item.risk || 'medium').toLowerCase();
  return {
    id,
    service,
    team: String(item.team || 'Unassigned').trim() || 'Unassigned',
    environment: String(item.environment || 'production').trim() || 'production',
    risk: ['low', 'medium', 'high', 'critical'].includes(risk) ? risk : 'medium',
    riskLabel: labelRisk(risk),
    riskWeight: riskWeight(risk),
    durationMinutes: Math.max(5, Number(item.durationMinutes || item.duration || 30)),
    dependsOn: cleanList(item.dependsOn),
    resources: cleanList(item.resources),
    tags: cleanList(item.tags)
  };
}

function cleanList(value) {
  if (!Array.isArray(value)) return [];
  return value.map(item => String(item || '').trim()).filter(Boolean);
}

function deduplicateTasks(tasks) {
  const byId = new Map();
  tasks.filter(Boolean).forEach(item => byId.set(item.id, item));
  return [...byId.values()];
}

function matrix(size) {
  return Array.from({ length: size }, () => Array(size).fill(false));
}

function edgeKey(a, b) {
  return `${Math.min(a, b)}:${Math.max(a, b)}`;
}

function sameText(a, b) {
  return String(a || '').trim().toLowerCase() === String(b || '').trim().toLowerCase();
}

function sharedValues(a, b) {
  const left = new Set((a || []).map(item => String(item).toLowerCase()));
  return (b || []).filter(item => left.has(String(item).toLowerCase()));
}

function dependsOn(taskValue, possibleDependency) {
  return (taskValue.dependsOn || []).some(value => {
    const normalized = slug(value);
    return sameText(value, possibleDependency.id)
      || sameText(value, possibleDependency.service)
      || normalized === possibleDependency.id;
  });
}

function degreeOf(graph, vertex) {
  return graph.conflicts[vertex].filter(Boolean).length;
}

function countConflictEdges(graph) {
  let count = 0;
  for (let i = 0; i < graph.tasks.length; i++) {
    for (let j = i + 1; j < graph.tasks.length; j++) {
      if (graph.conflicts[i][j]) count++;
    }
  }
  return count;
}

function countPrecedenceRules(graph) {
  let count = 0;
  for (let i = 0; i < graph.tasks.length; i++) {
    for (let j = 0; j < graph.tasks.length; j++) {
      if (graph.before[i][j]) count++;
    }
  }
  return count;
}

function addTrace(trace, event) {
  if (trace.length < TRACE_LIMIT) {
    trace.push(event);
  } else if (trace.length === TRACE_LIMIT) {
    trace.push(`Trace paused after ${TRACE_LIMIT} events to keep the UI readable.`);
  }
}

function labelTask(graph, vertex) {
  const item = graph.tasks[vertex];
  return `${item.service} (${labelRisk(item.risk).toLowerCase()})`;
}

function parseTimeMinutes(value) {
  const match = /^(\d{1,2}):(\d{2})$/.exec(String(value || '09:30'));
  if (!match) return 9 * 60 + 30;
  const hours = Math.min(23, Math.max(0, Number(match[1])));
  const minutes = Math.min(59, Math.max(0, Number(match[2])));
  return hours * 60 + minutes;
}

function formatTime(totalMinutes) {
  const minutesInDay = ((totalMinutes % 1440) + 1440) % 1440;
  const hours = Math.floor(minutesInDay / 60);
  const minutes = minutesInDay % 60;
  return `${String(hours).padStart(2, '0')}:${String(minutes).padStart(2, '0')}`;
}

function sequenceHint(taskValue) {
  if (!taskValue.dependsOn?.length) return 'Can start when the window opens';
  return `Wait until ${taskValue.dependsOn.join(', ')} is confirmed healthy`;
}

function windowTone(deployments) {
  if (deployments.some(item => item.risk === 'critical')) return 'critical';
  if (deployments.some(item => item.risk === 'high')) return 'high';
  return deployments.length > 2 ? 'busy' : 'calm';
}

function actionFor(taskValue) {
  if (taskValue.risk === 'critical') return 'Run with incident channel open and verify dashboards immediately.';
  if (taskValue.risk === 'high') return 'Deploy after owner check-in and watch shared resources.';
  if (taskValue.risk === 'medium') return 'Deploy, smoke-test, and hand off status.';
  return 'Deploy in parallel lane and confirm automated checks.';
}

function shortLabel(service) {
  return String(service || '?')
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map(word => word[0].toUpperCase())
    .join('') || '?';
}

function formatNumber(value) {
  return new Intl.NumberFormat('en-US', { maximumFractionDigits: 0 }).format(value);
}

function escapeHtml(value) {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');
}

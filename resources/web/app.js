const state = {
  tasks: [],
  selected: new Set(),
  mode: 'improved',
  lastResult: null
};

const palette = ['#8b5cf6', '#06b6d4', '#10b981', '#f59e0b', '#f43f5e', '#6366f1', '#14b8a6', '#ec4899'];

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
    el('healthPill').textContent = 'Planner offline';
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
    showToast(error.message || 'Could not load catalog.');
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
    const result = await response.json();
    if (!response.ok) throw new Error(result.detail || result.error || 'Planner failed');
    state.lastResult = result;
    renderResult(result);
    if (scroll) el('resultShell').scrollIntoView({ behavior: 'smooth', block: 'start' });
  } catch (error) {
    showToast(error.message || 'Could not generate plan.');
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

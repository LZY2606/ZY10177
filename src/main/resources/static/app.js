let state = {};

const svgNs = 'http://www.w3.org/2000/svg';

async function refresh() {
  const response = await fetch('/api/state');
  state = await response.json();
  render();
}

async function postJson(url, body) {
  const response = await fetch(url, {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify(body || {})
  });
  const data = await response.json();
  if (!response.ok) {
    document.getElementById('operationMessage').textContent = data.message || '操作失败';
    return;
  }
  state = data;
  render();
}

function element(name, attributes) {
  const node = document.createElementNS(svgNs, name);
  Object.entries(attributes || {}).forEach(([key, value]) => node.setAttribute(key, value));
  return node;
}

function render() {
  const counts = state.rawCounts || {};
  const version = state.latestVersion || {};
  document.getElementById('summary').textContent =
    `版本 #${version.id || '无'} · ${counts.scannerPoints || 0} 扫描点，`
    + `${counts.meltSamples || 0} 熔池样本，${counts.markers || 0} 锦标，`
    + `${counts.decisions || 0} 条追加决策`;
  renderTimeline();
  renderPaths();
  renderHeatmap();
  renderTables();
}

function renderTimeline() {
  const svg = document.getElementById('timeline');
  svg.innerHTML = '';
  const sessions = [...new Set((state.meltSamples || []).map(item => item.session_id))];
  sessions.forEach((sessionId, rowIndex) => {
    const y = 54 + rowIndex * 82;
    const samples = (state.alignedSamples || []).filter(item => item.session_id === sessionId);
    const clocks = samples.map(item => Number(item.sensor_clock));
    const min = Math.min(...clocks, 0);
    const max = Math.max(...clocks, 1);
    const scale = clock => 70 + ((clock - min) / (max - min)) * 960;
    svg.appendChild(element('text', {x: 24, y: y + 5, fill: '#18352d', 'font-size': 15}))
      .textContent = sessionId;
    svg.appendChild(element('line', {x1: 70, y1: y, x2: 1030, y2: y, stroke: '#8d9b92', 'stroke-width': 2}));
    samples.forEach(item => {
      const risky = item.status !== 'ALIGNED';
      svg.appendChild(element('circle', {
        cx: scale(Number(item.sensor_clock)),
        cy: y + (Number(item.is_non_laser) ? 18 : 0),
        r: risky ? 6 : 4,
        fill: colorFor(item),
        stroke: risky ? '#9d2f1b' : 'none',
        'stroke-width': risky ? 2 : 0
      }));
    });
    (state.markers || []).filter(marker => marker.session_id === sessionId)
      .forEach(marker => {
        const residual = (state.markerResiduals || []).find(item => item.marker_id === marker.id);
        const active = residual && Number(residual.active) === 1;
        const shape = element('polygon', {
          points: `${scale(marker.sensor_clock)},${y - 20} ${scale(marker.sensor_clock) + 8},${y - 10} `
            + `${scale(marker.sensor_clock)},${y} ${scale(marker.sensor_clock) - 8},${y - 10}`,
          fill: active ? '#23684a' : '#b94928',
          opacity: active ? 1 : 0.35
        });
        svg.appendChild(shape);
        svg.appendChild(element('text', {
          x: scale(marker.sensor_clock) - 9,
          y: y - 27,
          fill: '#18352d',
          'font-size': 11
        })).textContent = marker.id;
      });
  });
}

function colorFor(item) {
  if (item.status === 'ALIGNED') return '#2e8b57';
  if (item.status === 'NON_LASER') return '#7b8794';
  if (item.status === 'EXTRAPOLATION_RISK') return '#d99427';
  return '#c34423';
}

function renderPaths() {
  const svg = document.getElementById('pathMap');
  svg.innerHTML = '';
  const layers = [...new Set((state.scannerPoints || []).map(item => item.layer_id))];
  layers.forEach((layerId, index) => {
    const originX = 70;
    const originY = 48 + index * 155;
    svg.appendChild(element('text', {x: 18, y: originY - 16, fill: '#18352d', 'font-size': 14}))
      .textContent = layerId;
    const scale = (x, y) => [originX + x * 7, originY + y * 7];
    (state.segments || []).filter(segment => segment.layer_id === layerId)
      .forEach(segment => {
        const [x1, y1] = scale(Number(segment.x1), Number(segment.y1));
        const [x2, y2] = scale(Number(segment.x2), Number(segment.y2));
        svg.appendChild(element('line', {
          x1, y1, x2, y2,
          stroke: Number(segment.is_laser) === 1 ? '#d77a35' : '#69776f',
          'stroke-width': Number(segment.is_laser) === 1 ? 5 : 2,
          'stroke-dasharray': Number(segment.is_laser) === 1 ? 'none' : '7 5',
          'stroke-linecap': 'round',
          opacity: 0.9
        }));
      });
    (state.alignedSamples || []).filter(item => item.layer_id === layerId && item.x !== null)
      .forEach(item => {
        const [x, y] = scale(Number(item.x), Number(item.y));
        svg.appendChild(element('circle', {
          cx: x,
          cy: y,
          r: item.status === 'ALIGNED' ? 5 : 4,
          fill: colorFor(item),
          stroke: item.status.includes('RISK') || item.status.includes('OUT') ? '#9d2f1b' : 'none',
          'stroke-width': 2
        }));
      });
  });
}

function renderHeatmap() {
  const svg = document.getElementById('heatmap');
  svg.innerHTML = '';
  const samples = state.alignedSamples || [];
  const intensities = samples.map(item => Number(item.intensity));
  const min = Math.min(...intensities, 0);
  const max = Math.max(...intensities, 1);
  samples.forEach((item, index) => {
    const column = index % 10;
    const row = Math.floor(index / 10);
    const normalized = (Number(item.intensity) - min) / (max - min || 1);
    const fill = item.status === 'ALIGNED'
      ? `rgb(${Math.round(120 + normalized * 120)},${Math.round(70 + normalized * 60)},35)`
      : '#d8d0c2';
    svg.appendChild(element('rect', {
      x: 35 + column * 45,
      y: 34 + row * 45,
      width: 36,
      height: 36,
      rx: 6,
      fill,
      stroke: item.status === 'ALIGNED' ? '#74431f' : '#a55238',
      'stroke-width': item.status === 'ALIGNED' ? 1 : 2
    }));
    svg.appendChild(element('text', {
      x: 40 + column * 45,
      y: 58 + row * 45,
      fill: '#fff8ea',
      'font-size': 10
    })).textContent = item.melt_sample_id.replace('S1-', '').replace('S2-', '');
  });
}

function renderTables() {
  renderMarkerTable();
  renderSampleTable();
  renderDiagnosticTable();
}

function renderMarkerTable() {
  const rows = (state.markerResiduals || []).map(item => `<tr>
      <td>${item.marker_id}</td><td>${item.layer_id}</td>
      <td class="${Number(item.active) === 1 ? 'ok' : 'risk'}">${Number(item.active) === 1 ? '有效' : '撤销'}</td>
      <td>${item.residual ?? '—'}</td></tr>`).join('');
  document.getElementById('markers').innerHTML = `<table>
    <tr><th>锦标</th><th>层</th><th>状态</th><th>拟合残差</th></tr>${rows}</table>`;
}

function renderSampleTable() {
  const rows = (state.alignedSamples || []).map(item => `<tr>
      <td>${item.melt_sample_id}</td><td>${item.layer_id ?? '—'}</td>
      <td class="${item.status === 'ALIGNED' ? 'ok' : 'risk'}">${item.status}</td>
      <td>${item.risk_codes || '—'}</td></tr>`).join('');
  document.getElementById('samples').innerHTML = `<table>
    <tr><th>样本</th><th>层</th><th>状态</th><th>风险码</th></tr>${rows}</table>`;
}

function renderDiagnosticTable() {
  const rows = (state.diagnostics || []).map(item => `<tr>
      <td>${item.severity}</td><td>${item.code}</td><td>${item.session_id ?? '—'}</td>
      <td>${item.message}</td></tr>`).join('');
  document.getElementById('diagnostics').innerHTML = `<table>
    <tr><th>级别</th><th>代码</th><th>会话</th><th>说明</th></tr>${rows}</table>`;
}

document.getElementById('reload').onclick = () => postJson('/api/recompute', {reason: '页面手动重算'});
document.getElementById('loadFixture').onclick = () => postJson('/api/fixture/load');
document.getElementById('reset').onclick = () => {
  if (confirm('清空 SQLite 中所有数据？可随后用导出文件重新导入。')) {
    postJson('/api/reset');
  }
};
document.getElementById('exportRun').onclick = async () => {
  const response = await fetch('/api/export');
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = 'fuse-lineage-run.json';
  link.click();
  URL.revokeObjectURL(url);
};

document.getElementById('importRun').onchange = async event => {
  const file = event.target.files[0];
  if (!file) {
    return;
  }
  const content = await file.text();
  const parsed = JSON.parse(content);
  if (!parsed.raw) {
    document.getElementById('operationMessage').textContent = '导入文件必须包含 raw 字段';
    return;
  }
  await postJson('/api/import', {raw: parsed.raw});
};

document.querySelectorAll('[data-action]').forEach(button => {
  button.onclick = () => {
    const action = button.dataset.action;
    if (action === 'revoke' || action === 'confirm') {
      postJson('/api/decisions', {
        decisionType: action === 'revoke' ? 'REVOKE_TROPHY' : 'CONFIRM_TROPHY',
        targetId: document.getElementById('trophyId').value.trim(),
        reason: action === 'revoke' ? '页面撤销锦标' : '页面恢复锦标'
      });
    } else if (action === 'split') {
      postJson('/api/decisions', {
        decisionType: 'SPLIT_TRACK',
        sessionId: document.getElementById('splitSession').value.trim(),
        scannerClock: Number(document.getElementById('splitClock').value),
        reason: '页面拆分误连续道次'
      });
    } else {
      postJson('/api/decisions', {
        decisionType: 'KEEP_UNALIGNED',
        sessionId: document.getElementById('splitSession').value.trim(),
        sensorClockStart: Number(document.getElementById('keepStart').value),
        sensorClockEnd: Number(document.getElementById('keepEnd').value),
        reason: '页面保留未对齐信号段'
      });
    }
  };
});

refresh().catch(error => {
  document.getElementById('summary').textContent = error.message;
});

package com.privonym.http;

import com.privonym.application.port.in.QueryJobsUseCase;
import com.privonym.application.port.in.QueryEnvironmentsUseCase;
import com.privonym.application.port.in.QueryInventoryUseCase;
import com.privonym.application.port.in.PreviewSqlInventoryUseCase;
import com.privonym.application.port.in.CreateDraftInventoryUseCase;
import com.privonym.application.port.in.QueryMaskingInventoriesUseCase;
import com.privonym.application.port.in.ManageMaskingInventoryUseCase;
import com.privonym.application.port.in.RulesetAdministrationUseCase;
import com.privonym.application.port.in.DiscoveryUseCase;
import com.privonym.application.service.DiscoveryConnection;
import com.privonym.application.service.DiscoveryCreationResult;
import com.privonym.application.service.DiscoveryFinding;
import com.privonym.application.service.DiscoveryScan;
import com.privonym.application.service.DiscoverySelection;
import com.privonym.application.service.EnvironmentOption;
import com.privonym.application.service.ConnectionCreationResult;
import com.privonym.application.service.ConnectionUpdateCommand;
import com.privonym.application.service.DataSourceInventory;
import com.privonym.application.service.DataSourceSummary;
import com.privonym.application.service.EnvironmentDetails;
import com.privonym.application.service.EnvironmentSummary;
import com.privonym.application.service.JobDetails;
import com.privonym.application.service.JobStepDetails;
import com.privonym.application.service.JobSummary;
import com.privonym.application.service.InventoryColumn;
import com.privonym.application.service.InventorySchema;
import com.privonym.application.service.InventoryTable;
import com.privonym.application.service.SqlInventoryColumn;
import com.privonym.application.service.SqlInventoryPreview;
import com.privonym.application.service.SqlInventoryTable;
import com.privonym.application.service.DraftInventoryResult;
import com.privonym.application.service.MaskingInventoryDetails;
import com.privonym.application.service.MaskingInventoryMapping;
import com.privonym.application.service.MaskingInventorySummary;
import com.privonym.application.service.MaskingAlgorithmOption;
import com.privonym.application.service.InventoryMutationResult;
import com.privonym.application.service.LegacyInventoryCsv;
import com.privonym.application.service.JobCreationResult;
import com.privonym.application.service.JobUpdateResult;
import com.privonym.application.service.RulesetDetails;
import com.privonym.application.service.RulesetInventoryCsv;
import com.privonym.application.service.RulesetInventoryEntry;
import com.privonym.application.service.RulesetJobView;
import com.privonym.application.service.RulesetSummary;
import com.privonym.application.service.RulesetVersionView;
import com.privonym.application.service.RulesetCatalogColumn;
import com.privonym.application.service.RulesetInventorySelection;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

/** HTTP router for the Privonym web console and REST API. */
public final class PrivonymHttpHandler extends Handler.Abstract {
    private static final String HOME_PATH = "/";
    private static final String LOGO_PATH = "/assets/privonym.png";
    private static final String ORACLE_LOGO_PATH = "/assets/oracle-logo.png";
    private static final String POSTGRESQL_LOGO_PATH = "/assets/postgresql-logo.svg";
    private static final String SQLSERVER_LOGO_PATH = "/assets/sqlserver-logo.png";
    private static final String HEALTH_PATH = "/api/v1/health";
    private static final String JOBS_PATH = "/api/v1/jobs";
    private static final String ENVIRONMENTS_PATH = "/api/v1/environments";
    private static final String DATA_SOURCES_PATH = "/api/v1/data-sources/";
    private static final String SQL_IMPORT_PREVIEW_PATH = "/api/v1/inventory-imports/preview";
    private static final String TRANSFORMER_PREVIEW_PATH = "/api/v1/transformations/preview";
    private static final String TRANSFORMER_DRAFT_PATH = "/api/v1/transformations/drafts/";
    private static final String MASKING_INVENTORIES_PATH = "/api/v1/inventories";
    private static final String INVENTORY_CSV_PATH = "/api/v1/inventory-csv";
    private static final String ALGORITHMS_API_PATH = "/api/v1/algorithms";
    private static final String RULESETS_API_PATH = "/api/v1/rulesets";
    private static final String DISCOVERY_API_PATH = "/api/v1/discovery";
    private static final String LOGIN_PATH = "/login";
    private static final String LOGIN_API_PATH = "/api/v1/repository/login";
    private static final String LOGOUT_PATH = "/repository/logout";
    private static final String SECURITY_SETUP_PATH = "/security/setup";
    private static final String SECURITY_SETUP_API_PATH = "/api/v1/security/setup";
    private static final String SECURITY_LOGIN_API_PATH = "/api/v1/security/login";
    private static final String SECURITY_LOGOUT_PATH = "/security/logout";
    private static final String SECURITY_USERS_API_PATH = "/api/v1/security/users";
    private static final String SECURITY_ME_API_PATH = "/api/v1/security/me";
    private static final String SESSION_COOKIE = "PRIVONYM_SESSION";
    private static final long SESSION_SECONDS = 30 * 60;
    private static final String HOME_PAGE = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>Privonym — Anonymization Console</title>
              <style>
                :root { color-scheme:dark; --bg:#07111f; --panel:#0d1b2d; --line:#20344c;
                  --text:#eef5ff; --muted:#90a4bd; --green:#41d3a2; --blue:#5ba8ff; }
                * { box-sizing:border-box; }
                body { margin:0; min-height:100vh; font-family:Inter,Segoe UI,Arial,sans-serif; color:var(--text);
                  background:radial-gradient(circle at 75% 5%,#143457 0,transparent 32%),var(--bg); }
                .shell { display:grid; grid-template-columns:230px 1fr; min-height:100vh; }
                aside { padding:30px 22px; border-right:1px solid var(--line); background:rgba(5,14,26,.82); }
                .brand { font-size:22px; font-weight:750; letter-spacing:.3px; margin-bottom:8px; }
                .brand span { color:var(--green); }
                .version { color:var(--muted); font-size:12px; margin-bottom:42px; }
                nav a { display:block; padding:11px 12px; margin:5px 0; border-radius:9px; color:var(--muted);
                  text-decoration:none; font-size:14px; }
                nav a.active { color:var(--text); background:#132941; border:1px solid #254566; }
                main { padding:42px clamp(24px,5vw,72px); max-width:1350px; width:100%; }
                header { display:flex; justify-content:space-between; gap:24px; align-items:flex-start; margin-bottom:34px; }
                h1 { font-size:30px; margin:0 0 9px; letter-spacing:-.6px; }
                .subtitle { color:var(--muted); margin:0; }
                .live { display:flex; align-items:center; gap:9px; padding:9px 13px; border:1px solid #226c59;
                  border-radius:999px; color:#8ce7c9; background:#0b2a27; font-size:13px; white-space:nowrap; }
                .dot { width:8px; height:8px; border-radius:50%; background:var(--green); box-shadow:0 0 12px var(--green); }
                .grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:17px; }
                .card { padding:22px; border:1px solid var(--line); border-radius:14px;
                  background:linear-gradient(145deg,rgba(18,38,61,.94),rgba(10,24,41,.94)); }
                .card.wide { grid-column:span 2; }
                .card.full { grid-column:1/-1; }
                .label { color:var(--muted); font-size:12px; text-transform:uppercase; letter-spacing:1px; }
                .metric { margin:12px 0 4px; font-size:29px; font-weight:720; }
                .ok { color:var(--green); }
                .card p { color:var(--muted); font-size:13px; line-height:1.55; margin:8px 0 0; }
                .modes { display:flex; gap:10px; flex-wrap:wrap; margin-top:18px; }
                .mode { padding:9px 12px; border-radius:8px; border:1px solid #2b4866; background:#10253b;
                  font:600 12px Consolas,monospace; color:#b9d9ff; }
                .endpoint { margin-top:17px; padding:13px 15px; border:1px solid var(--line); border-radius:9px;
                  background:#071421; color:#a9c7e8; font:13px Consolas,monospace; overflow:auto; }
                .roadmap { display:grid; gap:12px; margin-top:16px; }
                .roadmap div { display:flex; align-items:center; gap:12px; color:#c7d5e5; font-size:14px; }
                .number { display:grid; place-items:center; width:25px; height:25px; border-radius:7px;
                  background:#17334f; color:var(--blue); font-size:12px; font-weight:700; }
                table { width:100%; margin-top:15px; border-collapse:collapse; font-size:13px; }
                th,td { padding:11px 9px; text-align:left; border-bottom:1px solid var(--line); }
                th { color:var(--muted); font-size:11px; text-transform:uppercase; letter-spacing:.7px; }
                td { color:#d7e5f5; } .job-ref { font-family:Consolas,monospace; color:#9ecbff; }
                #jobs-table tbody tr,#environments-table tbody tr,#sources-table tbody tr { cursor:pointer; transition:background .15s ease; }
                #jobs-table tbody tr:hover,#jobs-table tbody tr:focus,
                #environments-table tbody tr:hover,#environments-table tbody tr:focus,
                #sources-table tbody tr:hover,#sources-table tbody tr:focus { background:#17314b; outline:none; }
                .details-head { display:flex; align-items:flex-start; justify-content:space-between; gap:20px; }
                .details-title { margin:8px 0 0; font:700 20px Consolas,monospace; color:#cce5ff; }
                .details-grid { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:12px; margin-top:18px; }
                .detail { padding:13px; border:1px solid var(--line); border-radius:9px; background:#0a1929; }
                .detail strong { display:block; margin-top:7px; font-size:15px; overflow-wrap:anywhere; }
                .close { border:1px solid #31516f; border-radius:8px; background:#10253b; color:#cbe3fa;
                  padding:8px 12px; cursor:pointer; }
                .search { width:min(360px,100%); margin-top:16px; padding:10px 12px; color:var(--text);
                  background:#071421; border:1px solid #31516f; border-radius:8px; }
                .columns { color:var(--muted); line-height:1.7; font-size:12px; }
                .empty { padding:18px 0; color:var(--muted); } .error { color:#ff9b9b; }
                footer { color:#607892; font-size:12px; margin-top:28px; }
                @media (max-width:850px) { .shell{grid-template-columns:1fr} aside{display:none}
                  main{padding:28px 20px}.grid{grid-template-columns:1fr}.card.wide{grid-column:span 1}
                  .details-grid{grid-template-columns:repeat(2,minmax(0,1fr))}
                  header{display:block}.live{margin-top:18px;width:max-content} }
              </style>
            </head>
            <body>
              <div class="shell">
                <aside>
                  <div class="brand">Priv<span>onym</span></div>
                  <div class="version">Anonymization Engine · V2</div>
                  <nav><a class="active" href="/">Overview</a><a href="#jobs">Jobs</a>
                    <a href="#environments">Environments</a><a href="#algorithms">Algorithms</a></nav>
                </aside>
                <main>
                  <header><div><h1>Anonymization Console</h1>
                    <p class="subtitle">Manage and monitor your Privonym operations.</p></div>
                    <div class="live"><span class="dot"></span>Server operational</div></header>
                  <section class="grid">
                    <article class="card"><div class="label">Engine status</div><div class="metric ok">UP</div><p>Jetty 12 · REST API v1</p></article>
                    <article class="card"><div class="label">Target database</div><div class="metric">Oracle</div><p>Connector isolated from the repository</p></article>
                    <article class="card"><div class="label">Control flow</div><div class="metric">4 stages</div><p>Preview · Preflight · Validate · Report</p></article>
                    <article class="card wide" id="jobs"><div class="label">Available execution modes</div>
                      <div class="modes"><span class="mode">DIRECT</span><span class="mode">LOOKUP</span><span class="mode">CHUNKED</span></div>
                      <p>Job actions will be connected here through the Application layer.</p></article>
                    <article class="card"><div class="label">API active</div><div class="endpoint">GET /api/v1/health</div></article>
                    <article class="card wide"><div class="label">Recent jobs</div>
                      <div id="jobs-message" class="empty">Loading repository…</div>
                      <table id="jobs-table" hidden><thead><tr><th>Reference</th><th>Status</th>
                        <th>Rows</th><th>Planned at</th></tr></thead><tbody></tbody></table></article>
                    <article class="card"><div class="label">Security</div><div class="metric">Local</div>
                      <p>Bound to 127.0.0.1. No password is stored.</p></article>
                    <article class="card full" id="job-details" hidden>
                      <div class="details-head"><div><div class="label">Job details</div>
                        <div class="details-title" id="detail-reference"></div></div>
                        <button class="close" id="detail-close" type="button">Close</button></div>
                      <div class="details-grid">
                        <div class="detail"><div class="label">Status</div><strong id="detail-status"></strong></div>
                        <div class="detail"><div class="label">Rows processed</div><strong id="detail-rows"></strong></div>
                        <div class="detail"><div class="label">Ruleset version</div><strong id="detail-ruleset"></strong></div>
                        <div class="detail"><div class="label">Data source</div><strong id="detail-source"></strong></div>
                      </div>
                      <div id="detail-error" class="empty error" hidden></div>
                      <table id="steps-table"><thead><tr><th>Step</th><th>Type</th><th>Status</th>
                        <th>Rows</th></tr></thead><tbody></tbody></table>
                    </article>
                    <article class="card full" id="environments"><div class="label">Environments</div>
                      <div id="environments-message" class="empty">Loading environments…</div>
                      <table id="environments-table" hidden><thead><tr><th>Code</th><th>Name</th><th>Status</th>
                        <th>Owner</th><th>Data sources</th><th>Jobs</th></tr></thead><tbody></tbody></table>
                    </article>
                    <article class="card full" id="environment-details" hidden>
                      <div class="details-head"><div><div class="label">Environment details</div>
                        <div class="details-title" id="environment-title"></div></div>
                        <button class="close" id="environment-close" type="button">Close</button></div>
                      <p id="environment-description"></p>
                      <table id="sources-table"><thead><tr><th>Source</th><th>Technology</th><th>Connection</th>
                        <th>Username</th><th>Schemas</th><th>Tables</th><th>Jobs</th></tr></thead><tbody></tbody></table>
                    </article>
                    <article class="card full" id="inventory-details" hidden>
                      <div class="details-head"><div><div class="label">Data source inventory</div>
                        <div class="details-title" id="inventory-title"></div></div>
                        <button class="close" id="inventory-close" type="button">Close</button></div>
                      <input class="search" id="inventory-search" type="search"
                        placeholder="Filter schemas, tables or columns" aria-label="Filter inventory">
                      <div id="inventory-message" class="empty"></div>
                      <table id="inventory-table" hidden><thead><tr><th>Schema</th><th>Object</th><th>Type</th>
                        <th>Estimated rows</th><th>Columns</th><th>Last synchronized</th></tr></thead><tbody></tbody></table>
                    </article>
                  </section>
                  <footer>Privonym V2 · Local administration console</footer>
                </main>
              </div>
              <script>
                async function loadJobs() {
                  const message = document.getElementById('jobs-message');
                  const table = document.getElementById('jobs-table');
                  try {
                    const response = await fetch('/api/v1/jobs', {headers:{Accept:'application/json'}});
                    if (!response.ok) throw new Error('HTTP ' + response.status);
                    const payload = await response.json();
                    if (!payload.jobs.length) { message.textContent = 'No jobs available.'; return; }
                    const body = table.querySelector('tbody');
                    body.replaceChildren();
                    for (const job of payload.jobs) {
                      const row = document.createElement('tr');
                      row.tabIndex = 0;
                      row.setAttribute('aria-label', 'View ' + job.reference);
                      const values = [job.reference, job.status, String(job.rowsProcessed), job.plannedAt || '—'];
                      values.forEach((value, index) => {
                        const cell = document.createElement('td');
                        cell.textContent = value;
                        if (index === 0) cell.className = 'job-ref';
                        row.appendChild(cell);
                      });
                      row.addEventListener('click', () => loadJobDetails(job.reference));
                      row.addEventListener('keydown', event => {
                        if (event.key === 'Enter' || event.key === ' ') {
                          event.preventDefault(); loadJobDetails(job.reference);
                        }
                      });
                      body.appendChild(row);
                    }
                    message.hidden = true; table.hidden = false;
                  } catch (error) {
                    message.textContent = 'Repository unavailable: ' + error.message;
                    message.classList.add('error');
                  }
                }
                async function loadJobDetails(reference) {
                  const panel = document.getElementById('job-details');
                  const errorBox = document.getElementById('detail-error');
                  panel.hidden = false; errorBox.hidden = true;
                  document.getElementById('detail-reference').textContent = reference;
                  try {
                    const response = await fetch('/api/v1/jobs/' + encodeURIComponent(reference),
                      {headers:{Accept:'application/json'}});
                    if (!response.ok) throw new Error('HTTP ' + response.status);
                    const details = await response.json();
                    document.getElementById('detail-status').textContent = details.job.status;
                    document.getElementById('detail-rows').textContent = String(details.job.rowsProcessed);
                    document.getElementById('detail-ruleset').textContent = String(details.rulesetVersionId);
                    document.getElementById('detail-source').textContent = String(details.dataSourceId);
                    if (details.errorCode || details.errorMessage) {
                      errorBox.textContent = [details.errorCode, details.errorMessage].filter(Boolean).join(' — ');
                      errorBox.hidden = false;
                    }
                    const body = document.querySelector('#steps-table tbody');
                    body.replaceChildren();
                    for (const step of details.steps) {
                      const row = document.createElement('tr');
                      [step.number, step.type, step.status, step.rowsProcessed].forEach(value => {
                        const cell = document.createElement('td'); cell.textContent = String(value); row.appendChild(cell);
                      });
                      body.appendChild(row);
                    }
                    panel.scrollIntoView({behavior:'smooth', block:'start'});
                  } catch (error) {
                    errorBox.textContent = 'Unable to load job: ' + error.message;
                    errorBox.hidden = false;
                  }
                }
                document.getElementById('detail-close').addEventListener('click', () => {
                  document.getElementById('job-details').hidden = true;
                });
                async function loadEnvironments() {
                  const message = document.getElementById('environments-message');
                  const table = document.getElementById('environments-table');
                  try {
                    const response = await fetch('/api/v1/environments', {headers:{Accept:'application/json'}});
                    if (!response.ok) throw new Error('HTTP ' + response.status);
                    const payload = await response.json();
                    if (!payload.environments.length) { message.textContent = 'No environments available.'; return; }
                    const body = table.querySelector('tbody'); body.replaceChildren();
                    for (const environment of payload.environments) {
                      const row = document.createElement('tr'); row.tabIndex = 0;
                      row.setAttribute('aria-label', 'View ' + environment.name);
                      [environment.code, environment.name, environment.status, environment.owner || '—',
                        environment.dataSourceCount, environment.jobCount].forEach((value, index) => {
                        const cell = document.createElement('td'); cell.textContent = String(value);
                        if (index === 0) cell.className = 'job-ref'; row.appendChild(cell);
                      });
                      row.addEventListener('click', () => loadEnvironmentDetails(environment.code));
                      row.addEventListener('keydown', event => {
                        if (event.key === 'Enter' || event.key === ' ') {
                          event.preventDefault(); loadEnvironmentDetails(environment.code);
                        }
                      });
                      body.appendChild(row);
                    }
                    message.hidden = true; table.hidden = false;
                  } catch (error) {
                    message.textContent = 'Unable to load environments: ' + error.message;
                    message.classList.add('error');
                  }
                }
                async function loadEnvironmentDetails(code) {
                  const panel = document.getElementById('environment-details'); panel.hidden = false;
                  document.getElementById('environment-title').textContent = code;
                  try {
                    const response = await fetch('/api/v1/environments/' + encodeURIComponent(code),
                      {headers:{Accept:'application/json'}});
                    if (!response.ok) throw new Error('HTTP ' + response.status);
                    const details = await response.json();
                    document.getElementById('environment-title').textContent =
                      details.environment.name + ' · ' + details.environment.code;
                    document.getElementById('environment-description').textContent =
                      details.description || 'No description.';
                    const body = document.querySelector('#sources-table tbody'); body.replaceChildren();
                    for (const source of details.dataSources) {
                      const row = document.createElement('tr'); row.tabIndex = 0;
                      row.setAttribute('aria-label', 'View inventory for ' + source.name);
                      const connection = source.host + (source.port ? ':' + source.port : '')
                        + (source.service ? '/' + source.service : '');
                      [source.name, source.technology, connection, source.username || '—',
                        source.schemaCount, source.tableCount, source.jobCount].forEach(value => {
                        const cell = document.createElement('td'); cell.textContent = String(value); row.appendChild(cell);
                      });
                      row.addEventListener('click', () => loadInventory(source.id));
                      row.addEventListener('keydown', event => {
                        if (event.key === 'Enter' || event.key === ' ') {
                          event.preventDefault(); loadInventory(source.id);
                        }
                      });
                      body.appendChild(row);
                    }
                    panel.scrollIntoView({behavior:'smooth', block:'start'});
                  } catch (error) {
                    document.getElementById('environment-description').textContent =
                      'Unable to load this environment: ' + error.message;
                  }
                }
                document.getElementById('environment-close').addEventListener('click', () => {
                  document.getElementById('environment-details').hidden = true;
                });
                let currentInventory = null;
                async function loadInventory(dataSourceId) {
                  const panel = document.getElementById('inventory-details');
                  const message = document.getElementById('inventory-message');
                  panel.hidden = false; message.textContent = 'Loading inventory…';
                  document.getElementById('inventory-table').hidden = true;
                  try {
                    const response = await fetch('/api/v1/data-sources/' + dataSourceId + '/inventory',
                      {headers:{Accept:'application/json'}});
                    if (!response.ok) throw new Error('HTTP ' + response.status);
                    currentInventory = await response.json();
                    document.getElementById('inventory-title').textContent = currentInventory.dataSourceName;
                    document.getElementById('inventory-search').value = '';
                    renderInventory(''); panel.scrollIntoView({behavior:'smooth', block:'start'});
                  } catch (error) {
                    message.textContent = 'Unable to load inventory: ' + error.message;
                    message.classList.add('error');
                  }
                }
                function renderInventory(filter) {
                  const table = document.getElementById('inventory-table');
                  const message = document.getElementById('inventory-message');
                  const body = table.querySelector('tbody'); body.replaceChildren();
                  const query = filter.trim().toLowerCase(); let count = 0;
                  for (const schema of currentInventory.schemas) {
                    for (const object of schema.tables) {
                      const searchable = [schema.name, object.name, object.objectType,
                        ...object.columns.map(column => column.name + ' ' + column.nativeType)].join(' ').toLowerCase();
                      if (query && !searchable.includes(query)) continue;
                      const row = document.createElement('tr');
                      const columnSummary = object.columns.map(column =>
                        column.name + ' (' + column.nativeType + ')' + (column.primaryKey ? ' [PK]' : '')).join(', ');
                      [schema.name, object.name, object.objectType, object.estimatedRows ?? '—',
                        object.columns.length, object.synchronizedAt || schema.synchronizedAt || '—'].forEach(value => {
                        const cell = document.createElement('td'); cell.textContent = String(value); row.appendChild(cell);
                      });
                      const detailRow = document.createElement('tr'); detailRow.hidden = true;
                      const detailCell = document.createElement('td'); detailCell.colSpan = 6;
                      detailCell.className = 'columns'; detailCell.textContent = columnSummary || 'No columns catalogued.';
                      detailRow.appendChild(detailCell); row.tabIndex = 0;
                      row.addEventListener('click', () => { detailRow.hidden = !detailRow.hidden; });
                      row.addEventListener('keydown', event => {
                        if (event.key === 'Enter' || event.key === ' ') {
                          event.preventDefault(); detailRow.hidden = !detailRow.hidden;
                        }
                      });
                      body.append(row, detailRow); count++;
                    }
                  }
                  message.textContent = count ? count + ' catalogued object(s)' : 'No matching objects.';
                  table.hidden = count === 0;
                }
                document.getElementById('inventory-search').addEventListener('input', event => {
                  if (currentInventory) renderInventory(event.target.value);
                });
                document.getElementById('inventory-close').addEventListener('click', () => {
                  document.getElementById('inventory-details').hidden = true;
                });
                loadJobs(); loadEnvironments();
              </script>
            </body>
            </html>
            """;

    private final QueryJobsUseCase jobs;
    private final QueryEnvironmentsUseCase environments;
    private final QueryInventoryUseCase inventory;
    private final PreviewSqlInventoryUseCase sqlInventoryPreview;
    private final CreateDraftInventoryUseCase draftInventory;
    private final QueryMaskingInventoriesUseCase maskingInventories;
    private final ManageMaskingInventoryUseCase inventoryManagement;
    private final RulesetAdministrationUseCase rulesets;
    private final RepositoryLogin repositoryLogin;
    private final DiscoveryUseCase discovery;
    private final ApplicationSecurity applicationSecurity;
    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentHashMap<String, UserSession> userSessions = new ConcurrentHashMap<>();

    public PrivonymHttpHandler(QueryJobsUseCase jobs, QueryEnvironmentsUseCase environments,
            QueryInventoryUseCase inventory, PreviewSqlInventoryUseCase sqlInventoryPreview,
            CreateDraftInventoryUseCase draftInventory,
            QueryMaskingInventoriesUseCase maskingInventories,
            ManageMaskingInventoryUseCase inventoryManagement,
            RulesetAdministrationUseCase rulesets) {
        this(jobs, environments, inventory, sqlInventoryPreview, draftInventory,
                maskingInventories, inventoryManagement, rulesets, null, null, null);
    }

    public PrivonymHttpHandler(QueryJobsUseCase jobs, QueryEnvironmentsUseCase environments,
            QueryInventoryUseCase inventory, PreviewSqlInventoryUseCase sqlInventoryPreview,
            CreateDraftInventoryUseCase draftInventory,
            QueryMaskingInventoriesUseCase maskingInventories,
            ManageMaskingInventoryUseCase inventoryManagement,
            RulesetAdministrationUseCase rulesets, DiscoveryUseCase discovery,
            RepositoryLogin repositoryLogin) {
        this(jobs, environments, inventory, sqlInventoryPreview, draftInventory,
                maskingInventories, inventoryManagement, rulesets, discovery, repositoryLogin, null);
    }

    public PrivonymHttpHandler(QueryJobsUseCase jobs, QueryEnvironmentsUseCase environments,
            QueryInventoryUseCase inventory, PreviewSqlInventoryUseCase sqlInventoryPreview,
            CreateDraftInventoryUseCase draftInventory,
            QueryMaskingInventoriesUseCase maskingInventories,
            ManageMaskingInventoryUseCase inventoryManagement,
            RulesetAdministrationUseCase rulesets, DiscoveryUseCase discovery,
            RepositoryLogin repositoryLogin, ApplicationSecurity applicationSecurity) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.environments = Objects.requireNonNull(environments, "environments");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.sqlInventoryPreview = Objects.requireNonNull(sqlInventoryPreview, "sqlInventoryPreview");
        this.draftInventory = Objects.requireNonNull(draftInventory, "draftInventory");
        this.maskingInventories = Objects.requireNonNull(maskingInventories, "maskingInventories");
        this.inventoryManagement = Objects.requireNonNull(inventoryManagement, "inventoryManagement");
        this.rulesets = Objects.requireNonNull(rulesets, "rulesets");
        this.repositoryLogin = repositoryLogin;
        this.discovery = discovery;
        this.applicationSecurity = applicationSecurity;
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) {
        String path = request.getHttpURI().getPath();
        if (LOGO_PATH.equals(path)) {
            if (requireGet(request, response, callback)) {
                writeResource(response, callback, "/web/privonym.png", "image/png");
            }
            return true;
        }
        if (ORACLE_LOGO_PATH.equals(path)) {
            if (requireGet(request, response, callback)) {
                writeResource(response, callback, "/web/oracle-logo.png", "image/png");
            }
            return true;
        }
        if (POSTGRESQL_LOGO_PATH.equals(path)) {
            if (requireGet(request, response, callback)) {
                writeResource(response, callback, "/web/postgresql-logo.svg", "image/svg+xml");
            }
            return true;
        }
        if (SQLSERVER_LOGO_PATH.equals(path)) {
            if (requireGet(request, response, callback)) {
                writeResource(response, callback, "/web/sqlserver-logo.png", "image/png");
            }
            return true;
        }
        if (LOGIN_PATH.equals(path)) {
            if (requireGet(request, response, callback)) {
                if (repositoryLogin != null && !repositoryLogin.isConnected()) {
                    writeHtml(response, callback, PrivonymConsolePage.login());
                } else if (applicationSecurity != null && !applicationSecurity.isInitialized()) {
                    redirect(response, callback, SECURITY_SETUP_PATH);
                } else if (applicationSecurity != null && sessionUser(request) == null) {
                    writeHtml(response, callback, PrivonymConsolePage.applicationLogin());
                } else {
                    response.setStatus(303);
                    response.getHeaders().put("Location", "/overview");
                    callback.succeeded();
                }
            }
            return true;
        }
        if (LOGIN_API_PATH.equals(path)) {
            if (!"POST".equals(request.getMethod())) {
                response.getHeaders().put("Allow", "POST");
                writeJson(response, callback, 405,
                        "{\"error\":\"METHOD_NOT_ALLOWED\",\"message\":\"Only POST is allowed\"}");
            } else {
                handleRepositoryLogin(request, response, callback);
            }
            return true;
        }
        if (LOGOUT_PATH.equals(path)) {
            if (!"POST".equals(request.getMethod())) {
                response.getHeaders().put("Allow", "POST");
                writeJson(response, callback, 405,
                        "{\"error\":\"METHOD_NOT_ALLOWED\",\"message\":\"Only POST is allowed\"}");
            } else {
                PrivonymUser user = sessionUser(request);
                if (applicationSecurity != null && applicationSecurity.isInitialized()
                        && (user == null || !user.administrator())) {
                    forbidden(response, callback, "Only a Privonym administrator can disconnect the repository");
                    return true;
                }
                userSessions.clear();
                if (repositoryLogin != null) repositoryLogin.logout();
                response.setStatus(303);
                response.getHeaders().put("Location", LOGIN_PATH);
                callback.succeeded();
            }
            return true;
        }
        if (repositoryLogin != null && !repositoryLogin.isConnected()
                && !HEALTH_PATH.equals(path)) {
            if (path.startsWith("/api/")) {
                writeJson(response, callback, 401,
                        "{\"error\":\"REPOSITORY_LOGIN_REQUIRED\",\"message\":\"Repository login required\"}");
            } else {
                response.setStatus(303);
                response.getHeaders().put("Location", LOGIN_PATH);
                callback.succeeded();
            }
            return true;
        }
        if (applicationSecurity != null) {
            if (SECURITY_SETUP_PATH.equals(path)) {
                if (!requireGet(request, response, callback)) return true;
                if (applicationSecurity.isInitialized()) redirect(response, callback, LOGIN_PATH);
                else writeHtml(response, callback, PrivonymConsolePage.securitySetup());
                return true;
            }
            if (SECURITY_SETUP_API_PATH.equals(path)) {
                if (!"POST".equals(request.getMethod())) methodNotAllowed(response, callback, "POST");
                else handleSecuritySetup(request, response, callback);
                return true;
            }
            if (SECURITY_LOGIN_API_PATH.equals(path)) {
                if (!"POST".equals(request.getMethod())) methodNotAllowed(response, callback, "POST");
                else handleApplicationLogin(request, response, callback);
                return true;
            }
            if (SECURITY_LOGOUT_PATH.equals(path)) {
                if (!"POST".equals(request.getMethod())) methodNotAllowed(response, callback, "POST");
                else {
                    removeSession(request);
                    response.getHeaders().put("Set-Cookie", expiredSessionCookie());
                    redirect(response, callback, LOGIN_PATH);
                }
                return true;
            }
            if (!applicationSecurity.isInitialized()) {
                if (path.startsWith("/api/")) writeJson(response, callback, 428,
                        "{\"error\":\"SECURITY_SETUP_REQUIRED\",\"message\":\"Create the initial Privonym administrator\"}");
                else redirect(response, callback, SECURITY_SETUP_PATH);
                return true;
            }
            PrivonymUser currentUser = sessionUser(request);
            if (currentUser == null) {
                if (path.startsWith("/api/")) writeJson(response, callback, 401,
                        "{\"error\":\"APPLICATION_LOGIN_REQUIRED\",\"message\":\"Privonym login required\"}");
                else redirect(response, callback, LOGIN_PATH);
                return true;
            }
            if (SECURITY_ME_API_PATH.equals(path)) {
                if (requireGet(request, response, callback))
                    writeJson(response, callback, 200, userJson(currentUser));
                return true;
            }
            if (SECURITY_USERS_API_PATH.equals(path)) {
                if (!requirePrivilege(currentUser, ApplicationSecurity.USER_MANAGE, response, callback)) return true;
                if ("GET".equals(request.getMethod())) handleUserList(response, callback);
                else if ("POST".equals(request.getMethod())) handleUserCreate(currentUser, request, response, callback);
                else methodNotAllowed(response, callback, "GET, POST");
                return true;
            }
            if (path.startsWith(SECURITY_USERS_API_PATH + "/") && path.endsWith("/status")) {
                if (!requirePrivilege(currentUser, ApplicationSecurity.USER_MANAGE, response, callback)) return true;
                if (!"PUT".equals(request.getMethod())) methodNotAllowed(response, callback, "PUT");
                else handleUserStatus(currentUser, path, request, response, callback);
                return true;
            }
            if (path.startsWith(SECURITY_USERS_API_PATH + "/") && path.endsWith("/privileges")) {
                if (!requirePrivilege(currentUser, ApplicationSecurity.USER_MANAGE, response, callback)) return true;
                if (!"PUT".equals(request.getMethod())) methodNotAllowed(response, callback, "PUT");
                else handleUserPrivileges(currentUser, path, request, response, callback);
                return true;
            }
            if ("/users".equals(path)
                    && !requirePrivilege(currentUser, ApplicationSecurity.USER_MANAGE, response, callback)) return true;
            if (!authorizeMutation(currentUser, request, path, response, callback)) return true;
        }
        String consolePage = consolePage(path);
        if (consolePage != null) {
            if (requireGet(request, response, callback)) {
                writeHtml(response, callback, consolePage);
            }
            return true;
        }

        if (HEALTH_PATH.equals(path)) {
            if (requireGet(request, response, callback)) {
                writeJson(response, callback, 200,
                        "{\"status\":\"UP\",\"service\":\"privonym\",\"apiVersion\":\"v1\"}");
            }
            return true;
        }
        if ((DISCOVERY_API_PATH + "/connections").equals(path)) {
            if ("GET".equals(request.getMethod())) handleDiscoveryConnections(response,callback);
            else if ("POST".equals(request.getMethod())) handleConnectionCreate(request,response,callback);
            else {response.getHeaders().put("Allow","GET, POST");writeJson(response,callback,405,"{\"error\":\"METHOD_NOT_ALLOWED\"}");}
            return true;
        }
        if (path.startsWith(DISCOVERY_API_PATH + "/connections/")) {
            String id=path.substring((DISCOVERY_API_PATH+"/connections/").length());
            if ("DELETE".equals(request.getMethod())) handleConnectionDelete(id,response,callback);
            else if ("PUT".equals(request.getMethod())) handleConnectionCredentials(id,request,response,callback);
            else {response.getHeaders().put("Allow","PUT, DELETE");writeJson(response,callback,405,"{\"error\":\"METHOD_NOT_ALLOWED\"}");}
            return true;
        }
        if ((DISCOVERY_API_PATH + "/environments").equals(path)) {if(requireGet(request,response,callback))handleEnvironmentOptions(response,callback);return true;}
        if (path.startsWith(DISCOVERY_API_PATH + "/scan/")) {
            if (!"POST".equals(request.getMethod())) { response.getHeaders().put("Allow","POST"); writeJson(response,callback,405,"{\"error\":\"METHOD_NOT_ALLOWED\"}"); }
            else handleDiscoveryScan(path.substring((DISCOVERY_API_PATH+"/scan/").length()),response,callback);
            return true;
        }
        if (path.startsWith(DISCOVERY_API_PATH + "/rulesets/")) {
            if (!"POST".equals(request.getMethod())) { response.getHeaders().put("Allow","POST"); writeJson(response,callback,405,"{\"error\":\"METHOD_NOT_ALLOWED\"}"); }
            else handleDiscoveryCreate(path.substring((DISCOVERY_API_PATH+"/rulesets/").length()),request,response,callback);
            return true;
        }
        if (path.startsWith(DISCOVERY_API_PATH + "/empty-rulesets/")) {if(!"POST".equals(request.getMethod()))writeJson(response,callback,405,"{\"error\":\"METHOD_NOT_ALLOWED\"}");else handleEmptyRulesetCreate(path.substring((DISCOVERY_API_PATH+"/empty-rulesets/").length()),request,response,callback);return true;}

        if (JOBS_PATH.equals(path)) {
            if (requireGet(request, response, callback)) {
                handleJobList(response, callback);
            }
            return true;
        }

        if (path.startsWith(JOBS_PATH + "/")) {
            String relative = path.substring((JOBS_PATH + "/").length());
            if (relative.endsWith("/report.pdf")) {
                if (requireGet(request,response,callback))
                    handleJobReport(relative.substring(0,relative.length()-11),response,callback);
            } else if (relative.endsWith("/scripts/pre") || relative.endsWith("/scripts/post")) {
                if (!"POST".equals(request.getMethod())) { response.getHeaders().put("Allow","POST"); writeJson(response,callback,405,"{\"error\":\"METHOD_NOT_ALLOWED\"}"); }
                else {
                    String phase=relative.endsWith("/scripts/pre")?"PRE":"POST";
                    String suffix=phase.equals("PRE")?"/scripts/pre":"/scripts/post";
                    handleJobScript(relative.substring(0,relative.length()-suffix.length()),phase,request,response,callback);
                }
            } else if (relative.endsWith("/cancel")) {
                if (!"POST".equals(request.getMethod())) { response.getHeaders().put("Allow","POST"); writeJson(response,callback,405,"{\"error\":\"METHOD_NOT_ALLOWED\"}"); }
                else handleJobCancel(relative.substring(0,relative.length()-7),response,callback);
            } else if (relative.endsWith("/execute")) {
                if (!"POST".equals(request.getMethod())) { response.getHeaders().put("Allow","POST"); writeJson(response,callback,405,"{\"error\":\"METHOD_NOT_ALLOWED\"}"); }
                else handleJobExecute(relative.substring(0,relative.length()-8),response,callback);
            } else if ("DELETE".equals(request.getMethod())) handleJobDelete(relative,response,callback);
            else if ("PUT".equals(request.getMethod())) handleJobUpdate(relative,request,response,callback);
            else if (requireGet(request, response, callback)) handleJobDetails(relative, response, callback);
            return true;
        }

        if (ENVIRONMENTS_PATH.equals(path)) {
            if ("POST".equals(request.getMethod())) {
                handleEnvironmentCreate(request, response, callback);
            } else if (requireGet(request, response, callback)) {
                handleEnvironmentList(response, callback);
            }
            return true;
        }

        if (path.startsWith(ENVIRONMENTS_PATH + "/")) {
            if (requireGet(request, response, callback)) {
                handleEnvironmentDetails(path.substring((ENVIRONMENTS_PATH + "/").length()),
                        response, callback);
            }
            return true;
        }

        if (path.startsWith(DATA_SOURCES_PATH) && path.endsWith("/inventory")) {
            if (requireGet(request, response, callback)) {
                String idText = path.substring(DATA_SOURCES_PATH.length(),
                        path.length() - "/inventory".length());
                handleInventory(idText, response, callback);
            }
            return true;
        }

        if (SQL_IMPORT_PREVIEW_PATH.equals(path) || TRANSFORMER_PREVIEW_PATH.equals(path)) {
            if (!"POST".equals(request.getMethod())) {
                response.getHeaders().put("Allow", "POST");
                writeJson(response, callback, 405,
                        "{\"error\":\"METHOD_NOT_ALLOWED\",\"message\":\"Only POST is allowed\"}");
            } else {
                handleSqlImportPreview(request, response, callback);
            }
            return true;
        }

        if (path.startsWith(TRANSFORMER_DRAFT_PATH)) {
            if (!"POST".equals(request.getMethod())) {
                response.getHeaders().put("Allow", "POST");
                writeJson(response, callback, 405,
                        "{\"error\":\"METHOD_NOT_ALLOWED\",\"message\":\"Only POST is allowed\"}");
            } else {
                handleCreateDraftInventory(path.substring(TRANSFORMER_DRAFT_PATH.length()),
                        request, response, callback);
            }
            return true;
        }

        if ((INVENTORY_CSV_PATH + "/template").equals(path)) {
            if (requireGet(request, response, callback)) {
                writeCsv(response, callback, "privonym-inventory-template.csv",
                        LegacyInventoryCsv.template());
            }
            return true;
        }

        if (path.startsWith(INVENTORY_CSV_PATH + "/imports/")) {
            if (!"POST".equals(request.getMethod())) {
                response.getHeaders().put("Allow", "POST");
                writeJson(response, callback, 405,
                        "{\"error\":\"METHOD_NOT_ALLOWED\",\"message\":\"Only POST is allowed\"}");
            } else {
                handleCsvInventoryImport(path.substring((INVENTORY_CSV_PATH + "/imports/").length()),
                        request, response, callback);
            }
            return true;
        }

        if (MASKING_INVENTORIES_PATH.equals(path)) {
            if (requireGet(request, response, callback)) {
                writeJson(response, callback, 410,
                        "{\"error\":\"RESOURCE_REPLACED\",\"message\":\"Inventory now belongs to a Rule Set; use /api/v1/rulesets\"}");
            }
            return true;
        }

        if (ALGORITHMS_API_PATH.equals(path)) {
            if("GET".equals(request.getMethod()))handleAlgorithmList(response,callback);
            else if("POST".equals(request.getMethod()))handleAlgorithmClone(request,response,callback);
            else {response.getHeaders().put("Allow","GET, POST");writeJson(response,callback,405,"{\"error\":\"METHOD_NOT_ALLOWED\"}");}
            return true;
        }

        if (RULESETS_API_PATH.equals(path)) {
            if (requireGet(request, response, callback)) handleRulesetList(response, callback);
            return true;
        }

        if (path.startsWith(RULESETS_API_PATH + "/")) {
            handleRulesetRoute(path.substring((RULESETS_API_PATH + "/").length()),
                    request, response, callback);
            return true;
        }

        if (path.startsWith(MASKING_INVENTORIES_PATH + "/")
                && handleInventoryActionRoute(path, request, response, callback)) {
            return true;
        }

        if (path.startsWith(MASKING_INVENTORIES_PATH + "/") && path.endsWith("/export.csv")) {
            if (requireGet(request, response, callback)) {
                String id = path.substring((MASKING_INVENTORIES_PATH + "/").length(),
                        path.length() - "/export.csv".length());
                handleCsvInventoryExport(id, response, callback);
            }
            return true;
        }

        if (path.startsWith(MASKING_INVENTORIES_PATH + "/")) {
            if (requireGet(request, response, callback)) {
                handleMaskingInventoryDetails(path.substring((MASKING_INVENTORIES_PATH + "/").length()),
                        response, callback);
            }
            return true;
        }


        writeJson(response, callback, 404,
                "{\"error\":\"NOT_FOUND\",\"message\":\"Resource not found\"}");
        return true;
    }

    private void handleDiscoveryConnections(Response response, Callback callback) {
        try { StringBuilder out=new StringBuilder("{\"connections\":["); int i=0;
            for(DiscoveryConnection c:discovery.listConnections()){if(i++>0)out.append(',');out.append("{\"dataSourceId\":").append(c.dataSourceId())
                .append(",\"environmentId\":").append(c.environmentId()).append(",\"name\":\"").append(escape(c.name())).append("\",\"environment\":\"").append(escape(c.environment()))
                .append("\",\"technology\":\"").append(escape(c.technology())).append("\",\"endpoint\":\"").append(escape(c.endpoint()))
                .append("\",\"host\":\"").append(escape(c.host())).append("\",\"port\":").append(c.port()).append(",\"service\":\"").append(escape(c.service())).append("\",\"database\":\"").append(escape(c.database()))
                .append("\",\"username\":\"").append(escape(c.username())).append("\",\"schemaName\":\"").append(escape(c.schemaName())).append("\",\"secretProvider\":\"").append(escape(c.secretProvider())).append("\",\"secretReference\":\"").append(escape(c.secretReference()))
                .append("\",\"passwordConfigured\":").append(c.passwordConfigured()).append(",\"enabled\":").append(c.enabled())
                .append(",\"rulesetExists\":").append(c.rulesetExists()).append(",\"schemaCount\":").append(c.schemaCount())
                .append(",\"columnCount\":").append(c.columnCount()).append('}');} out.append("]}");writeJson(response,callback,200,out.toString());
        } catch(RuntimeException e){serviceUnavailable(response,callback,e);}
    }

    private void handleEnvironmentOptions(Response response,Callback callback){try{StringBuilder out=new StringBuilder("{\"environments\":[");int i=0;for(EnvironmentOption e:discovery.listEnvironmentOptions()){if(i++>0)out.append(',');out.append("{\"id\":").append(e.id()).append(",\"code\":\"").append(escape(e.code())).append("\",\"name\":\"").append(escape(e.name())).append("\"}");}out.append("]}");writeJson(response,callback,200,out.toString());}catch(RuntimeException e){serviceUnavailable(response,callback,e);}}
    private void handleConnectionCreate(Request request,Response response,Callback callback){char[] password=new char[0];try{String body=Content.Source.asString(request);String environmentValue=formParameter(body,"environmentId").trim(),portValue=formParameter(body,"port").trim();if(environmentValue.isBlank())throw new IllegalArgumentException("Environment is required");if(portValue.isBlank())throw new IllegalArgumentException("Port is required");long environmentId;int port;try{environmentId=Long.parseLong(environmentValue);}catch(NumberFormatException e){throw new IllegalArgumentException("Environment is invalid");}try{port=Integer.parseInt(portValue);}catch(NumberFormatException e){throw new IllegalArgumentException("Port must be a number");}password=formParameter(body,"password").toCharArray();ConnectionCreationResult r=discovery.createConnection(environmentId,formParameter(body,"name").trim(),formParameter(body,"technology").trim(),formParameter(body,"schemaName").trim(),formParameter(body,"host").trim(),port,formParameter(body,"service").trim(),formParameter(body,"database").trim(),formParameter(body,"user").trim(),formParameter(body,"secretProvider").trim(),formParameter(body,"secretReference").trim(),password);writeJson(response,callback,201,"{\"connectorId\":"+r.connectorId()+",\"dataSourceId\":"+r.dataSourceId()+",\"name\":\""+escape(r.name())+"\"}");}catch(IllegalArgumentException e){writeJson(response,callback,400,"{\"error\":\"INVALID_CONNECTION\",\"message\":\""+escape(e.getMessage())+"\"}");}catch(Exception e){serviceUnavailable(response,callback,new IllegalStateException(e.getMessage(),e));}finally{java.util.Arrays.fill(password,'\0');}}
    private void handleConnectionDelete(String id,Response response,Callback callback){try{discovery.deleteConnection(Long.parseLong(id));writeJson(response,callback,200,"{\"status\":\"DELETED\"}");}catch(IllegalArgumentException e){writeJson(response,callback,404,"{\"error\":\"CONNECTION_NOT_FOUND\"}");}catch(IllegalStateException e){writeJson(response,callback,409,"{\"error\":\"CONNECTION_IN_USE\",\"message\":\""+escape(e.getMessage())+"\"}");}}
    private void handleConnectionCredentials(String id,Request request,Response response,Callback callback){char[] password=new char[0];try{String body=Content.Source.asString(request);String environmentValue=formParameter(body,"environmentId").trim(),portValue=formParameter(body,"port").trim();if(environmentValue.isBlank())throw new IllegalArgumentException("Environment is required");if(portValue.isBlank())throw new IllegalArgumentException("Port is required");long environmentId;int port;try{environmentId=Long.parseLong(environmentValue);}catch(NumberFormatException error){throw new IllegalArgumentException("Environment is invalid");}try{port=Integer.parseInt(portValue);}catch(NumberFormatException error){throw new IllegalArgumentException("Port must be a number");}password=formParameter(body,"password").toCharArray();ConnectionUpdateCommand command=new ConnectionUpdateCommand(environmentId,formParameter(body,"name"),formParameter(body,"technology"),formParameter(body,"schemaName"),formParameter(body,"host"),port,formParameter(body,"service"),formParameter(body,"database"),formParameter(body,"user"),formParameter(body,"secretProvider"),formParameter(body,"secretReference"),password);discovery.updateConnection(Long.parseLong(id),command);writeJson(response,callback,200,"{\"status\":\"UPDATED\"}");}catch(IllegalArgumentException e){writeJson(response,callback,400,"{\"error\":\"INVALID_CONNECTION\",\"message\":\""+escape(e.getMessage())+"\"}");}catch(Exception e){serviceUnavailable(response,callback,new IllegalStateException(e.getMessage(),e));}finally{java.util.Arrays.fill(password,'\0');}}
    private void handleEmptyRulesetCreate(String id,Request request,Response response,Callback callback){try{long dataSourceId=Long.parseLong(id);String name=queryParameter(request,"name").trim();if(name.isBlank())throw new IllegalArgumentException("A Rule Set name is required");String code=(name+"_"+dataSourceId).toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]+","_").replaceAll("^_+|_+$","");if(code.isEmpty()||!Character.isLetter(code.charAt(0)))code="RULESET_"+code;if(code.length()>100)code=code.substring(0,100);DiscoveryCreationResult r=discovery.createEmptyRuleset(dataSourceId,code,name);assignOwner(request,r.rulesetId());writeJson(response,callback,201,"{\"rulesetId\":"+r.rulesetId()+",\"versionId\":"+r.versionId()+"}");}catch(IllegalArgumentException e){writeJson(response,callback,400,"{\"error\":\"INVALID_RULESET\",\"message\":\""+escape(e.getMessage())+"\"}");}catch(IllegalStateException e){writeJson(response,callback,409,"{\"error\":\"RULESET_EXISTS\",\"message\":\""+escape(e.getMessage())+"\"}");}}

    private void handleDiscoveryScan(String id,Response response,Callback callback){try{DiscoveryScan scan=discovery.scan(Long.parseLong(id));StringBuilder out=new StringBuilder("{\"dataSourceId\":").append(scan.dataSourceId()).append(",\"connectionName\":\"").append(escape(scan.connectionName())).append("\",\"rulesetExists\":").append(scan.rulesetExists()).append(",\"algorithms\":[");int i=0;for(MaskingAlgorithmOption a:scan.algorithms()){if(i++>0)out.append(',');out.append("{\"code\":\"").append(escape(a.code())).append("\",\"name\":\"").append(escape(a.name())).append("\",\"category\":\"").append(escape(a.category())).append("\"}");}out.append("],\"findings\":[");i=0;for(DiscoveryFinding f:scan.findings()){if(i++>0)out.append(',');out.append("{\"columnId\":").append(f.columnId()).append(",\"schema\":\"").append(escape(f.schema())).append("\",\"table\":\"").append(escape(f.table())).append("\",\"column\":\"").append(escape(f.column())).append("\",\"dataType\":\"").append(escape(f.dataType())).append("\",\"classification\":\"").append(escape(f.classification())).append("\",\"confidence\":").append(f.confidence()).append(",\"suggestedAlgorithm\":\"").append(escape(f.suggestedAlgorithm())).append("\"}");}out.append("]}");writeJson(response,callback,200,out.toString());}catch(IllegalArgumentException e){writeJson(response,callback,400,"{\"error\":\"INVALID_SCAN\",\"message\":\""+escape(e.getMessage())+"\"}");}catch(RuntimeException e){serviceUnavailable(response,callback,e);}}

    private void handleDiscoveryCreate(String id,Request request,Response response,Callback callback){try{long dataSourceId=Long.parseLong(id);String code=queryParameter(request,"code").trim().toUpperCase(java.util.Locale.ROOT);String name=queryParameter(request,"name").trim();if(!code.matches("[A-Z][A-Z0-9_]{2,99}")||name.isBlank())throw new IllegalArgumentException("A valid code and name are required");List<DiscoverySelection> selections=new java.util.ArrayList<>();for(String line:Content.Source.asString(request).split("\\R")){if(line.isBlank())continue;String[] pair=line.split(",",2);if(pair.length!=2)throw new IllegalArgumentException("Invalid selection");selections.add(new DiscoverySelection(Long.parseLong(pair[0]),pair[1].trim()));}DiscoveryCreationResult result=discovery.createRuleset(dataSourceId,code,name,selections);assignOwner(request,result.rulesetId());writeJson(response,callback,201,"{\"rulesetId\":"+result.rulesetId()+",\"versionId\":"+result.versionId()+",\"code\":\""+escape(result.code())+"\",\"rulesCreated\":"+result.rulesCreated()+"}");}catch(IllegalArgumentException e){writeJson(response,callback,400,"{\"error\":\"INVALID_DISCOVERY_REQUEST\",\"message\":\""+escape(e.getMessage())+"\"}");}catch(IllegalStateException e){writeJson(response,callback,409,"{\"error\":\"RULESET_ALREADY_EXISTS\",\"message\":\""+escape(e.getMessage())+"\"}");}catch(Exception e){serviceUnavailable(response,callback,new IllegalStateException(e.getMessage(),e));}}

    private void handleRepositoryLogin(Request request, Response response, Callback callback) {
        char[] password = new char[0];
        try {
            String body = Content.Source.asString(request);
            String value = formParameter(body, "password");
            password = value.toCharArray();
            if (password.length == 0) {
                writeJson(response, callback, 400,
                        "{\"error\":\"PASSWORD_REQUIRED\",\"message\":\"Password is required\"}");
                return;
            }
            repositoryLogin.login(password);
            writeJson(response, callback, 200, "{\"status\":\"CONNECTED\"}");
        } catch (Exception error) {
            error.printStackTrace(System.err);
            boolean invalidCredentials = hasOracleError(error, 1017);
            String message = invalidCredentials ? "Invalid repository password"
                    : "Repository initialization failed: " + safeMessage(error);
            writeJson(response, callback, invalidCredentials ? 401 : 503,
                    "{\"error\":\"" + (invalidCredentials ? "INVALID_CREDENTIALS" : "REPOSITORY_INITIALIZATION_FAILED")
                            + "\",\"message\":\"" + escape(message) + "\"}");
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    private static boolean hasOracleError(Throwable error, int errorCode) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof java.sql.SQLException sql && sql.getErrorCode() == errorCode) return true;
        }
        return false;
    }

    private static String safeMessage(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) return current.getMessage();
        }
        return "Unknown repository error";
    }

    private static String formParameter(String body, String expectedName) {
        for (String field : body.split("&")) {
            String[] pair = field.split("=", 2);
            if (URLDecoder.decode(pair[0], StandardCharsets.UTF_8).equals(expectedName)) {
                return pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            }
        }
        return "";
    }

    private static String consolePage(String path) {
        if (HOME_PATH.equals(path) || "/index.html".equals(path) || "/overview".equals(path)) {
            return PrivonymConsolePage.overview();
        }
        if ("/environments".equals(path)) {
            return PrivonymConsolePage.environments();
        }
        if (path.startsWith("/environments/")) {
            String code = path.substring("/environments/".length());
            if (code.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,29}")) {
                return PrivonymConsolePage.environmentDetails(code);
            }
            return null;
        }
        if (path.startsWith("/inventories/")) {
            String id = path.substring("/inventories/".length());
            if (id.matches("[1-9][0-9]*")) return PrivonymConsolePage.inventoryDetails(id);
            return null;
        }
        if (path.startsWith("/rulesets/")) {
            String id = path.substring("/rulesets/".length());
            if (id.matches("[1-9][0-9]*")) return PrivonymConsolePage.rulesetDetails(id);
            return null;
        }
        if (path.startsWith("/jobs/")) {
            String reference = path.substring("/jobs/".length());
            if (reference.matches("[A-Za-z0-9][A-Za-z0-9._~-]{0,199}")) {
                return PrivonymConsolePage.jobs();
            }
            return null;
        }
        return switch (path) {
            case "/connections" -> PrivonymConsolePage.connections();
            case "/inventories" -> PrivonymConsolePage.rulesets();
            case "/discovery" -> PrivonymConsolePage.discovery();
            case "/sql-import" -> PrivonymConsolePage.sqlImport();
            case "/transformer" -> PrivonymConsolePage.sqlImport();
            case "/rulesets" -> PrivonymConsolePage.rulesets();
            case "/jobs" -> PrivonymConsolePage.jobs();
            case "/monitor" -> PrivonymConsolePage.monitor();
            case "/scheduler" -> PrivonymConsolePage.scheduler();
            case "/users" -> PrivonymConsolePage.users();
            case "/algorithms" -> PrivonymConsolePage.algorithms();
            case "/audit" -> PrivonymConsolePage.placeholder("audit", "Audit");
            case "/settings" -> PrivonymConsolePage.placeholder("settings", "Settings");
            case "/about" -> PrivonymConsolePage.about();
            default -> null;
        };
    }

    private void handleJobList(Response response, Callback callback) {
        try {
            writeJson(response, callback, 200, summariesJson(jobs.listJobs(100)));
        } catch (RuntimeException error) {
            serviceUnavailable(response, callback, error);
        }
    }

    private void handleJobDetails(String reference, Response response, Callback callback) {
        try {
            writeJson(response, callback, 200, detailsJson(jobs.getJob(reference)));
        } catch (IllegalArgumentException error) {
            writeJson(response, callback, 404,
                    "{\"error\":\"JOB_NOT_FOUND\",\"message\":\"Job not found\"}");
        } catch (RuntimeException error) {
            serviceUnavailable(response, callback, error);
        }
    }

    private void handleJobDelete(String reference,Response response,Callback callback){try{long jobId=jobs.getJob(reference).job().id();rulesets.deleteJob(reference);deleteJobReports(jobId);writeJson(response,callback,200,"{\"jobId\":"+jobId+",\"status\":\"DELETED\"}");}catch(IllegalArgumentException e){writeJson(response,callback,404,"{\"error\":\"JOB_NOT_FOUND\",\"message\":\"Job not found\"}");}catch(IllegalStateException e){writeJson(response,callback,409,"{\"error\":\"JOB_IN_USE\",\"message\":\""+escape(e.getMessage())+"\"}");}catch(RuntimeException e){serviceUnavailable(response,callback,e);}}

    private void handleJobUpdate(String currentReference,Request request,Response response,
            Callback callback){
        try {
            String body=Content.Source.asString(request);
            long rulesetId=Long.parseLong(formParameter(body,"rulesetId"));
            int parallelDegree=Integer.parseInt(formParameter(body,"parallelDegree"));
            int chunkSize=Integer.parseInt(formParameter(body,"chunkSize"));
            long chunkThreshold=Long.parseLong(formParameter(body,"chunkThreshold"));
            JobUpdateResult result=rulesets.updateJob(currentReference,
                    formParameter(body,"reference"),rulesetId,parallelDegree,
                    formParameter(body,"executionMode"),chunkSize,chunkThreshold);
            if(result.planReset())deleteJobReports(result.jobId());
            writeJson(response,callback,200,jobUpdateJson(result));
        }catch(NumberFormatException error){writeJson(response,callback,400,"{\"error\":\"INVALID_JOB_UPDATE\",\"message\":\"Rule Set and valid execution settings are required\"}");}
        catch(IllegalArgumentException error){int status="Job not found".equals(error.getMessage())?404:400;writeJson(response,callback,status,"{\"error\":\"JOB_UPDATE_REJECTED\",\"message\":\""+escape(error.getMessage())+"\"}");}
        catch(IllegalStateException error){writeJson(response,callback,409,"{\"error\":\"JOB_IN_USE\",\"message\":\""+escape(error.getMessage())+"\"}");}
        catch(IOException error){writeJson(response,callback,400,"{\"error\":\"INVALID_JOB_UPDATE\",\"message\":\"Unable to read Job update\"}");}
        catch(RuntimeException error){serviceUnavailable(response,callback,error);}
    }

    private void handleJobScript(String reference,String phase,Request request,Response response,
            Callback callback) {
        try {
            String content=Content.Source.asString(request);
            String fileName=queryParameter(request,"fileName");
            rulesets.saveJobScript(reference,phase,fileName,content);
            writeJson(response,callback,200,"{\"reference\":\""+escape(reference)
                    +"\",\"phase\":\""+phase+"\",\"fileName\":\""
                    +escape(fileName)+"\",\"stored\":"+(!content.isBlank())+"}");
        } catch (IOException error) {
            writeJson(response,callback,400,"{\"error\":\"INVALID_JOB_SCRIPT\",\"message\":\"Unable to read SQL script\"}");
        } catch (IllegalArgumentException error) {
            writeJson(response,callback,400,"{\"error\":\"INVALID_JOB_SCRIPT\",\"message\":\""+escape(error.getMessage())+"\"}");
        } catch (IllegalStateException error) {
            writeJson(response,callback,409,"{\"error\":\"JOB_IN_USE\",\"message\":\""+escape(error.getMessage())+"\"}");
        }
    }

    private void handleJobExecute(String identifier,Response response,Callback callback){try{var result=identifier.matches("[1-9][0-9]*")?rulesets.executeJob(Long.parseLong(identifier)):rulesets.executeJob(identifier);writeJson(response,callback,200,"{\"jobId\":"+result.jobId().value()+",\"status\":\""+result.status()+"\",\"executedSteps\":"+result.executedStepCount()+",\"rowsProcessed\":"+result.rowsProcessed()+"}");}catch(IllegalArgumentException e){writeJson(response,callback,400,"{\"error\":\"JOB_EXECUTION_REJECTED\",\"message\":\""+escape(e.getMessage())+"\"}");}catch(IllegalStateException e){System.err.println("Job execution failed: "+e.getMessage());writeJson(response,callback,409,"{\"error\":\"JOB_EXECUTION_REJECTED\",\"message\":\""+escape(e.getMessage())+"\"}");}catch(RuntimeException e){System.err.println("Job execution failed: "+e.getMessage());writeJson(response,callback,500,"{\"error\":\"JOB_EXECUTION_FAILED\",\"message\":\""+escape(e.getMessage())+"\"}");}}

    private void handleJobCancel(String identifier,Response response,Callback callback){try{if(!identifier.matches("[1-9][0-9]*"))throw new IllegalArgumentException("Invalid Job identifier");long jobId=Long.parseLong(identifier);rulesets.cancelJob(jobId);writeJson(response,callback,202,"{\"jobId\":"+jobId+",\"status\":\"CANCELLING\"}");}catch(IllegalArgumentException e){writeJson(response,callback,404,"{\"error\":\"JOB_NOT_FOUND\",\"message\":\""+escape(e.getMessage())+"\"}");}catch(IllegalStateException e){writeJson(response,callback,409,"{\"error\":\"JOB_CANCELLATION_REJECTED\",\"message\":\""+escape(e.getMessage())+"\"}");}catch(RuntimeException e){serviceUnavailable(response,callback,e);}}

    private void handleJobReport(String identifier,Response response,Callback callback){
        try {
            if(!identifier.matches("[1-9][0-9]*"))throw new IllegalArgumentException("Invalid Job identifier");
            String prefix="privonym-job-"+identifier+"-";
            Path directory=Path.of(System.getProperty("privonym.report.directory","reports")).toAbsolutePath().normalize();
            if(!Files.isDirectory(directory)){writeJson(response,callback,404,"{\"error\":\"REPORT_NOT_FOUND\",\"message\":\"No report exists for this execution. Re-execute this Job to generate its validated PDF report.\"}");return;}
            Path report;
            try(var files=Files.list(directory)){report=files.filter(path->path.getFileName().toString().startsWith(prefix)&&path.getFileName().toString().endsWith(".pdf"))
                    .max(Comparator.comparing(path->path.getFileName().toString())).orElse(null);}
            if(report==null){writeJson(response,callback,404,"{\"error\":\"REPORT_NOT_FOUND\",\"message\":\"No report exists for this execution. Re-execute this Job to generate its validated PDF report.\"}");return;}
            byte[] bytes=Files.readAllBytes(report);response.setStatus(200);response.getHeaders().put("Content-Type","application/pdf");response.getHeaders().put("Content-Disposition","attachment; filename=\""+report.getFileName()+"\"");response.getHeaders().put("Cache-Control","no-store");response.getHeaders().put("X-Content-Type-Options","nosniff");response.write(true,ByteBuffer.wrap(bytes),callback);
        }catch(IllegalArgumentException error){writeJson(response,callback,400,"{\"error\":\"INVALID_JOB_ID\"}");}
        catch(IOException error){writeJson(response,callback,500,"{\"error\":\"REPORT_ERROR\",\"message\":\"Unable to read execution report\"}");}
    }

    private void handleEnvironmentList(Response response, Callback callback) {
        try {
            writeJson(response, callback, 200, environmentsJson(environments.listEnvironments()));
        } catch (RuntimeException error) {
            serviceUnavailable(response, callback, error);
        }
    }

    private void handleEnvironmentCreate(Request request, Response response, Callback callback) {
        try {
            String body = Content.Source.asString(request);
            EnvironmentSummary created = environments.createEnvironment(
                    formParameter(body, "code"), formParameter(body, "name"),
                    formParameter(body, "description"));
            writeJson(response, callback, 201, "{\"id\":" + created.id()
                    + ",\"code\":\"" + escape(created.code()) + "\",\"name\":\""
                    + escape(created.name()) + "\",\"status\":\"" + escape(created.status()) + "\"}");
        } catch (IllegalArgumentException error) {
            writeJson(response, callback, 400,
                    "{\"error\":\"ENVIRONMENT_CREATION_REJECTED\",\"message\":\""
                            + escape(error.getMessage()) + "\"}");
        } catch (Exception error) {
            serviceUnavailable(response, callback,
                    error instanceof RuntimeException runtime ? runtime
                            : new IllegalStateException(error.getMessage(), error));
        }
    }

    private void handleEnvironmentDetails(String code, Response response, Callback callback) {
        try {
            writeJson(response, callback, 200, environmentDetailsJson(environments.getEnvironment(code)));
        } catch (IllegalArgumentException error) {
            writeJson(response, callback, 404,
                    "{\"error\":\"ENVIRONMENT_NOT_FOUND\",\"message\":\"Environment not found\"}");
        } catch (RuntimeException error) {
            serviceUnavailable(response, callback, error);
        }
    }

    private void handleInventory(String idText, Response response, Callback callback) {
        try {
            long dataSourceId = Long.parseLong(idText);
            writeJson(response, callback, 200, inventoryJson(inventory.getInventory(dataSourceId)));
        } catch (IllegalArgumentException error) {
            writeJson(response, callback, 404,
                    "{\"error\":\"DATA_SOURCE_NOT_FOUND\",\"message\":\"Data source not found\"}");
        } catch (RuntimeException error) {
            serviceUnavailable(response, callback, error);
        }
    }

    private void handleSqlImportPreview(Request request, Response response, Callback callback) {
        try {
            long dataSourceId = Long.parseLong(queryParameter(request, "dataSourceId"));
            if (dataSourceId <= 0) throw new NumberFormatException();
            DataSourceInventory selectedInventory = inventory.getInventory(dataSourceId);
            String sql = Content.Source.asString(request);
            writeJson(response, callback, 200,
                    sqlInventoryPreviewJson(sqlInventoryPreview.preview(
                            selectedInventory.technology(), sql)));
        } catch (NumberFormatException error) {
            writeJson(response, callback, 400,
                    "{\"error\":\"DATA_SOURCE_REQUIRED\",\"message\":\"Select a connection before analyzing the script\"}");
        } catch (IOException error) {
            writeJson(response, callback, 400,
                    "{\"error\":\"INVALID_SQL_INPUT\",\"message\":\"Unable to read SQL content\"}");
        } catch (IllegalArgumentException error) {
            writeJson(response, callback, 400,
                    "{\"error\":\"INVALID_SQL_INPUT\",\"message\":\""
                            + escape(error.getMessage()) + "\"}");
        } catch (RuntimeException error) {
            writeJson(response, callback, 500,
                    "{\"error\":\"SQL_PARSE_FAILED\",\"message\":\"SQL parsing failed\"}");
        }
    }

    private void handleCreateDraftInventory(String dataSourceText, Request request,
            Response response, Callback callback) {
        try {
            long dataSourceId = Long.parseLong(dataSourceText);
            String code = queryParameter(request, "code");
            String name = queryParameter(request, "name");
            String sql = Content.Source.asString(request);
            DataSourceInventory selectedInventory = inventory.getInventory(dataSourceId);
            DraftInventoryResult created = draftInventory.create(dataSourceId,
                    selectedInventory.technology(), code, name, sql);
            assignVersionOwner(request, created.rulesetVersionId());
            writeJson(response, callback, 201, draftInventoryJson(created));
        } catch (NumberFormatException error) {
            writeJson(response, callback, 400,
                    "{\"error\":\"INVALID_DATA_SOURCE\",\"message\":\"A valid data source is required\"}");
        } catch (IOException | IllegalArgumentException error) {
            writeJson(response, callback, 400,
                    "{\"error\":\"DRAFT_INVENTORY_REJECTED\",\"message\":\""
                            + escape(error.getMessage()) + "\"}");
        } catch (RuntimeException error) {
            System.err.println("Draft inventory creation failed: " + error.getMessage());
            writeJson(response, callback, 503,
                    "{\"error\":\"DRAFT_INVENTORY_FAILED\",\"message\":\"Unable to create draft inventory\"}");
        }
    }

    private void handleCsvInventoryImport(String dataSourceText, Request request,
            Response response, Callback callback) {
        try {
            long dataSourceId = Long.parseLong(dataSourceText);
            String csv = Content.Source.asString(request);
            String code = LegacyInventoryCsv.rulesetName(csv);
            SqlInventoryPreview preview = LegacyInventoryCsv.parse(csv);
            // A CSV may be imported into an empty Rule Set whose connection has not been
            // scanned yet, or whose target schema has changed since the last scan.
            // Refresh the catalog before resolving imported table and column names.
            discovery.scan(dataSourceId);
            DraftInventoryResult created = draftInventory.createFromInventory(
                    dataSourceId, code, code, csv, preview);
            assignVersionOwner(request, created.rulesetVersionId());
            writeJson(response, callback, 201, draftInventoryJson(created));
        } catch (NumberFormatException error) {
            writeJson(response, callback, 400,
                    "{\"error\":\"INVALID_DATA_SOURCE\",\"message\":\"A valid data source is required\"}");
        } catch (IOException | IllegalArgumentException error) {
            writeJson(response, callback, 400,
                    "{\"error\":\"CSV_INVENTORY_REJECTED\",\"message\":\""
                            + escape(error.getMessage()) + "\"}");
        } catch (RuntimeException error) {
            System.err.println("CSV inventory import failed: " + error.getMessage());
            String detail = error.getMessage() == null || error.getMessage().isBlank()
                    ? "The repository did not provide an error detail"
                    : error.getMessage();
            writeJson(response, callback, 503,
                    "{\"error\":\"CSV_INVENTORY_FAILED\",\"message\":\"Unable to import CSV inventory: "
                            + escape(detail) + "\"}");
        }
    }

    private void handleCsvInventoryExport(String idText,
            Response response, Callback callback) {
        try {
            MaskingInventoryDetails details = maskingInventories.getInventory(Long.parseLong(idText));
            String filename = details.inventory().code().replaceAll("[^A-Za-z0-9._-]", "_")
                    + "-v" + details.inventory().versionNumber() + ".csv";
            writeCsv(response, callback, filename, LegacyInventoryCsv.export(details));
        } catch (IllegalArgumentException error) {
            writeJson(response, callback, 404,
                    "{\"error\":\"INVENTORY_NOT_FOUND\",\"message\":\"Inventory not found\"}");
        } catch (RuntimeException error) {
            serviceUnavailable(response, callback, error);
        }
    }

    private void handleMaskingInventoryList(Response response, Callback callback) {
        try {
            writeJson(response, callback, 200,
                    maskingInventoryListJson(maskingInventories.listInventories()));
        } catch (RuntimeException error) {
            serviceUnavailable(response, callback, error);
        }
    }

    private void handleMaskingInventoryDetails(String idText,
            Response response, Callback callback) {
        try {
            long id = Long.parseLong(idText);
            writeJson(response, callback, 200,
                    maskingInventoryDetailsJson(maskingInventories.getInventory(id)));
        } catch (IllegalArgumentException error) {
            writeJson(response, callback, 404,
                    "{\"error\":\"INVENTORY_NOT_FOUND\",\"message\":\"Inventory not found\"}");
        } catch (RuntimeException error) {
            serviceUnavailable(response, callback, error);
        }
    }

    private boolean handleInventoryActionRoute(String path, Request request,
            Response response, Callback callback) {
        String relative = path.substring((MASKING_INVENTORIES_PATH + "/").length());
        String[] parts = relative.split("/");
        boolean publish = parts.length == 2 && "publish".equals(parts[1]);
        boolean resolveAll = parts.length == 2 && "resolve-all".equals(parts[1]);
        boolean mappingAction = parts.length == 4 && "mappings".equals(parts[1])
                && ("resolve".equals(parts[3]) || "accept".equals(parts[3]));
        if (!publish && !resolveAll && !mappingAction) return false;
        if (!"POST".equals(request.getMethod())) {
            response.getHeaders().put("Allow", "POST");
            writeJson(response, callback, 405,
                    "{\"error\":\"METHOD_NOT_ALLOWED\",\"message\":\"Only POST is allowed\"}");
            return true;
        }
        try {
            long versionId = Long.parseLong(parts[0]);
            InventoryMutationResult result;
            if (publish) {
                result = inventoryManagement.publish(versionId);
            } else if (resolveAll) {
                result = inventoryManagement.resolveAllMappings(versionId);
            } else {
                long mappingId = Long.parseLong(parts[2]);
                result = "accept".equals(parts[3])
                        ? inventoryManagement.acceptMapping(versionId, mappingId)
                        : inventoryManagement.resolveMapping(versionId, mappingId,
                                queryParameter(request, "algorithm"),
                                queryParameter(request, "parameters"));
            }
            writeJson(response, callback, 200, inventoryMutationJson(result));
        } catch (IllegalArgumentException error) {
            writeJson(response, callback, 400,
                    "{\"error\":\"INVENTORY_ACTION_REJECTED\",\"message\":\""
                            + escape(error.getMessage()) + "\"}");
        } catch (RuntimeException error) {
            System.err.println("Inventory action failed: " + error.getMessage());
            writeJson(response, callback, 503,
                    "{\"error\":\"INVENTORY_ACTION_FAILED\",\"message\":\"Inventory action failed\"}");
        }
        return true;
    }

    private void handleAlgorithmList(Response response, Callback callback) {
        try {
            writeJson(response, callback, 200,
                    maskingAlgorithmsJson(inventoryManagement.listAlgorithms()));
        } catch (RuntimeException error) {
            serviceUnavailable(response, callback, error);
        }
    }
    private void handleAlgorithmClone(Request request,Response response,Callback callback){try{String body=Content.Source.asString(request);var result=inventoryManagement.cloneAlgorithm(queryParameter(request,"template"),queryParameter(request,"code"),queryParameter(request,"name"),lookupImportValues(body));writeJson(response,callback,201,"{\"algorithmId\":"+result.algorithmId()+",\"versionId\":"+result.versionId()+",\"code\":\""+escape(result.code())+"\",\"name\":\""+escape(result.name())+"\",\"implementationType\":\""+escape(result.implementationType())+"\",\"status\":\""+result.status()+"\",\"lookupValueCount\":"+result.lookupValueCount()+"}");}catch(IOException|IllegalArgumentException e){writeJson(response,callback,400,"{\"error\":\"ALGORITHM_CREATION_REJECTED\",\"message\":\""+escape(e.getMessage())+"\"}");}catch(RuntimeException e){System.err.println("Algorithm creation failed: "+e.getMessage());writeJson(response,callback,500,"{\"error\":\"ALGORITHM_CREATION_FAILED\",\"message\":\""+escape(e.getMessage())+"\"}");}}

    private static List<String> lookupImportValues(String body){List<String> values=new java.util.ArrayList<>();if(body==null)return values;for(String raw:body.split("\\R")){String line=raw.trim();if(line.isBlank())continue;String value=line;if(line.startsWith("\"")&&line.length()>1){int end=line.indexOf("\"",1);while(end>0&&end+1<line.length()&&line.charAt(end+1)=='\"')end=line.indexOf("\"",end+2);if(end>0)value=line.substring(1,end).replace("\"\"","\"");}else{int comma=line.indexOf(',');int semicolon=line.indexOf(';');int cut=comma<0?semicolon:semicolon<0?comma:Math.min(comma,semicolon);if(cut>=0)value=line.substring(0,cut).trim();}if(values.isEmpty()&&value.matches("(?i)value|value_text|name|nom|prenom|ville|adresse"))continue;if(!value.isBlank())values.add(value);}return values;}

    private void handleRulesetList(Response response, Callback callback) {
        try {
            writeJson(response, callback, 200, rulesetListJson(rulesets.listRulesets()));
        } catch (RuntimeException error) {
            serviceUnavailable(response, callback, error);
        }
    }

    private void handleRulesetRoute(String relative, Request request,
            Response response, Callback callback) {
        String[] parts = relative.split("/");
        try {
            long rulesetId = Long.parseLong(parts[0]);
            if (parts.length == 1) {
                if ("DELETE".equals(request.getMethod())) {
                    handleRulesetDelete(rulesetId, response, callback);
                } else if (requireGet(request, response, callback)) {
                    String version = queryParameter(request, "versionId");
                    Long versionId = version.isBlank() ? null : Long.valueOf(version);
                    writeJson(response, callback, 200,
                            rulesetDetailsJson(rulesets.getRuleset(rulesetId, versionId)));
                }
                return;
            }
            if (parts.length == 3 && "versions".equals(parts[1])) {
                if (!"DELETE".equals(request.getMethod())) {
                    response.getHeaders().put("Allow", "DELETE");
                    writeJson(response, callback, 405,
                            "{\"error\":\"METHOD_NOT_ALLOWED\",\"message\":\"Only DELETE is allowed\"}");
                } else {
                    long versionId = Long.parseLong(parts[2]);
                    rulesets.deleteVersion(rulesetId, versionId);
                    writeJson(response, callback, 200, "{\"status\":\"DELETED\"}");
                }
                return;
            }
            if (parts.length == 2 && ("export.csv".equals(parts[1])
                    || "inventory.csv".equals(parts[1]))) {
                if (requireGet(request, response, callback)) {
                    String version = queryParameter(request, "versionId");
                    Long versionId = version.isBlank() ? null : Long.valueOf(version);
                    RulesetDetails details = rulesets.getRuleset(rulesetId, versionId);
                    String filename = details.ruleset().code().replaceAll("[^A-Za-z0-9._-]", "_")
                            + "-v" + selectedVersionNumber(details) + "-ruleset.csv";
                    writeCsv(response, callback, filename, RulesetInventoryCsv.export(details));
                }
                return;
            }
            if (parts.length == 2 && "export.sql".equals(parts[1])) {
                if (requireGet(request, response, callback)) {
                    long versionId = Long.parseLong(queryParameter(request, "versionId"));
                    com.privonym.application.service.RulesetSqlExport export =
                            rulesets.exportSql(rulesetId, versionId);
                    String filename = export.rulesetCode().replaceAll("[^A-Za-z0-9._-]", "_")
                            + "-v" + export.versionNumber() + "-" 
                            + export.technology().toLowerCase(java.util.Locale.ROOT) + ".sql";
                    writeSql(response, callback, filename, export.sql());
                }
                return;
            }
            if (parts.length == 2 && "draft".equals(parts[1])) {
                if (!"POST".equals(request.getMethod())) {
                    response.getHeaders().put("Allow", "POST");
                    writeJson(response, callback, 405,
                            "{\"error\":\"METHOD_NOT_ALLOWED\",\"message\":\"Only POST is allowed\"}");
                } else {
                    long sourceVersionId = Long.parseLong(queryParameter(request, "versionId"));
                    RulesetDetails details = rulesets.getRuleset(rulesetId, sourceVersionId);
                    RulesetVersionView sourceVersion = details.versions().stream()
                            .filter(item -> item.id() == sourceVersionId).findFirst()
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "Rule Set version was not found"));
                    if (!"PUBLISHED".equals(sourceVersion.status())) {
                        throw new IllegalArgumentException(
                                "A draft can only be created from a published version");
                    }
                    if (details.versions().stream().anyMatch(
                            item -> "DRAFT".equals(item.status()))) {
                        throw new IllegalStateException(
                                "This Rule Set already has a DRAFT version");
                    }
                    discovery.scan(details.ruleset().dataSourceId());
                    RulesetVersionView version = rulesets.createDraft(rulesetId, sourceVersionId);
                    writeJson(response, callback, 201, "{\"versionId\":" + version.id()
                            + ",\"versionNumber\":" + version.number() + ",\"status\":\"DRAFT\"}");
                }
                return;
            }
            if (parts.length == 2 && "clone".equals(parts[1])) {
                if (!"POST".equals(request.getMethod())) {
                    response.getHeaders().put("Allow", "POST");
                    writeJson(response, callback, 405,
                            "{\"error\":\"METHOD_NOT_ALLOWED\",\"message\":\"Only POST is allowed\"}");
                } else {
                    long targetDataSourceId = Long.parseLong(
                            queryParameter(request, "targetDataSourceId"));
                    String name = queryParameter(request, "name");
                    discovery.scan(targetDataSourceId);
                    var result = rulesets.cloneRuleset(rulesetId, targetDataSourceId, name);
                    assignOwner(request, result.rulesetId());
                    writeJson(response, callback, 201, "{\"rulesetId\":" + result.rulesetId()
                            + ",\"versionId\":" + result.versionId()
                            + ",\"sourceVersionId\":" + result.sourceVersionId()
                            + ",\"columnRulesCloned\":" + result.columnRulesCloned()
                            + ",\"tableRulesCloned\":" + result.tableRulesCloned()
                            + ",\"skippedRules\":" + result.skippedRules() + "}");
                }
                return;
            }
            if (parts.length == 2 && "catalog".equals(parts[1])) {
                if (requireGet(request, response, callback)) {
                    long versionId = Long.parseLong(queryParameter(request, "versionId"));
                    writeJson(response, callback, 200,
                            rulesetCatalogJson(rulesets.listCatalog(rulesetId, versionId),
                                    inventoryManagement.listAlgorithms()));
                }
                return;
            }
            if (parts.length == 2 && "inventory".equals(parts[1])) {
                if (!"PUT".equals(request.getMethod())) {
                    response.getHeaders().put("Allow", "PUT");
                    writeJson(response, callback, 405,
                            "{\"error\":\"METHOD_NOT_ALLOWED\",\"message\":\"Only PUT is allowed\"}");
                } else {
                    long versionId = Long.parseLong(queryParameter(request, "versionId"));
                    List<RulesetInventorySelection> selections = new java.util.ArrayList<>();
                    for (String line : Content.Source.asString(request).split("\\R")) {
                        if (line.isBlank()) continue;
                        String[] fields = line.split("\\t", -1);
                        if ((fields.length != 4 && fields.length != 5) || fields[2].isBlank())
                            throw new IllegalArgumentException("Invalid inventory selection");
                        selections.add(new RulesetInventorySelection(fields[0],
                                Long.parseLong(fields[1].trim()), fields[2], fields[3],
                                fields.length == 5 ? parseFormParameters(fields[4]) : Map.of()));
                    }
                    int saved = rulesets.saveInventory(rulesetId, versionId, selections);
                    writeJson(response, callback, 200, "{\"saved\":" + saved + "}");
                }
                return;
            }
            if (parts.length == 2 && "jobs".equals(parts[1])) {
                if (!"POST".equals(request.getMethod())) {
                    response.getHeaders().put("Allow", "POST");
                    writeJson(response, callback, 405,
                            "{\"error\":\"METHOD_NOT_ALLOWED\",\"message\":\"Only POST is allowed\"}");
                } else {
                    long versionId = Long.parseLong(queryParameter(request, "versionId"));
                    int parallel = Integer.parseInt(queryParameter(request, "parallelDegree"));
                    int chunkSize = Integer.parseInt(queryParameter(request, "chunkSize"));
                    long chunkThreshold = Long.parseLong(queryParameter(request, "chunkThreshold"));
                    JobCreationResult result = rulesets.createJob(rulesetId, versionId,
                            queryParameter(request, "reference"), parallel,
                            queryParameter(request, "executionMode"), chunkSize, chunkThreshold);
                    writeJson(response, callback, 201, jobCreationJson(result));
                }
                return;
            }
            writeJson(response, callback, 404,
                    "{\"error\":\"NOT_FOUND\",\"message\":\"Ruleset resource not found\"}");
        } catch (NumberFormatException error) {
            writeJson(response, callback, 400,
                    "{\"error\":\"INVALID_RULESET_REQUEST\",\"message\":\"Invalid numeric value\"}");
        } catch (IllegalArgumentException error) {
            writeJson(response, callback, 400,
                    "{\"error\":\"RULESET_REQUEST_REJECTED\",\"message\":\""
                            + escape(error.getMessage()) + "\"}");
        } catch (IllegalStateException error) {
            writeJson(response, callback, 409,
                    "{\"error\":\"RULESET_STATE_CONFLICT\",\"message\":\""
                            + escape(error.getMessage()) + "\"}");
        } catch (IOException error) {
            writeJson(response, callback, 400,
                    "{\"error\":\"INVALID_RULESET_REQUEST\",\"message\":\"Unable to read request body\"}");
        } catch (RuntimeException error) {
            serviceUnavailable(response, callback, error);
        }
    }

    private void handleRulesetDelete(long rulesetId, Response response, Callback callback) {
        try {
            rulesets.deleteRuleset(rulesetId);
            writeJson(response, callback, 200,
                    "{\"status\":\"DELETED\",\"rulesetId\":" + rulesetId + "}");
        } catch (IllegalArgumentException error) {
            writeJson(response, callback, 404,
                    "{\"error\":\"RULESET_NOT_FOUND\",\"message\":\"Rule Set not found\"}");
        } catch (IllegalStateException error) {
            writeJson(response, callback, 409,
                    "{\"error\":\"RULESET_IN_USE\",\"message\":\""
                            + escape(error.getMessage()) + "\"}");
        } catch (RuntimeException error) {
            serviceUnavailable(response, callback, error);
        }
    }

    private static int selectedVersionNumber(RulesetDetails details) {
        return details.versions().stream()
                .filter(item -> item.id() == details.selectedVersionId())
                .mapToInt(RulesetVersionView::number).findFirst().orElse(0);
    }

    private static String queryParameter(Request request, String expectedName) {
        String query = request.getHttpURI().getQuery();
        if (query == null) return "";
        for (String item : query.split("&")) {
            String[] pair = item.split("=", 2);
            if (URLDecoder.decode(pair[0], StandardCharsets.UTF_8).equals(expectedName)) {
                return pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            }
        }
        return "";
    }


    private static void serviceUnavailable(Response response, Callback callback, RuntimeException error) {
        System.err.println("Repository query failed: " + error.getMessage());
        writeJson(response, callback, 503,
                "{\"error\":\"REPOSITORY_UNAVAILABLE\",\"message\":\"Repository unavailable\"}");
    }

    private static String summariesJson(List<JobSummary> summaries) {
        StringBuilder json = new StringBuilder("{\"jobs\":[");
        for (int index = 0; index < summaries.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            appendSummary(json, summaries.get(index));
        }
        return json.append("],\"count\":").append(summaries.size()).append('}').toString();
    }

    private static String detailsJson(JobDetails details) {
        StringBuilder json = new StringBuilder("{\"job\":");
        appendSummary(json, details.job());
        json.append(",\"rulesetVersionId\":").append(details.rulesetVersionId())
                .append(",\"dataSourceId\":").append(details.dataSourceId())
                .append(",\"errorCode\":\"").append(escape(details.errorCode()))
                .append("\",\"errorMessage\":\"").append(escape(details.errorMessage()))
                .append("\",\"preScriptFileName\":\"").append(escape(details.preScriptFileName()))
                .append("\",\"postScriptFileName\":\"").append(escape(details.postScriptFileName()))
                .append("\",\"steps\":[");
        for (int index = 0; index < details.steps().size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            JobStepDetails step = details.steps().get(index);
            json.append("{\"number\":").append(step.number())
                    .append(",\"type\":\"").append(escape(step.type()))
                    .append("\",\"status\":\"").append(escape(step.status()))
                    .append("\",\"rowsProcessed\":").append(step.rowsProcessed()).append('}');
        }
        return json.append("]}").toString();
    }

    private static String environmentsJson(List<EnvironmentSummary> environments) {
        StringBuilder json = new StringBuilder("{\"environments\":[");
        for (int index = 0; index < environments.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            appendEnvironment(json, environments.get(index));
        }
        return json.append("],\"count\":").append(environments.size()).append('}').toString();
    }

    private static String environmentDetailsJson(EnvironmentDetails details) {
        StringBuilder json = new StringBuilder("{\"environment\":");
        appendEnvironment(json, details.environment());
        json.append(",\"description\":\"").append(escape(details.description()))
                .append("\",\"dataSources\":[");
        for (int index = 0; index < details.dataSources().size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            DataSourceSummary source = details.dataSources().get(index);
            json.append("{\"id\":").append(source.id())
                    .append(",\"name\":\"").append(escape(source.name()))
                    .append("\",\"enabled\":").append(source.enabled())
                    .append(",\"technology\":\"").append(escape(source.technology()))
                    .append("\",\"connectorStatus\":\"").append(escape(source.connectorStatus()))
                    .append("\",\"host\":\"").append(escape(source.host()))
                    .append("\",\"port\":").append(source.port())
                    .append(",\"service\":\"").append(escape(source.service()))
                    .append("\",\"username\":\"").append(escape(source.username()))
                    .append("\",\"schemaCount\":").append(source.schemaCount())
                    .append(",\"tableCount\":").append(source.tableCount())
                    .append(",\"jobCount\":").append(source.jobCount()).append('}');
        }
        return json.append("]}").toString();
    }

    private static void appendEnvironment(StringBuilder json, EnvironmentSummary environment) {
        json.append("{\"id\":").append(environment.id())
                .append(",\"code\":\"").append(escape(environment.code()))
                .append("\",\"name\":\"").append(escape(environment.name()))
                .append("\",\"status\":\"").append(escape(environment.status()))
                .append("\",\"owner\":\"").append(escape(environment.owner()))
                .append("\",\"dataSourceCount\":").append(environment.dataSourceCount())
                .append(",\"jobCount\":").append(environment.jobCount()).append('}');
    }

    private static String inventoryJson(DataSourceInventory inventory) {
        StringBuilder json = new StringBuilder("{\"dataSourceId\":")
                .append(inventory.dataSourceId()).append(",\"dataSourceName\":\"")
                .append(escape(inventory.dataSourceName())).append("\",\"technology\":\"")
                .append(escape(inventory.technology())).append("\",\"schemas\":[");
        for (int schemaIndex = 0; schemaIndex < inventory.schemas().size(); schemaIndex++) {
            if (schemaIndex > 0) {
                json.append(',');
            }
            InventorySchema schema = inventory.schemas().get(schemaIndex);
            json.append("{\"name\":\"").append(escape(schema.name()))
                    .append("\",\"synchronizedAt\":\"").append(escape(schema.synchronizedAt()))
                    .append("\",\"tables\":[");
            for (int tableIndex = 0; tableIndex < schema.tables().size(); tableIndex++) {
                if (tableIndex > 0) {
                    json.append(',');
                }
                appendInventoryTable(json, schema.tables().get(tableIndex));
            }
            json.append("]}");
        }
        return json.append("]}").toString();
    }

    private static void appendInventoryTable(StringBuilder json, InventoryTable table) {
        json.append("{\"id\":").append(table.id())
                .append(",\"name\":\"").append(escape(table.name()))
                .append("\",\"objectType\":\"").append(escape(table.objectType()))
                .append("\",\"estimatedRows\":");
        if (table.estimatedRows() == null) {
            json.append("null");
        } else {
            json.append(table.estimatedRows());
        }
        json.append(",\"enabled\":").append(table.enabled())
                .append(",\"synchronizedAt\":\"").append(escape(table.synchronizedAt()))
                .append("\",\"columns\":[");
        for (int index = 0; index < table.columns().size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            InventoryColumn column = table.columns().get(index);
            json.append("{\"name\":\"").append(escape(column.name()))
                    .append("\",\"position\":").append(column.position())
                    .append(",\"nativeType\":\"").append(escape(column.nativeType()))
                    .append("\",\"normalizedType\":\"").append(escape(column.normalizedType()))
                    .append("\",\"nullable\":").append(column.nullable())
                    .append(",\"primaryKey\":").append(column.primaryKey())
                    .append(",\"unique\":").append(column.unique()).append('}');
        }
        json.append("]}");
    }

    private static String sqlInventoryPreviewJson(SqlInventoryPreview preview) {
        StringBuilder json = new StringBuilder("{\"technology\":\"")
                .append(escape(preview.technology())).append("\",\"checksum\":\"")
                .append(escape(preview.checksum())).append("\",\"statementCount\":")
                .append(preview.statementCount()).append(",\"tableCount\":")
                .append(preview.tables().size()).append(",\"columnCount\":")
                .append(preview.columnCount()).append(",\"actionCount\":")
                .append(preview.actionCount()).append(",\"tables\":[");
        for (int tableIndex = 0; tableIndex < preview.tables().size(); tableIndex++) {
            if (tableIndex > 0) json.append(',');
            SqlInventoryTable table = preview.tables().get(tableIndex);
            json.append("{\"schema\":\"").append(escape(table.schema()))
                    .append("\",\"name\":\"").append(escape(table.name()))
                    .append("\",\"operation\":\"").append(escape(table.operation()))
                    .append("\",\"algorithmCode\":\"").append(escape(table.algorithmCode()))
                    .append("\",\"filterExpression\":\"").append(escape(table.filterExpression()))
                    .append("\",\"columns\":[");
            for (int columnIndex = 0; columnIndex < table.columns().size(); columnIndex++) {
                if (columnIndex > 0) json.append(',');
                SqlInventoryColumn column = table.columns().get(columnIndex);
                json.append("{\"name\":\"").append(escape(column.name()))
                        .append("\",\"position\":").append(column.position())
                        .append(",\"sourceExpression\":\"").append(escape(column.sourceExpression()))
                        .append("\",\"algorithmCode\":\"").append(escape(column.algorithmCode()))
                        .append("\",\"matchStatus\":\"").append(escape(column.matchStatus()))
                        .append("\",\"parameters\":{");
                int parameterIndex = 0;
                for (var parameter : column.parameters().entrySet()) {
                    if (parameterIndex++ > 0) json.append(',');
                    json.append('"').append(escape(parameter.getKey())).append("\":\"")
                            .append(escape(parameter.getValue())).append('"');
                }
                json.append("}}");
            }
            json.append("]}");
        }
        json.append("],\"warnings\":[");
        for (int index = 0; index < preview.warnings().size(); index++) {
            if (index > 0) json.append(',');
            json.append('"').append(escape(preview.warnings().get(index))).append('"');
        }
        return json.append("]}").toString();
    }

    private static String draftInventoryJson(DraftInventoryResult result) {
        return new StringBuilder("{\"importId\":").append(result.importId())
                .append(",\"importReference\":\"").append(escape(result.importReference()))
                .append("\",\"rulesetVersionId\":").append(result.rulesetVersionId())
                .append(",\"inventoryCode\":\"").append(escape(result.inventoryCode()))
                .append("\",\"versionNumber\":").append(result.versionNumber())
                .append(",\"status\":\"").append(escape(result.status()))
                .append("\",\"columnRulesCreated\":").append(result.columnRulesCreated())
                .append(",\"tableRulesCreated\":").append(result.tableRulesCreated())
                .append(",\"reviewItems\":").append(result.reviewItems())
                .append(",\"checksum\":\"").append(escape(result.checksum()))
                .append("\"}").toString();
    }

    private static String maskingInventoryListJson(List<MaskingInventorySummary> inventories) {
        StringBuilder json = new StringBuilder("{\"inventories\":[");
        for (int index = 0; index < inventories.size(); index++) {
            if (index > 0) json.append(',');
            appendMaskingInventory(json, inventories.get(index));
        }
        return json.append("],\"count\":").append(inventories.size()).append('}').toString();
    }

    private static String maskingInventoryDetailsJson(MaskingInventoryDetails details) {
        StringBuilder json = new StringBuilder("{\"inventory\":");
        appendMaskingInventory(json, details.inventory());
        json.append(",\"mappings\":[");
        for (int index = 0; index < details.mappings().size(); index++) {
            if (index > 0) json.append(',');
            MaskingInventoryMapping mapping = details.mappings().get(index);
            json.append("{\"id\":").append(mapping.mappingId())
                    .append(",\"statementNumber\":").append(mapping.statementNumber())
                    .append(",\"executionOrder\":").append(mapping.executionOrder())
                    .append(",\"targetType\":\"").append(escape(mapping.targetType()))
                    .append("\",\"operation\":\"").append(escape(mapping.operation()))
                    .append("\",\"schema\":\"").append(escape(mapping.schema()))
                    .append("\",\"table\":\"").append(escape(mapping.table()))
                    .append("\",\"column\":\"").append(escape(mapping.column()))
                    .append("\",\"algorithmCode\":\"").append(escape(mapping.algorithmCode()))
                    .append("\",\"matchStatus\":\"").append(escape(mapping.matchStatus()))
                    .append("\",\"diagnostic\":\"").append(escape(mapping.diagnostic()))
                    .append("\",\"parametersJson\":\"").append(escape(mapping.parametersJson()))
                    .append("\",\"filterExpression\":\"").append(escape(mapping.filterExpression()))
                    .append("\",\"sourceExpression\":\"").append(escape(mapping.sourceExpression()))
                    .append("\"}");
        }
        return json.append("]}").toString();
    }

    private static void appendMaskingInventory(StringBuilder json, MaskingInventorySummary inventory) {
        json.append("{\"rulesetVersionId\":").append(inventory.rulesetVersionId())
                .append(",\"code\":\"").append(escape(inventory.code()))
                .append("\",\"name\":\"").append(escape(inventory.name()))
                .append("\",\"versionNumber\":").append(inventory.versionNumber())
                .append(",\"status\":\"").append(escape(inventory.status()))
                .append("\",\"createdAt\":\"").append(escape(inventory.createdAt()))
                .append("\",\"columnRuleCount\":").append(inventory.columnRuleCount())
                .append(",\"tableRuleCount\":").append(inventory.tableRuleCount())
                .append(",\"reviewItemCount\":").append(inventory.reviewItemCount())
                .append(",\"dataSourceName\":\"").append(escape(inventory.dataSourceName()))
                .append("\",\"importReference\":\"").append(escape(inventory.importReference()))
                .append("\"}");
    }

    private static String maskingAlgorithmsJson(List<MaskingAlgorithmOption> algorithms) {
        StringBuilder json = new StringBuilder("{\"algorithms\":[");
        for (int index = 0; index < algorithms.size(); index++) {
            if (index > 0) json.append(',');
            MaskingAlgorithmOption algorithm = algorithms.get(index);
            json.append("{\"code\":\"").append(escape(algorithm.code()))
                    .append("\",\"name\":\"").append(escape(algorithm.name()))
                    .append("\",\"category\":\"").append(escape(algorithm.category()))
                    .append("\",\"versionStatus\":\"").append(escape(algorithm.versionStatus()))
                    .append("\",\"implementationType\":\"").append(escape(algorithm.implementationType()))
                    .append("\",\"parameters\":[");
            for (int parameterIndex = 0; parameterIndex < algorithm.parameters().size(); parameterIndex++) {
                if (parameterIndex > 0) json.append(',');
                json.append('"').append(escape(algorithm.parameters().get(parameterIndex))).append('"');
            }
            json.append("]}");
        }
        return json.append("],\"count\":").append(algorithms.size()).append('}').toString();
    }

    private static String inventoryMutationJson(InventoryMutationResult result) {
        return new StringBuilder("{\"rulesetVersionId\":").append(result.rulesetVersionId())
                .append(",\"status\":\"").append(escape(result.status()))
                .append("\",\"reviewItems\":").append(result.reviewItems())
                .append(",\"message\":\"").append(escape(result.message()))
                .append("\"}").toString();
    }

    private static String rulesetListJson(List<RulesetSummary> rulesets) {
        StringBuilder json = new StringBuilder("{\"rulesets\":[");
        for (int index = 0; index < rulesets.size(); index++) {
            if (index > 0) json.append(',');
            appendRuleset(json, rulesets.get(index));
        }
        return json.append("],\"count\":").append(rulesets.size()).append('}').toString();
    }

    private static String rulesetDetailsJson(RulesetDetails details) {
        StringBuilder json = new StringBuilder("{\"ruleset\":");
        appendRuleset(json, details.ruleset());
        json.append(",\"selectedVersionId\":").append(details.selectedVersionId())
                .append(",\"versions\":[");
        for (int index = 0; index < details.versions().size(); index++) {
            if (index > 0) json.append(',');
            RulesetVersionView version = details.versions().get(index);
            json.append("{\"id\":").append(version.id())
                    .append(",\"number\":").append(version.number())
                    .append(",\"status\":\"").append(escape(version.status()))
                    .append("\",\"createdAt\":\"").append(escape(version.createdAt()))
                    .append("\",\"publishedAt\":\"").append(escape(version.publishedAt()))
                    .append("\",\"inventoryCount\":").append(version.inventoryCount())
                    .append(",\"jobCount\":").append(version.jobCount()).append('}');
        }
        json.append("],\"inventory\":[");
        for (int index = 0; index < details.inventory().size(); index++) {
            if (index > 0) json.append(',');
            RulesetInventoryEntry entry = details.inventory().get(index);
            json.append("{\"ruleId\":").append(entry.ruleId())
                    .append(",\"tableOwner\":\"").append(escape(entry.tableOwner()))
                    .append("\",\"tableName\":\"").append(escape(entry.tableName()))
                    .append("\",\"columnName\":\"").append(escape(entry.columnName()))
                    .append("\",\"dataType\":\"").append(escape(entry.dataType()))
                    .append("\",\"algorithm\":\"").append(escape(entry.algorithm()))
                    .append("\",\"masked\":").append(entry.masked())
                    .append(",\"criteria\":\"").append(escape(entry.criteria())).append("\"}");
        }
        json.append("],\"jobs\":[");
        for (int index = 0; index < details.jobs().size(); index++) {
            if (index > 0) json.append(',');
            RulesetJobView job = details.jobs().get(index);
            json.append("{\"id\":").append(job.id())
                    .append(",\"reference\":\"").append(escape(job.reference()))
                    .append("\",\"status\":\"").append(escape(job.status()))
                    .append("\",\"versionNumber\":").append(job.versionNumber())
                    .append(",\"createdAt\":\"").append(escape(job.createdAt()))
                    .append("\",\"plannedAt\":\"").append(escape(job.plannedAt())).append("\"}");
        }
        return json.append("]}").toString();
    }

    private static String rulesetCatalogJson(List<RulesetCatalogColumn> columns,
            List<MaskingAlgorithmOption> algorithms) {
        StringBuilder json = new StringBuilder("{\"columns\":[");
        for (int index = 0; index < columns.size(); index++) {
            if (index > 0) json.append(',');
            RulesetCatalogColumn column = columns.get(index);
            json.append("{\"tableId\":").append(column.tableId())
                    .append(",\"columnId\":").append(column.columnId())
                    .append(",\"schema\":\"").append(escape(column.schema()))
                    .append("\",\"table\":\"").append(escape(column.table()))
                    .append("\",\"column\":\"").append(escape(column.column()))
                    .append("\",\"dataType\":\"").append(escape(column.dataType()))
                    .append("\",\"tableSelected\":").append(column.tableSelected())
                    .append(",\"tableAlgorithmCode\":\"").append(escape(column.tableAlgorithmCode()))
                    .append("\",\"tableCriteria\":\"").append(escape(column.tableCriteria()))
                    .append("\",\"selected\":").append(column.selected())
                    .append(",\"algorithmCode\":\"").append(escape(column.algorithmCode()))
                    .append("\",\"criteria\":\"").append(escape(column.criteria()))
                    .append("\",\"configuredParameters\":[");
            for (int parameterIndex = 0;
                    parameterIndex < column.configuredParameters().size(); parameterIndex++) {
                if (parameterIndex > 0) json.append(',');
                json.append('\"').append(escape(column.configuredParameters().get(parameterIndex)))
                        .append('\"');
            }
            json.append("],\"compatibleAlgorithmCodes\":[");
            for (int algorithmIndex = 0;
                    algorithmIndex < column.compatibleAlgorithmCodes().size(); algorithmIndex++) {
                if (algorithmIndex > 0) json.append(',');
                json.append('\"').append(escape(
                        column.compatibleAlgorithmCodes().get(algorithmIndex))).append('\"');
            }
            json.append("]}");
        }
        json.append("],\"algorithms\":[");
        for (int index = 0; index < algorithms.size(); index++) {
            if (index > 0) json.append(',');
            MaskingAlgorithmOption algorithm = algorithms.get(index);
            json.append("{\"code\":\"").append(escape(algorithm.code()))
                    .append("\",\"name\":\"").append(escape(algorithm.name()))
                    .append("\",\"category\":\"").append(escape(algorithm.category()))
                    .append("\",\"versionStatus\":\"").append(escape(algorithm.versionStatus()))
                    .append("\",\"implementationType\":\"").append(escape(algorithm.implementationType()))
                    .append("\",\"parameters\":[");
            for (int parameterIndex = 0; parameterIndex < algorithm.parameters().size(); parameterIndex++) {
                if (parameterIndex > 0) json.append(',');
                json.append('\"').append(escape(algorithm.parameters().get(parameterIndex))).append('\"');
            }
            json.append("]}");
        }
        return json.append("]}").toString();
    }

    private static Map<String, String> parseFormParameters(String encoded) {
        if (encoded == null || encoded.isBlank()) return Map.of();
        Map<String, String> result = new java.util.LinkedHashMap<>();
        for (String pair : encoded.split("&")) {
            if (pair.isBlank()) continue;
            String[] parts = pair.split("=", 2);
            String name = java.net.URLDecoder.decode(parts[0], java.nio.charset.StandardCharsets.UTF_8);
            String value = java.net.URLDecoder.decode(parts.length == 2 ? parts[1] : "",
                    java.nio.charset.StandardCharsets.UTF_8);
            if (name.isBlank() || result.putIfAbsent(name, value) != null) {
                throw new IllegalArgumentException("Invalid or duplicate Rule Set parameter");
            }
        }
        return Map.copyOf(result);
    }

    private static void appendRuleset(StringBuilder json, RulesetSummary ruleset) {
        json.append("{\"id\":").append(ruleset.id())
                .append(",\"code\":\"").append(escape(ruleset.code()))
                .append("\",\"name\":\"").append(escape(ruleset.name()))
                .append("\",\"description\":\"").append(escape(ruleset.description()))
                .append("\",\"dataSourceId\":").append(ruleset.dataSourceId())
                .append(",\"dataSourceName\":\"").append(escape(ruleset.dataSourceName()))
                .append("\",\"technology\":\"").append(escape(ruleset.technology()))
                .append("\",\"environmentCode\":\"").append(escape(ruleset.environmentCode()))
                .append("\",\"environmentName\":\"").append(escape(ruleset.environmentName()))
                .append("\",\"versionCount\":").append(ruleset.versionCount())
                .append(",\"inventoryCount\":").append(ruleset.inventoryCount())
                .append(",\"jobCount\":").append(ruleset.jobCount()).append('}');
    }

    private static String jobCreationJson(JobCreationResult result) {
        return new StringBuilder("{\"jobId\":").append(result.jobId())
                .append(",\"reference\":\"").append(escape(result.reference()))
                .append("\",\"status\":\"").append(escape(result.status()))
                .append("\",\"rulesetVersionId\":").append(result.rulesetVersionId())
                .append('}').toString();
    }

    private static String jobUpdateJson(JobUpdateResult result) {
        return new StringBuilder("{\"jobId\":").append(result.jobId())
                .append(",\"reference\":\"").append(escape(result.reference()))
                .append("\",\"status\":\"").append(escape(result.status()))
                .append("\",\"rulesetId\":").append(result.rulesetId())
                .append(",\"rulesetVersionId\":").append(result.rulesetVersionId())
                .append(",\"planReset\":").append(result.planReset()).append('}').toString();
    }

    private void handleSecuritySetup(Request request, Response response, Callback callback) {
        char[] password = new char[0];
        try {
            if (applicationSecurity.isInitialized()) {
                writeJson(response, callback, 409,
                        "{\"error\":\"ALREADY_INITIALIZED\",\"message\":\"Privonym security is already initialized\"}");
                return;
            }
            String body = Content.Source.asString(request);
            password = formParameter(body, "password").toCharArray();
            PrivonymUser user = applicationSecurity.createInitialAdministrator(
                    formParameter(body, "username"), formParameter(body, "displayName"), password);
            createSession(response, user);
            writeJson(response, callback, 201, userJson(user));
        } catch (IllegalArgumentException | IllegalStateException error) {
            writeJson(response, callback, 400,
                    "{\"error\":\"SECURITY_SETUP_FAILED\",\"message\":\"" + escape(error.getMessage()) + "\"}");
        } catch (IOException error) {
            serviceUnavailable(response, callback, new IllegalStateException("Unable to read security setup", error));
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    private void handleApplicationLogin(Request request, Response response, Callback callback) {
        char[] password = new char[0];
        try {
            String body = Content.Source.asString(request);
            password = formParameter(body, "password").toCharArray();
            PrivonymUser user = applicationSecurity.authenticate(
                    formParameter(body, "username"), password);
            createSession(response, user);
            writeJson(response, callback, 200, userJson(user));
        } catch (IllegalArgumentException error) {
            writeJson(response, callback, 401,
                    "{\"error\":\"INVALID_CREDENTIALS\",\"message\":\"Invalid username or password\"}");
        } catch (RuntimeException error) {
            serviceUnavailable(response, callback, error);
        } catch (IOException error) {
            serviceUnavailable(response, callback, new IllegalStateException("Unable to read login request", error));
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    private void handleUserList(Response response, Callback callback) {
        try {
            StringBuilder json = new StringBuilder("{\"users\":[");
            List<PrivonymUser> users = applicationSecurity.listUsers();
            for (int index = 0; index < users.size(); index++) {
                if (index > 0) json.append(',');
                appendUser(json, users.get(index));
            }
            writeJson(response, callback, 200,
                    json.append("],\"availablePrivileges\":[")
                            .append(quotedValues(ApplicationSecurity.AVAILABLE_PRIVILEGES))
                            .append("]}").toString());
        } catch (RuntimeException error) {
            serviceUnavailable(response, callback, error);
        }
    }

    private void handleUserCreate(PrivonymUser actor, Request request,
            Response response, Callback callback) {
        char[] password = new char[0];
        try {
            String body = Content.Source.asString(request);
            password = formParameter(body, "password").toCharArray();
            Set<String> privileges = new HashSet<>();
            String values = formParameter(body, "privileges");
            if (!values.isBlank()) for (String value : values.split(",")) privileges.add(value);
            boolean administrator = "true".equalsIgnoreCase(formParameter(body, "administrator"));
            if (administrator && !actor.administrator()) {
                throw new IllegalStateException("Only an administrator can create another administrator");
            }
            if (!actor.administrator() && !actor.privileges().containsAll(privileges)) {
                throw new IllegalStateException("A user cannot grant privileges they do not own");
            }
            PrivonymUser created = applicationSecurity.createUser(actor.username(),
                    formParameter(body, "username"), formParameter(body, "displayName"),
                    password, administrator, privileges);
            writeJson(response, callback, 201, userJson(created));
        } catch (IllegalArgumentException | IllegalStateException error) {
            writeJson(response, callback, 400,
                    "{\"error\":\"INVALID_USER\",\"message\":\"" + escape(error.getMessage()) + "\"}");
        } catch (RuntimeException error) {
            serviceUnavailable(response, callback, error);
        } catch (IOException error) {
            serviceUnavailable(response, callback, new IllegalStateException("Unable to read user request", error));
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    private void handleUserStatus(PrivonymUser actor, String path, Request request,
            Response response, Callback callback) {
        try {
            String value = path.substring((SECURITY_USERS_API_PATH + "/").length(),
                    path.length() - "/status".length());
            long userId = Long.parseLong(value);
            String body = Content.Source.asString(request);
            boolean enabled = "true".equalsIgnoreCase(formParameter(body, "enabled"));
            applicationSecurity.setUserEnabled(actor.username(), userId, enabled);
            if (!enabled) userSessions.entrySet().removeIf(
                    entry -> entry.getValue().user().id() == userId);
            writeJson(response, callback, 200,
                    "{\"userId\":" + userId + ",\"enabled\":" + enabled + "}");
        } catch (IllegalArgumentException error) {
            writeJson(response, callback, 400,
                    "{\"error\":\"INVALID_USER\",\"message\":\"" + escape(error.getMessage()) + "\"}");
        } catch (IllegalStateException error) {
            writeJson(response, callback, 409,
                    "{\"error\":\"USER_UPDATE_REJECTED\",\"message\":\"" + escape(error.getMessage()) + "\"}");
        } catch (IOException error) {
            serviceUnavailable(response, callback, new IllegalStateException("Unable to read user status", error));
        }
    }

    private void handleUserPrivileges(PrivonymUser actor, String path, Request request,
            Response response, Callback callback) {
        try {
            String value = path.substring((SECURITY_USERS_API_PATH + "/").length(),
                    path.length() - "/privileges".length());
            long userId = Long.parseLong(value);
            String body = Content.Source.asString(request);
            Set<String> privileges = new HashSet<>();
            String values = formParameter(body, "privileges");
            if (!values.isBlank()) for (String item : values.split(",")) privileges.add(item);
            boolean administrator = "true".equalsIgnoreCase(formParameter(body, "administrator"));
            if (administrator && !actor.administrator()) {
                throw new IllegalStateException("Only an administrator can grant the administrator role");
            }
            if (!actor.administrator() && !actor.privileges().containsAll(privileges)) {
                throw new IllegalStateException("A user cannot grant privileges they do not own");
            }
            PrivonymUser updated = applicationSecurity.updateUserPrivileges(actor.username(), userId,
                    administrator, privileges);
            userSessions.entrySet().removeIf(entry -> entry.getValue().user().id() == userId
                    && entry.getValue().user().id() != actor.id());
            writeJson(response, callback, 200, userJson(updated));
        } catch (IOException error) {
            serviceUnavailable(response, callback, new IllegalStateException("Unable to read privileges", error));
        } catch (IllegalArgumentException error) {
            writeJson(response, callback, 400,
                    "{\"error\":\"INVALID_PRIVILEGES\",\"message\":\"" + escape(error.getMessage()) + "\"}");
        } catch (IllegalStateException error) {
            writeJson(response, callback, 409,
                    "{\"error\":\"PRIVILEGE_UPDATE_REJECTED\",\"message\":\"" + escape(error.getMessage()) + "\"}");
        }
    }

    private boolean authorizeMutation(PrivonymUser user, Request request, String path,
            Response response, Callback callback) {
        String method = request.getMethod();
        if ("GET".equals(method) || "HEAD".equals(method) || user.administrator()) return true;
        String privilege = null;
        Long rulesetId = null;
        Long versionId = null;
        if (path.startsWith(DISCOVERY_API_PATH + "/scan/")) privilege = ApplicationSecurity.DISCOVERY_RUN;
        else if (path.startsWith(DISCOVERY_API_PATH + "/connections")) privilege = ApplicationSecurity.CONNECTION_MANAGE;
        else if (path.startsWith(DISCOVERY_API_PATH + "/rulesets/")
                || path.startsWith(DISCOVERY_API_PATH + "/empty-rulesets/")
                || path.startsWith(INVENTORY_CSV_PATH + "/imports/")
                || path.startsWith(TRANSFORMER_DRAFT_PATH)) privilege = ApplicationSecurity.RULESET_CREATE;
        else if (path.startsWith(ENVIRONMENTS_PATH)) privilege = ApplicationSecurity.ENVIRONMENT_MANAGE;
        else if (path.startsWith(MASKING_INVENTORIES_PATH + "/") && path.endsWith("/publish")) {
            String value = path.substring((MASKING_INVENTORIES_PATH + "/").length(),
                    path.length() - "/publish".length());
            try { versionId = Long.valueOf(value); } catch (NumberFormatException ignored) { }
            privilege = ApplicationSecurity.RULESET_PUBLISH_OWN;
        }
        else if (path.startsWith(ALGORITHMS_API_PATH)
                || path.startsWith(MASKING_INVENTORIES_PATH)) privilege = ApplicationSecurity.ALGORITHM_MANAGE;
        else if (path.startsWith(JOBS_PATH + "/")) privilege = path.endsWith("/execute")
                ? ApplicationSecurity.JOB_EXECUTE : ApplicationSecurity.JOB_MANAGE;
        else if (path.startsWith(RULESETS_API_PATH + "/")) {
            String relative = path.substring((RULESETS_API_PATH + "/").length());
            String first = relative.contains("/") ? relative.substring(0, relative.indexOf('/')) : relative;
            try { rulesetId = Long.valueOf(first); } catch (NumberFormatException ignored) { }
            if ("DELETE".equals(method)) privilege = ApplicationSecurity.RULESET_DELETE_OWN;
            else if (relative.endsWith("/jobs")) privilege = ApplicationSecurity.JOB_MANAGE;
            else privilege = ApplicationSecurity.RULESET_MODIFY_OWN;
        }
        if (privilege == null || !user.has(privilege)) {
            forbidden(response, callback, "Missing privilege: " + (privilege == null ? "ADMINISTRATOR" : privilege));
            return false;
        }
        if (rulesetId != null && !applicationSecurity.ownsRuleset(user.id(), rulesetId)) {
            forbidden(response, callback, "This Rule Set belongs to another Privonym user");
            return false;
        }
        if (versionId != null && !applicationSecurity.ownsRulesetVersion(user.id(), versionId)) {
            forbidden(response, callback, "This Rule Set version belongs to another Privonym user");
            return false;
        }
        return true;
    }

    private boolean requirePrivilege(PrivonymUser user, String privilege,
            Response response, Callback callback) {
        if (user.has(privilege)) return true;
        forbidden(response, callback, "Missing privilege: " + privilege);
        return false;
    }

    private void assignOwner(Request request, long rulesetId) {
        if (applicationSecurity == null) return;
        PrivonymUser user = sessionUser(request);
        if (user != null) applicationSecurity.assignRulesetOwner(rulesetId, user.id());
    }

    private void assignVersionOwner(Request request, long rulesetVersionId) {
        if (applicationSecurity == null) return;
        PrivonymUser user = sessionUser(request);
        if (user != null) applicationSecurity.assignRulesetVersionOwner(rulesetVersionId, user.id());
    }

    private void createSession(Response response, PrivonymUser user) {
        byte[] bytes = new byte[32]; secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        userSessions.put(token, new UserSession(user, Instant.now().plusSeconds(SESSION_SECONDS)));
        response.getHeaders().put("Set-Cookie", SESSION_COOKIE + "=" + token
                + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + SESSION_SECONDS);
    }

    private PrivonymUser sessionUser(Request request) {
        String token = sessionToken(request);
        if (token.isBlank()) return null;
        UserSession session = userSessions.get(token);
        if (session == null) return null;
        if (session.expiresAt().isBefore(Instant.now())) {
            userSessions.remove(token);
            return null;
        }
        userSessions.put(token, new UserSession(session.user(),
                Instant.now().plusSeconds(SESSION_SECONDS)));
        return session.user();
    }

    private void removeSession(Request request) {
        String token = sessionToken(request);
        if (!token.isBlank()) userSessions.remove(token);
    }

    private static String sessionToken(Request request) {
        String cookies = request.getHeaders().get("Cookie");
        if (cookies == null) return "";
        for (String cookie : cookies.split(";")) {
            String[] pair = cookie.trim().split("=", 2);
            if (pair.length == 2 && SESSION_COOKIE.equals(pair[0])) return pair[1];
        }
        return "";
    }

    private static String expiredSessionCookie() {
        return SESSION_COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0";
    }

    private static String userJson(PrivonymUser user) {
        StringBuilder json = new StringBuilder(); appendUser(json, user); return json.toString();
    }

    private static void appendUser(StringBuilder json, PrivonymUser user) {
        json.append("{\"id\":").append(user.id())
                .append(",\"username\":\"").append(escape(user.username()))
                .append("\",\"displayName\":\"").append(escape(user.displayName()))
                .append("\",\"administrator\":").append(user.administrator())
                .append(",\"enabled\":").append(user.enabled())
                .append(",\"privileges\":[").append(quotedValues(user.privileges())).append("]}");
    }

    private static String quotedValues(Set<String> values) {
        return values.stream().sorted().map(value -> "\"" + escape(value) + "\"")
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static void methodNotAllowed(Response response, Callback callback, String allow) {
        response.getHeaders().put("Allow", allow);
        writeJson(response, callback, 405,
                "{\"error\":\"METHOD_NOT_ALLOWED\",\"message\":\"Allowed methods: " + escape(allow) + "\"}");
    }

    private static void forbidden(Response response, Callback callback, String message) {
        writeJson(response, callback, 403,
                "{\"error\":\"FORBIDDEN\",\"message\":\"" + escape(message) + "\"}");
    }

    private static void redirect(Response response, Callback callback, String location) {
        response.setStatus(303); response.getHeaders().put("Location", location); callback.succeeded();
    }

    private record UserSession(PrivonymUser user, Instant expiresAt) { }


    private static void appendSummary(StringBuilder json, JobSummary summary) {
        json.append("{\"id\":").append(summary.id())
                .append(",\"reference\":\"").append(escape(summary.reference()))
                .append("\",\"status\":\"").append(escape(summary.status()))
                .append("\",\"plannedAt\":\"").append(escape(summary.plannedAt()))
                .append("\",\"startedAt\":\"").append(escape(summary.startedAt()))
                .append("\",\"rowsProcessed\":").append(summary.rowsProcessed())
                .append(",\"expectedRows\":").append(summary.expectedRows())
                .append(",\"rulesetId\":").append(summary.rulesetId())
                .append(",\"rulesetName\":\"").append(escape(summary.rulesetName())).append('"')
                .append(",\"progressPercent\":").append(summary.progressPercent())
                .append(",\"parallelDegree\":").append(summary.parallelDegree())
                .append(",\"executionMode\":\"").append(escape(summary.executionMode())).append('"')
                .append(",\"chunkSize\":").append(summary.chunkSize())
                .append(",\"chunkThreshold\":").append(summary.chunkThreshold())
                .append(",\"completedChunks\":").append(summary.completedChunks())
                .append(",\"totalChunks\":").append(summary.totalChunks())
                .append(",\"preScriptFileName\":\"").append(escape(summary.preScriptFileName())).append('"')
                .append(",\"postScriptFileName\":\"").append(escape(summary.postScriptFileName())).append('"')
                .append(",\"reportAvailable\":").append(reportAvailable(summary.id())).append('}');
    }

    private static void deleteJobReports(long jobId) {
        Path directory=Path.of(System.getProperty("privonym.report.directory","reports"))
                .toAbsolutePath().normalize();
        if(!Files.isDirectory(directory))return;
        String prefix="privonym-job-"+jobId+"-";
        try(var files=Files.list(directory)){
            for(Path path:files.filter(item->item.getFileName().toString().startsWith(prefix)
                    && item.getFileName().toString().endsWith(".pdf")).toList()){
                Files.deleteIfExists(path);
            }
        }catch(IOException error){System.err.println("Unable to remove Job "+jobId+" reports: "+error.getMessage());}
    }

    private static boolean reportAvailable(long jobId) {
        Path directory=Path.of(System.getProperty("privonym.report.directory","reports"))
                .toAbsolutePath().normalize();
        if(!Files.isDirectory(directory))return false;
        String prefix="privonym-job-"+jobId+"-";
        try(var files=Files.list(directory)){
            return files.anyMatch(path->path.getFileName().toString().startsWith(prefix)
                    && path.getFileName().toString().endsWith(".pdf"));
        }catch(IOException error){return false;}
    }

    private static String escape(String value) {
        if (value == null) return "";
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static boolean requireGet(Request request, Response response, Callback callback) {
        if ("GET".equals(request.getMethod())) {
            return true;
        }
        response.getHeaders().put("Allow", "GET");
        writeJson(response, callback, 405,
                "{\"error\":\"METHOD_NOT_ALLOWED\",\"message\":\"Only GET is allowed\"}");
        return false;
    }

    private static void writeHtml(Response response, Callback callback, String body) {
        response.setStatus(200);
        response.getHeaders().put("Content-Type", "text/html; charset=utf-8");
        response.getHeaders().put("Cache-Control", "no-store");
        response.getHeaders().put("X-Content-Type-Options", "nosniff");
        response.getHeaders().put("Content-Security-Policy",
                "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; "
                        + "connect-src 'self'; base-uri 'none'; frame-ancestors 'none'");
        Content.Sink.write(response, true, body, callback);
    }

    private static void writeResource(Response response, Callback callback,
            String resourceName, String contentType) {
        try (InputStream input = PrivonymHttpHandler.class.getResourceAsStream(resourceName)) {
            if (input == null) {
                writeJson(response, callback, 404,
                        "{\"error\":\"NOT_FOUND\",\"message\":\"Resource not found\"}");
                return;
            }
            byte[] bytes = input.readAllBytes();
            response.setStatus(200);
            response.getHeaders().put("Content-Type", contentType);
            response.getHeaders().put("Cache-Control", "public, max-age=86400");
            response.getHeaders().put("X-Content-Type-Options", "nosniff");
            response.write(true, ByteBuffer.wrap(bytes), callback);
        } catch (IOException error) {
            writeJson(response, callback, 500,
                    "{\"error\":\"RESOURCE_ERROR\",\"message\":\"Unable to load resource\"}");
        }
    }

    private static void writeJson(Response response, Callback callback, int status, String body) {
        response.setStatus(status);
        response.getHeaders().put("Content-Type", "application/json; charset=utf-8");
        response.getHeaders().put("Cache-Control", "no-store");
        response.getHeaders().put("X-Content-Type-Options", "nosniff");
        Content.Sink.write(response, true, body, callback);
    }

    private static void writeCsv(Response response, Callback callback,
            String filename, String body) {
        response.setStatus(200);
        response.getHeaders().put("Content-Type", "text/csv; charset=utf-8");
        response.getHeaders().put("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        response.getHeaders().put("Cache-Control", "no-store");
        response.getHeaders().put("X-Content-Type-Options", "nosniff");
        Content.Sink.write(response, true, "\uFEFF" + body, callback);
    }

    private static void writeSql(Response response, Callback callback,
            String filename, String body) {
        response.setStatus(200);
        response.getHeaders().put("Content-Type", "application/sql; charset=utf-8");
        response.getHeaders().put("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        response.getHeaders().put("Cache-Control", "no-store");
        response.getHeaders().put("X-Content-Type-Options", "nosniff");
        Content.Sink.write(response, true, body, callback);
    }
}

// ============ State ============
let currentEnv = 'prod';
let currentConfig = null; // { browser, device, admin, server, raw }
let devicesTimer = null;

// ============ API ============
const api = {
    async login(user, password) {
        const r = await fetch('/api/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ user, password }),
        });
        if (!r.ok) throw new Error('Anmeldung fehlgeschlagen');
        return r.json();
    },
    async logout() {
        await fetch('/api/logout', { method: 'POST', credentials: 'include' });
    },
    async me() {
        const r = await fetch('/api/me', { credentials: 'include' });
        if (!r.ok) return null;
        return r.json();
    },
    async getConfig(env) {
        const r = await fetch(`/api/config/${env}`, { credentials: 'include' });
        if (!r.ok) throw new Error('Konnte Config nicht laden');
        return r.json();
    },
    async putConfig(env, xml) {
        const r = await fetch(`/api/config/${env}`, {
            method: 'PUT',
            credentials: 'include',
            headers: { 'Content-Type': 'application/xml' },
            body: xml,
        });
        if (!r.ok) throw new Error(`Speichern fehlgeschlagen (${r.status})`);
        return r.json();
    },
    async pushOnly(env) {
        const r = await fetch(`/api/config/${env}/push`, {
            method: 'POST',
            credentials: 'include',
        });
        return r.json();
    },
    async hashPin(pin) {
        const r = await fetch(`/api/hash-pin`, {
            method: 'POST',
            credentials: 'include',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ pin }),
        });
        return r.json();
    },
    async devices() {
        const r = await fetch(`/api/devices`, { credentials: 'include' });
        if (!r.ok) return { devices: [] };
        return r.json();
    },
    async envs() {
        const r = await fetch(`/api/envs`, { credentials: 'include' });
        if (!r.ok) return { stats: { test: 0, prod: 0 } };
        return r.json();
    },
};

// ============ XML <-> Object ============
function parseXml(xml) {
    const doc = new DOMParser().parseFromString(xml || '<config/>', 'text/xml');
    const cfg = doc.querySelector('config');
    const text = (sel) => cfg?.querySelector(sel)?.textContent?.trim() || '';
    const bookmarks = Array.from(cfg?.querySelectorAll('bookmarks bookmark') || []).map(b => ({
        name: b.getAttribute('name') || '',
        url: b.getAttribute('url') || '',
    }));
    return {
        version: cfg?.getAttribute('version') || '1',
        environment: cfg?.getAttribute('environment') || currentEnv,
        browser: {
            startUrl: text('browser startUrl'),
            bookmarks,
            clearCacheOnExit: text('browser clearCacheOnExit').toLowerCase() === 'true',
            javaScriptEnabled: text('browser javaScriptEnabled').toLowerCase() !== 'false',
            oskMode: (text('browser oskMode') || 'auto').toLowerCase(),
            oskToggleVisible: text('browser oskToggleVisible').toLowerCase() !== 'false',
        },
        device: {
            orientation: text('device orientation') || 'auto',
            displayTimeout: parseInt(text('device displayTimeout') || '0', 10),
        },
        admin: {
            pinHash: text('admin pinHash'),
        },
        server: {
            configUrl: text('server configUrl'),
            pollIntervalSec: parseInt(text('server pollIntervalSec') || '900', 10),
        },
    };
}

function buildXml(cfg) {
    const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    const bookmarks = cfg.browser.bookmarks.filter(b => b.name && b.url).map(b =>
        `        <bookmark name="${esc(b.name)}" url="${esc(b.url)}" />`
    ).join('\n');
    return `<?xml version="1.0" encoding="UTF-8"?>
<config version="${cfg.version || '1'}" environment="${cfg.environment}">
    <browser>
        <startUrl>${esc(cfg.browser.startUrl)}</startUrl>
        <bookmarks>
${bookmarks}
        </bookmarks>
        <clearCacheOnExit>${cfg.browser.clearCacheOnExit ? 'true' : 'false'}</clearCacheOnExit>
        <javaScriptEnabled>${cfg.browser.javaScriptEnabled ? 'true' : 'false'}</javaScriptEnabled>
        <oskMode>${esc(cfg.browser.oskMode || 'auto')}</oskMode>
        <oskToggleVisible>${cfg.browser.oskToggleVisible !== false ? 'true' : 'false'}</oskToggleVisible>
    </browser>
    <device>
        <orientation>${esc(cfg.device.orientation)}</orientation>
        <displayTimeout>${cfg.device.displayTimeout || 0}</displayTimeout>
    </device>
    <admin>
        <pinHash>${esc(cfg.admin.pinHash)}</pinHash>
    </admin>
    <server>
        <configUrl>${esc(cfg.server.configUrl)}</configUrl>
        <pollIntervalSec>${cfg.server.pollIntervalSec || 900}</pollIntervalSec>
    </server>
</config>
`;
}

// ============ UI ============
function $(sel) { return document.querySelector(sel); }
function $$(sel) { return Array.from(document.querySelectorAll(sel)); }

function toast(msg, type = '') {
    const el = $('#toast');
    el.textContent = msg;
    el.className = `toast ${type}`;
    setTimeout(() => el.classList.add('hidden'), 3500);
    setTimeout(() => el.className = 'toast hidden', 4000);
}

async function loadConfig() {
    const data = await api.getConfig(currentEnv);
    currentConfig = parseXml(data.xml);
    populateForm();
}

function populateForm() {
    if (!currentConfig) return;
    $('#cfg-start-url').value = currentConfig.browser.startUrl;
    $('#cfg-clear-cache').checked = currentConfig.browser.clearCacheOnExit;
    $('#cfg-js-enabled').checked = currentConfig.browser.javaScriptEnabled;
    $('#cfg-osk-mode').value = currentConfig.browser.oskMode || 'auto';
    $('#cfg-osk-toggle').checked = currentConfig.browser.oskToggleVisible !== false;
    $('#cfg-orientation').value = currentConfig.device.orientation;
    $('#cfg-display-timeout').value = currentConfig.device.displayTimeout;
    $('#cfg-pin').value = '';
    $('#cfg-pin-hash').value = currentConfig.admin.pinHash || '(keine PIN gesetzt)';
    $('#raw-xml').value = buildXml(currentConfig);
    renderBookmarks();
}

function renderBookmarks() {
    const list = $('#bookmarks-list');
    list.innerHTML = '';
    currentConfig.browser.bookmarks.forEach((bm, idx) => {
        const div = document.createElement('div');
        div.className = 'bookmark-item';
        div.innerHTML = `
            <input class="name" placeholder="Name" value="${bm.name.replace(/"/g, '&quot;')}" />
            <input class="url" placeholder="https://..." value="${bm.url.replace(/"/g, '&quot;')}" />
            <button class="danger" data-idx="${idx}">Entfernen</button>
        `;
        const [nameInp, urlInp, delBtn] = div.querySelectorAll('input, button');
        nameInp.oninput = (e) => { currentConfig.browser.bookmarks[idx].name = e.target.value; };
        urlInp.oninput = (e) => { currentConfig.browser.bookmarks[idx].url = e.target.value; };
        delBtn.onclick = () => {
            currentConfig.browser.bookmarks.splice(idx, 1);
            renderBookmarks();
        };
        list.appendChild(div);
    });
}

async function saveAndPush() {
    // Merge form back into config
    currentConfig.browser.startUrl = $('#cfg-start-url').value.trim();
    currentConfig.browser.clearCacheOnExit = $('#cfg-clear-cache').checked;
    currentConfig.browser.javaScriptEnabled = $('#cfg-js-enabled').checked;
    currentConfig.browser.oskMode = $('#cfg-osk-mode').value;
    currentConfig.browser.oskToggleVisible = $('#cfg-osk-toggle').checked;
    currentConfig.device.orientation = $('#cfg-orientation').value;
    currentConfig.device.displayTimeout = parseInt($('#cfg-display-timeout').value || '0', 10);

    const xml = buildXml(currentConfig);
    try {
        const res = await api.putConfig(currentEnv, xml);
        toast(`Gespeichert. ${res.delivered || 0} Geräte erhielten Push.`, 'success');
        $('#raw-xml').value = xml;
        $('#active-count').textContent = res.delivered || 0;
    } catch (e) {
        toast(e.message, 'error');
    }
}

async function savePin() {
    const pin = $('#cfg-pin').value.trim();
    if (!pin) {
        if (confirm('PIN-Feld ist leer. PIN entfernen (auf Bypass-Modus 0000 zurücksetzen)?')) {
            currentConfig.admin.pinHash = '';
            await saveCurrentConfig();
        }
        return;
    }
    try {
        const r = await api.hashPin(pin);
        currentConfig.admin.pinHash = r.hash;
        await saveCurrentConfig();
        $('#cfg-pin').value = '';
        $('#cfg-pin-hash').value = r.hash;
        toast('PIN gespeichert und gepusht', 'success');
    } catch (e) {
        toast(e.message, 'error');
    }
}

async function saveCurrentConfig() {
    const xml = buildXml(currentConfig);
    await api.putConfig(currentEnv, xml);
    $('#raw-xml').value = xml;
}

async function saveRawXml() {
    const xml = $('#raw-xml').value;
    try {
        await api.putConfig(currentEnv, xml);
        await loadConfig();
        toast('XML gespeichert und gepusht', 'success');
    } catch (e) {
        toast(e.message, 'error');
    }
}

async function loadDevices() {
    const data = await api.devices();
    const body = $('#devices-body');
    body.innerHTML = '';
    if (!data.devices.length) {
        body.innerHTML = '<tr><td colspan="7" style="text-align:center;color:var(--text-dim);padding:24px;">Noch keine Geräte registriert</td></tr>';
        $('#devices-count').textContent = '0';
        return;
    }
    data.devices.forEach(d => {
        const lastSeen = new Date(d.last_seen).toLocaleString('de-DE');
        const online = d.connected ? 'online' : '';
        const tr = document.createElement('tr');
        tr.innerHTML = `
            <td><span class="status-dot ${online}"></span> ${d.connected ? 'online' : 'offline'}</td>
            <td><code>${d.device_id}</code></td>
            <td>${d.model || '-'}</td>
            <td>${d.app_version || '-'}</td>
            <td>${d.env || '-'}</td>
            <td><code>${d.ip || '-'}</code></td>
            <td>${lastSeen}</td>
        `;
        body.appendChild(tr);
    });
    $('#devices-count').textContent = data.devices.length;
}

async function refreshStats() {
    const data = await api.envs();
    $('#active-count').textContent = data.stats?.[currentEnv] || 0;
}

// ============ Event Wiring ============
function showApp(user) {
    $('#login-screen').classList.add('hidden');
    $('#app').classList.remove('hidden');
    $('#user-label').textContent = user;
    loadConfig();
    loadDevices();
    refreshStats();
    devicesTimer = setInterval(() => { loadDevices(); refreshStats(); }, 5000);
}

function showLogin() {
    $('#app').classList.add('hidden');
    $('#login-screen').classList.remove('hidden');
    if (devicesTimer) { clearInterval(devicesTimer); devicesTimer = null; }
}

$('#login-form').onsubmit = async (e) => {
    e.preventDefault();
    $('#login-error').textContent = '';
    try {
        await api.login($('#login-user').value, $('#login-pass').value);
        const me = await api.me();
        showApp(me.user);
    } catch (err) {
        $('#login-error').textContent = err.message;
    }
};

$('#logout-btn').onclick = async () => {
    await api.logout();
    showLogin();
};

$$('.env-btn').forEach(btn => {
    btn.onclick = () => {
        $$('.env-btn').forEach(b => b.classList.remove('active'));
        btn.classList.add('active');
        currentEnv = btn.dataset.env;
        loadConfig();
        refreshStats();
    };
});

$$('.tab').forEach(tab => {
    tab.onclick = () => {
        $$('.tab').forEach(t => t.classList.remove('active'));
        $$('.tab-content').forEach(c => c.classList.remove('active'));
        tab.classList.add('active');
        $(`.tab-content[data-tab="${tab.dataset.tab}"]`).classList.add('active');
        if (tab.dataset.tab === 'devices') loadDevices();
    };
});

$('#btn-add-bookmark').onclick = () => {
    currentConfig.browser.bookmarks.push({ name: '', url: '' });
    renderBookmarks();
};

$('#btn-push').onclick = saveAndPush;
$('#btn-save-bookmarks').onclick = saveAndPush;
$('#btn-save-device').onclick = saveAndPush;
$('#btn-save-pin').onclick = savePin;
$('#btn-save-raw').onclick = saveRawXml;

// ============ Boot ============
(async () => {
    const me = await api.me();
    if (me?.user) showApp(me.user);
    else showLogin();
})();

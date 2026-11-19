// Phantom AI Integration remote client. The pairing token lives only in the HttpOnly cookie: this script never sees it, and
// same-origin fetch and WebSocket send the cookie by themselves.
(function () {
  'use strict';
  var treeEl = document.getElementById('tree');
  var msgEl = document.getElementById('msg');
  var connEl = document.getElementById('conn');
  var titleEl = document.getElementById('title');
  var current = null; // {tabId, ws}
  var term = null;
  var fit = null;

  function pairAgain() {
    msgEl.hidden = false;
    msgEl.textContent = 'Pair again from Phantom: Settings | Tools | Phantom AI Integration | Remote Control.';
    connEl.textContent = 'not paired';
  }

  function note(text) {
    msgEl.hidden = !text;
    msgEl.textContent = text || '';
  }

  function api(path, body) {
    var opts = body === undefined ? { credentials: 'same-origin' } :
      { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) };
    return fetch(path, opts).then(function (r) {
      if (r.status === 401) { pairAgain(); throw new Error('401'); }
      return r.json().then(function (j) { return { ok: r.ok, json: j }; });
    });
  }

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined && text !== null) e.textContent = text;
    return e;
  }

  function render(state) {
    treeEl.textContent = '';
    state.projects.forEach(function (p) {
      treeEl.appendChild(el('div', 'project', p.name));
      p.repos.forEach(function (r) {
        if (p.repos.length > 1) treeEl.appendChild(el('div', 'repo', r.name));
        r.worktrees.forEach(function (w) {
          var box = el('div', 'wt');
          var head = el('div', 'name');
          var label = el('div');
          label.appendChild(el('div', null, w.title));
          if (w.branch) label.appendChild(el('div', 'branch', w.branch));
          head.appendChild(label);
          var btns = el('div');
          ['claude', 'codex'].forEach(function (kind) {
            var b = el('button', null, '+ ' + (kind === 'claude' ? 'Claude' : 'Codex'));
            b.type = 'button';
            b.onclick = function () { launch(w.path, kind); };
            btns.appendChild(b);
          });
          head.appendChild(btns);
          box.appendChild(head);
          w.sessions.forEach(function (s) {
            var row = el('div', 'sess' + (current && current.tabId === s.tabId ? ' sel' : ''));
            row.appendChild(el('span', 'dot ' + s.status));
            row.appendChild(el('span', 't', s.title));
            row.appendChild(el('span', 'chip', s.kind));
            row.onclick = function () { s.live ? open(s.tabId, s.title) : resume(s.tabId, s.title); };
            box.appendChild(row);
          });
          treeEl.appendChild(box);
        });
      });
    });
  }

  function poll() {
    api('/api/state').then(function (r) {
      connEl.textContent = 'connected';
      if (r.ok) { msgEl.hidden = true; render(r.json); }
    }).catch(function (e) {
      if (String(e.message) !== '401') connEl.textContent = 'offline';
    });
  }

  function launch(worktree, kind) {
    api('/api/launch', { worktree: worktree, kind: kind }).then(function (r) {
      if (r.ok && r.json.tabId) open(r.json.tabId, kind); else note('Could not start the agent.');
      poll();
    }).catch(function () {});
  }

  function resume(tabId, title) {
    api('/api/resume', { tabId: tabId }).then(function (r) {
      if (r.ok && r.json.tabId) open(r.json.tabId, title); else note('This session cannot be resumed.');
      poll();
    }).catch(function () {});
  }

  function ensureTerm() {
    if (term) return;
    term = new Terminal({ convertEol: false, cursorBlink: true, fontSize: 13, scrollback: 0 });
    fit = new FitAddon.FitAddon();
    term.loadAddon(fit);
    term.open(document.getElementById('term'));
    term.onData(function (d) {
      if (current && current.ws && current.ws.readyState === 1) current.ws.send(JSON.stringify({ t: 'input', data: d }));
    });
    window.addEventListener('resize', function () { fit.fit(); });
  }

  function open(tabId, title) {
    ensureTerm();
    if (current && current.ws) { current.ws.onclose = null; current.ws.close(); }
    titleEl.textContent = title || tabId;
    term.reset();
    var scheme = location.protocol === 'https:' ? 'wss://' : 'ws://';
    var ws = new WebSocket(scheme + location.host + '/ws?tab=' + encodeURIComponent(tabId));
    current = { tabId: tabId, ws: ws };
    ws.onopen = function () { ws.send(JSON.stringify({ t: 'auth', token: '' })); fit.fit(); };
    ws.onmessage = function (ev) {
      var m;
      try { m = JSON.parse(ev.data); } catch (e) { return; }
      if (m.t === 'screen') term.write(m.data);
      else if (m.t === 'closed') titleEl.textContent = (title || tabId) + ' (closed)';
    };
    ws.onclose = function (ev) {
      if (ev.code === 4401) pairAgain();
    };
  }

  document.getElementById('keys').addEventListener('click', function (ev) {
    var k = ev.target && ev.target.getAttribute && ev.target.getAttribute('data-k');
    if (k && current && current.ws && current.ws.readyState === 1) current.ws.send(JSON.stringify({ t: 'input', data: k }));
  });

  poll();
  setInterval(poll, 2000);
})();

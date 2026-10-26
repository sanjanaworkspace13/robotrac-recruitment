/* HR: authorise candidate emails for a drive (single, pasted list or CSV file) and revoke before they start. */
(function () {
  'use strict';
  var el = UI.el;
  var state = { driveId: null, page: 0, q: '' };

  document.addEventListener('DOMContentLoaded', async function () {
    try { await Admin.init(); } catch (e) { return; }
    var sel = document.getElementById('driveSel');
    var notice = document.getElementById('notice');
    try {
      var drives = await Admin.fillDriveSelect(sel, false);
      if (!drives.length) {
        UI.showNotice(notice, 'info', 'Create a drive first on the Drives page.');
        return;
      }
    } catch (e) { Admin.handleError(e, notice); return; }
    state.driveId = sel.value;
    sel.addEventListener('change', function () {
      state.driveId = sel.value; state.page = 0;
      history.replaceState(null, '', '?driveId=' + state.driveId);
      load();
    });
    document.getElementById('addForm').addEventListener('submit', addOne);
    document.getElementById('bulkBtn').addEventListener('click', addBulk);
    document.getElementById('csvBtn').addEventListener('click', function () { document.getElementById('csvFile').click(); });
    document.getElementById('csvFile').addEventListener('change', uploadCsv);
    document.getElementById('search').addEventListener('input', UI.debounce(function (e) {
      state.q = e.target.value.trim(); state.page = 0; load();
    }, 300));
    load();
  });

  async function load() {
    var tbody = document.getElementById('rows');
    try {
      var params = '?page=' + state.page + '&size=25' + (state.q ? '&q=' + encodeURIComponent(state.q) : '');
      var res = await Api.get('/api/admin/drives/' + state.driveId + '/candidates' + params);
      UI.clear(tbody);
      if (!res.items.length) {
        tbody.appendChild(el('tr', {}, [el('td', { colspan: '6', class: 'empty', text: state.q ? 'No candidate matches the search.' : 'No candidates authorised for this drive yet.' })]));
      }
      res.items.forEach(function (c) {
        var actions = el('div', { class: 'row-actions' });
        if (c.status === 'AUTHORIZED' && c.attemptStatus === 'NOT_STARTED') {
          var b = el('button', { type: 'button', class: 'btn btn-danger btn-sm', text: 'Revoke' });
          b.addEventListener('click', function () { revoke(c); });
          actions.appendChild(b);
        } else if (c.status === 'REVOKED') {
          var r = el('button', { type: 'button', class: 'btn btn-secondary btn-sm', text: 'Re-authorise' });
          r.addEventListener('click', function () { reauthorise(c); });
          actions.appendChild(r);
        }
        if (c.attemptId) {
          actions.appendChild(el('a', { class: 'btn btn-ghost btn-sm', href: 'results-dashboard.html?driveId=' + state.driveId + '&q=' + encodeURIComponent(c.email), text: 'Result' }));
        }
        tbody.appendChild(el('tr', {}, [
          el('td', { class: 'mono small', text: c.email }),
          el('td', { text: c.fullName || '—' }),
          el('td', {}, [UI.badge(c.status === 'AUTHORIZED' ? 'Authorised' : 'Revoked', c.status === 'AUTHORIZED' ? 'green' : 'red')]),
          el('td', {}, [Admin.attemptBadge(c.attemptStatus)]),
          el('td', { class: 'small', text: UI.formatDateTime(c.addedAt) }),
          el('td', {}, [actions])
        ]));
      });
      UI.renderPager(document.getElementById('pager'), res.page, res.totalPages, res.totalItems, function (p) { state.page = p; load(); });
    } catch (e) { Admin.handleError(e, document.getElementById('notice')); }
  }

  async function addOne(ev) {
    ev.preventDefault();
    var notice = document.getElementById('notice');
    var email = document.getElementById('addEmail');
    var name = document.getElementById('addName');
    if (!email.value.trim()) { UI.showNotice(notice, 'error', 'Enter an email address.'); email.focus(); return; }
    var btn = document.getElementById('addBtn');
    UI.setBusy(btn, true, 'Adding…');
    try {
      var c = await Api.post('/api/admin/drives/' + state.driveId + '/candidates', { email: email.value.trim(), fullName: name.value.trim() || null });
      UI.showNotice(notice, 'success', c.email + ' is authorised for this drive.');
      email.value = ''; name.value = '';
      email.focus();
      load();
    } catch (e) { Admin.handleError(e, notice); }
    finally { UI.setBusy(btn, false); }
  }

  function reportNotice(report) {
    var lines = report.invalid.map(function (x) { return 'Line ' + x.line + ': "' + x.value + '" — ' + x.reason; });
    var msg = report.added + ' added, ' + report.reactivated + ' re-authorised, ' + report.alreadyAuthorized + ' already on the list' +
      (report.invalid.length ? ', ' + report.invalid.length + ' line(s) skipped:' : '.');
    UI.showNotice(document.getElementById('bulkNotice'), report.invalid.length ? 'warning' : 'success', msg, lines);
  }

  async function addBulk() {
    var text = document.getElementById('bulkText').value;
    if (!text.trim()) { UI.showNotice(document.getElementById('bulkNotice'), 'error', 'Paste at least one email.'); return; }
    var btn = document.getElementById('bulkBtn');
    UI.setBusy(btn, true, 'Adding…');
    try {
      var report = await Api.post('/api/admin/drives/' + state.driveId + '/candidates/bulk', { text: text });
      reportNotice(report);
      if (!report.invalid.length) document.getElementById('bulkText').value = '';
      load();
    } catch (e) { Admin.handleError(e, document.getElementById('bulkNotice')); }
    finally { UI.setBusy(btn, false); }
  }

  async function uploadCsv(ev) {
    var file = ev.target.files[0];
    ev.target.value = '';
    if (!file) return;
    if (file.size > 1024 * 1024) { UI.showNotice(document.getElementById('bulkNotice'), 'error', 'The CSV file must be smaller than 1 MB.'); return; }
    var fd = new FormData();
    fd.append('file', file);
    var btn = document.getElementById('csvBtn');
    UI.setBusy(btn, true, 'Uploading…');
    try {
      reportNotice(await Api.upload('/api/admin/drives/' + state.driveId + '/candidates/import', fd));
      load();
    } catch (e) { Admin.handleError(e, document.getElementById('bulkNotice')); }
    finally { UI.setBusy(btn, false); }
  }

  async function revoke(c) {
    var ok = await UI.confirmDialog({
      title: 'Revoke ' + c.email + '?',
      message: 'This candidate will no longer be able to log in to this drive. The change is recorded in the audit log and can be undone with Re-authorise.',
      confirmLabel: 'Revoke', danger: true
    });
    if (!ok) return;
    try {
      await Api.del('/api/admin/drives/' + state.driveId + '/candidates/' + c.authorizationId);
      UI.toast(c.email + ' revoked.');
      load();
    } catch (e) { Admin.handleError(e, document.getElementById('notice')); }
  }

  async function reauthorise(c) {
    try {
      await Api.post('/api/admin/drives/' + state.driveId + '/candidates', { email: c.email, fullName: c.fullName });
      UI.toast(c.email + ' re-authorised.');
      load();
    } catch (e) { Admin.handleError(e, document.getElementById('notice')); }
  }
})();

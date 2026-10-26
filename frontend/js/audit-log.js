(function () {
  'use strict';
  var el = UI.el;
  var state = { page: 0 };

  document.addEventListener('DOMContentLoaded', async function () {
    try { await Admin.init(); } catch (e) { return; }
    document.getElementById('actionSel').addEventListener('change', function () { state.page = 0; load(); });
    document.getElementById('search').addEventListener('input', UI.debounce(function () { state.page = 0; load(); }, 300));
    load();
  });

  async function load() {
    var p = new URLSearchParams();
    var action = document.getElementById('actionSel').value;
    var q = document.getElementById('search').value.trim();
    if (action) p.set('action', action);
    if (q) p.set('q', q);
    p.set('page', state.page);
    p.set('size', 50);
    var tbody = document.getElementById('rows');
    try {
      var res = await Api.get('/api/admin/audit?' + p.toString());
      UI.clear(tbody);
      if (!res.items.length) tbody.appendChild(el('tr', {}, [el('td', { colspan: '6', class: 'empty', text: 'No entries.' })]));
      res.items.forEach(function (a) {
        var failed = /FAILED|REFUSED/.test(a.action);
        tbody.appendChild(el('tr', {}, [
          el('td', { class: 'small', text: UI.formatDateTime(a.createdAt) + ' ' + UI.formatTime(a.createdAt) }),
          el('td', { class: 'small' }, [UI.badge(a.actorType, a.actorType === 'ADMIN' ? 'green' : (a.actorType === 'CANDIDATE' ? 'blue' : '')), ' ', a.actorName || '']),
          el('td', {}, [UI.badge(a.action, failed ? 'red' : '')]),
          el('td', { class: 'small mono', text: (a.entityType || '') + (a.entityId ? ' #' + a.entityId : '') }),
          el('td', { class: 'small', text: a.details || '' }),
          el('td', { class: 'small mono', text: a.ipAddress || '' })
        ]));
      });
      UI.renderPager(document.getElementById('pager'), res.page, res.totalPages, res.totalItems, function (pg) { state.page = pg; load(); });
    } catch (e) { Admin.handleError(e, document.getElementById('notice')); }
  }
})();

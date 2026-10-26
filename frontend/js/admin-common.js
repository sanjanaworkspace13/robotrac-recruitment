/* Shared HR console behaviour: session guard, navigation state, logout and error handling. */
(function () {
  'use strict';

  async function init() {
    var me;
    try {
      me = await Api.me();
    } catch (e) {
      document.body.prepend(UI.el('div', { class: 'notice error', role: 'alert', text: e.message }));
      throw e;
    }
    if (!me || !me.authenticated || me.role !== 'ADMIN') {
      window.location.replace('admin-login.html');
      throw new Error('redirecting');
    }
    var name = document.getElementById('adminName');
    if (name) name.textContent = me.displayName || '';
    var page = window.location.pathname.split('/').pop() || 'admin-dashboard.html';
    document.querySelectorAll('.admin-nav a').forEach(function (a) {
      if (a.getAttribute('href') === page) a.setAttribute('aria-current', 'page');
    });
    var logout = document.getElementById('adminLogout');
    if (logout) {
      logout.addEventListener('click', async function () {
        try { await Api.logout(); } catch (e) { /* ignore */ }
        window.location.replace('admin-login.html');
      });
    }
    return me;
  }

  /** Shows an API error; redirects to the login page when the session has expired. */
  function handleError(e, noticeEl) {
    if (e && e.status === 401) {
      window.location.replace('admin-login.html');
      return;
    }
    var details = Array.isArray(e && e.details) ? e.details.map(function (d) {
      return typeof d === 'string' ? d : (d.field ? d.field + ': ' + d.message : JSON.stringify(d));
    }) : null;
    var message = (e && e.message) || 'Something went wrong.';
    if (details && details.length > 1) {
      message = 'Please fix the following:'; // the list below says it item by item
    } else {
      details = null;                       // a single detail is the same as the message
    }
    if (noticeEl) UI.showNotice(noticeEl, 'error', message, details);
    else UI.toast((e && e.message) || 'Something went wrong.', 'error');
  }

  function phaseBadge(phase) {
    var colors = { DRAFT: '', SCHEDULED: 'blue', LIVE: 'green', ENDED: 'amber', CLOSED: 'red' };
    var labels = { DRAFT: 'Draft', SCHEDULED: 'Scheduled', LIVE: 'Live', ENDED: 'Window ended', CLOSED: 'Closed' };
    return UI.badge(labels[phase] || phase, colors[phase]);
  }

  function attemptBadge(status) {
    var map = {
      NOT_STARTED: ['Not started', ''], IN_PROGRESS: ['In progress', 'blue'],
      SUBMITTED: ['Submitted', 'green'], TIMED_OUT: ['Timed out', 'amber']
    };
    var m = map[status] || [status, ''];
    return UI.badge(m[0], m[1]);
  }

  async function loadDrives() {
    return Api.get('/api/admin/drives');
  }

  /** Fills a <select> with drives; keeps ?driveId= from the URL selected. */
  async function fillDriveSelect(select, includeAll) {
    var drives = await loadDrives();
    UI.clear(select);
    if (includeAll) select.appendChild(UI.el('option', { value: '', text: 'All drives' }));
    drives.forEach(function (d) {
      select.appendChild(UI.el('option', { value: String(d.id), text: d.code + ' — ' + d.name }));
    });
    var wanted = new URLSearchParams(window.location.search).get('driveId');
    if (wanted) select.value = wanted;
    return drives;
  }

  window.Admin = {
    init: init, handleError: handleError, phaseBadge: phaseBadge, attemptBadge: attemptBadge,
    loadDrives: loadDrives, fillDriveSelect: fillDriveSelect
  };
})();

(function () {
  'use strict';
  var el = UI.el;

  document.addEventListener('DOMContentLoaded', async function () {
    try { await Admin.init(); } catch (e) { return; }
    document.getElementById('createForm').addEventListener('submit', createUser);
    document.getElementById('pwForm').addEventListener('submit', changePassword);
    load();
  });

  async function load() {
    try {
      var users = await Api.get('/api/admin/users');
      var tbody = UI.clear(document.getElementById('rows'));
      users.forEach(function (u) {
        tbody.appendChild(el('tr', {}, [
          el('td', { class: 'mono small', text: u.username }), el('td', { text: u.fullName }),
          el('td', { class: 'small', text: UI.formatDateTime(u.createdAt) }), el('td', { class: 'small', text: UI.formatDateTime(u.lastLoginAt) })
        ]));
      });
    } catch (e) { Admin.handleError(e, document.getElementById('notice')); }
  }

  async function createUser(ev) {
    ev.preventDefault();
    var notice = document.getElementById('createNotice');
    var btn = document.getElementById('createBtn');
    UI.setBusy(btn, true, 'Creating…');
    try {
      var u = await Api.post('/api/admin/users', {
        username: document.getElementById('c-user').value.trim(),
        fullName: document.getElementById('c-name').value.trim(),
        password: document.getElementById('c-pass').value
      });
      UI.showNotice(notice, 'success', 'Account ' + u.username + ' created. Share the initial password privately and ask them to change it.');
      document.getElementById('createForm').reset();
      load();
    } catch (e) { Admin.handleError(e, notice); }
    finally { UI.setBusy(btn, false); }
  }

  async function changePassword(ev) {
    ev.preventDefault();
    var notice = document.getElementById('pwNotice');
    var next = document.getElementById('p-new').value;
    if (next !== document.getElementById('p-new2').value) { UI.showNotice(notice, 'error', 'The new passwords do not match.'); return; }
    var btn = document.getElementById('pwBtn');
    UI.setBusy(btn, true, 'Saving…');
    try {
      await Api.post('/api/admin/users/me/password', { currentPassword: document.getElementById('p-cur').value, newPassword: next });
      UI.showNotice(notice, 'success', 'Password changed.');
      document.getElementById('pwForm').reset();
    } catch (e) { Admin.handleError(e, notice); }
    finally { UI.setBusy(btn, false); }
  }
})();

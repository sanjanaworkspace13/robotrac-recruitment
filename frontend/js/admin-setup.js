(function () {
  'use strict';
  document.addEventListener('DOMContentLoaded', async function () {
    var form = document.getElementById('setupForm');
    var status = document.getElementById('statusNotice');
    var notice = document.getElementById('notice');
    var submit = document.getElementById('submitBtn');

    try {
      var s = await Api.get('/api/setup/status');
      if (!s.setupRequired) {
        UI.showNotice(status, 'info', 'Setup is already complete. Please use the HR login page.');
        return;
      }
    } catch (e) {
      UI.showNotice(status, 'error', e.message);
      return;
    }
    UI.showNotice(status, 'info', 'Look at the window where the backend is running: it prints a one-time setup token in a box titled "ROBOTRAC FIRST-TIME SETUP".');
    form.classList.remove('hidden');

    form.addEventListener('submit', async function (ev) {
      ev.preventDefault();
      UI.hideNotice(notice);
      var pw = document.getElementById('password').value;
      if (pw !== document.getElementById('password2').value) {
        UI.showNotice(notice, 'error', 'The two passwords do not match.');
        return;
      }
      UI.setBusy(submit, true, 'Creating…');
      try {
        await Api.post('/api/setup/admin', {
          setupToken: document.getElementById('token').value.trim(),
          username: document.getElementById('username').value.trim(),
          fullName: document.getElementById('fullName').value.trim(),
          password: pw
        });
        form.classList.add('hidden');
        UI.showNotice(status, 'success', 'Administrator created. You can now log in. The setup page is now permanently disabled.');
        status.appendChild(UI.el('p', {}, [UI.el('a', { href: 'admin-login.html', text: 'Go to HR login →' })]));
      } catch (e) {
        UI.setBusy(submit, false);
        UI.showNotice(notice, 'error', e.message, Array.isArray(e.details) ? e.details.filter(function (d) { return typeof d === 'string'; }) : null);
      }
    });
  });
})();

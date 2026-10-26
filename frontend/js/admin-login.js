(function () {
  'use strict';
  document.addEventListener('DOMContentLoaded', function () {
    var form = document.getElementById('loginForm');
    var username = document.getElementById('username');
    var password = document.getElementById('password');
    var toggle = document.getElementById('toggle');
    var submit = document.getElementById('submitBtn');
    var notice = document.getElementById('notice');

    Api.me().then(function (me) {
      if (me && me.authenticated && me.role === 'ADMIN') window.location.replace('admin-dashboard.html');
    }).catch(function () {});
    Api.get('/api/setup/status').then(function (s) {
      if (s.setupRequired) {
        document.getElementById('setupLink').classList.remove('hidden');
        UI.showNotice(notice, 'info', 'No HR account exists yet. Use "First-time setup" below with the token printed in the server console.');
      }
    }).catch(function () {});

    toggle.addEventListener('click', function () {
      var hidden = password.type === 'password';
      password.type = hidden ? 'text' : 'password';
      toggle.textContent = hidden ? 'HIDE' : 'SHOW';
      toggle.setAttribute('aria-pressed', String(hidden));
      toggle.setAttribute('aria-label', hidden ? 'Hide password' : 'Show password');
    });

    form.addEventListener('submit', async function (ev) {
      ev.preventDefault();
      UI.hideNotice(notice);
      if (!username.value.trim() || !password.value) {
        UI.showNotice(notice, 'error', 'Please enter your username and password.');
        return;
      }
      UI.setBusy(submit, true, 'Signing in…');
      try {
        await Api.post('/api/auth/admin/login', { username: username.value.trim(), password: password.value });
        password.value = '';
        Api.resetCsrf();
        window.location.replace('admin-dashboard.html');
      } catch (e) {
        UI.setBusy(submit, false);
        UI.showNotice(notice, 'error', e.message);
      }
    });
  });
})();

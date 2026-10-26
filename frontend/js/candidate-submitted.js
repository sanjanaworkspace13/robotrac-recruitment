/* Neutral confirmation page: no score or outcome is ever requested or shown here. */
(function () {
  'use strict';
  document.addEventListener('DOMContentLoaded', function () {
    var btn = document.getElementById('logoutBtn');
    btn.addEventListener('click', async function () {
      UI.setBusy(btn, true, 'Logging out…');
      try { await Api.logout(); } catch (e) { /* already logged out */ }
      window.location.replace('candidate-login.html');
    });
  });
})();

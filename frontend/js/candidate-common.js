/* Helpers shared by the candidate pages (after login). */
(function () {
  'use strict';

  /** Ensures a candidate session exists; otherwise sends the user to the login page. */
  async function requireCandidate() {
    var me;
    try {
      me = await Api.me();
    } catch (e) {
      throw e;
    }
    if (!me || !me.authenticated || me.role !== 'CANDIDATE') {
      window.location.replace('candidate-login.html');
      return null;
    }
    var drive = document.getElementById('hdrDrive');
    var email = document.getElementById('hdrEmail');
    if (drive) drive.textContent = me.driveName || me.driveCode || '';
    if (email) email.textContent = me.email || '';
    return me;
  }

  function wireLogout(confirmFirst) {
    var btn = document.getElementById('logoutBtn');
    if (!btn) return;
    btn.addEventListener('click', async function () {
      if (confirmFirst) {
        var ok = await UI.confirmDialog({
          title: 'Log out?',
          message: 'Your attempt continues and the timer keeps running while you are logged out. Log in again with the same details to resume.',
          confirmLabel: 'Log out', danger: true
        });
        if (!ok) return;
      }
      try { await Api.logout(); } catch (e) { /* session may already be gone */ }
      window.location.replace('candidate-login.html');
    });
  }

  window.Candidate = { requireCandidate: requireCandidate, wireLogout: wireLogout };
})();

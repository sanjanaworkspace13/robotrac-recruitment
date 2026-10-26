/*
 * Robotrac frontend configuration - the ONE place that decides where the API lives.
 *
 * Recommended: open the site from the backend itself (http://localhost:8080/...). Then the API is on the
 * same origin and apiBase stays "".
 * If you serve the frontend separately (for example VS Code Live Server on http://localhost:5500), the
 * API is assumed to be http://localhost:8080. Change BACKEND_URL below if your backend runs elsewhere,
 * and add your frontend origin to APP_CORS_ORIGINS on the backend.
 */
(function () {
  'use strict';
  var BACKEND_URL = 'http://localhost:8080';
  var sameOrigin = window.location.port === '8080' || window.location.origin === BACKEND_URL;
  window.ROBOTRAC_CONFIG = Object.freeze({
    apiBase: sameOrigin ? '' : BACKEND_URL,
    // How often the exam page re-synchronises its clock with the server (ms).
    clockSyncMs: 60000
  });
})();

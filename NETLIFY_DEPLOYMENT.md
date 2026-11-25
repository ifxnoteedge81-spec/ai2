# Netlify (static download site only)

One-time: create a Netlify site (no Git connection needed). Add repo secrets `NETLIFY_AUTH_TOKEN` (User settings → Applications → Personal access tokens) and `NETLIFY_SITE_ID` (Site settings → Site ID). These are deployment credentials, not app credentials.

On each successful build the `deploy-site` job copies `site/` plus `downloads/{PersonalAIComputer.apk, .sha256, version.json}` into `_site/`, deploys with the Netlify CLI (`--prod`), downloads the APK back from the deploy URL and fails if its SHA-256 differs.
If the build job fails the deploy job does not run, so the previous working APK stays live. If the secrets are missing the deploy is skipped with a warning.
`site/app.js` reads `downloads/version.json`; if it is absent the page says no build exists and the button stays disabled.
Fill the support/source placeholders in `site/index.html`.

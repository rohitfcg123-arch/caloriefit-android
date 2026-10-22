# CalorieFit Web / PWA

A responsive, installable web tracker intended to share the CalorieFit visual style across desktop and mobile.

## Current features
- Responsive dashboard and mobile bottom navigation
- Daily food log and searchable starter database with common Indian foods
- Calories, protein, carbohydrates and fat totals
- Custom food quick-add
- Daily calorie and macro targets, weight goals and activity-level fields
- Water, steps and exercise tracking
- Weight history and simple trend chart
- 7 / 30 / 90-day reports, CSV export, JSON backup and JSON import
- Recipe ideas and a small nutrition information page
- Local browser persistence and an app-shell service worker for basic offline opening

## Run locally
Serve this folder using any static web server. For example, with Python:
```sh
python -m http.server 8000
```
Then open http://localhost:8000.

## Deploy on GitHub Pages
In repository Settings → Pages, select the `main` branch and `/(root)` as the source, then save. Wait for GitHub Pages to publish the site.

## Important limitations
- This initial version saves data in the current browser/device using localStorage. Data does not automatically sync across devices.
- Firebase Authentication and Cloud Firestore are not enabled because this repository did not contain a Firebase web config or backend setup.
- Food nutrition values are estimates; recipes and brands vary.
- The PWA manifest uses an SVG icon. For broadest Play Store / Android install compatibility, add 192px and 512px PNG icons later.
- A web app cannot reliably read step counts or health data from the phone without platform permissions/integrations. Steps are manually entered in this version.

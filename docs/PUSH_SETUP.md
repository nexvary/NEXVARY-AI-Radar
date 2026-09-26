# Firebase / Push configuration

The Android app does not require `google-services.json` in the repository.

Provide these values as Gradle properties or environment variables when building:

- `FIREBASE_APP_ID`
- `FIREBASE_API_KEY`
- `FIREBASE_PROJECT_ID`
- `FIREBASE_SENDER_ID`
- optional `RADAR_API_BASE`

The server requires `FIREBASE_SERVICE_ACCOUNT_JSON` containing the service-account JSON and publishes to the `ai-radar` topic.

If Firebase values are absent, Android falls back to WorkManager polling and still builds normally.

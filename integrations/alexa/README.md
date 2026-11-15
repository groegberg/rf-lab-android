# RF Lab – Alexa Smart Home Integration (developer adapter)

**Status:** Contract generator and offline tests are implemented; no Alexa account is connected and no real device command is sent from the Android APK.

## Intended architecture

1. RF Lab localizes the phone from fresh Wi-Fi RSSI fingerprints, using room-level probabilities and explicitly UNKNOWN when data are old or ambiguous.
2. After a configurable dwell, the local engine emits a validated, simulation-only virtual room presence transition.
3. A future opt-in secure bridge sends that transition to a customer-authenticated cloud backend; the full room fingerprint library stays local.
4. The Alexa Smart Home add-on exposes one virtual MotionSensor endpoint per configured room and supplies discovery, retrievable state and proactive ChangeReport events.
5. In the customer's Alexa app, an explicitly configured routine may control an existing light. Direct enumeration or control of all devices registered in Alexa is *not* offered by the standard Smart Home Skill API.

## Implemented

- `messageFactory.mjs`: message objects for Discovery, StateReport and ChangeReport with MotionSensor and EndpointHealth capabilities.
- `messageFactory.test.mjs`: tests endpoint IDs, properties, payload values and minimum transition guard.
- Run with `node integrations/alexa/messageFactory.test.mjs`.
- Android v0.4.0: local-only spatial home map, RF fingerprints, guarded automation simulator, event log.

## Not implemented; mandatory before enabling live Alexa operation

- Customer identity / user account management and security model.
- Alexa Developer account and registered Smart Home add-on, including account linking via a proper OAuth 2.0 authorization-code server.
- Handling `Alexa.Authorization.AcceptGrant` and secure exchange/refresh/storage of per-user LWA event-gateway credentials.
- Hosted HTTPS backend with persistent room sensor state, strong device-to-cloud authentication, replay protection, rate limits and revocation.
- Deployment of the cloud Lambda or other skill endpoint and full Alexa directive handling with robust error responses.
- Sending signed/authorized events to the regional Alexa event gateway, handling retries and state reconciliation.
- Confirming the proposed virtual room occupancy sensor meets the Alexa MotionSensor semantics and is accepted for de-DE routines.
- A consent screen, account linking/unlinking and explicit opt-in on Android. The APK currently does not request INTERNET permission.

Do not paste Alexa tokens into the phone UI or commit credentials to this repository.

## Critical semantics and safety

- MotionSensor reports DETECTED / NOT_DETECTED; it is not an arbitrary room-presence API. It may not be suitable to report a phone's Wi-Fi-inferred position without additional review.
- Amazon requires at least 30 seconds before reporting NOT_DETECTED after DETECTED. The adapter's `maySendMotionTransition` enforces this minimal guard.
- Alexa ChangeReport uses a per-customer event-gateway bearer token and must include the updated MotionSensor property and EndpointHealth context.
- Unknown or stale RSSI location must not be translated into immediate NOT_DETECTED: the sensor's availability/health must be modeled separately.
- Events from a foreground Android app may stop when the app is backgrounded; no reliable background scanner exists in this release.
- Wi-Fi scan throttling means many rapid location transitions cannot be discovered reliably without optional external infrastructure.
- Smart plugs should not be controlled automatically unless explicitly approved and safe for the specific appliance.

## Official documentation

- https://developer.amazon.com/docs/alexaplus/device-apis/alexa-motionsensor
- https://developer.amazon.com/docs/alexaplus/smarthome/create-smart-home-addon
- https://developer.amazon.com/docs/alexaplus/smarthome/send-events-to-the-alexa-event-gateway
- https://developer.amazon.com/docs/alexaplus/smarthome/state-reporting-for-smart-home-addons

**Decision gate:** First validate room inference on the Pixel and Alexa's German motion-trigger behavior in the developer sandbox. Deploy only after both are demonstrably reliable.

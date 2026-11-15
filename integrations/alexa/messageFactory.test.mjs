import assert from "node:assert/strict";
import {
  endpointId, discoveryResponse, stateReport, changeReport,
  maySendMotionTransition
} from "./messageFactory.mjs";

const rooms = [
  { id: "livingroom-1", name: "Wohnzimmer" },
  { id: "kitchen-2", name: "Küche" }
];
const d = discoveryResponse(rooms);
assert.equal(d.event.header.name, "Discover.Response");
assert.equal(d.event.payload.endpoints.length, 2);
assert.equal(d.event.payload.endpoints[0].displayCategories[0], "MOTION_SENSOR");
assert.equal(d.event.payload.endpoints[0].capabilities[1].properties.proactivelyReported, true);
assert.equal(d.event.payload.endpoints[0].capabilities[2].interface, "Alexa.EndpointHealth");
assert.throws(() => endpointId("../unsafe"));
const state = stateReport({
  roomId: "livingroom-1", detected: true, connected: true,
  changedAt: "2026-11-15T11:00:00Z", healthAt: "2026-11-15T11:00:00Z",
  correlationToken: "corr", token: "PLACEHOLDER_NOT_A_REAL_TOKEN"
});
assert.equal(state.event.header.name, "StateReport");
assert.equal(state.context.properties[0].value, "DETECTED");
assert.equal(state.event.header.correlationToken, "corr");
const report = changeReport({
  roomId: "livingroom-1", detected: false, connected: true,
  changedAt: "2026-11-15T11:01:00Z", healthAt: "2026-11-15T11:00:00Z",
  token: "PLACEHOLDER_NOT_A_REAL_TOKEN"
});
assert.equal(report.event.payload.change.properties[0].value, "NOT_DETECTED");
assert.equal(report.context.properties[0].namespace, "Alexa.EndpointHealth");
assert.equal(maySendMotionTransition(null, "DETECTED", 100000), true);
assert.equal(maySendMotionTransition({state:"DETECTED",changedAtMs:100000}, "NOT_DETECTED",120000),false);
assert.equal(maySendMotionTransition({state:"DETECTED",changedAtMs:100000}, "NOT_DETECTED",130000),true);
assert.equal(maySendMotionTransition({state:"DETECTED",changedAtMs:100000}, "UNKNOWN",140000),false);
assert.equal(maySendMotionTransition({state:"DETECTED",changedAtMs:100000}, "DETECTED",140000),false);
console.log("PASS: Alexa offline message factory and transition invariants");

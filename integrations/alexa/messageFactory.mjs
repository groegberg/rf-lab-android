import { randomUUID } from "node:crypto";

/**
 * Alexa Smart Home message factory; intentionally transport-independent.
 * Do not send directly from Android. Requires hosted OAuth, verified customer
 * account linking, per-user LWA tokens, secure cloud state and event gateway.
 */
const header = (namespace, name) => ({
  namespace, name, payloadVersion: "3", messageId: randomUUID()
});
const stamp = (at) => new Date(at).toISOString();

export function endpointId(roomId) {
  if (!/^[a-zA-Z0-9_-]{1,80}$/.test(roomId))
    throw new Error("Unsafe or invalid room ID");
  return "rflab-room-" + roomId;
}

export function discoveryResponse(rooms) {
  if (!Array.isArray(rooms)) throw new TypeError("rooms must be an array");
  return {
    event: {
      header: header("Alexa.Discovery", "Discover.Response"),
      payload: {
        endpoints: rooms.map(room => ({
          endpointId: endpointId(room.id),
          manufacturerName: "RF Lab",
          friendlyName: "RF Lab " + String(room.name).slice(0, 80),
          description: "Experimental RF fingerprint room presence sensor",
          displayCategories: ["MOTION_SENSOR"],
          cookie: {},
          capabilities: [
            { type: "AlexaInterface", interface: "Alexa", version: "3" },
            {
              type: "AlexaInterface", interface: "Alexa.MotionSensor", version: "3",
              properties: {
                supported: [{ name: "detectionState" }],
                proactivelyReported: true, retrievable: true
              }
            },
            {
              type: "AlexaInterface", interface: "Alexa.EndpointHealth", version: "3",
              properties: {
                supported: [{ name: "connectivity" }],
                proactivelyReported: true, retrievable: true
              }
            }
          ]
        }))
      }
    }
  };
}

const detectionProperty = (detected, at, uncertaintyMs) => ({
  namespace: "Alexa.MotionSensor", name: "detectionState",
  value: detected ? "DETECTED" : "NOT_DETECTED",
  timeOfSample: stamp(at), uncertaintyInMilliseconds: uncertaintyMs
});
const healthProperty = (connected, at) => ({
  namespace: "Alexa.EndpointHealth", name: "connectivity",
  value: { value: connected ? "OK" : "UNREACHABLE" },
  timeOfSample: stamp(at), uncertaintyInMilliseconds: 0
});

export function stateReport({
  roomId, detected, connected, changedAt, healthAt,
  correlationToken, token, uncertaintyMs = 30000
}) {
  if (typeof token !== "string" || !token) throw new Error("Token required");
  const h = header("Alexa", "StateReport");
  if (correlationToken) h.correlationToken = correlationToken;
  return {
    context: {
      properties: [
        detectionProperty(Boolean(detected), changedAt, uncertaintyMs),
        healthProperty(Boolean(connected), healthAt)
      ]
    },
    event: {
      header: h,
      endpoint: {
        endpointId: endpointId(roomId),
        scope: { type: "BearerToken", token }
      },
      payload: {}
    }
  };
}

export function changeReport({
  roomId, detected, changedAt, healthAt, token,
  connected = true, uncertaintyMs = 30000
}) {
  if (typeof token !== "string" || !token) throw new Error("Token required");
  return {
    context: { properties: [healthProperty(connected, healthAt)] },
    event: {
      header: header("Alexa", "ChangeReport"),
      endpoint: {
        endpointId: endpointId(roomId),
        scope: { type: "BearerToken", token }
      },
      payload: {
        change: {
          cause: { type: "RULE_TRIGGER" },
          properties: [detectionProperty(detected, changedAt, uncertaintyMs)]
        }
      }
    }
  };
}

/**
 * Validates the minimum state transition duration required for virtual
 * MotionSensor reports. NOT_DETECTED must not follow DETECTED in <30 seconds.
 * UNKNOWN must not turn into NOT_DETECTED automatically: use disconnected
 * EndpointHealth if a real backend is implemented later.
 */
export function maySendMotionTransition(previous, next, nowMs) {
  if (!previous) return next === "DETECTED";
  if (next === previous.state) return false;
  if (next === "NOT_DETECTED") {
    return previous.state === "DETECTED" &&
      nowMs - previous.changedAtMs >= 30000;
  }
  return next === "DETECTED";
}

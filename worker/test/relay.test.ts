import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import worker from "../src/index";
import { resetFcmCaches } from "../src/fcm";
import type { Env, RateLimiter } from "../src/types";

// ---------------------------------------------------------------------------
// Doublures : KV en mémoire, limiteur, contexte d'exécution, Google (OAuth + FCM)
// ---------------------------------------------------------------------------

class FakeKV {
  store = new Map<string, { value: string; expiresAt?: number }>();

  async get(key: string, type?: string | { type: string }): Promise<unknown> {
    const entry = this.store.get(key);
    if (!entry) return null;
    if (entry.expiresAt !== undefined && entry.expiresAt <= Date.now()) {
      this.store.delete(key);
      return null;
    }
    const kind = typeof type === "string" ? type : (type?.type ?? "text");
    return kind === "json" ? JSON.parse(entry.value) : entry.value;
  }

  async put(key: string, value: string, options?: { expirationTtl?: number }): Promise<void> {
    if (options?.expirationTtl !== undefined && options.expirationTtl < 60) throw new Error("TTL KV < 60 s");
    const expiresAt = options?.expirationTtl ? Date.now() + options.expirationTtl * 1000 : undefined;
    this.store.set(key, { value, expiresAt });
  }

  async delete(key: string): Promise<void> {
    this.store.delete(key);
  }
}

function fakeLimiter(max: number): RateLimiter {
  const counts = new Map<string, number>();
  return {
    async limit({ key }) {
      const count = (counts.get(key) ?? 0) + 1;
      counts.set(key, count);
      return { success: count <= max };
    },
  };
}

interface FcmCall {
  token: string;
  data: Record<string, string>;
  android: Record<string, unknown>;
  authorization: string;
}

const TOKEN_A = `tokA:APA91b${"a".repeat(140)}`;
const TOKEN_B = `tokB:APA91b${"b".repeat(140)}`;

let keyPair: CryptoKeyPair;
let serviceAccount: string;
let kv: FakeKV;
let env: Env;
let pending: Promise<unknown>[];
let fcmCalls: FcmCall[];
let oauthAssertions: string[];
let fcmResponder: (token: string) => Response;

const ctx = {
  waitUntil: (promise: Promise<unknown>) => pending.push(promise),
  passThroughOnException: () => {},
  props: {},
} as unknown as ExecutionContext;

const b64 = (buffer: ArrayBuffer) => btoa(String.fromCharCode(...new Uint8Array(buffer)));
const b64urlDecode = (value: string) =>
  Uint8Array.from(atob(value.replace(/-/g, "+").replace(/_/g, "/")), (c) => c.charCodeAt(0));

beforeAll(async () => {
  keyPair = (await crypto.subtle.generateKey(
    { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
    true,
    ["sign", "verify"],
  )) as CryptoKeyPair;
  const pkcs8 = b64((await crypto.subtle.exportKey("pkcs8", keyPair.privateKey)) as ArrayBuffer);
  const pem = `-----BEGIN PRIVATE KEY-----\n${pkcs8.match(/.{1,64}/g)!.join("\n")}\n-----END PRIVATE KEY-----\n`;
  serviceAccount = JSON.stringify({
    type: "service_account",
    project_id: "echo-test",
    client_email: "relay@echo-test.iam.gserviceaccount.com",
    private_key: pem,
    token_uri: "https://oauth2.googleapis.com/token",
  });
});

beforeEach(() => {
  resetFcmCaches();
  kv = new FakeKV();
  env = {
    ECHO_KV: kv as unknown as KVNamespace,
    FIREBASE_SERVICE_ACCOUNT: serviceAccount,
    PING_LIMITER: fakeLimiter(100),
    PAIR_LIMITER: fakeLimiter(100),
  };
  pending = [];
  fcmCalls = [];
  oauthAssertions = [];
  fcmResponder = () => Response.json({ name: "projects/echo-test/messages/1" });

  vi.stubGlobal("fetch", async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input instanceof Request ? input.url : input);
    if (url === "https://oauth2.googleapis.com/token") {
      oauthAssertions.push(new URLSearchParams(String(init?.body)).get("assertion")!);
      return Response.json({ access_token: `at-${oauthAssertions.length}`, expires_in: 3599, token_type: "Bearer" });
    }
    if (url === "https://fcm.googleapis.com/v1/projects/echo-test/messages:send") {
      const { message } = JSON.parse(String(init?.body));
      const reserved = Object.keys(message.data ?? {}).filter(
        (k) => k === "from" || k === "message_type" || k.startsWith("google.") || k.startsWith("gcm."),
      );
      if (reserved.length > 0) {
        return Response.json({ error: { code: 400, status: "INVALID_ARGUMENT", message: `Invalid data key ${reserved}` } }, { status: 400 });
      }
      const headers = new Headers(init?.headers);
      fcmCalls.push({
        token: message.token,
        data: message.data,
        android: message.android,
        authorization: headers.get("authorization") ?? "",
      });
      return fcmResponder(message.token);
    }
    throw new Error(`fetch inattendu : ${url}`);
  });
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

interface Auth {
  pairId: string;
  secret: string;
}

async function call(path: string, body?: unknown, auth?: Auth, init: RequestInit = {}) {
  const headers: Record<string, string> = { "content-type": "application/json", "cf-connecting-ip": "203.0.113.7" };
  if (auth) {
    headers.authorization = `Bearer ${auth.secret}`;
    headers["x-pair-id"] = auth.pairId;
  }
  const request = new Request(`https://relay.test${path}`, {
    method: "POST",
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    ...init,
  });
  const response = await worker.fetch(request as never, env, ctx);
  return { status: response.status, body: (await response.json()) as Record<string, any> };
}

async function flush() {
  await Promise.all(pending.splice(0));
}

async function pairPhones(): Promise<{ a: Auth; b: Auth; code: string }> {
  const created = await call("/pair/create", { token: TOKEN_A });
  expect(created.status).toBe(201);
  const joined = await call("/pair/join", { code: created.body.code, token: TOKEN_B });
  expect(joined.status).toBe(200);
  await flush();
  return {
    a: { pairId: created.body.pairId, secret: created.body.secret },
    b: { pairId: joined.body.pairId, secret: joined.body.secret },
    code: created.body.code,
  };
}

// ---------------------------------------------------------------------------

describe("appairage", () => {
  it("crée un code de 6 caractères puis lie les deux téléphones", async () => {
    const created = await call("/pair/create", { token: TOKEN_A });
    expect(created.status).toBe(201);
    expect(created.body.code).toMatch(/^[A-HJ-NP-Z2-9]{6}$/);
    expect(created.body.role).toBe("a");
    expect(created.body.expiresAt - Date.now()).toBeGreaterThan(590_000);

    const a = { pairId: created.body.pairId, secret: created.body.secret };
    expect((await call("/pair/status", {}, a)).body).toMatchObject({ ok: true, paired: false, role: "a" });

    // Saisie tolérante : minuscules et espaces.
    const typed = created.body.code.toLowerCase().replace(/^(...)/, "$1 ");
    const joined = await call("/pair/join", { code: typed, token: TOKEN_B });
    expect(joined.status).toBe(200);
    expect(joined.body).toMatchObject({ ok: true, pairId: a.pairId, role: "b" });
    expect(joined.body.secret).not.toBe(a.secret);

    await flush();
    expect(fcmCalls).toHaveLength(1);
    expect(fcmCalls[0]).toMatchObject({ token: TOKEN_A, data: { type: "paired", pairId: a.pairId } });
    expect((await call("/pair/status", {}, a)).body).toMatchObject({ paired: true, role: "a" });

    // Les secrets ne sont stockés que sous forme d'empreinte.
    const stored = [...kv.store.values()].map((entry) => entry.value).join();
    expect(stored).not.toContain(a.secret);
    expect(stored).not.toContain(joined.body.secret);
  });

  it("refuse les codes invalides, inconnus, réutilisés ou venant du même téléphone", async () => {
    expect((await call("/pair/join", { code: "AB1", token: TOKEN_B })).body.error).toBe("code_invalid");
    expect((await call("/pair/join", { code: "ZZZZZZ", token: TOKEN_B })).status).toBe(404);

    const created = await call("/pair/create", { token: TOKEN_A });
    const self = await call("/pair/join", { code: created.body.code, token: TOKEN_A });
    expect(self).toMatchObject({ status: 400, body: { error: "same_device" } });

    const joined = await call("/pair/join", { code: created.body.code, token: TOKEN_B });
    expect(joined.status).toBe(200);
    const intruder = await call("/pair/join", { code: created.body.code, token: `tokC:${"c".repeat(150)}` });
    expect(intruder).toMatchObject({ status: 409, body: { error: "code_used" } });
  });

  it("accepte que le même téléphone rejoue la saisie (réponse perdue) avec un nouveau secret", async () => {
    const created = await call("/pair/create", { token: TOKEN_A });
    const first = await call("/pair/join", { code: created.body.code, token: TOKEN_B });
    const retry = await call("/pair/join", { code: created.body.code, token: TOKEN_B });
    expect(retry).toMatchObject({ status: 200, body: { pairId: first.body.pairId, role: "b" } });
    expect(retry.body.secret).not.toBe(first.body.secret);
    expect((await call("/pair/status", {}, { pairId: first.body.pairId, secret: first.body.secret })).status).toBe(401);
    expect((await call("/pair/status", {}, { pairId: retry.body.pairId, secret: retry.body.secret })).body.paired).toBe(true);
  });

  it("fait expirer le code et la paire en attente après 10 minutes", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    const created = await call("/pair/create", { token: TOKEN_A });
    vi.setSystemTime(Date.now() + 601_000);
    expect((await call("/pair/join", { code: created.body.code, token: TOKEN_B })).status).toBe(404);
    const status = await call("/pair/status", {}, { pairId: created.body.pairId, secret: created.body.secret });
    expect(status).toMatchObject({ status: 404, body: { error: "pair_not_found" } });
  });

  it("une paire finalisée n'expire plus", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    const { a } = await pairPhones();
    vi.setSystemTime(Date.now() + 30 * 24 * 3600 * 1000);
    expect((await call("/pair/status", {}, a)).body.paired).toBe(true);
  });

  it("limite les tentatives de code par IP", async () => {
    env.PAIR_LIMITER = fakeLimiter(2);
    await call("/pair/join", { code: "ZZZZZZ", token: TOKEN_B });
    await call("/pair/join", { code: "ZZZZZZ", token: TOKEN_B });
    expect((await call("/pair/join", { code: "ZZZZZZ", token: TOKEN_B })).status).toBe(429);
  });
});

describe("authentification", () => {
  it("exige pairId + secret valides", async () => {
    const { a } = await pairPhones();
    expect((await call("/ping", {})).status).toBe(401);
    expect((await call("/ping", {}, { pairId: a.pairId, secret: "x".repeat(43) })).status).toBe(401);
    expect((await call("/ping", {}, { pairId: "inconnu-inconnu-inconnu", secret: a.secret })).status).toBe(404);
  });
});

describe("échos", () => {
  it("envoie un message data haute priorité au partenaire, avec un JWT signé en RS256", async () => {
    const { a, b } = await pairPhones();
    fcmCalls = [];

    const sent = await call("/ping", { emoji: "🫶", pattern: "heartbeat" }, a);
    expect(sent.status).toBe(200);
    expect(fcmCalls).toHaveLength(1);
    const [message] = fcmCalls;
    expect(message.token).toBe(TOKEN_B);
    expect(message.data).toEqual({
      type: "echo",
      pairId: a.pairId,
      emoji: "🫶",
      pattern: "heartbeat",
      sentAt: String(sent.body.sentAt),
      sender: "a",
    });
    expect(message.android).toEqual({ priority: "HIGH", ttl: "900s", collapse_key: "echo" });

    // Le JWT envoyé à Google est correctement signé.
    const [header, claims, signature] = oauthAssertions[0].split(".");
    expect(JSON.parse(new TextDecoder().decode(b64urlDecode(header)))).toEqual({ alg: "RS256", typ: "JWT" });
    const decoded = JSON.parse(new TextDecoder().decode(b64urlDecode(claims)));
    expect(decoded).toMatchObject({
      iss: "relay@echo-test.iam.gserviceaccount.com",
      scope: "https://www.googleapis.com/auth/firebase.messaging",
      aud: "https://oauth2.googleapis.com/token",
    });
    expect(decoded.exp - decoded.iat).toBe(3600);
    const valid = await crypto.subtle.verify(
      "RSASSA-PKCS1-v1_5",
      keyPair.publicKey,
      b64urlDecode(signature),
      new TextEncoder().encode(`${header}.${claims}`),
    );
    expect(valid).toBe(true);

    // Dans l'autre sens, avec le jeton d'accès en cache (un seul échange OAuth en tout).
    expect((await call("/ping", { emoji: "💜", pattern: "short" }, b)).status).toBe(200);
    expect(fcmCalls[1]).toMatchObject({ token: TOKEN_A, data: { sender: "b" }, authorization: "Bearer at-1" });
    expect(oauthAssertions).toHaveLength(1);
  });

  it("réutilise le jeton d'accès mis en cache dans KV par un autre isolat", async () => {
    const { a } = await pairPhones();
    resetFcmCaches();
    await call("/ping", {}, a);
    expect(oauthAssertions).toHaveLength(1);
    expect(fcmCalls.at(-1)?.authorization).toBe("Bearer at-1");
  });

  it("redemande un jeton d'accès si FCM répond 401", async () => {
    const { a } = await pairPhones();
    let first = true;
    fcmResponder = () => {
      if (first) {
        first = false;
        return new Response("{}", { status: 401 });
      }
      return Response.json({});
    };
    expect((await call("/ping", {}, a)).status).toBe(200);
    expect(oauthAssertions).toHaveLength(2);
  });

  it("refuse d'envoyer tant que l'autre téléphone n'a pas rejoint", async () => {
    const created = await call("/pair/create", { token: TOKEN_A });
    const result = await call("/ping", {}, { pairId: created.body.pairId, secret: created.body.secret });
    expect(result).toMatchObject({ status: 409, body: { error: "not_paired" } });
  });

  it("signale un partenaire injoignable (jeton FCM désinscrit)", async () => {
    const { a } = await pairPhones();
    fcmResponder = () =>
      Response.json(
        {
          error: {
            code: 404,
            status: "NOT_FOUND",
            details: [{ "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError", errorCode: "UNREGISTERED" }],
          },
        },
        { status: 404 },
      );
    expect(await call("/ping", {}, a)).toMatchObject({ status: 410, body: { error: "partner_unreachable" } });
  });

  it("ne prend un INVALID_ARGUMENT pour un jeton périmé que s'il porte sur le jeton", async () => {
    const { a } = await pairPhones();
    fcmResponder = () =>
      Response.json({ error: { code: 400, status: "INVALID_ARGUMENT", message: "Request contains an invalid argument." } }, { status: 400 });
    expect(await call("/ping", {}, a)).toMatchObject({ status: 502, body: { error: "fcm_error" } });
    fcmResponder = () =>
      Response.json(
        {
          error: {
            code: 400,
            status: "INVALID_ARGUMENT",
            message: "The registration token is not a valid FCM registration token",
            details: [
              { "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError", errorCode: "INVALID_ARGUMENT" },
              { "@type": "type.googleapis.com/google.rpc.BadRequest", fieldViolations: [{ field: "message.token" }] },
            ],
          },
        },
        { status: 400 },
      );
    expect(await call("/ping", {}, a)).toMatchObject({ status: 410, body: { error: "partner_unreachable" } });
  });

  it("applique la limitation de débit par paire", async () => {
    const { a, b } = await pairPhones();
    env.PING_LIMITER = fakeLimiter(2);
    expect((await call("/ping", {}, a)).status).toBe(200);
    expect((await call("/ping", {}, b)).status).toBe(200);
    expect(await call("/ping", {}, a)).toMatchObject({ status: 429, body: { error: "rate_limited" } });
  });

  it("valide l'emoji et le motif", async () => {
    const { a } = await pairPhones();
    expect((await call("/ping", { emoji: "" }, a)).status).toBe(400);
    expect((await call("/ping", { emoji: "x".repeat(33) }, a)).status).toBe(400);
    expect((await call("/ping", { pattern: "../etc" }, a)).status).toBe(400);
    expect((await call("/ping", { emoji: "❤️‍🔥", pattern: "purr" }, a)).status).toBe(200);
  });

  it("transmet la phrase de l'expéditeur, seulement si elle n'est pas vide", async () => {
    const { a } = await pairPhones();
    fcmCalls = [];
    expect((await call("/ping", { emoji: "💜", text: "  Tu me manques 🌙 " }, a)).status).toBe(200);
    expect(fcmCalls[0].data.text).toBe("Tu me manques 🌙");
    expect((await call("/ping", { text: "   " }, a)).status).toBe(200);
    expect((await call("/ping", { text: null }, a)).status).toBe(200);
    expect(fcmCalls[1].data).not.toHaveProperty("text");
    expect(fcmCalls[2].data).not.toHaveProperty("text");

    expect((await call("/ping", { text: "x".repeat(201) }, a)).status).toBe(400);
    expect((await call("/ping", { text: "ligne 1\nligne 2" }, a)).status).toBe(400);
    expect((await call("/ping", { text: 42 }, a)).status).toBe(400);
    expect(fcmCalls).toHaveLength(3);
  });

  it("renvoie un écho de test à l'expéditeur, éventuellement différé", async () => {
    const { a } = await pairPhones();
    fcmCalls = [];
    expect((await call("/ping", { target: "self" }, a)).status).toBe(200);
    expect(fcmCalls[0]).toMatchObject({ token: TOKEN_A, data: { type: "echo", test: "1" } });

    vi.useFakeTimers({ toFake: ["setTimeout"] });
    const delayed = await call("/ping", { target: "self", delay: 10 }, a);
    expect(delayed).toMatchObject({ status: 202, body: { delayed: 10 } });
    expect(fcmCalls).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(10_000);
    await flush();
    expect(fcmCalls).toHaveLength(2);
    expect(fcmCalls[1]).toMatchObject({ token: TOKEN_A, data: { test: "1" } });
  });
});

describe("jeton FCM et désappairage", () => {
  it("met à jour le jeton FCM d'un membre sans toucher à l'enregistrement de l'autre", async () => {
    const { a, b } = await pairPhones();
    const recordA = kv.store.get(`pair:${a.pairId}:a`)?.value;
    const newToken = `tokB2:APA91b${"n".repeat(140)}`;
    expect((await call("/token/update", { token: newToken }, b)).status).toBe(200);
    expect(kv.store.get(`pair:${a.pairId}:a`)?.value).toBe(recordA);
    await call("/ping", {}, a);
    expect(fcmCalls.at(-1)?.token).toBe(newToken);
  });

  it("conserve l'expiration d'une paire en attente lors d'une mise à jour de jeton", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    const created = await call("/pair/create", { token: TOKEN_A });
    const a = { pairId: created.body.pairId, secret: created.body.secret };
    vi.setSystemTime(Date.now() + 300_000);
    await call("/token/update", { token: `tokA2:${"z".repeat(150)}` }, a);
    vi.setSystemTime(Date.now() + 301_000);
    expect((await call("/pair/status", {}, a)).status).toBe(404);
  });

  it("supprime la paire et prévient l'autre téléphone", async () => {
    const { a, b } = await pairPhones();
    fcmCalls = [];
    expect((await call("/pair/leave", {}, a)).status).toBe(200);
    await flush();
    expect(fcmCalls).toEqual([
      expect.objectContaining({ token: TOKEN_B, data: { type: "unpaired", pairId: a.pairId } }),
    ]);
    expect((await call("/ping", {}, b)).status).toBe(404);
    expect(kv.store.size).toBe(1); // ne reste que le jeton d'accès OAuth en cache
  });
});

describe("robustesse HTTP", () => {
  it("gère routes, méthodes et corps invalides", async () => {
    const health = await worker.fetch(new Request("https://relay.test/health") as never, env, ctx);
    expect(health.status).toBe(200);
    expect((await call("/inconnu", {})).status).toBe(404);
    expect((await call("/ping", undefined, undefined, { method: "GET" })).status).toBe(405);
    expect((await call("/pair/create", "{oups", undefined, { body: "{oups" })).status).toBe(400);
    expect((await call("/pair/create", { token: "x".repeat(5000) })).status).toBe(413);
    expect((await call("/pair/create", { token: "court" })).status).toBe(400);
  });

  it("signale un compte de service manquant sans divulguer de détails", async () => {
    const { a } = await pairPhones();
    env.FIREBASE_SERVICE_ACCOUNT = "";
    resetFcmCaches();
    kv.store.delete("fcm:access_token");
    expect(await call("/ping", {}, a)).toMatchObject({ status: 500, body: { error: "server_misconfigured" } });
  });
});

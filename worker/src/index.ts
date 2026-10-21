import { CODE_REGEX, normalizeCode, randomCode, randomToken, sha256, timingSafeEqual } from "./crypto";
import { ConfigError, sendFcm, type FcmOptions, type FcmResult } from "./fcm";
import type { Env, FcmData, MemberRecord, Role } from "./types";

/** Durée de validité d'un code d'appairage (et de la paire tant que le second téléphone n'a pas rejoint). */
const PENDING_TTL_SECONDS = 600;
/** Nettoyage des paires abandonnées ; l'expiration à 10 min est vérifiée à la lecture (createdAt). */
const ABANDONED_PAIR_TTL_SECONDS = 7 * 24 * 3600;
const MAX_BODY_BYTES = 4096;
const MAX_SELF_TEST_DELAY_SECONDS = 20;

const TOKEN_REGEX = /^[A-Za-z0-9_:.-]{20,4096}$/;
const PAIR_ID_REGEX = /^[A-Za-z0-9_-]{16,64}$/;
const SECRET_REGEX = /^[A-Za-z0-9_-]{32,128}$/;
const PATTERN_REGEX = /^[a-z_]{1,24}$/;
// eslint-disable-next-line no-control-regex
const CONTROL_CHARS = /[\u0000-\u001f\u007f]/;

// Un écho n'a de sens que s'il arrive vite ; les échos en attente sont fusionnés.
const ECHO_FCM: FcmOptions = { ttlSeconds: 15 * 60, collapseKey: "echo" };
const CONTROL_FCM: FcmOptions = { ttlSeconds: 28 * 24 * 3600 };

class HttpError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message?: string,
  ) {
    super(message ?? code);
  }
}

type Body = Record<string, unknown>;

interface RouteContext {
  request: Request;
  env: Env;
  ctx: ExecutionContext;
  body: Body;
}

interface Pair {
  pairId: string;
  a: MemberRecord;
  /** `null` tant que le second téléphone n'a pas rejoint. */
  b: MemberRecord | null;
}

interface AuthenticatedPair extends Pair {
  role: Role;
}

const ROUTES: Record<string, (c: RouteContext) => Promise<Response>> = {
  "/pair/create": createPair,
  "/pair/join": joinPair,
  "/pair/status": pairStatus,
  "/pair/leave": leavePair,
  "/token/update": updateToken,
  "/ping": ping,
};

export default {
  async fetch(request, env, ctx): Promise<Response> {
    try {
      return await route(request, env, ctx);
    } catch (error) {
      if (error instanceof HttpError) {
        return json(error.status, { ok: false, error: error.code, message: error.message });
      }
      if (error instanceof ConfigError) {
        console.error("configuration:", error.message);
        return json(500, { ok: false, error: "server_misconfigured", message: "Relais mal configuré" });
      }
      console.error("erreur inattendue:", error);
      return json(500, { ok: false, error: "internal" });
    }
  },
} satisfies ExportedHandler<Env>;

async function route(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  const { pathname } = new URL(request.url);
  if (request.method === "GET" && (pathname === "/" || pathname === "/health")) {
    return json(200, { ok: true, service: "echo-relay" });
  }
  const handler = ROUTES[pathname];
  if (!handler) throw new HttpError(404, "not_found");
  if (request.method !== "POST") throw new HttpError(405, "method_not_allowed");
  return handler({ request, env, ctx, body: await readBody(request) });
}

// ---------------------------------------------------------------------------
// Appairage
// ---------------------------------------------------------------------------

async function createPair({ request, env, body }: RouteContext): Promise<Response> {
  await limitByIp(env, request);
  const token = requireFcmToken(body.token);
  const pairId = randomToken(16);
  const secret = randomToken(32);
  const code = await allocateCode(env);
  const now = Date.now();
  const a: MemberRecord = { v: 1, token, secretHash: await sha256(secret), createdAt: now, updatedAt: now, code };
  await putMember(env, pairId, "a", a, ABANDONED_PAIR_TTL_SECONDS);
  await env.ECHO_KV.put(codeKey(code), pairId, { expirationTtl: PENDING_TTL_SECONDS });
  return json(201, {
    ok: true,
    pairId,
    secret,
    role: "a",
    code,
    expiresAt: now + PENDING_TTL_SECONDS * 1000,
  });
}

async function joinPair({ request, env, ctx, body }: RouteContext): Promise<Response> {
  await limitByIp(env, request);
  const token = requireFcmToken(body.token);
  const code = typeof body.code === "string" ? normalizeCode(body.code) : "";
  if (!CODE_REGEX.test(code)) throw new HttpError(400, "code_invalid", "Code invalide");

  const pairId = await env.ECHO_KV.get(codeKey(code));
  const pair = pairId ? await loadPair(env, pairId) : null;
  if (!pair || pair.a.code !== code || isExpiredPending(pair)) {
    throw new HttpError(404, "code_invalid", "Code inconnu ou expiré");
  }
  if (pair.a.token === token) throw new HttpError(400, "same_device", "Ce code a été créé sur ce téléphone");

  const secret = randomToken(32);
  const secretHash = await sha256(secret);
  const now = Date.now();
  if (pair.b) {
    // Même téléphone qui rejoue la requête (réponse perdue) : nouveau secret, rien d'autre ne change.
    if (pair.b.token !== token) throw new HttpError(409, "code_used", "Ce code a déjà été utilisé");
    await putMember(env, pair.pairId, "b", { ...pair.b, secretHash, updatedAt: now });
    return json(200, { ok: true, pairId: pair.pairId, secret, role: "b" });
  }

  await putMember(env, pair.pairId, "b", { v: 1, token, secretHash, createdAt: pair.a.createdAt, updatedAt: now, joinedAt: now });
  // La paire devient permanente : le membre A perd son expiration de nettoyage.
  await putMember(env, pair.pairId, "a", pair.a);
  ctx.waitUntil(deliver(env, pair.a.token, { type: "paired", pairId: pair.pairId }, CONTROL_FCM));
  return json(200, { ok: true, pairId: pair.pairId, secret, role: "b" });
}

async function pairStatus({ request, env }: RouteContext): Promise<Response> {
  const { b, role } = await authenticate(env, request);
  return json(200, { ok: true, paired: b !== null, role, pairedAt: b?.joinedAt ?? null });
}

async function leavePair({ request, env, ctx }: RouteContext): Promise<Response> {
  const { pairId, a, b, role } = await authenticate(env, request);
  await env.ECHO_KV.delete(memberKey(pairId, "a"));
  await env.ECHO_KV.delete(memberKey(pairId, "b"));
  if (a.code) await env.ECHO_KV.delete(codeKey(a.code));
  const other = role === "a" ? b : a;
  if (other) ctx.waitUntil(deliver(env, other.token, { type: "unpaired", pairId }, CONTROL_FCM));
  return json(200, { ok: true });
}

async function updateToken({ request, env, body }: RouteContext): Promise<Response> {
  const pair = await authenticate(env, request);
  const token = requireFcmToken(body.token);
  const member = pair.role === "a" ? pair.a : pair.b!;
  if (member.token !== token) {
    // Sans expiration : on ne la recalcule jamais à partir d'une lecture KV peut-être périmée.
    await putMember(env, pair.pairId, pair.role, { ...member, token, updatedAt: Date.now() });
  }
  return json(200, { ok: true });
}

// ---------------------------------------------------------------------------
// Échos
// ---------------------------------------------------------------------------

async function ping({ request, env, ctx, body }: RouteContext): Promise<Response> {
  const { pairId, a, b, role } = await authenticate(env, request);
  if (env.PING_LIMITER && !(await env.PING_LIMITER.limit({ key: `pair:${pairId}` })).success) {
    throw new HttpError(429, "rate_limited", "Trop d'échos d'un coup, patiente un peu");
  }
  const emoji = parseEmoji(body.emoji);
  const pattern = parsePattern(body.pattern);
  const sentAt = Date.now();

  if (body.target === "self") {
    // Écho de test renvoyé à l'expéditeur, éventuellement différé pour laisser le temps de verrouiller l'écran.
    const delay = parseDelay(body.delay);
    const token = (role === "a" ? a : b!).token;
    const message = (at: number): FcmData => ({ type: "echo", test: "1", pairId, emoji, pattern, sentAt: String(at) });
    if (delay > 0) {
      ctx.waitUntil(sleep(delay * 1000).then(() => deliver(env, token, message(Date.now()), ECHO_FCM)));
      return json(202, { ok: true, sentAt: sentAt + delay * 1000, delayed: delay });
    }
    assertDelivered(await sendFcm(env, token, message(sentAt), ECHO_FCM), "self_unreachable");
    return json(200, { ok: true, sentAt });
  }

  const partner = role === "a" ? b : a;
  if (!partner) throw new HttpError(409, "not_paired", "L'autre téléphone n'a pas encore rejoint");
  const result = await sendFcm(
    env,
    partner.token,
    // « from » est une clé réservée par FCM : l'expéditeur s'appelle « sender ».
    { type: "echo", pairId, emoji, pattern, sentAt: String(sentAt), sender: role },
    ECHO_FCM,
  );
  assertDelivered(result, "partner_unreachable");
  return json(200, { ok: true, sentAt });
}

function assertDelivered(result: FcmResult, unreachableCode: string): void {
  if (result.ok) return;
  if (result.reason === "unregistered") {
    throw new HttpError(410, unreachableCode, "Le jeton FCM n'est plus valide : il faut rouvrir Écho sur ce téléphone");
  }
  console.error("FCM:", result.status, result.detail ?? "");
  throw new HttpError(502, "fcm_error", "Firebase a refusé l'envoi");
}

async function deliver(env: Env, token: string, data: FcmData, options: FcmOptions): Promise<void> {
  try {
    const result = await sendFcm(env, token, data, options);
    if (!result.ok) console.warn(`FCM (${data.type}):`, result.status, result.detail ?? result.reason);
  } catch (error) {
    console.error(`FCM (${data.type}):`, error);
  }
}

// ---------------------------------------------------------------------------
// Authentification et stockage
// ---------------------------------------------------------------------------

async function authenticate(env: Env, request: Request): Promise<AuthenticatedPair> {
  const pairId = request.headers.get("x-pair-id") ?? "";
  const authorization = request.headers.get("authorization") ?? "";
  const secret = authorization.startsWith("Bearer ") ? authorization.slice(7).trim() : "";
  if (!PAIR_ID_REGEX.test(pairId) || !SECRET_REGEX.test(secret)) throw new HttpError(401, "unauthorized");

  const pair = await loadPair(env, pairId);
  if (!pair || isExpiredPending(pair)) throw new HttpError(404, "pair_not_found", "Appairage introuvable");
  const hash = await sha256(secret);
  const isA = timingSafeEqual(hash, pair.a.secretHash);
  const isB = pair.b !== null && timingSafeEqual(hash, pair.b.secretHash);
  if (!isA && !isB) throw new HttpError(401, "unauthorized");
  return { ...pair, role: isA ? "a" : "b" };
}

const memberKey = (pairId: string, role: Role) => `pair:${pairId}:${role}`;
const codeKey = (code: string) => `code:${code}`;

async function loadPair(env: Env, pairId: string): Promise<Pair | null> {
  const [a, b] = await Promise.all([
    env.ECHO_KV.get<MemberRecord>(memberKey(pairId, "a"), "json"),
    env.ECHO_KV.get<MemberRecord>(memberKey(pairId, "b"), "json"),
  ]);
  return a ? { pairId, a, b } : null;
}

function isExpiredPending(pair: Pair): boolean {
  return pair.b === null && Date.now() - pair.a.createdAt > PENDING_TTL_SECONDS * 1000;
}

async function putMember(env: Env, pairId: string, role: Role, member: MemberRecord, ttlSeconds?: number): Promise<void> {
  await env.ECHO_KV.put(memberKey(pairId, role), JSON.stringify(member), ttlSeconds ? { expirationTtl: ttlSeconds } : {});
}

async function allocateCode(env: Env): Promise<string> {
  for (let attempt = 0; attempt < 5; attempt++) {
    const code = randomCode();
    if ((await env.ECHO_KV.get(codeKey(code))) === null) return code;
  }
  throw new HttpError(503, "busy", "Impossible de générer un code, réessaie");
}

async function limitByIp(env: Env, request: Request): Promise<void> {
  if (!env.PAIR_LIMITER) return;
  const ip = request.headers.get("cf-connecting-ip") ?? "inconnue";
  if (!(await env.PAIR_LIMITER.limit({ key: `ip:${ip}` })).success) {
    throw new HttpError(429, "rate_limited", "Trop de tentatives, réessaie dans une minute");
  }
}

// ---------------------------------------------------------------------------
// Validation et utilitaires
// ---------------------------------------------------------------------------

async function readBody(request: Request): Promise<Body> {
  const declared = Number(request.headers.get("content-length") ?? "0");
  if (declared > MAX_BODY_BYTES) throw new HttpError(413, "payload_too_large");
  const text = await request.text();
  if (text.length > MAX_BODY_BYTES) throw new HttpError(413, "payload_too_large");
  if (text.trim() === "") return {};
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    throw new HttpError(400, "bad_request", "JSON invalide");
  }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
    throw new HttpError(400, "bad_request", "Objet JSON attendu");
  }
  return parsed as Body;
}

function requireFcmToken(value: unknown): string {
  if (typeof value !== "string" || !TOKEN_REGEX.test(value)) {
    throw new HttpError(400, "bad_request", "Jeton FCM invalide");
  }
  return value;
}

function parseEmoji(value: unknown): string {
  if (value === undefined) return "💜";
  if (typeof value !== "string" || value.length === 0 || value.length > 32 || CONTROL_CHARS.test(value)) {
    throw new HttpError(400, "bad_request", "Emoji invalide");
  }
  return value;
}

function parsePattern(value: unknown): string {
  if (value === undefined) return "heartbeat";
  if (typeof value !== "string" || !PATTERN_REGEX.test(value)) {
    throw new HttpError(400, "bad_request", "Motif de vibration invalide");
  }
  return value;
}

function parseDelay(value: unknown): number {
  if (value === undefined) return 0;
  if (typeof value !== "number" || !Number.isFinite(value)) throw new HttpError(400, "bad_request", "Délai invalide");
  return Math.min(MAX_SELF_TEST_DELAY_SECONDS, Math.max(0, Math.round(value)));
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function json(status: number, body: Record<string, unknown>): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

import { base64UrlEncode, pemToPkcs8 } from "./crypto";
import type { Env, FcmData } from "./types";

const FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
const DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token";
const ACCESS_TOKEN_KV_KEY = "fcm:access_token";

interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
  token_uri?: string;
}

interface CachedToken {
  token: string;
  /** Expiration en ms (epoch). */
  exp: number;
}

export interface FcmOptions {
  /** Durée de vie du message côté FCM, en secondes. */
  ttlSeconds: number;
  /** Les messages en attente avec la même clé sont fusionnés (seul le dernier est livré). */
  collapseKey?: string;
}

export type FcmResult =
  | { ok: true }
  | { ok: false; reason: "unregistered" | "fcm_error"; status: number; detail?: string };

export class ConfigError extends Error {}

// Caches au niveau de l'isolat : évitent de re-signer un JWT et de relire KV à chaque requête.
let serviceAccountCache: { raw: string; account: ServiceAccount; key: Promise<CryptoKey> } | null = null;
let memoryToken: CachedToken | null = null;

/** Pour les tests uniquement. */
export function resetFcmCaches(): void {
  serviceAccountCache = null;
  memoryToken = null;
}

function loadServiceAccount(env: Env): { account: ServiceAccount; key: Promise<CryptoKey> } {
  const raw = env.FIREBASE_SERVICE_ACCOUNT;
  if (serviceAccountCache?.raw === raw) return serviceAccountCache;
  if (!raw) throw new ConfigError("FIREBASE_SERVICE_ACCOUNT manquant");
  let account: ServiceAccount;
  try {
    account = JSON.parse(raw) as ServiceAccount;
  } catch {
    throw new ConfigError("FIREBASE_SERVICE_ACCOUNT n'est pas un JSON valide");
  }
  if (!account.project_id || !account.client_email || !account.private_key) {
    throw new ConfigError("FIREBASE_SERVICE_ACCOUNT incomplet (project_id, client_email, private_key)");
  }
  const key = crypto.subtle.importKey(
    "pkcs8",
    pemToPkcs8(account.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  serviceAccountCache = { raw, account, key };
  return serviceAccountCache;
}

export async function createSignedJwt(account: ServiceAccount, key: CryptoKey, nowSeconds: number): Promise<string> {
  const header = { alg: "RS256", typ: "JWT" };
  const claims = {
    iss: account.client_email,
    scope: FCM_SCOPE,
    aud: account.token_uri ?? DEFAULT_TOKEN_URI,
    iat: nowSeconds,
    exp: nowSeconds + 3600,
  };
  const unsigned = `${base64UrlEncode(JSON.stringify(header))}.${base64UrlEncode(JSON.stringify(claims))}`;
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned));
  return `${unsigned}.${base64UrlEncode(signature)}`;
}

async function getAccessToken(env: Env, forceRefresh: boolean): Promise<string> {
  const now = Date.now();
  const margin = 2 * 60 * 1000;
  if (!forceRefresh) {
    if (memoryToken && memoryToken.exp - margin > now) return memoryToken.token;
    const cached = await env.ECHO_KV.get<CachedToken>(ACCESS_TOKEN_KV_KEY, "json");
    if (cached && cached.exp - margin > now) {
      memoryToken = cached;
      return cached.token;
    }
  }

  const { account, key } = loadServiceAccount(env);
  const assertion = await createSignedJwt(account, await key, Math.floor(now / 1000));
  const response = await fetch(account.token_uri ?? DEFAULT_TOKEN_URI, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }).toString(),
  });
  if (!response.ok) {
    throw new ConfigError(`Échange OAuth refusé (${response.status}) : ${(await response.text()).slice(0, 200)}`);
  }
  const body = (await response.json()) as { access_token: string; expires_in: number };
  memoryToken = { token: body.access_token, exp: now + body.expires_in * 1000 };
  // Partagé entre isolats ; KV impose un TTL d'au moins 60 s.
  await env.ECHO_KV.put(ACCESS_TOKEN_KV_KEY, JSON.stringify(memoryToken), {
    expirationTtl: Math.max(60, body.expires_in - 180),
  });
  return memoryToken.token;
}

interface GoogleError {
  error?: {
    message?: string;
    details?: Array<{ "@type"?: string; errorCode?: string; fieldViolations?: Array<{ field?: string }> }>;
  };
}

function fcmErrorCode(body: GoogleError | null): string | undefined {
  return body?.error?.details?.find((d) => String(d["@type"] ?? "").endsWith("google.firebase.fcm.v1.FcmError"))
    ?.errorCode;
}

/** Un INVALID_ARGUMENT ne veut dire « jeton périmé » que s'il porte sur `message.token`. */
function isInvalidToken(body: GoogleError | null): boolean {
  const onToken = body?.error?.details?.some((d) => d.fieldViolations?.some((v) => v.field === "message.token"));
  return onToken === true || /registration token/i.test(body?.error?.message ?? "");
}

/** Clés interdites par FCM dans `data` (sinon INVALID_ARGUMENT pour tout le message). */
export function isReservedDataKey(key: string): boolean {
  return key === "from" || key === "message_type" || key.startsWith("google.") || key.startsWith("gcm.");
}

/** Envoie un message « data » en priorité haute (réveille l'appli même fermée / en Doze). */
export async function sendFcm(env: Env, token: string, data: FcmData, options: FcmOptions): Promise<FcmResult> {
  const reserved = Object.keys(data).filter(isReservedDataKey);
  if (reserved.length > 0) throw new Error(`Clés réservées par FCM dans data : ${reserved.join(", ")}`);
  const { account } = loadServiceAccount(env);
  const url = `https://fcm.googleapis.com/v1/projects/${account.project_id}/messages:send`;
  const payload = JSON.stringify({
    message: {
      token,
      data,
      android: {
        priority: "HIGH",
        ttl: `${options.ttlSeconds}s`,
        ...(options.collapseKey ? { collapse_key: options.collapseKey } : {}),
      },
    },
  });

  for (let attempt = 0; attempt < 2; attempt++) {
    const accessToken = await getAccessToken(env, attempt > 0);
    const response = await fetch(url, {
      method: "POST",
      headers: { authorization: `Bearer ${accessToken}`, "content-type": "application/json" },
      body: payload,
    });
    if (response.ok) return { ok: true };
    // Jeton d'accès révoqué ou expiré : on en redemande un, une seule fois.
    if (response.status === 401 && attempt === 0) {
      memoryToken = null;
      continue;
    }
    const body = (await response.json().catch(() => null)) as GoogleError | null;
    const code = fcmErrorCode(body);
    if (response.status === 404 || code === "UNREGISTERED") {
      return { ok: false, reason: "unregistered", status: response.status, detail: code };
    }
    if (response.status === 400 && isInvalidToken(body)) {
      return { ok: false, reason: "unregistered", status: response.status, detail: code ?? "INVALID_ARGUMENT" };
    }
    return { ok: false, reason: "fcm_error", status: response.status, detail: code ?? body?.error?.message?.slice(0, 200) };
  }
  return { ok: false, reason: "fcm_error", status: 401 };
}

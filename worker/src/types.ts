export interface RateLimiter {
  limit(options: { key: string }): Promise<{ success: boolean }>;
}

export interface Env {
  ECHO_KV: KVNamespace;
  /** JSON complet du compte de service Firebase (wrangler secret put FIREBASE_SERVICE_ACCOUNT). */
  FIREBASE_SERVICE_ACCOUNT: string;
  /** Limiteur des envois d'échos, clé = pairId. */
  PING_LIMITER?: RateLimiter;
  /** Limiteur de create/join, clé = IP (anti force brute des codes). */
  PAIR_LIMITER?: RateLimiter;
}

export type Role = "a" | "b";

/**
 * Un enregistrement KV par membre (`pair:<pairId>:a` et `pair:<pairId>:b`) : chaque téléphone ne
 * réécrit que le sien, si bien qu'une lecture KV pas encore propagée ne peut pas écraser les
 * données de l'autre.
 */
export interface MemberRecord {
  v: 1;
  token: string;
  secretHash: string;
  updatedAt: number;
  /** Création de la paire : une paire sans membre B expire 10 min après. */
  createdAt: number;
  /** Membre A : code d'appairage (conservé pour pouvoir le supprimer au désappairage). */
  code?: string;
  /** Membre B : date d'appairage. */
  joinedAt?: number;
}

/** Données envoyées dans les messages FCM (toutes les valeurs doivent être des chaînes). */
export type FcmData = Record<string, string>;

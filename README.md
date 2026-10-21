# Écho 💜

Appli Android pour deux personnes : **un tap sur le widget fait vibrer le téléphone de l'autre**.
Pas de compte, pas de message : juste un « je pense à toi ».

- **Widget** d'écran d'accueil (Jetpack Glance) : un tap envoie un écho ; il affiche le dernier écho reçu (« 💜 il y a 5 min »). Couleurs dynamiques, coins arrondis du système, redimensionnable de 1×1 à 4×2.
- **Appli** (Jetpack Compose, Material 3 Expressive) : choix de l'emoji et du motif de vibration (court, double, cœur battant, long, vague, ronron), historique local des échos envoyés et reçus (Room), réglages.
- **Réception** : message FCM « data » en priorité haute → vibration immédiate avec le motif reçu (même appli fermée), notification discrète avec bouton « Renvoyer un écho », mise à jour du widget.
- **Silencieux / Ne pas déranger** : vibration jouée directement par l'appli avec l'usage `ALARM`, canal de notification dédié avec `setBypassDnd(true)`, accès « Ne pas déranger » demandé au premier lancement (écran explicatif), interrupteur « Vibrer même en Ne pas déranger » (activé par défaut), guide de dépannage Honor / MagicOS.
- **Anti-spam** : 5 s minimum entre deux envois (appli), limitation de débit par paire (relais).
- **Appairage sans compte** : le téléphone A affiche un code à 6 caractères, le téléphone B le saisit. Le relais lie les deux jetons FCM et remet à chacun un `pairId` + un secret (stockés en EncryptedSharedPreferences). Désappairage depuis les réglages.

```
 Téléphone A ──POST /ping──▶ Cloudflare Worker ──FCM HTTP v1──▶ Téléphone B
   (widget)                  (KV : paires, jeton OAuth)            (vibration)
```

| Dossier | Contenu |
|---|---|
| `app/` | Appli Android (Kotlin, Compose, Glance, Room, DataStore, WorkManager, Firebase Messaging) |
| `worker/` | Relais Cloudflare Workers + KV (TypeScript, wrangler) |
| `.github/workflows/build.yml` | Tests du relais et de l'appli, puis APK release signé en artefact |

---

## Mise en place (étapes manuelles)

Il faut : un compte Google (Firebase), un compte Cloudflare (offre gratuite suffisante), Node.js 22+ et `keytool` (fourni avec tout JDK).

### 1. Projet Firebase

1. Sur <https://console.firebase.google.com>, **Ajouter un projet** (Google Analytics inutile).
2. **Ajouter une appli Android** avec le nom de package `com.karelisio.echo` (pas besoin d'empreinte SHA-1).
3. Télécharger **`google-services.json`**. Ne pas le commiter : il est ignoré par git et sera fourni à la CI en secret (étape 5).
4. Dans *Paramètres du projet → Cloud Messaging*, vérifier que **l'API Firebase Cloud Messaging (V1)** est activée (c'est le cas par défaut).

### 2. Compte de service (pour que le relais puisse envoyer via FCM)

1. *Paramètres du projet → Comptes de service → Générer une nouvelle clé privée* : un fichier JSON est téléchargé.
2. Ce fichier donne le droit d'envoyer des notifications à vos téléphones : ne le partagez pas, ne le commitez pas, supprimez-le après l'étape 3.

> Option plus restreinte : dans la console Google Cloud du même projet, créer un compte de service dédié avec le seul rôle **Firebase Cloud Messaging API Admin** et utiliser sa clé JSON.

### 3. Déployer le relais (Cloudflare Worker)

Depuis un ordinateur avec Node.js 22+ :

```bash
cd worker
npm ci
npx wrangler login      # ouvre le navigateur pour se connecter à Cloudflare
npx wrangler deploy     # crée aussi l'espace KV au premier déploiement

# Compte de service Firebase, stocké comme secret du Worker (jamais dans le code)
npx wrangler secret put FIREBASE_SERVICE_ACCOUNT < /chemin/vers/compte-de-service.json
# Windows PowerShell :
# Get-Content C:\chemin\vers\compte-de-service.json -Raw | npx wrangler secret put FIREBASE_SERVICE_ACCOUNT
```

`wrangler deploy` affiche l'URL du relais : `https://echo-relay.<votre-sous-domaine>.workers.dev`, où `echo-relay` est le nom du Worker (`worker/wrangler.toml`) et `<votre-sous-domaine>` le sous-domaine `workers.dev` de votre compte Cloudflare (choisi au premier déploiement, visible dans *Workers & Pages*). Vérification :

```bash
curl https://echo-relay.<votre-sous-domaine>.workers.dev/health
# {"ok":true,"service":"echo-relay"}
```

Le Worker signe lui-même le JWT OAuth du compte de service avec WebCrypto, met le jeton d'accès en cache (mémoire + KV, ~1 h) et appelle l'API FCM HTTP v1. Journaux en direct : `npx wrangler tail`.

### 4. Clé de signature de l'APK

```bash
keytool -genkeypair -v -keystore echo-release.jks -alias echo \
  -keyalg RSA -keysize 4096 -validity 10000
```

Gardez ce fichier et ses mots de passe en lieu sûr (gestionnaire de mots de passe) : sans eux, impossible de publier une mise à jour installable par-dessus l'appli existante.

Encodage en base64 pour les secrets GitHub :

```bash
base64 -w0 echo-release.jks > echo-release.jks.b64        # macOS : base64 -i echo-release.jks
base64 -w0 google-services.json > google-services.json.b64
```

### 5. Secrets GitHub

Dans le dépôt : *Settings → Secrets and variables → Actions*.

| Type | Nom | Valeur |
|---|---|---|
| Secret | `GOOGLE_SERVICES_JSON_BASE64` | contenu de `google-services.json.b64` |
| Secret | `KEYSTORE_BASE64` | contenu de `echo-release.jks.b64` |
| Secret | `KEYSTORE_PASSWORD` | mot de passe du keystore |
| Secret | `KEY_ALIAS` | `echo` (ou l'alias choisi) |
| Secret | `KEY_PASSWORD` | mot de passe de la clé |
| Variable | `ECHO_RELAY_URL` | URL du Worker, ex. `https://echo-relay.xxx.workers.dev` |

Supprimez ensuite les fichiers `.b64` locaux.

### 6. Récupérer et installer l'APK

À chaque push sur `main`, sur un tag `v*` ou en lancement manuel (*Actions → Build → Run workflow*, sur n'importe quelle branche), le workflow **Build** :
1. teste le relais (`tsc` + vitest) et l'appli (tests unitaires + Robolectric) ;
2. construit l'APK release (R8) signé avec votre clé, vérifie la signature ;
3. publie l'APK en **artefact** `echo-<version>` (onglet *Actions* → l'exécution → *Artifacts*).

Installez l'APK sur les deux téléphones (autoriser « Installer des applis inconnues » pour le navigateur ou le gestionnaire de fichiers). Les autres branches et les pull requests lancent seulement les tests (sans secrets).

### 7. Premier lancement et appairage

1. Sur chaque téléphone, suivez l'accueil : notifications, **accès « Ne pas déranger »** (pour que les échos passent en silencieux / Ne pas déranger), puis **exclusion de l'optimisation de la batterie**.
2. Téléphone A : **Afficher un code**. Téléphone B : **J'ai un code** et saisir les 6 caractères (valable 10 minutes).
3. Ajoutez le widget **Écho** sur l'écran d'accueil (appui long sur le fond → Widgets).
4. Dans *Réglages → Tester*, envoyez-vous un **écho de test « Dans 10 s »**, verrouillez l'écran : le téléphone doit vibrer.

Faites l'appairage avec les deux téléphones côte à côte : le stockage KV de Cloudflare peut mettre jusqu'à une minute à se propager entre régions. Si un code est refusé alors qu'il vient d'être créé, patientez une minute et réessayez.

---

## Honor / MagicOS (et Huawei)

MagicOS ferme agressivement les applis en arrière-plan et applique Ne pas déranger plus strictement. Si l'écho de test ne vibre pas (l'appli propose alors ce guide, aussi accessible dans *Réglages → Dépannage*) :

1. **Lancement d'applications** : Paramètres → Batterie → Lancement d'applications → Écho → désactiver la gestion automatique, activer *Lancement automatique*, *Lancement secondaire* et *Exécution en arrière-plan* (bouton « Ouvrir » dans l'appli).
2. **Exceptions Ne pas déranger** : Paramètres → Sons et vibrations → Ne pas déranger → Exceptions (ou « Autoriser les interruptions ») → Applications → ajouter Écho ; laisser les alarmes autorisées.
3. **Canal « Échos reçus »** : notifications d'Écho → « Échos reçus » → activer *Ignorer Ne pas déranger* / *Prioritaire*.
4. **Verrouiller Écho** dans les applis récentes (glisser la vignette vers le bas → cadenas).

Les intitulés exacts varient selon la version de MagicOS.

---

## Développement local

Prérequis : JDK 21, SDK Android avec la plateforme 37 (`sdkmanager "platforms;android-37.0"`).

```bash
cp ~/Téléchargements/google-services.json app/        # ignoré par git
echo "sdk.dir=$ANDROID_HOME" >> local.properties
echo "echo.relayUrl=https://echo-relay.xxx.workers.dev" >> local.properties

./gradlew :app:assembleDebug          # APK de debug
./gradlew :app:testDebugUnitTest      # tests unitaires + Robolectric (écrans et widget)
```

L'URL du relais peut aussi venir de `-PechoRelayUrl=…` ou de la variable d'environnement `ECHO_RELAY_URL`. Pour signer une release en local, définir `ECHO_KEYSTORE_FILE`, `ECHO_KEYSTORE_PASSWORD`, `ECHO_KEY_ALIAS` et `ECHO_KEY_PASSWORD` puis `./gradlew :app:assembleRelease`.

Relais :

```bash
cd worker
npm test                  # vitest : appairage, auth, FCM (JWT vérifié), limitation de débit…
npm run check             # TypeScript
npx wrangler dev          # local ; secrets dans worker/.dev.vars (ignoré par git)
```

### Versions

Kotlin 2.4, AGP 9.4, Gradle 9.8, compileSdk 37 / targetSdk 36 / minSdk 26, Compose BOM 2026.09 avec Material 3 **1.5.0-beta01** (les composants Expressive — `MaterialExpressiveTheme`, `LoadingIndicator`, `MaterialShapes`, `ButtonGroup`… — ne sont publiés que dans la branche 1.5), Glance 1.2, Room 2.8, Firebase BoM 35.

---

## API du relais

Tous les corps sont en JSON (4 Ko max). Les routes authentifiées attendent les en-têtes `X-Pair-Id: <pairId>` et `Authorization: Bearer <secret>`.

| Route | Auth | Corps | Réponse |
|---|---|---|---|
| `POST /pair/create` | — (limitée par IP) | `{ token }` | `201 { pairId, secret, role: "a", code, expiresAt }` |
| `POST /pair/join` | — (limitée par IP) | `{ code, token }` | `200 { pairId, secret, role: "b" }` + message FCM `paired` au téléphone A |
| `POST /pair/status` | oui | `{}` | `{ paired, role, pairedAt }` |
| `POST /token/update` | oui | `{ token }` | `{ ok: true }` |
| `POST /ping` | oui (limitée par paire) | `{ emoji, pattern }` ou `{ target: "self", delay }` | `{ sentAt }` |
| `POST /pair/leave` | oui | `{}` | `{ ok: true }` + message FCM `unpaired` à l'autre |
| `GET /health` | — | | `{ ok: true }` |

Erreurs : `{ ok: false, error, message }` avec notamment `code_invalid` (404), `code_used` (409), `unauthorized` (401), `pair_not_found` (404), `not_paired` (409), `rate_limited` (429), `partner_unreachable` (410, jeton FCM de l'autre périmé), `fcm_error` (502), `server_misconfigured` (500).

Codes et paires en attente expirent après **10 minutes** ; le téléphone B peut rejouer la saisie du même code (réponse perdue) sans casser le lien. Chaque téléphone a son propre enregistrement KV, si bien qu’une copie KV pas encore propagée ne peut pas écraser les données de l’autre. Les messages FCM sont des messages *data* en priorité `HIGH`, TTL 15 min, regroupés (`collapse_key`) si le téléphone est hors ligne : seul le dernier écho est livré, et un écho arrivé plus de 5 minutes après son envoi est enregistré sans faire vibrer.

## Sécurité et vie privée

- Chaque téléphone a **son propre secret** (32 octets aléatoires) ; le relais n'en stocke que l'empreinte SHA-256 et compare en temps constant.
- Codes de 6 caractères sur un alphabet de 32 symboles (sans 0/O ni 1/I), valables 10 min, tentatives limitées à 10/min par IP.
- Côté téléphone, `pairId` et secret sont dans des **EncryptedSharedPreferences** (clé du Keystore Android) ; sauvegarde et transfert d'appareil désactivés.
- Aucune clé ni `google-services.json` dans le dépôt : secrets GitHub (APK) et `wrangler secret` (relais).
- Pas de compte, pas d'analytics. Le contenu d'un écho (emoji, motif, heure) transite par Cloudflare et Google (FCM) en TLS, mais n'est pas chiffré de bout en bout.

## Limites connues

- Le mode « Silence total » d'Android coupe aussi les alarmes : aucun écho ne vibre dans ce mode.
- L'offre gratuite de Cloudflare KV permet 1 000 écritures/jour : largement assez pour deux personnes (un envoi d'écho ne fait aucune écriture KV).
- `EncryptedSharedPreferences` est dépréciée par androidx mais reste fonctionnelle ; `FirebaseMessaging.getToken()` est dépréciée dans firebase-messaging 26 au profit d'une API d'enregistrement encore optionnelle (`register()` / `onRegistered`, déjà gérée côté service).

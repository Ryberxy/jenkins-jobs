# 📦 Generación de Jobs Multibranch con Job DSL (Prod & Sandbox)

Este repositorio contiene dos scripts **Job DSL** para **crear automáticamente** Jobs *Multibranch Pipeline* en Jenkins a partir de ficheros YAML:

* `generateJobs.groovy` → **Producción** (rama(s) controladas).
* `generateJobsDev.groovy` → **Desarrollo/Sandbox** (por carpeta aplica una **Folder Library** con la **rama de la shared library** a probar).

Además, los pipelines usan **Config File Provider** para cargar el Jenkinsfile (prod y dev), permitiendo mantener los Jenkinsfiles en Jenkins y no en cada repo de aplicación.

---
# Generación de Jobs Multibranch (Prod & Sandbox) con Job DSL

Este repo incluye dos **seeds Job DSL** para que Jenkins genere Jobs **multibranch** a partir de YAMLs:

- `generateJobs.groovy` → **Producción** (descubre ramas controladas, usa `Jenkinsfile`).
- `generateJobsDev.groovy` → **Sandbox/Desarrollo** (aplica **Folder Library por carpeta** con la **rama** de la shared library a probar y usa `Jenkinsfile-DEV`).

---

## 🧭 Diagrama de alto nivel

```text
                            ┌──────────────────────────────┐
                            │            Jenkins           │
                            └──────────────┬───────────────┘
                                           │
                               (A) Seed PRODUCCIÓN
                               generateJobs.groovy
                                           │
                              lee /jobs/*.yaml (prod)
                                           │
                         ┌─────────────────▼──────────────────┐
                         │  Crea carpetas y Multibranch       │
                         │  - Ramas: integration,certification│
                         │  - Jenkinsfile (Config File: PROD) │
                         └─────────────────┬──────────────────┘
                                           │
                                      Builds PROD
                                           │
                        usa shared library global implícita (main)
                                           │
──────────────────────────────────────────────────────────────────────────────
                                           │
                               (B) Seed SANDBOX/DEV
                               generateJobsDev.groovy
                                           │
                              lee /jobs-dev/*.yaml (dev)
                                           │
                  ┌─────────────────────────▼────────────────────────┐
                  │  Crea carpetas destino (p.ej. PDU/Orq/...)       │
                  │  En la carpeta FINAL aplica Folder Library       │
                  │   - name = pipeline.name                         │
                  │   - branch = pipeline.branch                     │
                  │   - implicit / allowOverride / includeChangeSet  │
                  │   - repo / credentialsId (opcional)              │
                  └─────────────────────────┬────────────────────────┘
                                            │
                                Crea Multibranch DEV
                                - Ramas: integration, certification,
                                  feature/*, hotfix/*, release/*
                                - Jenkinsfile-DEV (Config File)
                                            │
                                      Builds DEV
                                            │
                     usan la shared library “trusted” por carpeta
                     (rama indicada en pipeline.branch del YAML)
```

## 🧩 Requisitos previos en Jenkins

Instala/activa estos plugins:

* **Job DSL**
* **Pipeline** (+ *Pipeline: Multibranch*, *Branch API*)
* **Git** (SCM)
* **Config File Provider**
* (Recomendado) **Script Security** habilitado (por defecto)

Configura también:

* Credencial Git por defecto: `gitlab-token-xxxxxx` (puede cambiarse dentro del YAML de cada job).
* Config Files con IDs:

  * Producción → `Jenkinsfile`
  * Desarrollo → `Jenkinsfile-DEV`

> Los Jenkinsfiles pueden ser **minimalistas**: normalmente sólo llaman a tu entrypoint de la shared library, por ejemplo:
>
> ```groovy
> // Jenkinsfile (PROD)
> dirayaPipeline()
>
> // Jenkinsfile-DEV (SANDBOX)
> // Confía en la Folder Library que aplica el generador de Jobs en la carpeta
> sasPipelineDev()
> ```

---

## 📁 Estructura del repositorio

```
.
├─ jobs/                  # YAMLs de PRODUCCIÓN
│  ├─ PDU_Operacional_svcA.yaml
│  └─ ...
├─ jobs-dev/              # YAMLs de DESARROLLO / SANDBOX
│  ├─ Sandbox_Pipeline_integracionSona_svcB.yaml
│  └─ ...
├─ generateJobs.groovy        # generador de Jobs PRODUCCIÓN
└─ generateJobsDev.groovy     # generador de Jobs SANDBOX (configura Folder Library por carpeta)
```

---

## 🧠 Cómo nombrar los ficheros YAML (contrato)

El **nombre del fichero** define la **jerarquía de carpetas** y el **nombre del job**:

```
<AAA>_<BBB>_..._<Job>.yaml
```

* Todo lo anterior al último `_` → **carpetas** (se crean en Jenkins).
* El último segmento → **nombre del job**.
* Ejemplos:

  * `PDU_Operacional_svcA.yaml` → carpetas `PDU/Operacional/` + job `svcA`
  * `EquipoX_API_Pedidos.yaml` → `EquipoX/API/` + job `Pedidos`

> ⚠️ Deben existir **al menos 2 segmentos** (carpetas + job). Si no, el generador de Jobs **omite** el fichero.

---

## 🧾 Formato de los YAML

### 1) YAML mínimo (común a PROD & DEV)

```yaml
default:
  git:
    url: https://gitlab/mi-grupo/mi-repo.git     # OBLIGATORIO
    credentialsId: gitlab-token-xxxxx         # Opcional (si falta, usa el por defecto)
```

* `default.git.url` es **obligatorio**. Si no está, el generador de Jobs **no** crea el job.
* `credentialsId` es **opcional**; si se omite, se usa el **por defecto** del generador de Jobs.

### 2) Campos extra para **SANDBOX** (solo `generateJobsDev.groovy`)

En *desarrollo*, además puedes indicar la **shared library** a usar en **esa carpeta** (Folder Library), con el bloque `pipeline:`:

```yaml
pipeline:
  name: sas-pipeline-library            # OBLIGATORIO (nombre lógico de la lib)
  branch: develop                       # OBLIGATORIO (rama por defecto en esa carpeta)
  loadImplicit: true                    # Opcional (default: true)
  allowVersionOverride: false           # Opcional (default: false)
  includeChangeSet: true                # Opcional (default: true)
  repo: https://.../jenkins-shared-library.git    # Opcional (default del generador de Jobs)
  credentialsId: gitlab-token-xxxxx            # Opcional (default del generador de Jobs)
```

* A esta carpeta **Folder Library**, se le aplica la shared library definida en el yaml **en la carpeta final** donde cuelga el job.
* Los jobs bajo esa carpeta **usarán esa shared library** (rama y repo) por defecto.
* Si `allowVersionOverride: true`, se permite usar `@Library('nombre@otra-rama')` en el `Jenkinsfile-DEV` para pruebas puntuales.

---

## ⚙️ ¿Qué crea cada generador de Jobs?

### 🔵 `generateJobs.groovy` (PRODUCCIÓN)

* Lee **todos** los `.yaml` de `jobs/`.
* Para cada YAML:

  * Crea la **jerarquía de carpetas**.
  * Crea un **Multibranch Pipeline** (nombre = último segmento del fichero).
  * SCM:

    * `remote` = `default.git.url`
    * `credentialsId` = `default.git.credentialsId` (o el default del generador de Jobs)
  * Descubre **sólo** ramas: `integration` y `certification`.
  * Usa `Config File Provider` → **`scriptId 'Jenkinsfile'`**.

### 🟣 `generateJobsDev.groovy` (SANDBOX / DESARROLLO)

* Lee **todos** los `.yaml` de `jobs-dev/`.
* Para cada YAML:

  * Crea la **jerarquía de carpetas** (tal cual; tú decides si cuelga bajo `sandbox/` o no, según tu naming — en el script actual no prefiere `sandbox/` automáticamente).
  * **En la carpeta final** aplica la **Folder Library** con la configuración que contiene `pipeline.*`.
  * Crea el **Multibranch Pipeline** en esa carpeta.
  * SCM:

    * `remote` = `default.git.url`
    * `credentialsId` = `default.git.credentialsId` (o el default)
  * Descubre ramas: `integration`, `certification`, `feature/*`, `hotfix/*`, `release/*`.
  * Usa `Config File Provider` → **`scriptId 'Jenkinsfile-DEV'`**.

> Si quieres forzar que *todo* lo de dev cuelgue de `sandbox/...`, nómbralos así:
> `Sandbox_PDU_Orquestacion_svcB.yaml` → carpetas `Sandbox/PDU/Orquestacion/` + job `svcB`.

---

## ▶️ Cómo ejecutar los generador de Jobss

1. Crea un **generador de Jobs** (estilo “Freestyle”) que ejecute el Groovy correspondiente añadiendo el paso Process DSL:

   * **Producción** → `generateJobs.groovy`
   * **Desarrollo** → `generateJobsDev.groovy`

2. Ejecuta el generador de Jobs después de añadir/modificar YAMLs.

3. Comprueba en Jenkins que:

   * Los jobs de **prod** aparecen en su jerarquía.
   * Los jobs de **dev** aparecen en su jerarquía y **la carpeta final** tiene aplicada la **Folder Library** con el `pipeline.branch` indicado.

---

## 🧪 Ejemplos

### PROD (`jobs/PDU_Operacional_svcA.yaml`)

```yaml
default:
  git:
    url: https://gitlab/mi-grupo/svcA.git
    credentialsId: gitlab-token-xxxxx
```

→ Crea `PDU/Operacional/svcA` (Multibranch) con `Jenkinsfile`, ramas `integration|certification`.

---

### DEV (`jobs-dev/PDU_Orquestacion_svcB.yaml`)

```yaml
default:
  git:
    url: https://gitlab/mi-grupo/svcB.git

pipeline:
  name: sas-pipeline-library
  branch: feature/probar-nueva-funcionalidad
  loadImplicit: true
  allowVersionOverride: false
  includeChangeSet: true
  # repo/credentialsId: opcionales (usa defaults)
```

→ Crea `PDU/Orquestacion/svcB` (Multibranch) con `Jenkinsfile-DEV`, descubre `integration|certification|feature/*|hotfix/*|release/*`.
**En la carpeta `PDU/Orquestacion`** aplica **Folder Library** `sas-pipeline-library@feature/probar-nueva-funcionalidad`.

---

## 🔐 Seguridad & “trusted code”

* La **Pipeline o shared library** aplicada por carpeta hace que la shared library se ejecute como **trusted** (evita problemas del *sandbox*, como `RejectedAccessException` por métodos en clases).
* Evita, en lo posible, cargar libraries con:

  ```groovy
  library identifier: 'nombre@rama', retriever: modernSCM(...)
  ```

  porque esa carga suele ir a **sandbox** y requerirá *Script Approval*.
* Si necesitas override puntual, usa `allowVersionOverride: true` y `@Library('nombre@rama') _` en el Jenkinsfile **trusted**.

---

## 🛠️ Troubleshooting

**1) “No se encontraron YAMLs…”**
Asegúrate de que los ficheros están en `jobs/` (prod) o `jobs-dev/` (dev), con extensión `.yaml`/`.yml`.

**2) “falta 'default.git.url' → NO se crea el job.”**
Es obligatorio. Añádelo al YAML.

**3) “Se esperan al menos 2 segmentos (AAA-JOB.yaml)”**
Renombra el fichero para cumplir el contrato `<AAA>_<Job>.yaml` (o más carpetas si quieres).

**4) “Ambiguous library resource … entre … y …”**
Estás cargando **dos** libraries con el mismo nombre de recurso.
Soluciones:

* Usa **una sola** (global o folder) por carpeta.
* O aplica **namespacing** en `resources/` dentro de tus libraries.

**5) “MissingMethodException … método no existe en esta versión de la lib”**
Se mezclan dos versiones de la shared library a la vez.
Soluciones:

* Una sola **library por carpeta** (la Folder Library del generador de Jobs).
* Evitar mezclar **implícita global** con cargas dinámicas en el mismo job.

---

## 🧹 Mantenimiento / Cambios de comportamiento

* **Cambiar la rama por defecto** de la shared library en una carpeta:

  * Edita el YAML (campo `pipeline.branch`) y vuelve a ejecutar el generador de Jobs **dev**.
* **Habilitar override por rama** desde Jenkinsfile:

  * Pon `pipeline.allowVersionOverride: true` en el YAML y, si lo necesitas, usa `@Library('nombre@rama') _` en el `Jenkinsfile-DEV`.
* **Añadir nuevas ramas a descubrir**:

  * Modifica el `regex(...)` en `generateJobsDev.groovy` (sección `traits/headRegexFilter`).

---

## 📎 Anexos: plantillas de Jenkinsfile (Config File Provider)

**`Jenkinsfile` (PROD)**

```groovy
dirayaPipeline()
```

**`Jenkinsfile-DEV` (SANDBOX)**

```groovy
sasPipelioneDev()
```
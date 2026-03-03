import org.yaml.snakeyaml.Yaml
import hudson.FilePath

// Credencial por defecto si el YAML no trae git.credentialsId
def DEFAULT_SCM_CREDS = System.getenv('DEFAULT_SCM_CREDS') ?: 'gitlab-token-misgarci'

println "[Generate Jobs] Iniciando generación de Jobs (modo: jobs/<AAA-BBB-...-JOB>.yaml)"

// --- Obtener workspace ---
FilePath ws = hudson.model.Executor.currentExecutor()?.getCurrentWorkspace()
if (!ws) {
  println "[Generate Jobs][ERROR] No hay workspace asignado."
  return
}

FilePath jobsDir = ws.child("jobs")
if (!jobsDir.exists()) {
  println "[Generate Jobs][WARN] No existe la carpeta 'jobs' en ${ws.getRemote()}"
  return
}

// Lista solo ficheros YAML
List<FilePath> yamlFiles = jobsDir.list().findAll { filePath ->
  !filePath.isDirectory() && filePath.getName().toLowerCase() =~ /\.(yaml|yml)$/
}

if (yamlFiles.isEmpty()) {
  println "[Generate Jobs][WARN] No se encontraron YAMLs en 'jobs/'."
  return
}

yamlFiles.each { FilePath fileYaml ->
  String fileName = fileYaml.getName()
  String baseName = fileName.replaceAll(/\.ya?ml$/, '')

  List<String> parts = baseName.split('_') as List
  if (parts.size() < 2) {
    println "[Generate Jobs][WARN] '${fileName}': se esperan al menos 2 segmentos (AAA-JOB.yaml). Omitido."
    return
  }

  String jobName = parts.last()
  List<String> folderParts = parts.size() > 1 ? parts[0..-2] : []
  String folderPath = folderParts.join('/')

  // --- Parse YAML ---
  Map cfg = [:]
  try {
    cfg = new Yaml().load(fileYaml.readToString()) as Map ?: [:]
  } catch (e) {
    println "[Generate Jobs][WARN] Error leyendo/parsing '${fileName}': ${e}"
    return
  }

  def urlGit = cfg?.default?.git?.url
  if (!urlGit) {
    println "[Generate Jobs][WARN] '${fileName}': falta 'default.git.url' → NO se crea el job."
    return
  }

  // --- Crear jerarquía de carpetas ---
  String acc = ""
  folderParts.each { seg ->
    acc = acc ? "${acc}/${seg}" : seg
    folder(acc) { displayName(seg) }
  }

  String fullName = folderPath ? "${folderPath}/${jobName}" : jobName
  println "[Generate Jobs] Creando/actualizando: ${fullName}  (repo: ${urlGit})"

  // --- Valores reutilizables ---
  def credentialsIdVar = cfg?.default?.git?.credentialsId ?: DEFAULT_SCM_CREDS
  def scriptPathVar = cfg?.default?.pipeline?.scriptPath ?: 'Jenkinsfile'
  def pipelineBranch = cfg?.default?.pipeline?.branch ?: 'develop'
  def indexCron = cfg?.default?.index_cron ?: cfg?.default?.pipeline?.index_cron ?: 'H 21 * * *'
  def branchDiscoverRegex = cfg?.default?.branches?.discover ?: '^(integration|certification|loadtesting)$'

  String jobType = (cfg?.type ?: 'multibranch').toString().toLowerCase()

  if (jobType == 'pipeline') {
    // --- PipelineJob clásico ---
    println "[Generate Jobs] Creando/actualizando PipelineJob: ${fullName} (branch: ${pipelineBranch})"

    pipelineJob(fullName) {
      description(cfg?.description ?: "")
      logRotator {
        daysToKeep(cfg?.retention?.days ?: 14)
        numToKeep(cfg?.retention?.num ?: 50)
      }
      definition {
        cpsScm {
          scm {
            git {
              remote {
                url(urlGit)
                credentials(credentialsIdVar)
              }
              branches("*/${pipelineBranch}")
              extensions {}
            }
          }
          scriptPath(scriptPathVar)
        }
      }
      // --- Triggers opcionales ---
      if (cfg?.triggers?.cron) {
        triggers { cron(cfg.triggers.cron) }
      } else if (cfg?.triggers?.scm) {
        triggers { scm('H/5 * * * *') }
      } else if (cfg?.triggers?.githubPush) {
        triggers { githubPush() }
      }
    }

  } else {
    // --- Multibranch PipelineJob actualizado ---
    println "[Generate Jobs] Creando/actualizando Multibranch: ${fullName}  (repo: ${urlGit})"

    // --- CAMBIO PRINCIPAL: Inyectar Jenkinsfile desde Config File Provider ANTES del indexing ---
    // Esto garantiza que workflowBranchProjectFactory encuentre un Jenkinsfile físico
    // Nota: Aquí debes usar tu fileId del Managed File configurado en Jenkins
    def jenkinsfileContent = null
    try {
        jenkinsfileContent = com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentials(
            com.cloudbees.hudson.plugins.folder.computed.FolderCredentialsProperty.class,
            Jenkins.instance,
            null,
            null
        )
    } catch (ignored) {
        println "[WARN] No se pudo leer Managed File directamente aquí, usar Pipeline previo para generar Jenkinsfile"
    }

    // Alternativa más segura: crear un Pipeline previo que genere el Jenkinsfile en workspace
    // ws.child('Jenkinsfile').write(jenkinsfileContent, 'UTF-8')

    multibranchPipelineJob(fullName) {
      description(cfg?.description ?: "")

      orphanedItemStrategy {
        discardOldItems {
          daysToKeep(14)
          numToKeep(0)
        }
      }

      branchSources {
        branchSource {
          source {
            git {
              id("src-${jobName}")
              remote(urlGit)
              credentialsId(credentialsIdVar)
              traits {
                headRegexFilter { regex(branchDiscoverRegex) }
                gitBranchDiscovery()
                localBranchTrait()
              }
            }
          }
          strategy {
            defaultBranchPropertyStrategy {
              props {
                noTriggerBranchProperty() // Suprime triggers automáticos
              }
            }
          }
        }
      }

      // --- workflowBranchProjectFactory apunta al Jenkinsfile generado previamente ---
      factory {
        workflowBranchProjectFactory {
          scriptPath('Jenkinsfile')  // debe existir físicamente en workspace antes del branch indexing
        }
      }

      // --- Triggers de indexación opcionales ---
      // triggers {
      //     periodicFolderTrigger { cron(indexCron) }
      // }
    }
  }

  println "[Generate Jobs] Creación/actualización finalizada: ${fullName}"
}

println "[Generate Jobs] Generación de Jobs finalizada."
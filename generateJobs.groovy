import org.yaml.snakeyaml.Yaml
import hudson.FilePath

// Credencial por defecto si el YAML no trae git.credentialsId
def DEFAULT_SCM_CREDS = System.getenv('DEFAULT_SCM_CREDS') ?: 'gitlab-token-misgarci'

println "[Generate Jobs] Iniciando generación de Jobs (modo: jobs/<AAA-BBB-...-JOB>.yaml)"

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

// Lista sólo ficheros .yaml/.yml dentro del direction /jobs del proyecto
List<FilePath> yamlFiles = jobsDir.list().findAll { filePath ->
  !filePath.isDirectory() && filePath.getName().toLowerCase() =~ /\.(yaml|yml)$/
}

if (yamlFiles.isEmpty()) {
  println "[Generate Jobs][WARN] No se encontraron YAMLs en 'jobs/'."
  return
}

yamlFiles.each { FilePath fileYaml ->
  String fileName = fileYaml.getName()                   // "PDU_Operacional_microservicio.yaml"
  String baseName = fileName.replaceAll(/\.ya?ml$/, '') // "PDU_Operacional_microservicio"

  // Partes separadas por '_' ; 
  //  - Todas menos la última = carpetas
  //  - Última parte = nombre del job
  // Nota: Deben de tener al menos AAA-BBB-fichero.yaml
  List<String> parts = baseName.split('_') as List
  if (parts.size() < 2) {
    println "[Generate Jobs][WARN] '${fileName}': se esperan al menos 2 segmentos (AAA-JOB.yaml). Omitido."
    return
  }

  String jobName = parts.last()
  List<String> folderParts = parts.size() > 1 ? parts[0..-2] : []
  String folderPath = folderParts.join('/')

  // Cargamos el YAML
  Map cfg = [:]
  String content
  try {
    content = fileYaml.readToString()
    cfg = new Yaml().load(content) as Map ?: [:]
  } catch (e) {
    println "[Generate Jobs][WARN] Error leyendo/parsing '${fileName}': ${e}"
    return
  }

  // Validación mínima: git.url
  def urlGit = cfg?.default?.git?.url
  if (!urlGit) {
    println "[Generate Jobs][WARN] '${fileName}': falta 'default.git.url' → NO se crea el job."
    return
  }

  // Crear jerarquía de carpetas según las partes (AAA/BBB/…)
  String acc = ""
  folderParts.each { seg ->
    acc = acc ? "${acc}/${seg}" : seg
    folder(acc) { displayName(seg) }
  }

  String fullName = folderPath ? "${folderPath}/${jobName}" : jobName
  println "[Generate Jobs] Creando/actualizando: ${fullName}  (repo: ${urlGit})"

  // --- CAMBIO PRINCIPAL: soportar tipo 'pipeline' en YAML (por defecto multibranch) ---
  // Si en el YAML defines: type: pipeline  -> creará pipelineJob (apunta a una rama)
  // Si no o type: multibranch -> creará multibranchPipelineJob (comportamiento original)
  String jobType = (cfg?.type ?: 'multibranch').toString().toLowerCase()

  // valores reutilizables
  def credentialsIdVar = cfg?.default?.git?.credentialsId ?: DEFAULT_SCM_CREDS
  def scriptPathVar = cfg?.default?.pipeline?.scriptPath ?: 'Jenkinsfile'
  def pipelineBranch = cfg?.default?.pipeline?.branch ?: 'develop'
  def indexCron = cfg?.default?.index_cron ?: cfg?.default?.pipeline?.index_cron ?: 'H 21 * * *'
  def branchDiscoverRegex = cfg?.default?.branches?.discover ?: '^(integration|certification|loadtesting)$'

  if (jobType == 'pipeline') {
    // Crear pipelineJob (clásico) que lee Jenkinsfile desde el repo en una rama concreta
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
      // triggers opcionales si están en YAML
      if (cfg?.triggers?.cron) {
        triggers {
          cron(cfg.triggers.cron)
        }
      } else if (cfg?.triggers?.scm) {
        // ejemplo: si defines triggers.scm: true -> utiliza pollSCM (opcional)
        triggers {
          scm('H/5 * * * *')
        }
      }
    }

  } else {
    // Comportamiento original: Multibranch
    println "[Generate Jobs] Creando/actualizando Multibranch: ${fullName}  (repo: ${urlGit})"

    multibranchPipelineJob(fullName) {
      orphanedItemStrategy {
        discardOldItems {
          daysToKeep(14)
          numToKeep(0)
        }
      }

      // añadido: indexación periódica del folder si se desea (mínimo cambio)
      // triggers {
      //   periodicFolderTrigger {
      //     cron(indexCron)
      //   }
      // }

      branchSources {
        branchSource {
          source {
            git {
              id("src-${jobName}")
              remote(urlGit)
              credentialsId(credentialsIdVar)
              traits {
                // descubre SOLO estas ramas (puedes cambiar el regex desde YAML)
                headRegexFilter { regex('^(integration|certification|loadtesting)$') }
                gitBranchDiscovery()
                localBranchTrait()
                // gitTagDiscovery()
              }
            }
          }
          // >>> suprime disparos automáticos por indexación/eventos SCM
          strategy {
            defaultBranchPropertyStrategy {
              props {
                noTriggerBranchProperty()  // "Suppress automatic SCM triggering"
              }
            }
          }
        }
      }
      factory {
        pipelineBranchDefaultsProjectFactory {
          // Jenkinsfile gestionado por Config File Provider con:  sasPipeline()
          scriptId 'Jenkinsfile'
          useSandbox true
        }
      }
    }
  }

  println "[Generate Jobs] Creación/actualización finalizada: ${fullName}"
}

println "[Generate Jobs] Generación de Jobs finalizada."

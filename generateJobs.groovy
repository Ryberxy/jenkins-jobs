import org.yaml.snakeyaml.Yaml
import hudson.FilePath

// Credencial por defecto si el YAML no trae git.credentialsId
def DEFAULT_SCM_CREDS = System.getenv('DEFAULT_SCM_CREDS') ?: 'gitlab-token-misgarci'

println "[Generate Jobs] Iniciando generación de Jobs (modo: jobs/<AAA-BBB-...-JOB>.yaml)"

// Obtener workspace del seed-job
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

// Lista de YAMLs en jobs/
List<FilePath> yamlFiles = jobsDir.list().findAll { filePath ->
  !filePath.isDirectory() && filePath.getName().toLowerCase() =~ /\.(yaml|yml)$/
}

if (yamlFiles.isEmpty()) {
  println "[Generate Jobs][WARN] No se encontraron YAMLs en 'jobs/'."
  return
}

// Iterar sobre cada YAML
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

  // Leer YAML
  Map cfg = [:]
  try {
    cfg = new Yaml().load(fileYaml.readToString()) as Map ?: [:]
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

  // Crear jerarquía de carpetas
  String acc = ""
  folderParts.each { seg ->
    acc = acc ? "${acc}/${seg}" : seg
    folder(acc) { displayName(seg) }
  }

  String fullName = folderPath ? "${folderPath}/${jobName}" : jobName
  println "[Generate Jobs] Creando/actualizando Multibranch: ${fullName}  (repo: ${urlGit})"

  // Valores reutilizables
  def credentialsIdVar = cfg?.default?.git?.credentialsId ?: DEFAULT_SCM_CREDS
  def scriptPathVar = cfg?.default?.pipeline?.scriptPath ?: 'Jenkinsfile'
  def branchDiscoverRegex = cfg?.default?.branches?.discover ?: '^(integration|certification|loadtesting)$'
  def indexCron = cfg?.default?.index_cron ?: cfg?.default?.pipeline?.index_cron ?: 'H/5 * * * *' // indexa cada 5 min por defecto

  // --- Crear Multibranch Pipeline con triggers automáticos por GitHub push ---
  multibranchPipelineJob(fullName) {

    description(cfg?.description ?: "")
    
    orphanedItemStrategy {
      discardOldItems {
        daysToKeep(cfg?.retention?.days ?: 14)
        numToKeep(cfg?.retention?.num ?: 0)
      }
    }

    // Triggers: indexación periódica para detectar ramas nuevas
    triggers {
        periodicFolderTrigger {
            interval('1')  // en minutos, intervalo mínimo para reindexar el Multibranch
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
              // Descubre solo ramas que coincidan con el regex
              headRegexFilter { regex(branchDiscoverRegex) }
              gitBranchDiscovery()
              localBranchTrait()
            }
          }
        }
        // --- QUITADO noTriggerBranchProperty para permitir builds automáticos ---
        strategy {
          defaultBranchPropertyStrategy {
            props {
              // ahora sí permitirá disparos al recibir webhook
            }
          }
        }
      }
    }

    factory {
      workflowBranchProjectFactory {
        scriptPath(scriptPathVar) // Jenkinsfile en cada rama
      }
    }

    println "[Generate Jobs] Multibranch configurado para disparos automáticos en push."
  }

  println "[Generate Jobs] Creación/actualización finalizada: ${fullName}"
}

println "[Generate Jobs] Generación de Jobs finalizada."
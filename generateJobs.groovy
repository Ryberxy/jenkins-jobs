import org.yaml.snakeyaml.Yaml
import hudson.FilePath

// Credencial por defecto si el YAML no trae git.credentialsId
def DEFAULT_SCM_CREDS = System.getenv('DEFAULT_SCM_CREDS') ?: 'gitlab-token-misgarci'

println "[Generate Jobs] Iniciando generación de Jobs (modo jobs/<AAA-BBB-...-JOB>.yaml)"

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

// Lista de ficheros YAML en /jobs
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

    // Separar carpetas y nombre del job
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

    // Validación mínima
    def urlGit = cfg?.default?.git?.url
    if (!urlGit) {
        println "[Generate Jobs][WARN] '${fileName}': falta 'default.git.url' → NO se crea el job."
        return
    }

    // Crear carpetas en Jenkins
    String acc = ""
    folderParts.each { seg ->
        acc = acc ? "${acc}/${seg}" : seg
        folder(acc) { displayName(seg) }
    }

    String fullName = folderPath ? "${folderPath}/${jobName}" : jobName
    println "[Generate Jobs] Creando/actualizando Multibranch: ${fullName} (repo: ${urlGit})"

    // Valores reutilizables
    def credentialsIdVar = cfg?.default?.git?.credentialsId ?: DEFAULT_SCM_CREDS
    def branchDiscoverRegex = cfg?.default?.branches?.discover ?: '.*'  // todas las ramas
    def scriptPathVar = cfg?.default?.pipeline?.scriptPath ?: 'Jenkinsfile'

    // Crear Multibranch Pipeline
    multibranchPipelineJob(fullName) {
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
                            gitBranchDiscovery()             // descubrir ramas automáticamente
                            headRegexFilter { regex(branchDiscoverRegex) }  // opcional filtro por regex
                            localBranchTrait()
                        }
                    }
                }
                strategy {
                    defaultBranchPropertyStrategy {
                        props {
                            noTriggerBranchProperty()  // evita triggers automáticos internos
                        }
                    }
                }
            }
        }

        factory {
            workflowBranchProjectFactory {
                scriptPath(scriptPathVar)  // Jenkinsfile en cada rama
            }
        }

        // NOTA: no se usa periodicFolderTrigger, webhook GitHub se encargará del trigger
    }

    println "[Generate Jobs] Creación/actualización finalizada: ${fullName}"
}

println "[Generate Jobs] Generación de Jobs finalizada."
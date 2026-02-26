import org.yaml.snakeyaml.Yaml
import hudson.FilePath

// ====== Entorno de pruebas ======
//def DEFAULT_SCM_CREDS = 'gitlab-token-misgarci'      // credencial git por defecto
//def CFGFILE_SCRIPT_ID = 'Jenkinsfile-DEV'            // ID del Config File Provider para DEV
def devDirName = 'jobs-dev'                   // carpeta con los YAMLs de pruebas

// Valores por defecto en caso de que el YAML no aporte repo/credenciales/branch
//def LIB_DEFAULT_NAME   = 'sas-pipeline-library'
//def LIB_DEFAULT_REPO   = 'https://umane.emeal.nttdata.com/git/SWFPRESUNISWFPRESCRI/tools/devops/swfpresuni-tools-devops-jenkins-shared-library.git'
//def LIB_DEFAULT_CREDS  = DEFAULT_SCM_CREDS
//def LIB_DEFAULT_BRANCH = 'develop'

println "[Generate Job DEV] Iniciando (dev dir: ${devDirName}/)"

FilePath workspace = hudson.model.Executor.currentExecutor()?.getCurrentWorkspace()
if (!workspace) {
    println "[Generate Job DEV][ERROR] No hay workspace asignado."
    return
}

// Se procesan todos los YAMLs de jobs-dev/
FilePath devDir = workspace.child(devDirName)
if (!devDir.exists()) {
    println "[Generate Job DEV][WARN] No existe '${devDirName}/'. No se generan Jobs para Dev."
    return
}

// Se procesan todos los YAML de devDirName/
processDevDir(devDir)

println '[Generate Job DEV] Finalizado.'

// ==================================
// Funciones del generador de Jobs
// ==================================

void processDevDir(FilePath baseDir) {
    List<FilePath> yamlFiles = baseDir.list().findAll { filePath ->
        !filePath.isDirectory() && filePath.getName().toLowerCase() =~ /\.(yaml|yml)$/
    }

    if (yamlFiles.isEmpty()) {
        println "[Generate Job DEV][WARN] No hay YAMLs en '${baseDir.getRemote()}'"
        return
    }

    yamlFiles.each { FilePath fileYaml ->
        String fileName = fileYaml.getName()

        // Carga YAML del Job
        Map cfg = [:]
        try {
            cfg = new Yaml().load(fileYaml.readToString()) as Map ?: [:]
        } catch (e) {
            println "[Generate Job DEV][WARN] Error leyendo '${fileName}': ${e}"
            return
        }

        // Validación mínima necesaria
        String urlGit = cfg?.default?.git?.url ?: ''
        if (!urlGit) {
            println "[Generate Job DEV][WARN] '${fileName}': falta 'default.git.url'. Se omite."
            return
        }

        //Credenciales para el git
        String appCreds = cfg?.default?.git?.credentialsId ?: 'gitlab-token-misgarci'

        // Ruta a partir del nombre del fichero: <AAA>_<BBB>_..._<Job>.yaml
        String baseName = fileName.replaceAll(/\.ya?ml$/, '')
        List<String> parts = baseName.split('_') as List
        if (parts.size() < 2) {
            println "[Generate Job DEV][WARN] '${fileName}': patrón inválido (<AAA>_<BBB>_..._<Job>.yaml). Se omite."
            return
        }

        String jobName = parts.last()
        List<String> folderParts = (parts.size() > 1) ? parts[0..-2] : []

        // Ruta final bajo sandbox/
        List<String> sandboxFolders = folderParts
        String folderPath = sandboxFolders.join('/')
        String fullName   = "${folderPath}/${jobName}"

        // === 1) Crear carpetas y configurar Folder Library en la carpeta FINAL ===
        Map pipelineCfg = (cfg?.pipeline ?: [:]) as Map
        configureFolderWithLibrary(sandboxFolders, pipelineCfg)

        // === 2) Crear el Multibranch dentro de esa carpeta ===
        println "[Generate Job DEV] Creando/actualizando: ${fullName} (repo: ${urlGit})"
        createMultibranch(fullName, urlGit, appCreds)
    }
}

/**
 * Crea la jerarquía de carpetas y en la última carpeta aplica la configuración de Folder Library
 * usando los campos del bloque 'pipeline' del YAML.
 *
 * YAML esperado (mínimo):
 *   pipeline:
 *     name: <nombre-lib>          (obligatorio)
 *     branch: <rama>              (obligatorio)
 *     loadImplicit: true|false    (opcional; default=true)
 *     allowVersionOverride: true|false (opcional; default=false)
 *     includeChangeSet: true|false     (opcional; default=true)
 *     repo: <url git de la lib>   (opcional; default LIB_DEFAULT_REPO)
 *     credentialsId: <id cred>    (opcional; default LIB_DEFAULT_CREDS)
 */
void configureFolderWithLibrary(List<String> folders, Map pipelineCfg) {
    if (!folders || folders.isEmpty()) return

    // Defaults de la lib (si el YAML no aporta)
    String libName              = pipelineCfg?.name ?: 'sas-pipeline-library'
    String libBranch            = pipelineCfg?.branch ?: 'develop'
    boolean loadImplicit        = (pipelineCfg?.loadImplicit as boolean) ?: true
    boolean allowOverride       = (pipelineCfg?.allowVersionOverride as boolean) ?: false
    boolean includeChangeSet    = (pipelineCfg?.includeChangeSet as boolean) ?: true
    String libRepo              = pipelineCfg?.repo ?: 'https://umane.emeal.nttdata.com/git/SWFPRESUNISWFPRESCRI/tools/devops/swfpresuni-tools-devops-jenkins-shared-library.git'
    String libCreds             = pipelineCfg?.credentialsId ?: 'gitlab-token-misgarci'

    String acc = ''
    folders.eachWithIndex { seg, idx ->
        acc = acc ? "${acc}/${seg}" : seg
        boolean isJob = (idx == folders.size() - 1)

        if (!isJob) {
            folder(acc) { displayName(seg) }
        } else {
            // Carpeta final: además de crearla, configuramos la Folder Library
            folder(acc) {
                displayName(seg)
                properties {
                    folderLibraries {
                        libraries {
                            libraryConfiguration {
                                name(libName)
                                defaultVersion(libBranch)
                                implicit(loadImplicit)
                                allowVersionOverride(allowOverride)
                                includeInChangesets(includeChangeSet)
                                retriever {
                                    modernSCM {
                                        scm {
                                            git {
                                                remote(libRepo)
                                                credentialsId(libCreds)
                                                traits { } // añade shallow si quieres
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            println "[Generate Job DEV] Folder Library aplicada en '${acc}': ${libName}@${libBranch} (implicit=${loadImplicit}, allowOverride=${allowOverride}, includeChangeSet=${includeChangeSet})"
        }
    }
}

void createMultibranch(String fullName, String urlGit, String credsId) {
    multibranchPipelineJob(fullName) {
        orphanedItemStrategy {
           discardOldItems { daysToKeep(14); numToKeep(0) }
        }
        branchSources {
            branchSource {
                source {
                    git {
                        id("src-${fullName.replaceAll('/', '-')}")
                        remote(urlGit)
                        credentialsId(credsId)
                        traits {
                            headRegexFilter { regex('^(integration|certification|feature/.+|hotfix/.+|release/.+)$') }
                            gitBranchDiscovery()
                            localBranchTrait()
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
                scriptId 'Jenkinsfile-DEV'
                useSandbox true
            }
        }
    }
}

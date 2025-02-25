@file:JvmName("API")
package `in`.specmatic.stub

import `in`.specmatic.core.log.consoleLog
import `in`.specmatic.core.*
import `in`.specmatic.core.git.SystemGit
import `in`.specmatic.core.log.logger
import `in`.specmatic.core.log.StringLog
import `in`.specmatic.core.pattern.ContractException
import `in`.specmatic.core.utilities.contractStubPaths
import `in`.specmatic.core.utilities.jsonStringToValueMap
import `in`.specmatic.core.value.StringValue
import `in`.specmatic.mock.*
import java.io.File
import java.time.Duration

// Used by stub client code
fun createStubFromContractAndData(contractGherkin: String, dataDirectory: String, host: String = "localhost", port: Int = 9000): ContractStub {
    val contractBehaviour = parseGherkinStringToFeature(contractGherkin)

    val mocks = (File(dataDirectory).listFiles()?.filter { it.name.endsWith(".json") } ?: emptyList()).map { file ->
        consoleLog(StringLog("Loading data from ${file.name}"))

        stringToMockScenario(StringValue(file.readText(Charsets.UTF_8)))
                .also {
                    contractBehaviour.matchingStub(it, ContractAndStubMismatchMessages)
                }
    }

    return HttpStub(contractBehaviour, mocks, host, port, ::consoleLog)
}

// Used by stub client code
fun allContractsFromDirectory(dirContainingContracts: String): List<String> =
    File(dirContainingContracts).listFiles()?.filter { it.extension == CONTRACT_EXTENSION }?.map { it.absolutePath } ?: emptyList()

fun createStub(host: String = "localhost", port: Int = 9000): ContractStub {
    val workingDirectory = WorkingDirectory()
    val contractPaths = contractStubPaths().map { it.path }
    val stubs = loadContractStubsFromImplicitPaths(contractPaths)
    val features = stubs.map { it.first }
    val expectations = contractInfoToHttpExpectations(stubs)

    return HttpStub(features, expectations, host, port, log = ::consoleLog, workingDirectory = workingDirectory)
}

// Used by stub client code
fun createStub(dataDirPaths: List<String>, host: String = "localhost", port: Int = 9000): ContractStub {
    val contractPaths = contractStubPaths().map { it.path }
    val contractInfo = loadContractStubsFromFiles(contractPaths, dataDirPaths)
    val features = contractInfo.map { it.first }
    val httpExpectations = contractInfoToHttpExpectations(contractInfo)

    return HttpStub(features, httpExpectations, host, port, ::consoleLog)
}

fun createStubFromContracts(contractPaths: List<String>, dataDirPaths: List<String>, host: String = "localhost", port: Int = 9000): ContractStub {
    val contractInfo = loadContractStubsFromFiles(contractPaths, dataDirPaths)
    val features = contractInfo.map { it.first }
    val httpExpectations = contractInfoToHttpExpectations(contractInfo)

    return HttpStub(features, httpExpectations, host, port, ::consoleLog)
}

fun loadContractStubsFromImplicitPaths(contractPaths: List<String>): List<Pair<Feature, List<ScenarioStub>>> {
    return contractPaths.map { File(it) }.flatMap { contractPath ->
        when {
            contractPath.isFile && contractPath.extension in CONTRACT_EXTENSIONS -> {
                consoleLog(StringLog("Loading $contractPath"))
                try {
                    val feature = parseContractFileToFeature(contractPath, CommandHook(HookName.stub_load_contract))

                    val implicitDataDirs = listOf(implicitContractDataDir(contractPath.path)).plus(if(customImplicitStubBase() != null) listOf(implicitContractDataDir(contractPath.path, customImplicitStubBase())) else emptyList()).sorted()

                    val stubData = when {
                        implicitDataDirs.any { it.isDirectory } -> {
                            implicitDataDirs.filter { it.isDirectory }.flatMap { implicitDataDir ->
                                consoleLog("Loading stub expectations from ${implicitDataDir.path}".prependIndent("  "))
                                logIgnoredFiles(implicitDataDir)

                                val stubDataFiles = filesInDir(implicitDataDir)?.toList()?.filter { it.extension == "json" }.orEmpty().sorted()
                                printDataFiles(stubDataFiles)

                                stubDataFiles.map {
                                    try {
                                        Pair(it.path, stringToMockScenario(StringValue(it.readText())))
                                    } catch(e: Throwable) {
                                        logger.log(e, "    Could not load stub file ${it.canonicalPath}")
                                        null
                                    }
                                }.filterNotNull()
                            }
                        }
                        else -> emptyList()
                    }

                    loadContractStubs(listOf(Pair(contractPath.path, feature)), stubData)
                } catch(e: Throwable) {
                    logger.log(e, "    Could not load ${contractPath.canonicalPath}")
                    emptyList()
                }
            }
            contractPath.isDirectory -> {
                loadContractStubsFromImplicitPaths(contractPath.listFiles()?.toList()?.map { it.absolutePath } ?: emptyList())
            }
            else -> emptyList()
        }
    }
}

private fun logIgnoredFiles(implicitDataDir: File) {
    val ignoredFiles = implicitDataDir.listFiles()?.toList()?.filter { it.extension != "json" }?.filter { it.isFile } ?: emptyList()
    if (ignoredFiles.isNotEmpty()) {
        consoleLog(StringLog("Ignoring the following files:".prependIndent("  ")))
        for (file in ignoredFiles) {
            consoleLog(StringLog(file.absolutePath.prependIndent("    ")))
        }
    }
}

fun loadContractStubsFromFiles(contractPaths: List<String>, dataDirPaths: List<String>): List<Pair<Feature, List<ScenarioStub>>> {
    val contactPathsString = contractPaths.joinToString(System.lineSeparator())
    consoleLog(StringLog("Loading the following contracts:${System.lineSeparator()}$contactPathsString"))
    consoleLog(StringLog(""))

    val dataDirFileList = allDirsInTree(dataDirPaths).sorted()

    val features = contractPaths.map { path ->
        Pair(path, parseContractFileToFeature(path, CommandHook(HookName.stub_load_contract)))
    }

    val dataFiles = dataDirFileList.flatMap {
        consoleLog(StringLog("Loading stub expectations from ${it.path}".prependIndent("  ")))
        logIgnoredFiles(it)
        it.listFiles()?.toList() ?: emptyList<File>()
    }.filter { it.extension == "json" }.sorted()
    printDataFiles(dataFiles)

    val mockData = dataFiles.mapNotNull {
        try {
            Pair(it.path, stringToMockScenario(StringValue(it.readText())))
        } catch (e: Throwable) {
            logger.log(e, "    Could not load stub file ${it.canonicalPath}")
            null
        }
    }

    return loadContractStubs(features, mockData)
}

private fun printDataFiles(dataFiles: List<File>) {
    if (dataFiles.isNotEmpty()) {
        val dataFilesString = dataFiles.joinToString(System.lineSeparator()) { it.path.prependIndent("  ") }
        consoleLog(StringLog("Reading the following stub files:${System.lineSeparator()}$dataFilesString".prependIndent("  ")))
    }
}

class StubMatchExceptionReport(val request: HttpRequest, val e: NoMatchingScenario) {
    fun withoutFluff(fluffLevel: Int): StubMatchExceptionReport {
        return StubMatchExceptionReport(request, e.withoutFluff(fluffLevel))
    }

    fun hasErrors(): Boolean {
        return e.results.hasResults()
    }

    val message: String
        get() = e.report(request)
}

data class StubMatchErrorReport(val exceptionReport: StubMatchExceptionReport, val contractFilePath: String) {
    fun withoutFluff(fluffLevel: Int): StubMatchErrorReport {
        return this.copy(exceptionReport = exceptionReport.withoutFluff(fluffLevel))
    }

    fun hasErrors(): Boolean {
        return exceptionReport.hasErrors()
    }
}
data class StubMatchResults(val feature: Feature?, val errorReport: StubMatchErrorReport?)

fun stubMatchErrorMessage(
    matchResults: List<StubMatchResults>,
    stubFile: String
): String {
    val matchResultsWithErrorReports = matchResults.mapNotNull { it.errorReport }

    val errorReports: List<StubMatchErrorReport> = matchResultsWithErrorReports.map {
        it.withoutFluff(0)
    }.filter {
        it.hasErrors()
    }.ifEmpty {
        matchResultsWithErrorReports.map {
            it.withoutFluff(1)
        }.filter {
            it.hasErrors()
        }
    }

    if(errorReports.isEmpty() || matchResults.isEmpty())
        return "$stubFile didn't match any of the contracts\n${matchResults.firstOrNull()?.errorReport?.exceptionReport?.request?.requestNotRecognized()?.prependIndent("  ")}".trim()



    return errorReports.map { (exceptionReport, contractFilePath) ->
        "$stubFile didn't match $contractFilePath${System.lineSeparator()}${
            exceptionReport.message.prependIndent(
                "  "
            )
        }"
    }.joinToString("${System.lineSeparator()}${System.lineSeparator()}")
}

fun loadContractStubs(features: List<Pair<String, Feature>>, stubData: List<Pair<String, ScenarioStub>>): List<Pair<Feature, List<ScenarioStub>>> {
    val contractInfoFromStubs: List<Pair<Feature, List<ScenarioStub>>> = stubData.mapNotNull { (stubFile, stub) ->
        val matchResults = features.map { (qontractFile, feature) ->
            try {
                feature.matchingStub(stub.request, stub.response, ContractAndStubMismatchMessages)
                StubMatchResults(feature, null)
            } catch (e: NoMatchingScenario) {
                StubMatchResults(null, StubMatchErrorReport(StubMatchExceptionReport(stub.request, e), qontractFile))
            }
        }

        when (val feature = matchResults.mapNotNull { it.feature }.firstOrNull()) {
            null -> {
                val errorMessage = stubMatchErrorMessage(matchResults, stubFile).prependIndent("  ")

                consoleLog(StringLog(errorMessage))

                null
            }
            else -> Pair(feature, stub)
        }
    }.groupBy { it.first }.mapValues { it.value.map { it.second } }.entries.map { Pair(it.key, it.value) }

    val stubbedFeatures = contractInfoFromStubs.map { it.first }
    val missingFeatures = features.map { it.second }.filter { it !in stubbedFeatures }

    return contractInfoFromStubs.plus(missingFeatures.map { Pair(it, emptyList()) })
}

fun allDirsInTree(dataDirPath: String): List<File> = allDirsInTree(listOf(dataDirPath))
fun allDirsInTree(dataDirPaths: List<String>): List<File> =
        dataDirPaths.map { File(it) }.filter {
            it.isDirectory
        }.flatMap {
            val fileList: List<File> = it.listFiles()?.toList()?.filterNotNull() ?: emptyList()
            pathToFileListRecursive(fileList).plus(it)
        }

private fun pathToFileListRecursive(dataDirFiles: List<File>): List<File> =
        dataDirFiles.filter {
            it.isDirectory
        }.map {
            val fileList: List<File> = it.listFiles()?.toList()?.filterNotNull() ?: emptyList()
            pathToFileListRecursive(fileList).plus(it)
        }.flatten()

private fun filesInDir(implicitDataDir: File): List<File>? {
    val files = implicitDataDir.listFiles()?.map {
        when {
            it.isDirectory -> {
                filesInDir(it) ?: emptyList()
            }
            it.isFile -> {
                listOf(it)
            }
            else -> {
                logger.debug("Could not recognise ${it.absolutePath}, ignoring it.")
                emptyList()
            }
        }
    }

    return files?.flatten()
}

// Used by stub client code
fun createStubFromContracts(contractPaths: List<String>, host: String = "localhost", port: Int = 9000): ContractStub {
    val defaultImplicitDirs: List<String> = implicitContractDataDirs(contractPaths)

    val completeList = if(customImplicitStubBase() != null) {
        defaultImplicitDirs.plus(implicitContractDataDirs(contractPaths, customImplicitStubBase()))
    } else
        defaultImplicitDirs

    return createStubFromContracts(contractPaths, completeList, host, port)
}

fun implicitContractDataDirs(contractPaths: List<String>, customBase: String? = null) =
        contractPaths.map { implicitContractDataDir(it, customBase).absolutePath }

fun customImplicitStubBase(): String? = System.getenv("SPECMATIC_CUSTOM_IMPLICIT_STUB_BASE") ?: System.getProperty("customImplicitStubBase")

fun implicitContractDataDir(contractPath: String, customBase: String? = null): File {
    val contractFile = File(contractPath)

    return if(customBase == null)
        File("${contractFile.absoluteFile.parent}/${contractFile.nameWithoutExtension}$DATA_DIR_SUFFIX")
    else {
        val gitRoot: String = File(SystemGit().inGitRootOf(contractPath).workingDirectory).canonicalPath
        val fullContractPath = File(contractPath).canonicalPath

        val relativeContractPath = File(fullContractPath).relativeTo(File(gitRoot))
        File(gitRoot).resolve(customBase).resolve(relativeContractPath).let {
            File("${it.parent}/${it.nameWithoutExtension}$DATA_DIR_SUFFIX")
        }
    }
}


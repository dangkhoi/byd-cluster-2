package com.byd.clusternav.offcar

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ExpansionTransportFenceTest {
    @TempDir
    lateinit var temp: Path

    private val root: Path get() = Path.of(System.getProperty("clusternav.root")).toAbsolutePath().normalize()

    @Test
    fun `all 29 expansion current paths remain exact authorized and materialized`() {
        val activeSource = classPaths(authorityRevisions().last(), "SOURCE_SEAL_INPUT").toSet()
        assertEquals(29, CURRENT_PATHS.size)
        assertEquals(CURRENT_PATHS.size, CURRENT_PATHS.distinct().size)
        CURRENT_PATHS.forEach { relative ->
            assertFalse(relative.startsWith('/') || relative.contains("..") || relative.contains('*'), relative)
            val path = root.resolve(relative).normalize()
            assertTrue(path.startsWith(root), relative)
            assertTrue(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), relative)
            assertTrue(relative in activeSource, "expansion CURRENT is not authorized: $relative")
        }

        val spec = Files.readString(root.resolve(SPEC_PATH))
        val currentBlock = requireNotNull(
            Regex("<h4>CURRENT X0–X5</h4><pre><code>(.*?)</code></pre>", RegexOption.DOT_MATCHES_ALL).find(spec),
        ).groupValues[1].lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        assertEquals(CURRENT_PATHS, currentBlock)
        assertEquals(SOURCE_ARTIFACT_PATHS, CURRENT_PATHS.filter { it in SOURCE_ARTIFACT_PATHS })

        val output = root.resolve(ExpansionPackRenderer.OUTPUT_DIRECTORY)
        val outputPaths = Files.list(output).use { stream ->
            stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .map { root.relativize(it).toString() }.toList().toSet()
        }
        assertEquals(CURRENT_PATHS.filter { it.startsWith("$OUTPUT_DIRECTORY/") }.toSet(), outputPaths)
        assertEquals(ExpansionPackRenderer.OUTPUT_NAMES, outputPaths.map { Path.of(it).fileName.toString() }.toSet())
    }

    @Test
    fun `boundary authority is canonical append only exact and independently hashed`() {
        val path = root.resolve(BOUNDARY_PATH)
        val bytes = Files.readAllBytes(path)
        val authority = X4Json.asObject(X4Json.parse(bytes))
        assertArrayEquals(bytes, X4Json.canonical(authority))
        assertEquals(setOf("activeRevision", "revisions", "schemaId", "selfSha256"), authority.keys)
        assertEquals(2L, authority["activeRevision"])
        assertEquals("clusternav.offcar-boundary-revisions/v1", authority["schemaId"])
        val rootProjection = authority.toMutableMap().also { it.remove("selfSha256") }
        assertEquals(authority["selfSha256"], X4Json.sha256(X4Json.canonical(rootProjection)))

        val revisions = authorityRevisions()
        assertEquals(listOf(1L, 2L), revisions.map { it["revision"] })
        var predecessor: String? = null
        revisions.forEachIndexed { index, revision ->
            assertEquals(
                setOf("legacyParentBaselineSha256", "pathClasses", "predecessorRevisionSha256", "revision", "revisionSha256"),
                revision.keys,
            )
            assertEquals(predecessor, revision["predecessorRevisionSha256"])
            val projection = revision.toMutableMap().also { it.remove("revisionSha256") }
            val declared = X4Json.string(revision.getValue("revisionSha256"))
            assertEquals(declared, X4Json.sha256(X4Json.canonical(projection)))
            predecessor = declared
            val classes = X4Json.asObject(revision.getValue("pathClasses"))
            classes.forEach { (name, raw) ->
                val value = X4Json.asObject(raw)
                assertEquals(setOf("paths", "policyTokens"), value.keys, name)
                listOf("paths", "policyTokens").forEach { field ->
                    val items = X4Json.strings(value.getValue(field))
                    assertEquals(items.distinct().sorted(), items, "$name.$field")
                }
                classPaths(revision, name).forEach { relative ->
                    assertFalse(relative.startsWith('/') || relative.contains('\\') || relative.split('/').any { it.isEmpty() || it == "." || it == ".." } || relative.any { it in "*?[]" }, relative)
                }
            }
            val expected = if (index == 0) setOf("CURRENT", "FUTURE_T10", "FUTURE_T11")
                else setOf("FUTURE_FORBIDDEN", "LOCAL_IGNORED", "POST_BUILD_ATTESTATION", "SOURCE_SEAL_INPUT")
            assertEquals(expected, classes.keys)
        }
        assertEquals(REVISION_ONE_SHA256, revisions.first()["revisionSha256"])
        assertEquals(HISTORICAL_PARENT_BASELINE, revisions.first()["legacyParentBaselineSha256"])
        assertEquals(LegacyBaselineIdentity.PARENT_BASELINE_SHA256, revisions.last()["legacyParentBaselineSha256"])

        val source = classPaths(revisions.last(), "SOURCE_SEAL_INPUT").toSet()
        val post = classPaths(revisions.last(), "POST_BUILD_ATTESTATION").toSet()
        val local = classPaths(revisions.last(), "LOCAL_IGNORED").toSet()
        val forbidden = classPaths(revisions.last(), "FUTURE_FORBIDDEN").toSet()
        assertEquals(155, source.size)
        assertEquals(POST_BUILD_PATHS, post)
        assertEquals(setOf(".authorized-build", ".t10-local", "keystore.properties", "release.keystore"), local)
        assertEquals(setOf("POLICY-T10-APK-ARTIFACT-NAME"), classTokens(revisions.last(), "LOCAL_IGNORED").toSet())
        assertEquals(T11_PATHS, forbidden)
        assertTrue(source.intersect(post + forbidden + local).isEmpty())
        assertTrue(post.intersect(forbidden + local).isEmpty())
        assertTrue(forbidden.intersect(local).isEmpty())
        assertEquals(T10_SOURCE_PATHS, source.filter(::isT10InventoryPath).toSet())
        assertTrue(classPaths(revisions.first(), "CURRENT").all(source::contains))
        assertTrue(CURRENT_PATHS.all(source::contains))
    }

    @Test
    fun `parent baseline and T11 retain exact hashes while authorized T10 may be absent`() {
        LegacyBaselineIdentityTest.assertSealedParentFilesOnDisk(root, PARENT_ARTIFACT_HASHES)
        val baseline = LegacyBaselineIdentity.capture(root)
        assertEquals(LegacyBaselineIdentity.PARENT_BASELINE_SHA256, baseline.parentCombinedSha256)
        assertEquals(PARENT_ARTIFACT_HASHES, baseline.artifacts.associate { it.path to it.fullSha256 })

        val checkedBaseline = X4Json.asObject(
            X4Json.parse(Files.readAllBytes(root.resolve("$OUTPUT_DIRECTORY/legacy-baseline.json"))),
        )
        val checkedArtifacts = X4Json.array(checkedBaseline.getValue("artifacts")).associate { value ->
            val artifact = X4Json.asObject(value)
            X4Json.string(artifact.getValue("path")) to X4Json.string(artifact.getValue("fullSha256"))
        }
        assertEquals(PARENT_ARTIFACT_HASHES, checkedArtifacts)
        assertEquals(LegacyBaselineIdentity.PARENT_BASELINE_SHA256, checkedBaseline["parentCombinedSha256"])

        assertEquals(13, T11_PATHS.size)
        assertEquals(2, T11_HASHES.size)
        T11_PATHS.forEach { relative ->
            val path = root.resolve(relative).normalize()
            assertTrue(path.startsWith(root), relative)
            val expected = T11_HASHES[relative]
            if (expected == null) assertFalse(Files.exists(path, LinkOption.NOFOLLOW_LINKS), "T11 path must remain absent: $relative")
            else {
                assertTrue(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), relative)
                assertEquals(expected, sha256(Files.readAllBytes(path)), relative)
            }
        }
        (T10_SOURCE_PATHS + POST_BUILD_PATHS).forEach { relative ->
            val candidate = root.resolve(relative).normalize()
            assertTrue(candidate.startsWith(root), relative)
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                assertTrue(Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS), "authorized T10 path is not a regular file: $relative")
            }
        }
    }

    @Test
    fun `generation has no side effects outside the 12 expansion outputs`() {
        LegacyBaselineIdentityTest.assertSealedParentFilesOnDisk(root, PARENT_ARTIFACT_HASHES)
        val before = repositorySnapshotOutsideOutputs()
        val parentBefore = LegacyBaselineIdentity.parentCombinedSha256(root)
        val t11Before = T11_HASHES.mapValues { (relative, _) -> sha256(Files.readAllBytes(root.resolve(relative))) }
        val result = ExpansionPackRenderer(root).writePack(temp.resolve("generated"))
        val after = repositorySnapshotOutsideOutputs()

        assertEquals(before, after)
        assertEquals(parentBefore, LegacyBaselineIdentity.parentCombinedSha256(root))
        assertEquals(t11Before, T11_HASHES.mapValues { (relative, _) -> sha256(Files.readAllBytes(root.resolve(relative))) })
        assertEquals(ExpansionPackRenderer.OUTPUT_NAMES, result.files.keys)
        CURRENT_PATHS.filter { it.startsWith("$OUTPUT_DIRECTORY/") }.forEach { relative ->
            val name = Path.of(relative).fileName.toString()
            assertArrayEquals(Files.readAllBytes(root.resolve(relative)), result.files.getValue(name), relative)
        }
    }

    @Test
    fun `expansion planner source and compiled symbols contain no transport surface`() {
        val sourceBan = Regex(
            "(?i)(?<![A-Za-z0-9_])(?:Process|Runtime|network|socket|DADB|CarExec|Android|device|callback|execute)[A-Za-z0-9_]*(?![A-Za-z0-9_])|(?<![A-Za-z0-9_])ADB(?![A-Za-z0-9_])",
        )
        IMPLEMENTATION_SOURCES.forEach { relative ->
            val source = Files.readString(root.resolve(relative)).replace("device-width", "")
            val match = sourceBan.find(source)
            assertTrue(match == null, "$relative contains forbidden source symbol ${match?.value}")
        }

        val classesRoot = root.resolve("offcar-planner/build/classes/kotlin/main/com/byd/clusternav/offcar")
        assertTrue(Files.isDirectory(classesRoot), "compiled planner classes are required")
        val sourceNames = IMPLEMENTATION_SOURCES.map { Path.of(it).fileName.toString() }.toSet()
        val coveredSources = mutableSetOf<String>()
        var compiledClasses = 0
        Files.walk(classesRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".class") }.forEach { path ->
                val bytes = Files.readAllBytes(path)
                val sourceName = sourceNames.singleOrNull { bytes.containsUtf8(it) } ?: return@forEach
                coveredSources += sourceName
                compiledClasses++
                val symbols = String(bytes, StandardCharsets.ISO_8859_1).replace("device-width", "")
                COMPILED_BANS.forEach { ban ->
                    assertFalse(ban.containsMatchIn(symbols), "$path contains forbidden compiled symbol ${ban.pattern}")
                }
            }
        }
        assertEquals(sourceNames, coveredSources)
        assertTrue(compiledClasses >= sourceNames.size, "every implementation source must produce a scanned class")
    }

    @Test
    fun `verifier is argument-free hardened offline and has exact fixed gate mappings`() {
        val verifier = root.resolve(VERIFIER_PATH)
        assertTrue(Files.isExecutable(verifier), "verifier must be executable")
        val text = Files.readString(verifier)
        val lines = text.lineSequence().toList()
        assertEquals("#!/bin/bash -p", lines.first())
        assertEquals("set -euo pipefail", lines[1])
        listOf(
            "if (( $# != 0 )); then", "CLUSTERNAV_EXPANSION_GATE+set", "unset CLUSTERNAV_EXPANSION_GATE",
            "Kernel-dispatched privileged startup ignores BASH_ENV/ENV, imported shell functions",
            "SHELLOPTS/BASHOPTS/CDPATH/GLOBIGNORE before line 1",
            "ignored BASH_FUNC_* and option entries cannot propagate to any verifier child",
            "BOOTSTRAP_ENV=(", "/usr/bin/env -i", "/bin/bash -p -s", "CLUSTERNAV_VERIFIER_BODY",
            "GATE-X-O11 is the plain no-selector full verifier", "BASH_SOURCE[0]", "symbolic link component is forbidden",
            "PATH=\"/usr/bin:/bin\"", "unset TMPDIR TEMP TMP", "TEMP_BASE_LOGICAL=\"/tmp\"", "pwd -P",
            "CLUSTERNAV_OFFCAR_ONLY=1", "CLUSTERNAV_ALLOW_VEHICLE=0", "CLUSTERNAV_ALLOW_NETWORK=0",
            "GRADLE_OFFLINE=true", "--no-daemon", "--rerun-tasks", "--no-build-cache", "--console=plain",
            "Gradle test task was not freshly executed", "java\\.specification\\.version", "sys.version_info >= (3, 9)", "O_NOFOLLOW",
            "a5a5c199ba02189ae8c46a334223371a20599d9c298ef65e7540ede4a3f72d59",
            "497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7",
            "556f4aa5f360e35fca77b010b306307765d4f4915a82a612035cd5cfb7a587cb",
            "semantic privacy fence failed", "output allowlist/no-follow mismatch", "/usr/bin/diff -ru",
        ).forEach { token -> assertTrue(text.contains(token), token) }
        assertTrue(text.indexOf("unset CLUSTERNAV_EXPANSION_GATE") < text.indexOf("run_selected_gate"))
        assertFalse(Regex("\\$(?:\\{)?(?:PATH|TMPDIR)").containsMatchIn(text), "inherited PATH/TMPDIR expansion")

        val mappings = linkedMapOf(
            "GATE-X-O1" to "LegacyBaselineIdentityTest", "GATE-X-O2" to "ExpansionDeterminismTest",
            "GATE-X-O4" to "ExpansionPromotionTest", "GATE-X-O5" to "DerivationClosureTest",
            "GATE-X-O6" to "ExpansionPromotionTest", "GATE-X-O7" to "AdaptivePruningTest",
            "GATE-X-O9" to "ExpansionDeterminismTest",
            "GATE-X-O10" to "ExpansionTransportFenceTest", "GATE-X-O12" to "ExpansionTraceabilityTest",
        )
        mappings.forEach { (gate, test) ->
            val dispatch = "$gate) run_gradle_test \"com.byd.clusternav.offcar.$test\" ;;"
            assertTrue(text.contains(dispatch), dispatch)
        }
        val o8 = "GATE-X-O8) run_gradle_test \"com.byd.clusternav.offcar.SameSessionQuarantineTest\" " +
            "\"com.byd.clusternav.offcar.LedgerSemanticValidationTest\"; verify_o8_test_count ;;"
        assertTrue(text.contains(o8), o8)
        assertTrue(text.contains("GATE-X-O8 must select exactly 9 tests"))
        assertTrue(text.contains("SameSessionQuarantineTest\": 3") && text.contains("LedgerSemanticValidationTest\": 6"))
        assertTrue(text.contains("GATE-X-O3) run_python_coverage_test ;;"))
        assertTrue(text.contains("if [[ \"\$SELECTED_GATE\" == \"GATE-X-O12\" ]]; then begin_output_checks; fi"))
        assertFalse(text.contains("GATE-X-O12) scripts/"), "O12 must not recurse")
        Regex("run_gradle_test \\\"([^\\\"]+)\\\"").findAll(text).forEach { match ->
            val command = ":offcar-planner:test --tests ${match.groupValues[1]}"
            assertFalse(Regex("(?i):(?:app|core|car-integration):|assemble|build|install|connected|device").containsMatchIn(command), command)
        }
        listOf("eval ", "bash -c", "sh -c", "curl ", "wget ", "ssh ", "nc ", "adb ", "dadb ", "command -v", "readlink -f")
            .forEach { assertFalse(text.contains(it, ignoreCase = true), it) }
    }

    @Test
    fun `verifier privileged startup rejects BASH_ENV functions options and invalid selectors`() {
        val attackerBin = temp.resolve("attacker-bin")
        Files.createDirectories(attackerBin)
        val fakeBash = attackerBin.resolve("bash")
        Files.writeString(fakeBash, "#!/bin/sh\nprintf 'HIJACKED_INTERPRETER\\n'\n")
        assertTrue(fakeBash.toFile().setExecutable(true))
        val startupMarker = temp.resolve("prestart-bypass.marker")
        val bashEnv = temp.resolve("malicious-bash-env.sh")
        Files.writeString(bashEnv, "printf 'BASH_ENV_PRESTART_BYPASS\\n' >>\"\$STARTUP_MARKER\"\nexit 0\n")
        val poisoned = mapOf(
            "PATH" to attackerBin.toString(), "TMPDIR" to temp.resolve("attacker-tmp").toString(),
            "CLASSPATH" to "attacker.jar", "JAVA_TOOL_OPTIONS" to "-javaagent:attacker.jar",
            "HTTP_PROXY" to "http://proxy.internal.example", "BASH_ENV" to bashEnv.toString(),
            "BASH_FUNC_printf%%" to "() { /bin/echo IMPORTED_FUNCTION_BYPASS >> \"\$STARTUP_MARKER\"; }",
            "SHELLOPTS" to "xtrace", "BASHOPTS" to "extdebug",
            "PS4" to "\$(/bin/echo SHELLOPTS_PRESTART_BYPASS >> \"\$STARTUP_MARKER\")",
            "STARTUP_MARKER" to startupMarker.toString(), "CDPATH" to temp.toString(), "GLOBIGNORE" to "*",
        )
        val expected = linkedMapOf(
            "" to "must not be empty", "GATE-X-O99" to "unknown CLUSTERNAV_EXPANSION_GATE",
            " GATE-X-O1" to "unknown CLUSTERNAV_EXPANSION_GATE", "GATE-X-O11" to "plain no-selector full verifier",
        )
        expected.forEach { (selector, message) ->
            Files.deleteIfExists(startupMarker)
            val result = runProcess(
                listOf(root.resolve(VERIFIER_PATH).toString()),
                poisoned + ("CLUSTERNAV_EXPANSION_GATE" to selector),
            )
            assertTrue(result.exitCode != 0, selector)
            assertTrue(result.output.contains(message), result.output)
            assertFalse(result.output.contains("HIJACKED_INTERPRETER"), result.output)
            assertFalse(result.output.contains("PRESTART_BYPASS") || result.output.contains("IMPORTED_FUNCTION_BYPASS"), result.output)
            assertFalse(Files.exists(startupMarker, LinkOption.NOFOLLOW_LINKS), "startup payload ran for $selector")
        }
    }

    @Test
    fun `verifier rejects invocation through a symbolic link ancestor`() {
        val linkedRoot = temp.resolve("linked-repository")
        Files.createSymbolicLink(linkedRoot, root)
        val result = runProcess(
            listOf("/bin/bash", linkedRoot.resolve(VERIFIER_PATH).toString()),
            mapOf("CLUSTERNAV_EXPANSION_GATE" to "GATE-X-O3", "PATH" to temp.resolve("poison").toString()),
        )
        assertTrue(result.exitCode != 0)
        assertTrue(result.output.contains("symbolic link component is forbidden"), result.output)
    }

    @Test
    fun `renderer rejects symbolic link ancestors and never follows an output leaf`() {
        val physical = temp.resolve("physical")
        Files.createDirectories(physical)
        val linked = temp.resolve("linked")
        Files.createSymbolicLink(linked, physical)
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            ExpansionPackRenderer(root).writePack(linked.resolve("pack"))
        }

        val physicalParent = temp.toRealPath().resolve("physical-parent")
        val physicalProject = physicalParent.resolve("project")
        Files.createDirectories(physicalProject)
        val linkedParent = temp.toRealPath().resolve("linked-parent")
        Files.createSymbolicLink(linkedParent, physicalParent)
        val linkedProject = linkedParent.resolve("project")
        assertFalse(Files.isSymbolicLink(linkedProject))
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            ExpansionPackRenderer(linkedProject).writePack(linkedProject.resolve(OUTPUT_DIRECTORY))
        }
        assertFalse(Files.exists(physicalProject.resolve("docs"), LinkOption.NOFOLLOW_LINKS))

        val output = temp.resolve("nofollow")
        Files.createDirectories(output)
        val protected = temp.resolve("protected.txt")
        Files.writeString(protected, "must remain unchanged")
        Files.createSymbolicLink(output.resolve(ExpansionPackRenderer.BASELINE), protected)
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            ExpansionPackRenderer(root).writePack(output)
        }
        assertEquals("must remain unchanged", Files.readString(protected))
    }

    @Test
    fun `semantic privacy scanner rejects negative fixtures permits fixed metadata and refuses links`() {
        val verifier = Files.readString(root.resolve(VERIFIER_PATH))
        val program = verifier.substringAfter("# PRIVACY_SCANNER_PROGRAM_BEGIN\n")
            .substringBefore("\n# PRIVACY_SCANNER_PROGRAM_END")
        assertTrue(program.startsWith("import ipaddress"))
        val scanner = temp.resolve("privacy-scanner.py")
        Files.writeString(scanner, program)
        val canonicalResult = runPrivacyScanner(scanner, root.resolve(OUTPUT_DIRECTORY))
        assertEquals(0, canonicalResult.exitCode, canonicalResult.output)
        val fixture = temp.resolve("privacy-fixture")
        Files.createDirectories(fixture)
        val file = fixture.resolve("rendered.txt")
        val negative = listOf(
            "api_key=sk-proj-abcdef", "VIN 1HGCM82633A004352",
            "serialNumber=SERIAL-42", "rawDump=payload", "sourceLine=42", "decompiledBody=text",
            "GPS 21.0285,105.8542", "public 8.8.8.8", "ipv6 2001:db8::1", "/Users/example/private.txt",
            "https://example.com/private", "person@example.com", "+84912345678", "name=Jane Citizen",
            "governmentId=123456789", "card 4111 1111 1111 1111", "build.internal.example",
            "FACT-8.8.8.8", "VERSION-8.8.8.8", "FACT-1HGCM82633A004352",
            "FACT-RAW-DUMP-PAYLOAD", "FACT-SOURCE-LINE-42", "FACT-SERIAL-NUMBER-42", "FACT-GPS-21.0285",
        )
        negative.forEach { value ->
            Files.writeString(file, value)
            val result = runPrivacyScanner(scanner, fixture)
            assertTrue(result.exitCode != 0, "fixture escaped scanner: $value")
        }
        val nested = fixture.resolve("nested.json")
        val nestedLeaks = listOf(
            "{\"outer\":{\"token\":\"opaque-private-credential\"}}",
            "{\"outer\":{\"passwordSha256\":\"password=CorrectHorseBatteryStaple\"}}",
            "{\"outer\":{\"passwordSha256\":null}}",
            "{\"outer\":{\"value\":\"PASSWORD-CORRECTHORSEBATTERYSTAPLE\"}}",
            "{\"outer\":{\"password\":\"PASSWORD-CORRECTHORSEBATTERYSTAPLE\"}}",
            "{\"outer\":{\"factId\":\"FACT-PASSWORD-CORRECTHORSEBATTERYSTAPLE\"}}",
        )
        nestedLeaks.forEach { value ->
            Files.writeString(nested, value)
            assertTrue(runPrivacyScanner(scanner, fixture).exitCode != 0, "nested leak escaped scanner: $value")
        }
        Files.delete(nested)

        Files.writeString(
            file,
            "https://clusternav.invalid/schema/result-ledger.schema.json " +
                "https://json-schema.org/draft/2020-12/schema ${"a".repeat(64)} FACT-SAFE-EVIDENCE TOKEN-C01-QUERY Đăng Khôi · dangkhoi",
        )
        assertEquals(0, runPrivacyScanner(scanner, fixture).exitCode)
        Files.delete(file)
        val outside = temp.resolve("outside.txt")
        Files.writeString(outside, "clean")
        Files.createSymbolicLink(fixture.resolve("linked.txt"), outside)
        val linkedResult = runPrivacyScanner(scanner, fixture)
        assertTrue(linkedResult.exitCode != 0)
        assertTrue(linkedResult.output.contains("symbolic link output"), linkedResult.output)
    }

    private data class ProcessResult(val exitCode: Int, val output: String)

    private fun runPrivacyScanner(scanner: Path, fixture: Path): ProcessResult {
        val python = listOf(
            Path.of("/usr/bin/python3"), Path.of("/opt/homebrew/opt/python@3.14/bin/python3.14"),
            Path.of("/usr/local/bin/python3"),
        ).firstOrNull(Files::isExecutable)?.toRealPath() ?: error("explicit Python 3 candidate is unavailable")
        return runProcess(listOf(python.toString(), "-I", scanner.toString(), fixture.toString()))
    }

    private fun runProcess(command: List<String>, environment: Map<String, String> = emptyMap()): ProcessResult {
        val builder = ProcessBuilder(command).redirectErrorStream(true)
        builder.environment().putAll(environment)
        val process = builder.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return ProcessResult(process.waitFor(), output)
    }

    private fun authorityRevisions(): List<Map<String, Any?>> = X4Json.array(X4Json.asObject(
        X4Json.parse(Files.readAllBytes(root.resolve(BOUNDARY_PATH)))).getValue("revisions")).map(X4Json::asObject)
    private fun classPaths(revision: Map<String, Any?>, name: String) = X4Json.strings(
        X4Json.asObject(X4Json.asObject(revision.getValue("pathClasses")).getValue(name)).getValue("paths"))
    private fun classTokens(revision: Map<String, Any?>, name: String) = X4Json.strings(
        X4Json.asObject(X4Json.asObject(revision.getValue("pathClasses")).getValue(name)).getValue("policyTokens"))

    private fun repositorySnapshotOutsideOutputs(): Map<String, String> = buildMap {
        Files.walk(root).use { paths ->
            paths.filter { path ->
                val relative = root.relativize(path)
                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                    !relative.toString().startsWith("$OUTPUT_DIRECTORY/") &&
                    relative.none { it.toString() in IGNORED_DIRECTORY_NAMES }
            }.forEach { path -> put(root.relativize(path).toString(), sha256(Files.readAllBytes(path))) }
        }
    }.toSortedMap()

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun ByteArray.containsUtf8(value: String): Boolean { val target = value.toByteArray(StandardCharsets.UTF_8)
        return indices.any { start -> start + target.size <= size && target.indices.all { this[start + it] == target[it] } } }

    companion object {
        private const val SPEC_PATH = "docs/specs/seal-hud-sign-candidate-expansion.html"
        private const val OUTPUT_DIRECTORY = "docs/diagnostics/hud-sign-re/expansion"
        private const val VERIFIER_PATH = "scripts/verify-hud-sign-candidate-expansion.sh"

        private val IMPLEMENTATION_SOURCES = listOf(
            "offcar-planner/src/main/kotlin/com/byd/clusternav/offcar/ExpansionRegistry.kt",
            "offcar-planner/src/main/kotlin/com/byd/clusternav/offcar/DiscoveryProbe.kt",
            "offcar-planner/src/main/kotlin/com/byd/clusternav/offcar/ExpansionPackRenderer.kt",
            "offcar-planner/src/main/kotlin/com/byd/clusternav/offcar/ExpansionMain.kt",
        )
        private val SOURCE_ARTIFACT_PATHS = IMPLEMENTATION_SOURCES + listOf(
            "offcar-planner/src/main/resources/expansion-contracts.schema.json",
            "scripts/re/expand-candidate-coverage.py",
            VERIFIER_PATH,
        )
        private val CURRENT_PATHS = IMPLEMENTATION_SOURCES + listOf(
            "offcar-planner/src/main/resources/expansion-contracts.schema.json",
            "offcar-planner/src/test/kotlin/com/byd/clusternav/offcar/LegacyBaselineIdentityTest.kt",
            "offcar-planner/src/test/kotlin/com/byd/clusternav/offcar/ExpansionPromotionTest.kt",
            "offcar-planner/src/test/kotlin/com/byd/clusternav/offcar/SameSessionQuarantineTest.kt",
            "offcar-planner/src/test/kotlin/com/byd/clusternav/offcar/AdaptivePruningTest.kt",
            "offcar-planner/src/test/kotlin/com/byd/clusternav/offcar/ExpansionDeterminismTest.kt",
            "offcar-planner/src/test/kotlin/com/byd/clusternav/offcar/ExpansionTransportFenceTest.kt",
            "offcar-planner/src/test/kotlin/com/byd/clusternav/offcar/DerivationClosureTest.kt",
            "offcar-planner/src/test/kotlin/com/byd/clusternav/offcar/ExpansionTraceabilityTest.kt",
            "scripts/re/expand-candidate-coverage.py", "scripts/re/tests/test_expand_candidate_coverage.py", VERIFIER_PATH,
            "$OUTPUT_DIRECTORY/legacy-baseline.json", "$OUTPUT_DIRECTORY/candidate-registry.json",
            "$OUTPUT_DIRECTORY/evidence-map.json", "$OUTPUT_DIRECTORY/candidate-diff.json",
            "$OUTPUT_DIRECTORY/candidate-expansion-report.html", "$OUTPUT_DIRECTORY/vehicle-session-plan.json",
            "$OUTPUT_DIRECTORY/vehicle-session-plan.txt", "$OUTPUT_DIRECTORY/vehicle-session-checklist.html",
            "$OUTPUT_DIRECTORY/result-ledger.schema.json", "$OUTPUT_DIRECTORY/pack-manifest.json",
            "$OUTPUT_DIRECTORY/traceability.json", "$OUTPUT_DIRECTORY/corpus-coverage.json", SPEC_PATH,
        )
        private const val BOUNDARY_PATH = "docs/diagnostics/hud-sign-re/offcar-boundary-revisions.json"
        private const val REVISION_ONE_SHA256 = "a05be7e4d6a521a81a285321754e8370dec4402e4406f2969727cd3863c46301"
        private const val HISTORICAL_PARENT_BASELINE = "8f636f508aaf89592ca676d85a8d13dbb8c7e9225112957d281dc1368901e1d4"
        private val PARENT_ARTIFACT_HASHES = linkedMapOf(
            "docs/diagnostics/hud-sign-re/README.md" to "f854683acbda36c69f899a5d3a3bdc326c0df966dc49b24059eb2d94ccb1ee46",
            "docs/diagnostics/hud-sign-re/candidate-report.html" to "af3c3db29e29b5c4cd4ebd0a0d4ef863de5a7a2d7f60c527bbe989596ce3eb36",
            "docs/diagnostics/hud-sign-re/corpus-completeness.json" to "87610a0e7a54e7d634dbcad8a423906a494e2add2e6e50b61828e1ee7217db79",
            "docs/diagnostics/hud-sign-re/evidence-index.json" to "ac3fd27701e6b05c5037594b35d314b49ddadaeb0315d8a64c3a3da0bef980b9",
            "docs/diagnostics/hud-sign-re/first-launch-emulator-result.json" to "e60e63dced72dbc0d742476883930088385c5812b29d176f145f42430540ae39",
            "docs/diagnostics/hud-sign-re/m1-nav-hud-plan.json" to "c8eff7210e093fa1b2e32328abeddf37fa51d919d524df851e4ecd634d289bf9",
            "docs/diagnostics/hud-sign-re/m2-hud-road-plan.json" to "dffb04d3e26beadd9fd7f813870fca6712d5f213f4145787280ddb4718437faf",
            "docs/diagnostics/hud-sign-re/m3-cluster-sign-plan.json" to "ac2de2631de73ab2ddd2ad4efa2f33e805fae2d8007091e2ae3497c64625fb7e",
            "docs/diagnostics/hud-sign-re/m4-hud-sign-plan.json" to "51fc71db1050baa816e9df2e1250a196c1d6e608a48df228e5318d6077c910d1",
            "docs/diagnostics/hud-sign-re/native/libbydcluster-diff.json" to "d2d7f63ee1916905e8ef21ea58242f22f4e2c02a5a1e0b3854766341f77e9464",
            "docs/diagnostics/hud-sign-re/traceability.json" to "332ae311ed642441c7ec8640fc4c389e1e7865813eafef8b1442645ff3791e60",
            "docs/diagnostics/hud-sign-re/zero-hit-report.txt" to "55acd8bf51a0baf9f397b8765162f063fc1686d9544b3fdbc83eaa96955f273f",
            "docs/specs/seal-nav-hud-speed-sign-offcar.html" to "781ff2b47f38d51deec66a47464ab78f37d781970499fdc64b86386423a28f87",
        )
        private fun pathSet(raw: String) = raw.split('|').toSet()
        private val T10_SOURCE_PATHS = pathSet(".gitignore|app/build.gradle.kts|app/src/test/java/com/byd/clusternav/BuildArtifactNamingTest.kt|app/src/test/java/com/byd/clusternav/MainProbeSurfaceAbsenceTest.kt|app/src/testVehicleTest/java/com/byd/clusternav/VehicleTestSurfaceContractTest.kt|app/src/vehicleTest/AndroidManifest.xml|app/src/vehicleTest/java/com/byd/clusternav/HudSignProbeActivity.kt|app/src/vehicleTest/java/com/byd/clusternav/HudSignProbeReceiver.kt|car-integration/build.gradle.kts|car-integration/src/main/kotlin/com/byd/clusternav/vehicleprobe/DadbVehicleTransport.kt|car-integration/src/main/kotlin/com/byd/clusternav/vehicleprobe/HudSignSessionRunner.kt|car-integration/src/main/kotlin/com/byd/clusternav/vehicleprobe/T10LocalAuthorization.kt|car-integration/src/main/kotlin/com/byd/clusternav/vehicleprobe/T10ResultStore.kt|car-integration/src/main/kotlin/com/byd/clusternav/vehicleprobe/T10RunnerMain.kt|car-integration/src/test/kotlin/com/byd/clusternav/vehicleprobe/DadbVehicleTransportTest.kt|car-integration/src/test/kotlin/com/byd/clusternav/vehicleprobe/HudSignSessionRunnerTest.kt|core/src/main/kotlin/com/byd/clusternav/carexec/CarExecCatalog.kt|core/src/main/kotlin/com/byd/clusternav/carexec/CarExecClusterDiagnosticsCatalog.kt|core/src/main/kotlin/com/byd/clusternav/carexec/CarExecClusterLifecycleCatalog.kt|core/src/main/kotlin/com/byd/clusternav/carexec/CarExecClusterProjectionCatalog.kt|core/src/main/kotlin/com/byd/clusternav/carexec/CarExecHudCatalog.kt|core/src/main/kotlin/com/byd/clusternav/carexec/CarExecModels.kt|core/src/main/kotlin/com/byd/clusternav/carexec/CarExecNavigationCatalog.kt|core/src/main/kotlin/com/byd/clusternav/carexec/CarExecSpeedSignCatalog.kt|core/src/main/kotlin/com/byd/clusternav/carexec/T10FixedOperationCatalog.kt|core/src/main/kotlin/com/byd/clusternav/carexec/T10RollbackExecutor.kt|core/src/main/kotlin/com/byd/clusternav/carexec/T10SessionEngine.kt|core/src/test/kotlin/com/byd/clusternav/carexec/CarExecCatalogTest.kt|core/src/test/kotlin/com/byd/clusternav/carexec/T10SessionSafetyTest.kt|docs/_handoff/session-2026-08-10-hud-sign-t10-offcar-complete.md|docs/_handoff/session-2026-08-10-hud-sign-t10-preparation.md|gradle/authorized-apk-tasks.gradle.kts|gradle/exact-source-tasks.gradle.kts|scripts/evidence/gen-exact-source.py|scripts/evidence/tests/test_hud_sign_t10_evidence.py|scripts/evidence/verify-hud-sign-t10.py|scripts/vehicle/run-seal-hud-sign-matrix.sh|scripts/verify-seal-hud-sign-vehicle-test-t10.sh|vehicle-contracts/src/main/kotlin/com/byd/clusternav/vehicle/t10/T10Canonical.kt|vehicle-contracts/src/main/kotlin/com/byd/clusternav/vehicle/t10/T10Identity.kt|vehicle-contracts/src/main/kotlin/com/byd/clusternav/vehicle/t10/T10Ledger.kt|vehicle-contracts/src/main/kotlin/com/byd/clusternav/vehicle/t10/T10Session.kt|vehicle-contracts/src/main/kotlin/com/byd/clusternav/vehicle/t10/T10Transport.kt|vehicle-contracts/src/main/resources/t10-contracts.schema.json|vehicle-contracts/src/test/kotlin/com/byd/clusternav/vehicle/t10/T10ContractsTest.kt")
        private val POST_BUILD_PATHS = pathSet("docs/_handoff/hud-sign-t10-exact-source.json|docs/_handoff/hud-sign-vehicle-test-candidate.json|docs/diagnostics/hud-sign-re/vehicle/d-h0-hud-physical-temp-result.json|docs/diagnostics/hud-sign-re/vehicle/d-m1-nav-hud-result.json|docs/diagnostics/hud-sign-re/vehicle/d-m2-hud-road-result.json|docs/diagnostics/hud-sign-re/vehicle/d-m3-cluster-sign-result.json|docs/diagnostics/hud-sign-re/vehicle/d-m4-hud-sign-result.json")
        private val T11_PATHS = pathSet("app/src/main/java/com/byd/clusternav/vehicle/BydPropertyGateway.kt|app/src/main/java/com/byd/clusternav/vehicle/ClusterSpeedSignPort.kt|app/src/main/java/com/byd/clusternav/vehicle/HudSignSettingsController.kt|app/src/main/java/com/byd/clusternav/vehicle/HudSpeedSignPort.kt|app/src/main/java/com/byd/clusternav/vehicle/HudVehicleProfile.kt|app/src/main/java/com/byd/clusternav/vehicle/SpeedSignVehicleProfile.kt|app/src/main/res/layout/activity_main.xml|app/src/main/res/values/strings.xml|docs/_handoff/hud-sign-release-candidate.json|docs/diagnostics/hud-sign-re/vehicle/p-m1-nav-hud-result.json|docs/diagnostics/hud-sign-re/vehicle/p-m2-hud-road-result.json|docs/diagnostics/hud-sign-re/vehicle/p-m3-cluster-sign-result.json|docs/diagnostics/hud-sign-re/vehicle/p-m4-hud-sign-result.json")
        /**
         * ⚠ CẬP NHẬT 2026-08-24 — owner duyệt, KHÔNG phải sửa lén.
         *
         * `activity_main.xml` đổi vì gỡ ô chọn "Nguồn tốc độ" khỏi mục biển-báo-tốc-độ-trên-cụm: sau khi
         * B3.30 gỡ hẳn kênh Waze Mod (HLP) — đo thật, `logcat -s WazeHudLink` 0 dòng khi Waze đang dẫn, không
         * HUD BLE — ô đó chỉ còn ĐÚNG MỘT mục (widget VietMap), tức một nút bấm-không-làm-gì.
         * Owner chốt 08-22: *"cái nào work thì để, không thì remove hẳn, cả code + UI để khỏi nhầm"*
         * và xác nhận lại 08-24: *"bỏ là đúng, chỉ có vietmap, và không phải chọn gì nữa"*.
         *
         * Hằng cũ (giữ lại để trace): activity_main.xml = 4738ceb6…4946ec.
         * Chức năng KHÔNG mất: badge vẫn lấy từ widget VietMap, dòng trạng thái `txt_speed_source_bind` giữ nguyên.
         *
         * ⚠ CẬP NHẬT 2026-08-24 (lần 2) — owner duyệt, KHÔNG phải sửa lén.
         *
         * `activity_main.xml` đổi lần nữa vì **F3 — gán NHIỀU phím cho NHIỀU app**. Owner yêu cầu nguyên văn:
         * *"có thể binding nhiều nút vào nhiều app được không? … nên có giao diện kiểu sau khi chọn nút +
         * chọn app xong → add, thì ra 1 dòng đã binding nút và app, xong có thể chọn thêm add thêm, mình
         * listen thì listen theo cái danh sách đã save đó thôi"*.
         * Thay đổi trong mục "Nút vật lý → mở app": thêm `btn_voicekey_add` (Thêm gán), `list_voicekey_bindings`
         * (nơi bơm từng dòng đã gán) và `txt_voicekey_empty` (nói rõ danh sách rỗng ⇒ KHÔNG có gì chạy);
         * đánh số lại nhãn hai dropdown sẵn có thành "1 · Chọn nút" / "2 · Chọn app sẽ mở".
         * Dòng đã gán nằm ở layout MỚI `row_voicekey_binding.xml` (không thuộc danh sách canh này).
         * `strings.xml` KHÔNG đổi — nhãn đặt lúc chạy qua `Lang.t` để giữ song ngữ, đúng lối đang dùng.
         *
         * Hằng cũ (giữ lại để trace): activity_main.xml = fddc1ef5…a694d.
         *
         * ⚠ CẬP NHẬT 2026-08-25 (lần 3) — owner duyệt ("làm maximum có thể, không cần hỏi"), KHÔNG sửa lén.
         *
         * `activity_main.xml` đổi lần nữa vì **B3.20 — chip cảnh báo/camera VietMap trên cụm**. Thêm MỘT toggle
         * `switch_alert_chip` ("Hiện cảnh báo/camera VietMap", **mặc định TẮT** — opt-in, không phá bố trí badge
         * hiện có) ngay dưới `switch_upcoming_badge`, ở CẢ hai biến thể layout (portrait + `layout-w960dp` xe
         * render — bài học F3 P0). Chip đọc VietMap sticky ALERTS slot → `RoadAlertChipDecision` (:core) →
         * `AlertChipView` (cửa sổ overlay thứ 3, đặt PHẢI badge chính). `strings.xml` KHÔNG đổi (nhãn đặt lúc chạy).
         *
         * Hằng cũ (giữ lại để trace): activity_main.xml = 25fa8836…f202.
         *
         * ⚠ CẬP NHẬT 2026-08-25 (lần 4) — owner duyệt ("sửa hết luôn đi"), KHÔNG sửa lén.
         *
         * `strings.xml` đổi vì **B3.57 — status per-nguồn** (owner báo on-car: status "Chưa có phiên dẫn đường"
         * chỉ đúng cho GMaps, không phản ánh khi đọc VietMap/Waze). Sửa `status_need_perm`: "Cần cấp quyền
         * notification trước" → "Cần quyền truy cập thông báo để đọc dẫn đường" (nói rõ quyền để ĐỌC dẫn đường,
         * KHÔNG ngụ ý notification là đường duy nhất). Nhãn nguồn per-kênh (GMaps=thông báo · VietMap/Waze=đọc
         * màn hình) đặt lúc chạy qua `NavSourceLabels` (:core) + `Lang.t`, KHÔNG vào strings.xml.
         *
         * Hằng cũ (giữ lại để trace): strings.xml = 4b068200…6fa1.
         *
         * ⚠ CẬP NHẬT 2026-08-28 (lần 5) — owner duyệt (task ui-closing-cleanup), KHÔNG sửa lén.
         *
         * `activity_main.xml` đổi vì **closing UI cleanup** (sau khi gỡ nav VietMap/Waze — chỉ còn Google Maps):
         *   1) GỠ selector "chọn nguồn dẫn đường" (`spinner_nav_source` + nhãn) — chỉ còn một nguồn nên không
         *      cần chọn; `Prefs.sourceMode` giữ mặc định AUTO. Dòng trạng thái `txt_nav_source_active` GIỮ.
         *   3) THÊM panel "Vị trí bong bóng VietMap trên cụm" (`btn_vm_pos_left/right/up/down/right_half/apply`
         *      + `txt_vm_pos_hint`) ở CẢ hai biến thể layout (portrait + `layout-w960dp`), gate theo Cluster Cast.
         * `strings.xml` KHÔNG đổi (nhãn đặt lúc chạy qua `Lang.t`), nên hằng strings.xml giữ nguyên.
         *
         * Hằng cũ (giữ lại để trace): activity_main.xml = 20dca831…4412.
         *
         * ⚠ CẬP NHẬT 2026-08-28 (lần 6) — owner duyệt (task diag-remove-and-placement-ui), KHÔNG sửa lén.
         *
         * `activity_main.xml` đổi vì **UI vị trí bong bóng VietMap chuyển sang KÉO-THẢ**: gỡ 4 nút mũi tên
         * `btn_vm_pos_{left,right,up,down}` (owner chê), thay bằng khung proxy cụm kéo-thả
         * `vm_bubble_placement_container` (VmBubblePlacementView — giống UI đặt biển báo tốc độ) + thêm nút
         * `btn_vm_pos_reset` ("Đặt lại"); giữ `btn_vm_pos_right_half` + `btn_vm_pos_apply` + `txt_vm_pos_hint`.
         * Đổi ở CẢ hai biến thể layout (LayoutVariantIdParityTest giữ parity id). `strings.xml` KHÔNG đổi
         * (nhãn đặt lúc chạy qua `Lang.t`), nên hằng strings.xml giữ nguyên.
         *
         * Hằng cũ (giữ lại để trace): activity_main.xml = f13eeda5…d39f7.
         *
         * ⚠ CẬP NHẬT 2026-08-28 (lần 7) — owner duyệt (task move-vm-block-up), KHÔNG sửa lén.
         *
         * `activity_main.xml` đổi vì **chuyển khối UI "Vị trí bong bóng VietMap trên cụm" LÊN TRÊN mục
         * "Khắc phục sự cố"** (`cast_recovery_toggle`): khối vm (comment + `vm_bubble_placement_container` +
         * `btn_vm_pos_right_half` / `btn_vm_pos_reset` / `btn_vm_pos_apply` + `txt_vm_pos_hint`) trước nằm DƯỚI
         * card Cast, nay DÁN ngay TRƯỚC `cast_recovery_toggle` (trong `cast_body`). CHỈ DI CHUYỂN vị trí + thụt
         * lề cho khớp ngữ cảnh chèn — KHÔNG đổi id/nội dung (số id bất biến, LayoutVariantIdParityTest xanh).
         * Đổi ở CẢ hai biến thể layout; chỉ bản dọc bị pin hash ở đây nên chỉ cập nhật hằng bản dọc.
         * `strings.xml` KHÔNG đổi.
         *
         * Hằng cũ (giữ lại để trace): activity_main.xml = 5744260b…81da0a.
         *
         * ⚠ CẬP NHẬT 2026-08-28 (lần 8) — owner duyệt (task remove-diag-logging-toggle), KHÔNG sửa lén.
         *
         * `activity_main.xml` đổi vì **gỡ công tắc "Thu thập dữ liệu chẩn đoán (log + ảnh)"**
         * (`switch_diag_logging` + comment kèm theo) khỏi CẢ hai biến thể layout. Công tắc này đã vô nghĩa:
         * nguồn dữ liệu nó thu (screen-capture/log dẫn đường VietMap/Waze) đã bị gỡ — chỉ còn Google Maps.
         * Wiring MainActivity (`setDiagLogging` + nhấn-giữ ẩn trên nhãn phiên bản), `NavLogExport`, receiver
         * `EXPORT_LOGS` (NavAccessibilityService) và `Prefs.setNavVerboseLog` gỡ theo vì thành orphan.
         * GIỮ log chẩn đoán GMaps hợp lệ: `NavLog.verbose` + `Prefs.navVerboseLog` getter (nay chỉ do cờ build
         * `-PdiagLog=true`/`BuildConfig.DIAG_LOG` điều khiển) + `NavNotifLog`/`NavNotifRawLog`/`DiagStorageCap`.
         * `strings.xml` KHÔNG đổi (text công tắc hardcode trong layout, không phải `@string`) nên hằng
         * strings.xml giữ nguyên. Đổi ở CẢ hai biến thể (LayoutVariantIdParityTest giữ parity id); chỉ bản dọc
         * bị pin hash ở đây nên chỉ cập nhật hằng bản dọc.
         *
         * Hằng cũ (giữ lại để trace): activity_main.xml = 183dcd38…3e77dd.
         *
         * ⚠ CẬP NHẬT 2026-08-28 (lần 9) — owner duyệt (task vm-toggles-autostart), KHÔNG sửa lén.
         *
         * `activity_main.xml` đổi vì **thêm công tắc bong bóng VietMap trên cụm** (`switch_vm_bubble_enabled`,
         * **mặc định TẮT** — opt-in) vào ĐẦU card "Vị trí bong bóng VietMap trên cụm", ở CẢ hai biến thể layout
         * (LayoutVariantIdParityTest giữ parity id). Công tắc gate panel kéo-thả vị trí bong bóng và, khi bật,
         * tự khởi động VietMap MỘT LẦN (giống badge tốc độ; dedup pidof). Đi kèm việc đổi mặc định badge tốc độ
         * VietMap sang TẮT (`Prefs.badgeEnabled` default false) — thuần code, không đụng layout. `strings.xml`
         * KHÔNG đổi (nhãn công tắc + nhắc đặt lúc chạy qua `Lang.t`), nên hằng strings.xml giữ nguyên. Chỉ bản
         * dọc bị pin hash ở đây nên chỉ cập nhật hằng bản dọc; bản rộng (`layout-w960dp`) không pin.
         *
         * Hằng cũ (giữ lại để trace): activity_main.xml = 7d283550…b5ae15.
         *
         * ⚠ CHỈ được cập nhật hằng ở đây khi thay đổi là CHỦ Ý và có vết trong backlog. Cập nhật theo phản xạ
         * "cho test xanh" là **phá seal** — đúng thứ cơ chế này sinh ra để bắt.
         * ⚠ CẤM gỡ `activity_main.xml` khỏi `T11_PATHS` để né việc cập nhật hằng — nó có mặt trong danh sách
         * vì giao diện biển-báo-tốc-độ nằm trong file này (xem đính chính 08-24 ở `PROJECT-BACKLOG.md` E9).
         */
        private val T11_HASHES = mapOf(
            "app/src/main/res/layout/activity_main.xml" to "6369e4970769ed21971577295dda1b19a565cd0f4ef71d68b6be7d3456b2dfb2",
            "app/src/main/res/values/strings.xml" to "8300437c9f186d4296100f36b6a3960e0c9b693828fccb9145ec8e84e8fe4cdd",
        )
        private val T10_PREFIXES = "app/src/vehicleTest/|app/src/testVehicleTest/|car-integration/|core/src/main/kotlin/com/byd/clusternav/carexec/|core/src/test/kotlin/com/byd/clusternav/carexec/|gradle/|scripts/evidence/|scripts/vehicle/|vehicle-contracts/src/main/kotlin/com/byd/clusternav/vehicle/t10/|vehicle-contracts/src/test/kotlin/com/byd/clusternav/vehicle/t10/".split('|')
        private val T10_FIXED = pathSet(".gitignore|app/build.gradle.kts|app/src/test/java/com/byd/clusternav/BuildArtifactNamingTest.kt|app/src/test/java/com/byd/clusternav/MainProbeSurfaceAbsenceTest.kt|docs/_handoff/session-2026-08-10-hud-sign-t10-offcar-complete.md|docs/_handoff/session-2026-08-10-hud-sign-t10-preparation.md|scripts/verify-seal-hud-sign-vehicle-test-t10.sh|vehicle-contracts/src/main/resources/t10-contracts.schema.json")
        private fun isT10InventoryPath(path: String) = path in T10_FIXED || T10_PREFIXES.any(path::startsWith)
        private val IGNORED_DIRECTORY_NAMES = setOf(".git", ".gradle", ".idea", ".authorized-build", ".t10-local", "build", "node_modules")
        private val COMPILED_BANS = listOf(
            Regex("java/lang/Process(?:Builder)?(?:[^A-Za-z0-9_]|$)"), Regex("java/lang/Runtime(?:[^A-Za-z0-9_]|$)"),
            Regex("java/net/|java/nio/channels/(?:Server)?Socket", RegexOption.IGNORE_CASE),
            Regex("(?i)(?:^|[/.$;_<>()-])(?:network|socket|adb|dadb|device|callback|execute)(?:$|[/.$;_<>()-])"),
            Regex("(?i)carexec|android/|dalvik/|dadbvehicle|adbtransport"),
        )
    }
}

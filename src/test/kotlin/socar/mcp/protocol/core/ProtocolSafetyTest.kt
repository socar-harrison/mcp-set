package socar.mcp.protocol.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class ProtocolBranchTest {
    @Test
    fun `accepts ordinary remote branch names without changing them`() {
        listOf(
            "main",
            "feature/CSP-123",
            "release/1.2.3",
            "harrison/260805-marketing_privacy-agreement",
        ).forEach { branch ->
            assertEquals(branch, ProtocolBranch.validate(branch))
        }
    }

    @Test
    fun `rejects values that could escape a Kotlin string or are invalid git refs`() {
        listOf(
            "",
            " ",
            "-main",
            "feature branch",
            "feature\nmalicious",
            "feature\rmalicious",
            "feature\tmalicious",
            "feature/\"; error(\"injected\") //",
            "feature\\branch",
            "feature//branch",
            "feature/../branch",
            "feature@{branch",
            "feature/branch/",
            "feature/branch.lock",
            "\${System.getProperty(\"user.home\")}",
        ).forEach { branch ->
            assertFails("Expected branch to be rejected: ${branch.replace("\n", "\\n")}") {
                ProtocolBranch.validate(branch)
            }
        }
    }
}

class GenerateScriptTest {
    @Test
    fun `rewrites only the single branch declaration`() {
        val source = """
            import java.io.File

            // val branch = "comment-must-not-change"
            val branch = "main"
            val url = "branch=${'$'}branch"
        """.trimIndent()

        val rewritten = GenerateScript.withBranch(source, "feature/CSP-123")

        assertEquals(
            """
                import java.io.File

                // val branch = "comment-must-not-change"
                val branch = "feature/CSP-123"
                val url = "branch=${'$'}branch"
            """.trimIndent(),
            rewritten,
        )
    }

    @Test
    fun `preserves CRLF line endings and all surrounding lines`() {
        val source = "val before = true\r\nval branch = \"main\"\r\nval after = true\r\n"

        val rewritten = GenerateScript.withBranch(source, "release/1.2.3")

        assertEquals(
            "val before = true\r\nval branch = \"release/1.2.3\"\r\nval after = true\r\n",
            rewritten,
        )
    }

    @Test
    fun `fails closed when the declaration is missing`() {
        val failure = assertFails {
            GenerateScript.withBranch("val anotherValue = \"main\"\n", "feature/CSP-123")
        }

        assertTrue(failure.message.orEmpty().contains("branch", ignoreCase = true))
    }

    @Test
    fun `fails closed when more than one declaration exists`() {
        val source = """
            val branch = "main"
            val branch = "another"
        """.trimIndent()

        assertFails {
            GenerateScript.withBranch(source, "feature/CSP-123")
        }
    }

    @Test
    fun `validates the replacement before rewriting`() {
        assertFails {
            GenerateScript.withBranch("val branch = \"main\"\n", "bad\"; error(\"injected\") //")
        }
    }

    @Test
    fun `hardens the isolated script to use a local immutable protocol checkout and checked exits`() {
        val immutableSha = "0123456789abcdef0123456789abcdef01234567"
        val source = """
            import java.io.File

            val branch = "main"
            for (subDir in listOf("socar-server", "socar-mobile", "socarx")) {
                val socarProtocolRepo = "ssh://git@github.com/socar-inc/socar-protocol.git#subdir=${'$'}subDir,branch=${'$'}branch"
                ProcessBuilder("buf", "export", socarProtocolRepo)
                    .start()
                    .waitFor()
            }
            ProcessBuilder("protoc")
                .start()
                .waitFor()
        """.trimIndent()

        val hardened = GenerateScript.hardenedForLocalProtocol(source, immutableSha)

        assertTrue(hardened.contains("val branch = \"$immutableSha\""))
        assertTrue(hardened.contains("SOCAR_PROTOCOL_CHECKOUT"))
        assertEquals(0, Regex("ssh://git@github\\.com/socar-inc/socar-protocol\\.git").findAll(hardened).count())
        assertEquals(2, Regex("check\\(exitCode == 0\\)").findAll(hardened).count())
        assertEquals(0, Regex("\\.waitFor\\(\\)(?!\\.also)").findAll(hardened).count())
    }
}
